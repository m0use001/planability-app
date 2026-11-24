/* M18 web domain (plan §3 M18 item 2): web_fetch / web_search.
 *
 * Security posture, per plan: "web_fetch/web_search（danger 级默认 ask）".
 *
 *  - Both are risk=danger → they ALWAYS raise an approval card; there is no auto-allow path,
 *    because a prompt-injected agent that can silently exfiltrate to an arbitrary host is
 *    exactly the failure this domain is easiest to get wrong.
 *  - Reuses the existing SSRF guard (provider.assertPublicHttps): https-only, loopback and
 *    private ranges rejected, no redirect following.
 *  - Responses are truncated before they ever reach the model: a hostile page should not be
 *    able to flood the context window, and we never echo raw HTML.
 */

import { assertPublicHttps } from './provider';
import type { ToolRegistry } from './tools';

export const WEB_LIMITS = {
	maxBytes: 400_000,
	maxText: 20_000,
	timeoutMs: 15_000,
} as const;

/** Strip tags/scripts/styles and collapse whitespace; good enough for a model to read. */
export function htmlToText(html: string): string {
	return html
		.replace(/<script[\s\S]*?<\/script>/gi, ' ')
		.replace(/<style[\s\S]*?<\/style>/gi, ' ')
		.replace(/<noscript[\s\S]*?<\/noscript>/gi, ' ')
		.replace(/<!--[\s\S]*?-->/g, ' ')
		.replace(/<[^>]+>/g, ' ')
		.replace(/&nbsp;/gi, ' ').replace(/&amp;/gi, '&').replace(/&lt;/gi, '<').replace(/&gt;/gi, '>').replace(/&quot;/gi, '"')
		.replace(/\s{2,}/g, ' ')
		.trim();
}

/** A tiny, honest search backend: one GET to a public HTML endpoint. No API keys. */
export interface WebSearchBackend {
	search(query: string, limit: number): Promise<{ title: string; url: string; snippet: string }[]>;
}

/** DuckDuckGo html endpoint — no key required, returns parseable HTML. */
export const defaultSearchBackend: WebSearchBackend = {
	async search(query: string, limit: number) {
		const url = assertPublicHttps(`https://html.duckduckgo.com/html/?q=${encodeURIComponent(query)}`);
		const res = await fetch(url.toString(), {
			redirect: 'error',
			signal: AbortSignal.timeout(WEB_LIMITS.timeoutMs),
			headers: { 'user-agent': 'Planability/1.0 (+desktop)' },
		});
		if (!res.ok) { throw new Error(`搜索失败：HTTP ${res.status}`); }
		const html = (await res.text()).slice(0, WEB_LIMITS.maxBytes);
		const out: { title: string; url: string; snippet: string }[] = [];
		// result__a = title link, result__snippet = body text
		const re = /<a[^>]+class="[^"]*result__a[^"]*"[^>]+href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/gi;
		let m: RegExpExecArray | null;
		while ((m = re.exec(html)) && out.length < limit) {
			let href = m[1];
			// DDG wraps targets in /l/?uddg=<encoded>
			const wrapped = /[?&]uddg=([^&]+)/.exec(href);
			if (wrapped) { try { href = decodeURIComponent(wrapped[1]); } catch { /* keep raw */ } }
			out.push({ title: htmlToText(m[2]), url: href, snippet: '' });
		}
		return out;
	},
};

export function registerWebTools(
	reg: ToolRegistry,
	search: WebSearchBackend = defaultSearchBackend,
): void {
	const def = (
		name: string, risk: 'auto' | 'confirm' | 'danger', description: string,
		inputSchema: Record<string, unknown>, invoke: (input: any) => Promise<string>,
	) => reg.register({ name, risk, description, inputSchema, invoke });

	def('web_fetch', 'danger',
		'抓取一个公开 HTTPS 网页的正文文本（danger，每次都需用户确认）。仅允许公网 https，不访问内网/本机、不跟随跳转。',
		{ type: 'object', properties: { url: { type: 'string', description: '完整的 https 网址' } }, required: ['url'] },
		async (input) => {
			let url: URL;
			try { url = assertPublicHttps(String(input?.url ?? '')); }
			catch (e) { return `[FAIL] ${String((e as Error).message)}`; }
			try {
				const res = await fetch(url.toString(), {
					redirect: 'error',
					signal: AbortSignal.timeout(WEB_LIMITS.timeoutMs),
					headers: { 'user-agent': 'Planability/1.0 (+desktop)' },
				});
				if (!res.ok) { return `[FAIL] HTTP ${res.status} ${res.statusText}`; }
				const ct = res.headers.get('content-type') ?? '';
				const body = (await res.text()).slice(0, WEB_LIMITS.maxBytes);
				if (/application\/json/i.test(ct)) {
					return `[JSON] ${body.slice(0, WEB_LIMITS.maxText)}`;
				}
				const text = htmlToText(body).slice(0, WEB_LIMITS.maxText);
				return `标题：${url.hostname}\n${text || '(正文为空，可能是纯前端渲染页面)'}`;
			} catch (e) {
				return `[FAIL] 抓取失败：${String((e as Error)?.message ?? e)}`;
			}
		});

	def('web_search', 'danger',
		'搜索公开网页（danger，每次都需用户确认）。query 为搜索词，返回标题与链接。',
		{ type: 'object', properties: { query: { type: 'string' }, limit: { type: 'number' } }, required: ['query'] },
		async (input) => {
			const q = String(input?.query ?? '').trim();
			if (!q) { return '[FAIL] 搜索词为空。'; }
			try {
				const hits = await search.search(q, Math.max(1, Math.min(Number(input?.limit) || 5, 10)));
				if (!hits.length) { return '（无结果）'; }
				return hits.map((h, i) => `${i + 1}. ${h.title}\n   ${h.url}${h.snippet ? `\n   ${h.snippet}` : ''}`).join('\n');
			} catch (e) {
				return `[FAIL] 搜索失败：${String((e as Error)?.message ?? e)}`;
			}
		});
}