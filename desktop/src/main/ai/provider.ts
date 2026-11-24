/* OpenAI-compatible streaming provider — ported from COD planability-ai extension
 * (chatCompletion/SSE aggregation/tokenToSignal were all verified there).
 * vscode.Progress/CancellationToken replaced by async-generator yields + AbortSignal. */

export interface SlotConfig {
	url: string;
	model: string;
	authStyle: 'bearer' | 'api-key';
	/** Resolved key (config-first in main; env fallback). */
	key?: string;
}

/** Env/default fallback — main builds the effective slot config-first (UI changes win). */
export function mainSlotConfig(): SlotConfig {
	return {
		url: (process.env.PLANABILITY_AI_MAIN_URL || 'https://api.xiaomimimo.com/v1').replace(/\/+$/, ''),
		model: process.env.PLANABILITY_AI_MAIN_MODEL || 'mimo-v2.6-flash',
		authStyle: 'bearer',
		key: process.env.PLANABILITY_AI_MAIN_KEY
	};
}

/**
 * True for a host on the user's own machine or its network.
 *
 * This used to be an inline regex, and it was wrong in three ways that all read as correct —
 * `new URL` hands back the IPv6 literal rather than the address inside it, so:
 *
 *   http://[::1]/x                      hostname [::1]                 -> caught
 *   http://[::ffff:127.0.0.1]/x        hostname [::ffff:7f00:1]       -> WALKED THROUGH
 *   http://[::ffff:169.254.169.254]/x  hostname [::ffff:a9fe:a9fe]    -> WALKED THROUGH
 *   http://foo.localhost/x              hostname foo.localhost          -> WALKED THROUGH
 *
 * The middle one matters most: the guard named 169.254.169.254 explicitly, and an
 * IPv4-mapped spelling of it sailed past while carrying the metadata service's address. The
 * third is RFC 6761 — the whole `.localhost` TLD is loopback, but only `.local` was checked.
 *
 * IPv6 literals are handled by shape rather than by decoding them. A complete parser is more
 * code than this decision deserves and more room to be quietly wrong; instead the two spellings
 * that embed an IPv4 address fail CLOSED, plus unique-local and link-local, which have no place
 * in a public fetch. Anything else bracketed is treated as routable, which is the same
 * assumption the old check made.
 */
export function isPrivateHost(rawHost: string): boolean {
	const host = String(rawHost ?? '').trim().toLowerCase().replace(/^\[/, '').replace(/\]$/, '');
	if (!host) { return true; } // no host at all is not a public host
	if (host === 'localhost' || host.endsWith('.localhost') || host.endsWith('.local')) { return true; }
	if (host.includes(':')) {
		// An IPv4-mapped or IPv4-compatible literal spells the embedded address after `ffff:`
		// and `new URL` renders it in hex, so a check for `127.` cannot see it.
		if (host.includes('ffff')) { return true; }
		if (host.slice(0, 2) === 'fc' || host.slice(0, 2) === 'fd') { return true; } // fc00::/7
		if (/^fe[89ab]/.test(host)) { return true; }                                  // fe80::/10
		return host === '::1' || host === '0:0:0:0:0:0:0:1' || host === '::';
	}
	// `0.` covers 0.0.0.0, which on Windows means "every interface".
	return /^(127\.|10\.|192\.168\.|169\.254\.|172\.(1[6-9]|2\d|3[01])\.|0\.)/.test(host);
}

/** SSRF guard at the request site: https-only + loopback/private-host rejection, no redirects. */
export function assertPublicHttps(url: string): URL {
	const parsed = new URL(url);
	if (parsed.protocol !== 'https:') {
		throw new Error(`Planability AI: only https endpoints are allowed (got ${parsed.protocol})`);
	}
	if (isPrivateHost(parsed.hostname)) {
		throw new Error(`Planability AI: private/loopback host "${parsed.hostname}" is not allowed`);
	}
	return parsed;
}

/** Connection-layer failures (first-packet timeout / dead pooled socket) mean the request
 * never reached the server — safe to back off and retry. Aborts and HTTP errors are not retried. */
export async function fetchWithConnectRetry(url: string, init: RequestInit, tries = 3): Promise<Response> {
	for (let i = 0; ; i++) {
		try {
			return await fetch(url, init);
		} catch (e) {
			const code = (e as any)?.cause?.code;
			console.error(`[planability-ai] fetch attempt ${i + 1}/${tries} failed:`, (e as any)?.message, code || (e as any)?.cause?.message || '');
			const retriable = code === 'UND_ERR_CONNECT_TIMEOUT' || code === 'UND_ERR_SOCKET' || code === 'ECONNREFUSED' || code === 'ECONNRESET';
			if (!retriable || i >= tries - 1) { throw e; }
			await new Promise(r => setTimeout(r, 1000 * (i + 1)));
		}
	}
}

export interface ToolCall {
	id: string;
	name: string;
	arguments: string; // raw JSON string, may be accumulated across deltas
}

export type StreamChunk =
	| { type: 'text'; value: string }
	| { type: 'reasoning'; value: string }
	| { type: 'tool_calls'; calls: ToolCall[] };

export async function chatCompletion(
	slot: SlotConfig,
	body: Record<string, unknown>,
	signal?: AbortSignal
): Promise<Response> {
	const key = slot.key;
	if (!key) {
		throw new Error('Planability AI: 未配置 API Key——请到「设置」页填写主槽 Key（或用 launch-dev.cmd 启动）');
	}
	const endpoint = assertPublicHttps(`${slot.url}/chat/completions`);
	return fetchWithConnectRetry(endpoint.toString(), {
		method: 'POST',
		headers: { 'Content-Type': 'application/json', Authorization: slot.authStyle === 'api-key' ? `api-key:${key}` : `Bearer ${key}` },
		body: JSON.stringify({ model: slot.model, ...body }),
		redirect: 'error',
		signal
	});
}

/** Non-streaming one-shot call — used by the automation engine (bounded, tool-less). */
export async function callNonStream(slot: SlotConfig, system: string, user: string, signal?: AbortSignal): Promise<string> {
	const response = await chatCompletion(slot, {
		stream: false,
		messages: [{ role: 'system', content: system }, { role: 'user', content: user }]
	}, signal);
	if (!response.ok) {
		throw new Error(`Planability AI 主槽错误：${response.status}`);
	}
	const data: any = await response.json();
	return data.choices?.[0]?.message?.content ?? '';
}

/** SSE reader: yields text deltas live, aggregates tool_calls by index and flushes at
 * finish_reason=tool_calls / stream end (same algorithm as the COD provider). */
export async function* streamChat(response: Response): AsyncGenerator<StreamChunk> {
	if (!response.ok || !response.body) {
		const detail = response.body ? await response.text() : '';
		throw new Error(`Planability AI 主槽错误：${response.status} ${detail.slice(0, 300)}`);
	}
	const reader = response.body.getReader();
	const decoder = new TextDecoder();
	let buffer = '';
	const toolAcc: { id: string; name: string; args: string; emitted: boolean }[] = [];

	function* flushToolCalls(): Generator<StreamChunk> {
		for (const t of toolAcc) {
			if (!t.emitted && t.name) {
				t.emitted = true;
				yield { type: 'tool_calls', calls: [{ id: t.id || `call_${t.name}`, name: t.name, arguments: t.args }] };
			}
		}
	}

	while (true) {
		const { done, value } = await reader.read();
		if (done) { break; }
		buffer += decoder.decode(value, { stream: true });
		const lines = buffer.split('\n');
		buffer = lines.pop() || '';
		for (const line of lines) {
			const trimmed = line.trim();
			if (!trimmed.startsWith('data:')) { continue; }
			const payload = trimmed.slice(5).trim();
			if (payload === '[DONE]') { yield* flushToolCalls(); continue; }
			let chunk: any;
			try { chunk = JSON.parse(payload); } catch { continue; }
			const delta = chunk.choices?.[0]?.delta;
			if (!delta) { continue; }
			if (typeof delta.content === 'string' && delta.content) {
				yield { type: 'text', value: delta.content };
			}
			if (typeof delta.reasoning_content === 'string' && delta.reasoning_content) {
				yield { type: 'reasoning', value: delta.reasoning_content };
			}
			if (Array.isArray(delta.tool_calls)) {
				for (const tc of delta.tool_calls) {
					const idx = tc.index ?? 0;
					if (!toolAcc[idx]) { toolAcc[idx] = { id: '', name: '', args: '', emitted: false }; }
					if (tc.id) { toolAcc[idx].id = tc.id; }
					if (tc.function?.name) { toolAcc[idx].name += tc.function.name; }
					if (tc.function?.arguments) { toolAcc[idx].args += tc.function.arguments; }
				}
			}
			if (chunk.choices?.[0]?.finish_reason === 'tool_calls') { yield* flushToolCalls(); }
		}
	}
	yield* flushToolCalls();
}
