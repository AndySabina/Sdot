import assert from "node:assert/strict";
import { test } from "node:test";
import { SpeechChunker, toSpeakable } from "../src/speech.ts";

function feed(chunker: SpeechChunker, deltas: string[]): string[] {
	const out: string[] = [];
	for (const delta of deltas) out.push(...chunker.push(delta));
	out.push(...chunker.flush());
	return out;
}

test("emits complete sentences as soon as they end", () => {
	const chunker = new SpeechChunker();
	assert.deepEqual(chunker.push("Hello there. How"), ["Hello there."]);
	assert.deepEqual(chunker.push(" are you? Fine"), ["How are you?"]);
	assert.deepEqual(chunker.flush(), ["Fine"]);
});

test("waits for whitespace before treating a period as a sentence end", () => {
	const chunker = new SpeechChunker();
	assert.deepEqual(chunker.push("Version 3."), []);
	assert.deepEqual(chunker.push("5 is out. Done"), ["Version 3.5 is out."]);
	assert.deepEqual(chunker.flush(), ["Done"]);
});

test("treats a newline as a boundary", () => {
	assert.deepEqual(feed(new SpeechChunker(), ["First line\nSecond line"]), ["First line", "Second line"]);
});

test("drops fenced code blocks even when split across deltas", () => {
	const out = feed(new SpeechChunker(), ["I changed it.\n``", "`ts\nconst a = 1;\n", "const b = 2;\n`", "``\nTests pass."]);
	assert.deepEqual(out, ["I changed it.", "Tests pass."]);
});

test("drops an unterminated code block at flush", () => {
	assert.deepEqual(feed(new SpeechChunker(), ["Look:\n```\nrm -rf build\n"]), ["Look:"]);
});

test("splits very long runs without punctuation at a word boundary", () => {
	const words = Array.from({ length: 120 }, (_, i) => `word${i}`).join(" ");
	const out = new SpeechChunker().push(words);
	assert.ok(out.length >= 1);
	for (const piece of out) assert.ok(piece.length <= 300, `piece too long: ${piece.length}`);
	assert.ok(!out[0].endsWith("word"), "split must not cut a word");
});

test("toSpeakable strips markdown syntax and urls", () => {
	assert.equal(toSpeakable("## **Done**: see [the docs](https://example.com/x) and `src/a.ts`"), "Done: see the docs and src/a.ts");
	assert.equal(toSpeakable("- item one"), "item one");
	assert.equal(toSpeakable("> quoted _text_"), "quoted text");
	assert.equal(toSpeakable("visit https://example.com now"), "visit now");
	assert.equal(toSpeakable("| a | b |"), "a b");
	assert.equal(toSpeakable("|---|---|"), "");
});

test("does not emit chunks that are empty after cleanup", () => {
	assert.deepEqual(feed(new SpeechChunker(), ["---\n\n**\n"]), []);
});
