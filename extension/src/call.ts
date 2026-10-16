import { encodePairing } from "./pairing.ts";
import { type CallServer, createCallServer } from "./server.ts";
import { SpeechChunker } from "./speech.ts";
import { loadOrCreateToken } from "./token.ts";

/** The slice of the pi extension API the call needs; faked in tests. */
export interface CallHost {
	sendUserMessage(text: string, options?: { deliverAs: "steer" }): void;
	isIdle(): boolean;
	abort(): void;
	sessionName(): string;
}

export interface CallOptions {
	configDir: string;
	detectPublicUrl(): Promise<string | undefined>;
}

export const DEFAULT_PORT = 8787;
export const DEFAULT_HTTPS_PORT = 8443;

export const VOICE_PROMPT = `

## Voice call

The user is talking to you through a voice call from their phone. Their messages are speech-to-text transcripts and may contain recognition errors; infer the intended meaning. Your text replies are read aloud by text-to-speech. While the call lasts:
- Keep spoken replies short and conversational: a few sentences of plain prose.
- Do not put code, file contents, diffs, tables, or long paths in your replies; describe them in words. Code you write with tools is unaffected.
- Before long tool work, say in one short sentence what you are about to do.
- When you finish, summarize the outcome and any decision you need from the user.
- Reply in the language the user speaks.`;

export class CallController {
	private server: CallServer | undefined;
	private chunker = new SpeechChunker();
	private token: string | undefined;
	private readonly host: CallHost;
	private readonly options: CallOptions;

	constructor(host: CallHost, options: CallOptions) {
		this.host = host;
		this.options = options;
	}

	get running(): boolean {
		return this.server !== undefined;
	}

	get port(): number | undefined {
		return this.server?.port;
	}

	async start(port: number = DEFAULT_PORT): Promise<string[]> {
		if (this.server) return [`Sdot call is already running on 127.0.0.1:${this.server.port}.`];
		this.token = loadOrCreateToken(this.options.configDir);
		try {
			this.server = await createCallServer({
				token: this.token,
				port,
				onSay: (text) => {
					if (this.host.isIdle()) {
						this.host.sendUserMessage(text);
						return "prompt";
					}
					this.host.sendUserMessage(text, { deliverAs: "steer" });
					return "steer";
				},
				onAbort: () => this.host.abort(),
				getStatus: () => ({ busy: !this.host.isIdle(), session: this.host.sessionName() }),
			});
		} catch (error) {
			if ((error as NodeJS.ErrnoException).code === "EADDRINUSE") {
				return [`Sdot: port ${port} is in use; another session may already be on a call. Try /call start <port>.`];
			}
			throw error;
		}
		this.chunker = new SpeechChunker();
		const local = `http://127.0.0.1:${this.server.port}`;
		const lines = [
			`Sdot call server listening on 127.0.0.1:${this.server.port} (session: ${this.host.sessionName()}).`,
			`Expose it to your private network once with: tailscale serve --bg --https=${DEFAULT_HTTPS_PORT} ${local}`,
		];
		const url = await this.options.detectPublicUrl().catch(() => undefined);
		if (url) return [...lines, ...(await this.pair(url))];
		return [...lines, "Then get a pairing code with: /call pair https://<your-host>:<https-port>"];
	}

	async pair(url: string): Promise<string[]> {
		if (!this.server || !this.token) return ["Sdot call is not running; start it with /call."];
		try {
			return ["Pairing code for the Sdot app (keep it private, it grants access to this session):", encodePairing({ url, token: this.token })];
		} catch (error) {
			return [`Sdot: ${(error as Error).message}`];
		}
	}

	async stop(): Promise<string[]> {
		if (!this.server) return ["Sdot call is not running."];
		const server = this.server;
		this.server = undefined;
		await server.close();
		return ["Sdot call stopped."];
	}

	status(): string[] {
		if (!this.server) return ["Sdot call is not running."];
		return [`Sdot call running on 127.0.0.1:${this.server.port}; ${this.server.clientCount()} phone(s) connected.`];
	}

	systemPromptAddition(): string | undefined {
		return this.server && this.server.clientCount() > 0 ? VOICE_PROMPT : undefined;
	}

	onAgentStart(): void {
		this.server?.broadcast("busy", {});
	}

	onTextDelta(delta: string): void {
		if (!this.server) return;
		for (const text of this.chunker.push(delta)) this.server.broadcast("speech", { text });
	}

	onToolStart(tool: string): void {
		this.server?.broadcast("activity", { tool });
	}

	onMessageEnd(): void {
		if (!this.server) return;
		for (const text of this.chunker.flush()) this.server.broadcast("speech", { text });
	}

	onSettled(aborted: boolean): void {
		this.onMessageEnd();
		this.server?.broadcast("idle", { aborted });
	}
}
