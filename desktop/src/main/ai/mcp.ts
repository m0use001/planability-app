/* MCP hub (phone MCP 设置 parity): connects Streamable-HTTP servers, merges their tools
 * into the ToolRegistry as confirm-risk domain tools, and manages the server list.
 * Servers live in a plain JSON file next to the data store. */

import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import type { ToolRegistry } from './tools';
import { isPrivateHost } from './provider';

/**
 * Validate an MCP server address.
 *
 * This is deliberately NOT `provider.assertPublicHttps`, even though that is the same app's
 * guard and looks like it belongs here. The two have different threat models:
 *
 *  - `web_fetch` takes its URL from the MODEL, so it needs https-only and a loopback ban.
 *    Without them, a model talked into fetching a URL can read the user's own network and report
 *    the contents back.
 *  - An MCP server URL comes from the USER, in settings. The server list is a plain file beside
 *    the store and is NOT part of the dump, so importing a file cannot plant one. And the
 *    overwhelmingly common MCP deployment is a server on the user's own machine — copying the
 *    guard verbatim would refuse `http://127.0.0.1:3000/mcp` and break it, buying nothing.
 *
 * What is left is what costs nothing: the scheme is restricted, because no MCP server speaks
 * anything else and `new URL` cheerfully accepts `file:` and `javascript:`; and plain http is
 * refused for a REMOTE host, where the conversation (tool arguments and results included) would
 * otherwise cross the network in cleartext. Loopback and private hosts keep http, because that
 * traffic never leaves the machine. The loopback/private call itself stays the user's decision.
 */
export function assertMcpUrl(raw: string): URL {
	const text = String(raw ?? '').trim();
	let parsed: URL;
	try {
		parsed = new URL(text);
	} catch {
		// `new URL` throws a bare TypeError, which surfaces as an opaque failure with no hint
		// that the ADDRESS was the problem — as opposed to the server being down.
		throw new Error(`MCP 地址不是合法 URL：${text.slice(0, 80)}`);
	}
	if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') {
		throw new Error(`MCP 只支持 http / https 地址（收到 ${parsed.protocol}）`);
	}
	if (parsed.protocol === 'http:' && !isPrivateHost(parsed.hostname)) {
		throw new Error(`MCP 远程地址必须用 https（${parsed.hostname} 是公网主机，http 会明文传输工具调用与结果）`);
	}
	return parsed;
}

export interface McpServerDef {
	id: string;
	name: string;
	url: string;
	enabled: boolean;
	createdAt: number;
}

export interface McpTestResult {
	ok: boolean;
	toolNames?: string[];
	error?: string;
}

export class McpHub {
	private defs: McpServerDef[] = [];
	private readonly clients = new Map<string, Client>();
	private readonly registered = new Set<string>();
	private loaded = false;

	constructor(private readonly dataDir: string) { }

	private get file(): string { return path.join(this.dataDir, 'mcp_servers.json'); }

	private async load(): Promise<void> {
		if (this.loaded) { return; }
		try { this.defs = JSON.parse(await fs.readFile(this.file, 'utf8')); } catch { this.defs = []; }
		this.loaded = true;
	}

	private async persist(): Promise<void> {
		await fs.mkdir(this.dataDir, { recursive: true });
		await fs.writeFile(this.file, JSON.stringify(this.defs, null, 1), 'utf8');
	}

	async list(): Promise<McpServerDef[]> { await this.load(); return [...this.defs]; }

	async add(name: string, url: string): Promise<McpServerDef> {
		await this.load();
		// Validate BEFORE anything is written, so a rejected address cannot leave a half-added
		// row behind, and so the stored URL is the one that was checked.
		const parsed = assertMcpUrl(url);
		const def: McpServerDef = {
			id: `mcp_${Date.now().toString(36)}`,
			name: name.trim() || parsed.hostname,
			url: parsed.toString(),
			enabled: false,
			createdAt: Date.now()
		};
		this.defs.push(def);
		await this.persist();
		return def;
	}

	async setEnabled(id: string, enabled: boolean): Promise<boolean> {
		await this.load();
		const d = this.defs.find(x => x.id === id);
		if (!d) { return false; }
		d.enabled = enabled;
		await this.persist();
		return true;
	}

	async remove(id: string): Promise<boolean> {
		await this.load();
		const before = this.defs.length;
		this.defs = this.defs.filter(x => x.id !== id);
		await this.persist();
		// drop its tools from the registry view on next boot; close client now
		await this.clients.get(id)?.close().catch(() => undefined);
		this.clients.delete(id);
		this.registered.delete(id);
		return this.defs.length !== before;
	}

	private async connectOne(def: McpServerDef): Promise<Client> {
		const client = new Client({ name: 'planability-desktop', version: '0.1.0' });
		// Re-checked here, not only in `add`: this file is written on disk and edited by hand, so
		// the URL reaching the transport is not necessarily the one that was validated on entry.
		const transport = new StreamableHTTPClientTransport(assertMcpUrl(def.url));
		await client.connect(transport);
		return client;
	}

	/** One-shot connectivity + tool discovery probe (no persistent client kept). */
	async test(url: string): Promise<McpTestResult> {
		try {
			const parsed = assertMcpUrl(url);
			const client = await this.connectOne({ id: 'probe', name: 'probe', url: parsed.toString(), enabled: true, createdAt: 0 });
			const res = await client.listTools();
			await client.close().catch(() => undefined);
			return { ok: true, toolNames: (res.tools ?? []).map(t => t.name) };
		} catch (e) {
			return { ok: false, error: String((e as Error)?.message ?? e).slice(0, 300) };
		}
	}

	/** Connect every enabled server and merge its tools into the registry. */
	async connectEnabled(registry: ToolRegistry): Promise<void> {
		await this.load();
		for (const def of this.defs.filter(d => d.enabled)) {
			if (this.clients.has(def.id)) { continue; }
			try {
				const client = await this.connectOne(def);
				this.clients.set(def.id, client);
				const res = await client.listTools();
				for (const t of res.tools ?? []) {
					const name = `mcp_${def.name}_${t.name}`.replace(/[^a-zA-Z0-9_]/g, '_');
					if (registry.get(name)) { continue; }
					registry.register({
						name,
						risk: 'confirm',
						description: `[MCP·${def.name}] ${t.description ?? t.name}`,
						inputSchema: (t.inputSchema as Record<string, unknown>) ?? { type: 'object', properties: {} },
						invoke: async (input) => {
							const result = await client.callTool({ name: t.name, arguments: input });
							const parts = (result.content as { type: string; text?: string }[] | undefined) ?? [];
							const text = parts.filter(p => p.type === 'text').map(p => p.text ?? '').join('\n');
							return text || JSON.stringify(result.content ?? {}).slice(0, 4000);
						}
					});
				}
				this.registered.add(def.id);
			} catch (e) {
				console.error(`[mcp] connect failed: ${def.name}`, String(e));
			}
		}
	}
}
