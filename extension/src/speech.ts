/**
 * Turns streamed assistant markdown into short, speakable chunks.
 *
 * The agent writes markdown for a terminal; a voice listener needs plain
 * sentences delivered as soon as they are complete, without code blocks.
 */

const FENCE = "```";
const MAX_CHUNK = 300;
const SENTENCE_END = /[.!?…]+(?=\s)|\n/;

export function toSpeakable(line: string): string {
	let text = line;
	if (/^\s*\|?[\s:|-]+\|?\s*$/.test(text) && text.includes("-")) return "";
	text = text
		.replace(/!?\[([^\]]*)\]\([^)]*\)/g, "$1")
		.replace(/\bhttps?:\/\/\S+/g, "")
		.replace(/`+([^`]*)`+/g, "$1")
		.replace(/^\s{0,3}#{1,6}\s+/, "")
		.replace(/^\s*>+\s?/, "")
		.replace(/^\s*(?:[-*+]|\d+[.)])\s+/, "")
		.replace(/\*\*|__|~~|\*/g, "")
		.replace(/(?<!\w)_+|_+(?!\w)/g, "")
		.replace(/\|/g, " ");
	return text.replace(/\s+/g, " ").trim();
}

function speakable(raw: string): string[] {
	return raw
		.split("\n")
		.map(toSpeakable)
		.filter((line) => /[\p{L}\p{N}]/u.test(line));
}

export class SpeechChunker {
	private buffer = "";
	private inFence = false;

	push(delta: string): string[] {
		this.buffer += delta;
		return this.drain();
	}

	flush(): string[] {
		const out = this.drain();
		if (!this.inFence) out.push(...speakable(this.buffer));
		this.buffer = "";
		this.inFence = false;
		return out;
	}

	private drain(): string[] {
		const out: string[] = [];
		for (;;) {
			if (this.inFence) {
				const close = this.buffer.indexOf(FENCE);
				if (close < 0) {
					// Keep a possible partial closing fence for the next delta.
					this.buffer = this.buffer.slice(-2);
					return out;
				}
				this.buffer = this.buffer.slice(close + FENCE.length);
				this.inFence = false;
				continue;
			}
			const fence = this.buffer.indexOf(FENCE);
			const match = SENTENCE_END.exec(this.buffer);
			const end = match ? match.index + match[0].length : -1;
			if (fence >= 0 && (end < 0 || fence < end)) {
				out.push(...speakable(this.buffer.slice(0, fence)));
				this.buffer = this.buffer.slice(fence + FENCE.length);
				this.inFence = true;
				continue;
			}
			if (end >= 0) {
				out.push(...speakable(this.buffer.slice(0, end)));
				this.buffer = this.buffer.slice(end);
				continue;
			}
			if (this.buffer.length > MAX_CHUNK) {
				const cut = this.buffer.lastIndexOf(" ", MAX_CHUNK);
				const at = cut > 0 ? cut : MAX_CHUNK;
				out.push(...speakable(this.buffer.slice(0, at)));
				this.buffer = this.buffer.slice(at);
				continue;
			}
			return out;
		}
	}
}
