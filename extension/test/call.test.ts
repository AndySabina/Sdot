import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, test } from "node:test";
import { CallController, type CallHost, VOICE_PROMPT } from "../src/call.ts";
import { decodePairing } from "../src/pairing.ts";
import { loadOrCreateToken } from "../src/token.ts";
import { openEvents, post } from "./helpers.ts";

interface FakeHost extends CallHost {
	sent: Array<{ text: string; options?: { deliverAs: "steer" } }>;
	aborts: number;
	idle: boolean;
}

function fakeHost(): FakeHost {
	const host: FakeHost = {
		sent: [],
		aborts: 0,
		idle: true,
		sendUserMessage(text, options) {
			host.sent.push(options ? { text, options } : { text });
		},
		isIdle: () => host.idle,
		abort() {
			host.aborts++;
		},
		sessionName: () => "demo",
	};
	return host;
}

const controllers: CallController[] = [];

function setup(publicUrl?: string) {
	const dir = mkdtempSync(join(tmpdir(), "sdot-call-"));
	const host = fakeHost();
	const controller = new CallController(host, { configDir: dir, detectPublicUrl: async () => publicUrl });
	controllers.push(controller);
	return { host, controller, token: loadOrCreateToken(dir) };
}

afterEach(async () => {
	for (const controller of controllers.splice(0)) await controller.stop();
});

test("start listens on loopback and prints a pairing code for the detected https url", async () => {
	const { controller, token } = setup("https://box.example.ts.net:8443");
	const lines = await controller.start(0);
	const text = lines.join("\n");
	assert.match(text, /listening on 127\.0\.0\.1:\d+/);
	assert.match(text, /tailscale serve --bg --https=8443 http:\/\/127\.0\.0\.1:\d+/);
	const code = /sdot1:[A-Za-z0-9_-]+/.exec(text)?.[0];
	assert.ok(code, text);
	assert.deepEqual(decodePairing(code), { url: "https://box.example.ts.net:8443", token });
});

test("start without a detected url explains how to get a pairing code", async () => {
	const { controller } = setup(undefined);
	const text = (await controller.start(0)).join("\n");
	assert.doesNotMatch(text, /sdot1:/);
	assert.match(text, /\/call pair https:\/\//);
});

test("pair builds a code for an explicit url and rejects non-https", async () => {
	const { controller, token } = setup(undefined);
	await controller.start(0);
	const code = /sdot1:\S+/.exec((await controller.pair("https://me.example:9443")).join("\n"))?.[0];
	assert.deepEqual(decodePairing(code ?? ""), { url: "https://me.example:9443", token });
	assert.match((await controller.pair("http://insecure:80")).join("\n"), /https/);
});

test("start twice reports that the call is already running", async () => {
	const { controller } = setup();
	await controller.start(0);
	assert.match((await controller.start(0)).join("\n"), /already running/);
});

test("start on a busy port reports it without throwing", async () => {
	const blocker = createServer();
	await new Promise<void>((resolve) => blocker.listen(0, "127.0.0.1", resolve));
	const port = (blocker.address() as { port: number }).port;
	try {
		const { controller } = setup();
		assert.match((await controller.start(port)).join("\n"), new RegExp(`port ${port} is in use`));
		assert.equal(controller.running, false);
	} finally {
		blocker.close();
	}
});

test("speech from the phone becomes a prompt when idle and a steer when busy", async () => {
	const { controller, host, token } = setup();
	await controller.start(0);
	const port = controller.port ?? 0;
	assert.deepEqual((await post(port, token, "/v1/say", { text: "run the tests" })).body, { delivered: "prompt" });
	host.idle = false;
	assert.deepEqual((await post(port, token, "/v1/say", { text: "skip e2e" })).body, { delivered: "steer" });
	assert.deepEqual(host.sent, [{ text: "run the tests" }, { text: "skip e2e", options: { deliverAs: "steer" } }]);
	await post(port, token, "/v1/abort");
	assert.equal(host.aborts, 1);
});

test("assistant text streams to the phone as sentences, then idle when settled", async () => {
	const { controller, token } = setup();
	await controller.start(0);
	const stream = await openEvents(controller.port ?? 0, token);
	try {
		controller.onAgentStart();
		controller.onTextDelta("Reading the login. Then");
		controller.onToolStart("read");
		controller.onTextDelta(" I fix it");
		controller.onMessageEnd();
		controller.onSettled(false);
		const events = await stream.waitFor((all) => all.some((e) => e.event === "idle"));
		assert.deepEqual(
			events.filter((e) => e.event !== "hello"),
			[
				{ event: "busy", data: {} },
				{ event: "speech", data: { text: "Reading the login." } },
				{ event: "activity", data: { tool: "read" } },
				{ event: "speech", data: { text: "Then I fix it" } },
				{ event: "idle", data: { aborted: false } },
			],
		);
	} finally {
		stream.close();
	}
});

test("voice prompt is added only while a phone is connected", async () => {
	const { controller, token } = setup();
	assert.equal(controller.systemPromptAddition(), undefined);
	await controller.start(0);
	assert.equal(controller.systemPromptAddition(), undefined);
	const stream = await openEvents(controller.port ?? 0, token);
	await stream.waitFor((all) => all.some((e) => e.event === "hello"));
	assert.equal(controller.systemPromptAddition(), VOICE_PROMPT);
	stream.close();
	await new Promise((resolve) => setTimeout(resolve, 50));
	assert.equal(controller.systemPromptAddition(), undefined);
});

test("stop closes the server", async () => {
	const { controller } = setup();
	await controller.start(0);
	const lines = await controller.stop();
	assert.match(lines.join("\n"), /stopped/);
	assert.equal(controller.running, false);
	assert.match((await controller.stop()).join("\n"), /not running/);
});
