import assert from "node:assert/strict";
import { request } from "node:http";
import { after, before, test } from "node:test";
import { type CallServer, createCallServer } from "../src/server.ts";

const TOKEN = "x".repeat(43);
const said: string[] = [];
let aborts = 0;
let server: CallServer;
let busy = false;

before(async () => {
	server = await createCallServer({
		token: TOKEN,
		port: 0,
		onSay: (text) => {
			said.push(text);
			return busy ? "steer" : "prompt";
		},
		onAbort: () => {
			aborts++;
		},
		getStatus: () => ({ busy, session: "demo" }),
	});
});

after(async () => {
	await server.close();
});

interface Reply {
	status: number;
	body: string;
}

function call(method: string, path: string, options: { token?: string | null; body?: string; type?: string } = {}): Promise<Reply> {
	const headers: Record<string, string> = {};
	const token = options.token === undefined ? TOKEN : options.token;
	if (token !== null) headers.authorization = `Bearer ${token}`;
	if (options.body !== undefined) headers["content-type"] = options.type ?? "application/json";
	return new Promise((resolve, reject) => {
		const req = request({ host: "127.0.0.1", port: server.port, method, path, headers }, (res) => {
			let body = "";
			res.setEncoding("utf8");
			res.on("data", (chunk) => (body += chunk));
			res.on("end", () => resolve({ status: res.statusCode ?? 0, body }));
		});
		req.on("error", reject);
		if (options.body !== undefined) req.write(options.body);
		req.end();
	});
}

test("binds to loopback only by default", () => {
	assert.equal(server.host, "127.0.0.1");
});

test("rejects requests without a valid token and does not act on them", async () => {
	const before = said.length;
	for (const token of [null, "wrong", `${TOKEN}x`]) {
		const reply = await call("POST", "/v1/say", { token, body: JSON.stringify({ text: "hi" }) });
		assert.equal(reply.status, 401);
		assert.deepEqual(JSON.parse(reply.body), { error: "unauthorized" });
	}
	assert.equal((await call("POST", "/v1/abort", { token: "wrong" })).status, 401);
	assert.equal((await call("GET", "/v1/status", { token: null })).status, 401);
	assert.equal(said.length, before);
	assert.equal(aborts, 0);
});

test("say delivers the text as a prompt when idle and as a steer when busy", async () => {
	busy = false;
	let reply = await call("POST", "/v1/say", { body: JSON.stringify({ text: "  fix the login  " }) });
	assert.equal(reply.status, 202);
	assert.deepEqual(JSON.parse(reply.body), { delivered: "prompt" });
	assert.equal(said.at(-1), "fix the login");
	busy = true;
	reply = await call("POST", "/v1/say", { body: JSON.stringify({ text: "also add a test" }) });
	assert.deepEqual(JSON.parse(reply.body), { delivered: "steer" });
	busy = false;
});

test("say rejects invalid bodies without acting", async () => {
	const before = said.length;
	const cases: Array<[string, string | undefined, number]> = [
		["not json", undefined, 400],
		[JSON.stringify({ text: "   " }), undefined, 400],
		[JSON.stringify({ text: 42 }), undefined, 400],
		[JSON.stringify({ text: "a".repeat(4001) }), undefined, 400],
		[JSON.stringify({ text: "hi" }), "text/plain", 415],
		[JSON.stringify({ text: "a".repeat(20_000) }), undefined, 413],
	];
	for (const [body, type, status] of cases) {
		const reply = await call("POST", "/v1/say", { body, type });
		assert.equal(reply.status, status, `body ${body.slice(0, 20)} type ${type}`);
	}
	assert.equal(said.length, before);
});

test("abort calls the abort handler", async () => {
	const reply = await call("POST", "/v1/abort");
	assert.equal(reply.status, 202);
	assert.equal(aborts, 1);
});

test("status reports the session state", async () => {
	const reply = await call("GET", "/v1/status");
	assert.equal(reply.status, 200);
	assert.deepEqual(JSON.parse(reply.body), { busy: false, session: "demo", clients: 0 });
});

test("unknown routes and wrong methods are rejected", async () => {
	assert.equal((await call("GET", "/nope")).status, 404);
	assert.equal((await call("GET", "/v1/say")).status, 405);
});

test("events stream sends hello and broadcast events to connected clients", async () => {
	const received = await new Promise<string>((resolve, reject) => {
		const req = request(
			{ host: "127.0.0.1", port: server.port, path: "/v1/events", headers: { authorization: `Bearer ${TOKEN}` } },
			(res) => {
				assert.equal(res.statusCode, 200);
				assert.equal(res.headers["content-type"], "text/event-stream");
				let body = "";
				res.setEncoding("utf8");
				res.on("data", (chunk) => {
					body += chunk;
					if (body.includes("event: hello") && server.clientCount() === 1 && !body.includes("event: speech")) {
						server.broadcast("speech", { text: "All tests pass." });
					}
					if (body.includes("event: speech")) {
						req.destroy();
						resolve(body);
					}
				});
			},
		);
		req.on("error", (error) => {
			if ((error as NodeJS.ErrnoException).code !== "ECONNRESET") reject(error);
		});
		req.end();
	});
	assert.match(received, /event: hello\ndata: \{"busy":false,"session":"demo","clients":1\}\n\n/);
	assert.match(received, /event: speech\ndata: \{"text":"All tests pass\."\}\n\n/);
});
