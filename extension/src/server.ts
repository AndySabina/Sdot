import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";
import { tokensEqual } from "./token.ts";

/**
 * Small authenticated HTTP API the phone talks to.
 *
 *   GET  /v1/events  Server-Sent Events: hello, speech, activity, idle, busy
 *   POST /v1/say     {"text": "..."}  inject what the user said
 *   POST /v1/abort   stop the current agent run
 *   GET  /v1/status  current session state
 *
 * Every request needs `Authorization: Bearer <token>`. The server binds to
 * loopback; remote access goes through an HTTPS proxy on a private network.
 */

export type Delivery = "prompt" | "steer";

export interface CallServerOptions {
	token: string;
	port: number;
	host?: string;
	onSay(text: string): Delivery | Promise<Delivery>;
	onAbort(): void;
	getStatus(): Record<string, unknown>;
	onClientsChanged?(count: number): void;
}

export interface CallServer {
	host: string;
	port: number;
	broadcast(event: string, data: unknown): void;
	clientCount(): number;
	close(): Promise<void>;
}

const MAX_BODY_BYTES = 16 * 1024;
const MAX_TEXT_CHARS = 4000;
const KEEPALIVE_MS = 15_000;

function send(res: ServerResponse, status: number, body: unknown): void {
	res.writeHead(status, { "content-type": "application/json", "cache-control": "no-store" });
	res.end(JSON.stringify(body));
}

function bearer(req: IncomingMessage): string {
	const header = req.headers.authorization ?? "";
	return header.startsWith("Bearer ") ? header.slice(7).trim() : "";
}

function readBody(req: IncomingMessage): Promise<string | null> {
	return new Promise((resolve, reject) => {
		const chunks: Buffer[] = [];
		let size = 0;
		req.on("data", (chunk: Buffer) => {
			size += chunk.length;
			if (size <= MAX_BODY_BYTES) chunks.push(chunk);
		});
		req.on("end", () => resolve(size > MAX_BODY_BYTES ? null : Buffer.concat(chunks).toString("utf8")));
		req.on("error", reject);
	});
}

function parseText(body: string): string | null {
	try {
		const parsed = JSON.parse(body);
		if (typeof parsed?.text !== "string") return null;
		const text = parsed.text.trim();
		return text && text.length <= MAX_TEXT_CHARS ? text : null;
	} catch {
		return null;
	}
}

export async function createCallServer(options: CallServerOptions): Promise<CallServer> {
	const host = options.host ?? "127.0.0.1";
	const clients = new Set<ServerResponse>();
	const status = () => ({ ...options.getStatus(), clients: clients.size });
	const event = (name: string, data: unknown) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`;

	const openStream = (req: IncomingMessage, res: ServerResponse) => {
		res.writeHead(200, {
			"content-type": "text/event-stream",
			"cache-control": "no-store",
			connection: "keep-alive",
			"x-accel-buffering": "no",
		});
		res.write("retry: 3000\n\n");
		clients.add(res);
		res.write(event("hello", status()));
		const keepalive = setInterval(() => res.write(": ping\n\n"), KEEPALIVE_MS);
		keepalive.unref();
		req.on("close", () => {
			clearInterval(keepalive);
			clients.delete(res);
			options.onClientsChanged?.(clients.size);
		});
		options.onClientsChanged?.(clients.size);
	};

	const handle = async (req: IncomingMessage, res: ServerResponse) => {
		if (!tokensEqual(bearer(req), options.token)) {
			req.resume();
			return send(res, 401, { error: "unauthorized" });
		}
		const path = new URL(req.url ?? "/", "http://localhost").pathname;
		const routes: Record<string, string> = { "/v1/events": "GET", "/v1/status": "GET", "/v1/say": "POST", "/v1/abort": "POST" };
		if (!(path in routes)) {
			req.resume();
			return send(res, 404, { error: "not found" });
		}
		if (req.method !== routes[path]) {
			req.resume();
			return send(res, 405, { error: "method not allowed" });
		}
		if (path === "/v1/events") return openStream(req, res);
		if (path === "/v1/status") return send(res, 200, status());
		if (path === "/v1/abort") {
			req.resume();
			options.onAbort();
			return send(res, 202, { aborted: true });
		}
		if (!(req.headers["content-type"] ?? "").startsWith("application/json")) {
			req.resume();
			return send(res, 415, { error: "expected application/json" });
		}
		const body = await readBody(req);
		if (body === null) return send(res, 413, { error: "body too large" });
		const text = parseText(body);
		if (text === null) return send(res, 400, { error: `expected {"text": string} with 1-${MAX_TEXT_CHARS} characters` });
		try {
			return send(res, 202, { delivered: await options.onSay(text) });
		} catch {
			return send(res, 500, { error: "delivery failed" });
		}
	};

	const server = createServer((req, res) => {
		handle(req, res).catch(() => {
			if (!res.headersSent) send(res, 500, { error: "internal error" });
			else res.end();
		});
	});

	await new Promise<void>((resolve, reject) => {
		server.once("error", reject);
		server.listen(options.port, host, () => {
			server.off("error", reject);
			resolve();
		});
	});

	return {
		host,
		port: (server.address() as AddressInfo).port,
		broadcast(name, data) {
			const payload = event(name, data);
			for (const client of clients) client.write(payload);
		},
		clientCount: () => clients.size,
		close() {
			for (const client of clients) client.end();
			clients.clear();
			return new Promise((resolve) => {
				server.close(() => resolve());
				server.closeAllConnections();
			});
		},
	};
}
