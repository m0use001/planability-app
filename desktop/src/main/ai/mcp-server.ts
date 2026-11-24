/* M20 backlog #5b: stdio entry point for the agent kernel as an MCP server.
 *
 * Kept as its OWN file and its OWN process because it must be loadable by plain node:
 * importing it from index.ts would pull `electron` into the module graph and the whole point is
 * that the kernel works outside the app (xcore6 pattern).
 *
 * Run: node out/main/ai/mcp-server.js --workspace <dir> [--allow-writes]
 *
 * STDOUT IS THE PROTOCOL. Every diagnostic goes to stderr — a stray console.log on stdout
 * corrupts the JSON-RPC framing and the client hangs with no useful message. That is the single
 * most common way a stdio MCP server dies.
 */

import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import {
	CallToolRequestSchema, ListToolsRequestSchema,
} from '@modelcontextprotocol/sdk/types.js';

import { createKernel } from './mcp-kernel';
import { createDataTools } from './data-tools';
import type { AgentCapability } from './team-assemble';
import * as fs from 'node:fs';

function arg(name: string, fallback?: string): string | undefined {
	const i = process.argv.indexOf(name);
	return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : fallback;
}

async function main(): Promise<void> {
	// The kernel's whole contract is confinement under `workspace`, so pin it to an existing
	// directory the caller actually pointed at — realpath first, then the isDirectory check.
	const workspace = fs.realpathSync(arg('--workspace') ?? process.cwd());
	if (!fs.statSync(workspace).isDirectory()) { throw new Error('--workspace must be an existing directory'); }
	const allowWrites = process.argv.includes('--allow-writes');

	// P5: read-only view of the app's planning collections (JSON store). Optional on purpose:
	// without `--store` the caller never pointed at a data dir, and advertising memory tools
	// that cannot answer teaches the caller to retry. Same confinement rule as workspace.
	const storeArg = arg('--store');
	let data: ReturnType<typeof createDataTools> | null = null;
	if (storeArg) {
		const storeDir = fs.realpathSync(storeArg);
		if (!fs.statSync(storeDir).isDirectory()) { throw new Error('--store must be an existing directory'); }
		data = createDataTools(storeDir);
	}

	// Agents are declared as JSON on the command line rather than read from the app's database:
	// better-sqlite3 is rebuilt against Electron's ABI and will not load under plain node, so the
	// kernel cannot open the app's store. Declaring them keeps this process independent.
	let agents: AgentCapability[] = [];
	const agentsJson = arg('--agents');
	if (agentsJson) {
		try { agents = JSON.parse(agentsJson); } catch { /* ignore: assembly will say it has no agents */ }
	}

	const kernel = createKernel({
		workspace, allowWrites, agents,
		// No runStep here on purpose: a stdio server has no model configured. `workflow_run` then
		// refuses with a clear message instead of pretending, and the caller can host the kernel
		// in-process (where it does supply runStep) to get execution.
	});

	const server = new Server(
		{ name: 'planability-kernel', version: '0.1.0' },
		{ capabilities: { tools: {} } },
	);

	server.setRequestHandler(ListToolsRequestSchema, async () => ({
		tools: [
			...kernel.tools().map(t => ({
				name: t.name,
				description: t.description,
				inputSchema: t.inputSchema,
			})),
			...(data ? data.tools : []),
		],
	}));

	server.setRequestHandler(CallToolRequestSchema, async (req) => {
		const name = req.params.name;
		const input = (req.params.arguments ?? {}) as Record<string, unknown>;
		// Data tools are read-only and never touch the kernel's confinement; route by prefix so
		// a future kernel tool with the same name cannot silently win.
		const r = name.startsWith('planability_data_') && data
			? await data.call(name, input)
			: await kernel.call(name, input);
		return {
			content: [{ type: 'text' as const, text: r.text }],
			isError: !r.ok,
		};
	});

	const transport = new StdioServerTransport();
	await server.connect(transport);
	// stderr only. See the file header.
	process.stderr.write(
		`[planability-mcp] workspace=${workspace} writes=${allowWrites} ` +
		`tools=${kernel.tools().length + (data ? data.tools.length : 0)}` +
		`${data ? ` store=${storeArg}` : ' store=(none, data tools off)'}\n`,
	);
}

main().catch((e) => {
	process.stderr.write(`[planability-mcp] fatal: ${(e as Error)?.stack ?? e}\n`);
	process.exit(1);
});
