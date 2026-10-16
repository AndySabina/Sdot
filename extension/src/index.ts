import { execFile } from "node:child_process";
import { homedir } from "node:os";
import { basename } from "node:path";
import type { ExtensionAPI, ExtensionContext } from "@earendil-works/pi-coding-agent";
import { CallController, DEFAULT_HTTPS_PORT, DEFAULT_PORT } from "./call.ts";
import { configDir } from "./token.ts";

/** Finds this machine's Tailscale HTTPS URL at runtime; nothing is hardcoded. */
function detectPublicUrl(): Promise<string | undefined> {
	if (process.env.SDOT_PUBLIC_URL) return Promise.resolve(process.env.SDOT_PUBLIC_URL);
	const httpsPort = process.env.SDOT_HTTPS_PORT || String(DEFAULT_HTTPS_PORT);
	return new Promise((resolve) => {
		execFile("tailscale", ["status", "--json"], { timeout: 3000 }, (error, stdout) => {
			if (error) return resolve(undefined);
			try {
				const dnsName = String(JSON.parse(stdout)?.Self?.DNSName ?? "").replace(/\.$/, "");
				resolve(dnsName ? `https://${dnsName}:${httpsPort}` : undefined);
			} catch {
				resolve(undefined);
			}
		});
	});
}

function parsePort(value: string | undefined): number | undefined {
	if (!value) return undefined;
	const port = Number(value);
	return Number.isInteger(port) && port > 0 && port < 65536 ? port : undefined;
}

export default function sdot(pi: ExtensionAPI) {
	let ctx: ExtensionContext | undefined;
	const controller = new CallController(
		{
			sendUserMessage: (text, options) => pi.sendUserMessage(text, options),
			isIdle: () => ctx?.isIdle() ?? true,
			abort: () => ctx?.abort(),
			sessionName: () => pi.getSessionName() ?? (ctx ? basename(ctx.cwd) : "pi"),
		},
		{ configDir: configDir(process.env, homedir()), detectPublicUrl },
	);

	pi.on("session_start", (_event, context) => {
		ctx = context;
	});
	pi.on("session_shutdown", async () => {
		await controller.stop();
	});

	pi.on("before_agent_start", (event) => {
		const addition = controller.systemPromptAddition();
		return addition ? { systemPrompt: event.systemPrompt + addition } : undefined;
	});
	pi.on("agent_start", () => controller.onAgentStart());
	pi.on("message_update", (event) => {
		if (event.message.role === "assistant" && event.assistantMessageEvent.type === "text_delta") {
			controller.onTextDelta(event.assistantMessageEvent.delta);
		}
	});
	pi.on("message_end", (event) => {
		if (event.message.role === "assistant") controller.onMessageEnd();
	});
	pi.on("tool_execution_start", (event) => {
		if (!event.parentToolCallId) controller.onToolStart(event.toolName);
	});
	pi.on("agent_settled", (event) => controller.onSettled(event.aborted));

	pi.registerCommand("call", {
		description: "Voice call with this session from the Sdot Android app: /call [start [port] | pair <https-url> | status | stop]",
		getArgumentCompletions: (prefix) =>
			["start", "pair", "status", "stop"].filter((name) => name.startsWith(prefix)).map((name) => ({ value: name, label: name })),
		handler: async (args, context) => {
			ctx = context;
			const [action = "start", value] = args.trim().split(/\s+/).filter(Boolean);
			let lines: string[];
			if (action === "start") {
				const port = value ? parsePort(value) : (parsePort(process.env.SDOT_PORT) ?? DEFAULT_PORT);
				lines = port === undefined ? [`Sdot: invalid port "${value}".`] : await controller.start(port);
			} else if (action === "pair" && value) lines = await controller.pair(value);
			else if (action === "status") lines = controller.status();
			else if (action === "stop") lines = await controller.stop();
			else lines = ["Usage: /call [start [port] | pair <https-url> | status | stop]"];
			context.ui.notify(lines.join("\n"), lines[0]?.startsWith("Sdot:") ? "warning" : "info");
		},
	});
}
