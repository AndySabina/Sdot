import { type ClientRequest, request } from "node:http";

export interface SseEvent {
	event: string;
	data: unknown;
}

export interface EventStream {
	events: SseEvent[];
	waitFor(predicate: (events: SseEvent[]) => boolean, timeoutMs?: number): Promise<SseEvent[]>;
	close(): void;
}

/** Opens /v1/events and collects parsed events until closed. */
export function openEvents(port: number, token: string): Promise<EventStream> {
	return new Promise((resolve, reject) => {
		const events: SseEvent[] = [];
		const waiters = new Set<() => void>();
		let buffer = "";
		const req: ClientRequest = request({ host: "127.0.0.1", port, path: "/v1/events", headers: { authorization: `Bearer ${token}` } }, (res) => {
			if (res.statusCode !== 200) return reject(new Error(`status ${res.statusCode}`));
			res.setEncoding("utf8");
			res.on("data", (chunk: string) => {
				buffer += chunk;
				let end = buffer.indexOf("\n\n");
				while (end >= 0) {
					const block = buffer.slice(0, end);
					buffer = buffer.slice(end + 2);
					const name = /^event: (.*)$/m.exec(block)?.[1];
					const data = /^data: (.*)$/m.exec(block)?.[1];
					if (name && data) events.push({ event: name, data: JSON.parse(data) });
					end = buffer.indexOf("\n\n");
				}
				for (const wake of waiters) wake();
			});
			resolve({
				events,
				waitFor(predicate, timeoutMs = 2000) {
					return new Promise((done, fail) => {
						const check = () => {
							if (!predicate(events)) return;
							waiters.delete(check);
							clearTimeout(timer);
							done(events);
						};
						const timer = setTimeout(() => {
							waiters.delete(check);
							fail(new Error(`timed out; got ${JSON.stringify(events)}`));
						}, timeoutMs);
						waiters.add(check);
						check();
					});
				},
				close: () => req.destroy(),
			});
		});
		req.on("error", (error: NodeJS.ErrnoException) => {
			if (error.code !== "ECONNRESET") reject(error);
		});
		req.end();
	});
}

export function post(port: number, token: string, path: string, body?: unknown): Promise<{ status: number; body: unknown }> {
	return new Promise((resolve, reject) => {
		const payload = body === undefined ? undefined : JSON.stringify(body);
		const headers: Record<string, string> = { authorization: `Bearer ${token}` };
		if (payload !== undefined) headers["content-type"] = "application/json";
		const req = request({ host: "127.0.0.1", port, method: "POST", path, headers }, (res) => {
			let text = "";
			res.setEncoding("utf8");
			res.on("data", (chunk) => (text += chunk));
			res.on("end", () => resolve({ status: res.statusCode ?? 0, body: text ? JSON.parse(text) : undefined }));
		});
		req.on("error", reject);
		if (payload !== undefined) req.write(payload);
		req.end();
	});
}
