/**
 * Pairing code shown by `/call` and pasted once into the Android app.
 * Format: `sdot1:` + base64url(JSON {u: url, t: token}).
 */

export interface Pairing {
	url: string;
	token: string;
}

const PREFIX = "sdot1:";

function validate(pairing: Pairing): Pairing {
	let parsed: URL;
	try {
		parsed = new URL(pairing.url);
	} catch {
		throw new Error(`Invalid Sdot URL: ${pairing.url}`);
	}
	if (parsed.protocol !== "https:") throw new Error("Sdot pairing requires an https URL");
	if (!/^[A-Za-z0-9_-]{32,}$/.test(pairing.token)) throw new Error("Invalid Sdot token");
	return { url: pairing.url.replace(/\/+$/, ""), token: pairing.token };
}

export function encodePairing(pairing: Pairing): string {
	const valid = validate(pairing);
	return PREFIX + Buffer.from(JSON.stringify({ u: valid.url, t: valid.token })).toString("base64url");
}

export function decodePairing(code: string): Pairing {
	const trimmed = code.trim();
	if (!trimmed.startsWith(PREFIX)) throw new Error("Invalid Sdot pairing code");
	try {
		const raw = JSON.parse(Buffer.from(trimmed.slice(PREFIX.length), "base64url").toString("utf8"));
		return validate({ url: String(raw.u), token: String(raw.t) });
	} catch {
		throw new Error("Invalid Sdot pairing code");
	}
}
