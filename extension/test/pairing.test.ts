import assert from "node:assert/strict";
import { test } from "node:test";
import { decodePairing, encodePairing } from "../src/pairing.ts";

test("pairing code round-trips url and token", () => {
	const code = encodePairing({ url: "https://host.example.ts.net:8443", token: "t".repeat(43) });
	assert.match(code, /^sdot1:[A-Za-z0-9_-]+$/);
	assert.deepEqual(decodePairing(code), { url: "https://host.example.ts.net:8443", token: "t".repeat(43) });
});

test("pairing code rejects non-https urls", () => {
	assert.throws(() => encodePairing({ url: "http://host:8787", token: "t".repeat(43) }), /https/);
});

test("decodePairing rejects malformed codes", () => {
	assert.throws(() => decodePairing("nope"), /pairing code/);
	assert.throws(() => decodePairing("sdot1:%%%"), /pairing code/);
});
