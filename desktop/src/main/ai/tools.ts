/* Planability domain chat tools — ported from COD planability-core/src/tools.ts.
 * vscode.lm.registerTool replaced by our own ToolRegistry (the phone PTL risk model):
 *   auto    → straight through
 *   confirm → approval card (allow once / always allow for session / deny)
 *   danger  → approval card (allow once / deny only)
 * Descriptions/schemas mirror the COD package.json languageModelTools declarations. */

import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import type { Memory, Store } from '../store/store';
import type { MemoryLog } from './memory-log';
import type { UndoStack } from './undo';

export type Risk = 'auto' | 'confirm' | 'danger';

/** Per-invocation context the caller supplies (see ChatAgent.runTool). Lets a tool know
 *  which session/run it is executing inside without the model having to pass it — the model
 *  cannot know its own session id, so a tool that needs it must read it from here. */
export interface ToolContext {
	/** session id of the run executing this tool call */
	sessionId?: string;
}

import { toolDomain } from '../../../shared/tool-domains';
import { repeatParamOf, repeatText, parseRepeat, occurrences, REPEAT_SYNTAX } from './recurrence';
import { appendCheckin } from './plan-tools';
export interface ToolDef {
	name: string;
	description: string;
	inputSchema: Record<string, unknown>;
	risk: Risk;
	invoke: (input: any, ctx?: ToolContext) => Promise<string>;
	/**
	 * Which capability area this tool belongs to; see shared/tool-domains.ts. Absent reads as
	 * `system`, which cannot be switched off, so a tool that forgets to declare a domain is
	 * merely ungated rather than wrongly gated. That default is the safe direction to fail in.
	 */
	domain?: string;
}

export class ToolRegistry {
	private readonly defs = new Map<string, ToolDef>();
	/**
	 * The domain currently being registered into. Set per MODULE (see shared/tool-domains.ts for
	 * why not per tool) - registerPlanabilityTools and index.ts each call setDomain() before the
	 * group they register.
	 */
	private domain = 'system';
	/** Domain -> tool names a switch kept out. Empty when everything is enabled. Kept so the
	 *  Plugins page can say what a switch costs and a test can prove the switch did something. */
	private readonly skipped = new Map<string, string[]>();
	/** Domain -> tool names registered under it. How the page says what a switch would cost. */
	private readonly byDomain = new Map<string, string[]>();
	/**
	 * Defs a filter removed. Kept rather than discarded, and that is the whole point: a filter
	 * that deletes outright can only ever be applied ONCE, so "turn the capability back on"
	 * would be impossible from the model side and a switch edit would need a restart. The agent
	 * re-reads listForApi() every turn, so restoring here makes a switch bite on the next turn.
	 */
	private readonly stashed = new Map<string, ToolDef>();

	/** Start a group. Everything registered until the next call belongs to `domain`. */
	setDomain(domain: string): void { this.domain = domain; }

	register(def: ToolDef): void {
		const domain = this.domain;
		// An unknown domain is registered and REPORTED, never dropped. Dropping it would be the same
		// failure as a silently-ignored filter: the model asks for a tool that exists, gets "no such
		// tool", and nothing anywhere says a switch did it.
		if (!toolDomain(domain)) {
			console.warn('[tools] 未声明的工具域 ' + domain + '（工具 ' + def.name + '），已按启用处理');
		}
		this.defs.set(def.name, { ...def, domain });
		const list = this.byDomain.get(domain) ?? [];
		list.push(def.name);
		this.byDomain.set(domain, list);
	}

	/**
	 * Drop every tool whose domain is not in `enabled`, unless that domain cannot be turned off.
	 * `null` means "all enabled" and is the path every existing config takes.
	 *
	 * Returns what it dropped, by domain, so the caller can tell the user and a test can assert it.
	 */
	applyDomainFilter(enabled: Set<string> | null): Map<string, string[]> {
		// Always start from the FULL set. Without this, a second call could only ever remove
		// more: the first one already threw the defs away, so re-enabling was unrepresentable.
		// Always start from the FULL set. Without this, a second call could only ever remove
		// more: the first one already threw the defs away, so re-enabling was unrepresentable.
		this.restoreAll();
		if (!enabled) { return this.skipped; }
		for (const [domain, names] of this.byDomain) {
			const def = toolDomain(domain);
			if (!def || def.alwaysOn || enabled.has(domain)) { continue; }
			// Unknown and always-on domains are never filtered - see register().
			for (const name of names) {
				const tool = this.defs.get(name);
				if (tool) { this.stashed.set(name, tool); this.defs.delete(name); }
			}
			this.skipped.set(domain, names);
		}
		return this.skipped;
	}

	/** Put back everything a filter took out, so the next filter starts from the full set. */
	private restoreAll(): void {
		for (const [name, def] of this.stashed) { this.defs.set(name, def); }
		this.stashed.clear();
		this.skipped.clear();
	}

	get(name: string): ToolDef | undefined { return this.defs.get(name); }

	/** Domain -> tool names a switch removed. Empty when nothing was filtered. */
	skippedByDomain(): Map<string, string[]> { return this.skipped; }

	/** Shape for the OpenAI-compatible `tools` request field. */
	listForApi(): { type: 'function'; function: { name: string; description: string; parameters: Record<string, unknown> } }[] {
		return [...this.defs.values()].map(d => ({
			type: 'function' as const,
			function: { name: d.name, description: d.description, parameters: d.inputSchema }
		}));
	}
}

/** Local calendar day. NOT toISOString(): that flips to yesterday before 08:00 in UTC+8 — the
 *  same drift the phone side fixed with daykey.ts. Schedule ranges compare date strings to this. */
function localDay(d: Date = new Date()): string {
	return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function today(): string {
	return localDay();
}

function fmtDate(iso?: string): string {
	return iso ?? '（未指定日期）';
}

/** Score = 0.5·BM25-ish + 0.25·recency + 0.15·importance + 0.1·log(1+access) + 0.1·inIndex (phone formula). */
async function searchMemories(store: Store, query: string, limit: number): Promise<{ m: Memory; score: number }[]> {
	const all = await store.live<Memory>('memories');
	const terms = query.toLowerCase().split(/\s+/).filter(Boolean);
	const now = Date.now();
	const scored = all.map(m => {
		const hay = `${m.name} ${m.description} ${m.body}`.toLowerCase();
		let hits = 0;
		for (const t of terms) { if (hay.includes(t)) { hits++; } }
		const bm25 = terms.length ? hits / terms.length : 0;
		const days = (now - m.updatedAt) / 864e5;
		const recency = Math.exp(-days / 30);
		const importance = m.importance ?? 0.5;
		const access = Math.log(1 + (m.accessCount ?? 0));
		const inIndex = m.inIndex ? 1 : 0;
		const score = 0.5 * bm25 + 0.25 * recency + 0.15 * importance + 0.1 * access + 0.1 * inIndex;
		return { m, score: terms.length && hits === 0 ? score * 0.2 : score };
	});
	return scored.sort((a, b) => b.score - a.score).slice(0, limit);
}

export interface SettleResult {
	ok: boolean;
	reason?: string;
	applied: { key: string; kind: string; seq: number }[];
	rejected: { name: string; reason: string }[];
	note?: string;
}

export interface MemoryToolDeps {
	/**
	 * The append-only log. Injected rather than created here so the store and the tools share
	 * ONE log: two instances would each fold to their own view and quietly disagree.
	 */
	memLog: MemoryLog;
	/** Current workspace root, for workspace-scoped memories. */
	workspaceRoot: () => string;
	/**
	 * Settle the current turn into memories. Owned by the agent (it has the transcript) and
	 * injected here so `memory_settle` and the post-turn hook share ONE implementation.
	 */
	settle: () => Promise<SettleResult>;
}

/**
 * The `extend` domain: tools that drive the SAME features the UI already drives, through the
 * same modules. They are injected rather than imported because index.ts owns the instances
 * (the persona cache, the MCP hub) and two owners would be two caches that disagree.
 */
export interface ExtendToolDeps {
	/** Refresh the persona cache after an assistant write. index.ts owns that cache. */
	onAssistantsChanged: () => void | Promise<void>;
	/** The one skill loader the system prompt also uses, so a skill listed here is a skill in
	 *  the prompt — not a second, parallel view. */
	listSkills: () => Promise<Array<{ key: string; name: string; description: string; origin: string }>>;
	mcp: {
		/** Async because McpHub.list() is — and so that list/connect/disconnect all read the
		 *  hub the same way. A sync shape here would be a second, staler view of the hub. */
		list: () => Promise<Array<{ id: string; name: string; url: string; enabled: boolean }>>;
		setEnabled: (id: string, on: boolean) => void;
	};
}

/**
 * Write the log's folded view into the `memories` collection.
 *
 * The collection is a projection, not the truth — but the renderer, the dump/import path and the
 * phone sync all read the collection directly, so it has to be kept in step. Called after every
 * memory write rather than on a timer, so the collection is never stale for long.
 */
export async function projectMemories(log: MemoryLog, store: Store): Promise<number> {
	const view = log.fold();
	let n = 0;
	for (const m of view) {
		const existing = (await store.live<Memory>('memories')).find(x => x.id === m.id || x.name === m.name);
		if (m.forgotten) {
			// A forgotten memory is soft-deleted in the collection, so the phone sync and the
			// renderer stop showing it, while the log keeps the full trail.
			if (existing && !existing.deletedAt) { await store.softDelete('memories', existing.id); n++; }
			continue;
		}
		// `<Memory>` rather than an inferred literal: this is the one place a row is written from
		// the view, so it is where a field that exists in the log but not in the entity (which is
		// exactly how scope/workspace went missing) would be caught instead of silently dropped.
		await store.upsert<Memory>('memories', {
			id: existing?.id ?? m.id,
			createdAt: existing?.createdAt ?? m.createdAt,
			updatedAt: 0,
			name: m.name,
			description: m.description,
			body: m.body,
			type: m.type,
			importance: m.importance,
			accessCount: existing?.accessCount ?? m.accessCount,
			lastAccessAt: existing?.lastAccessAt ?? m.lastAccessAt,
			inIndex: existing?.inIndex ?? m.inIndex,
			links: existing?.links ?? m.links,
			// Scope lives with the ROW, not only in the log: without these two the entity had
			// nowhere to put them, so the memory page could not tell a user memory from a
			// workspace one no matter what it asked for. The log has carried them all along
			// (decision 3); the projection simply never carried them across.
			scope: m.scope,
			workspace: m.workspace ?? '',
		});
		n++;
	}
	return n;
}

/** Outcome of an attempted forget. Flat rather than a discriminated union because both callers
 * (the danger tool and the IPC channel) build a human sentence from it, and neither needs
 * narrowing to do that. */
export interface ForgetOutcome {
	ok: boolean;
	/** The log key that was forgotten. Present on success. */
	key?: string;
	/** Sequence number of the `forget` event, so a caller can cite it. */
	seq?: number;
	/** Name of the row, for a caller building a message. */
	name?: string;
	/** Why it did not happen. */
	reason?: string;
	/**
	 * Exists, but was already forgotten. Deliberately not the same as "unknown": a user who
	 * cannot tell "already deleted" from "never existed" reads both as "my delete was ignored".
	 */
	already?: boolean;
}

/**
 * Erase a memory by APPENDING a `forget` event, then re-projecting. This is the only erase path.
 *
 * Marking the row deleted in the collection while telling the log nothing is the resurrection
 * bug: the row leaves `store.live()` so `existing` comes back undefined, `m.forgotten` is still
 * false, and the next `projectMemories` upserts it straight back under the same id. A
 * materialised view cannot be edited independently of its source — erase in the source and derive.
 *
 * Three spellings of identity, most specific first: a log key (what `memory_search` and
 * `history` return), a store row id (what the renderer's collection list carries), and a name
 * (what a person or a model actually says). Accepting only one of them is how a delete ends up
 * silently deleting nothing.
 */
export async function forgetMemory(log: MemoryLog, store: Store, idOrKey: string): Promise<ForgetOutcome> {
	const want = String(idOrKey ?? '').trim();
	if (!want) { return { ok: false, reason: '需要 id 或 name。' }; }

	const storeRow = (await store.live<Memory>('memories')).find(r => r.id === want || r.name === want);
	// `get` answers by KEY only, which says nothing for a caller holding a name (what search
	// output and people use) or a store row id (what the page's list carries). `fold` still
	// contains forgotten rows, and searching it is what lets "already deleted" be told apart
	// from "never existed" — a row that was erased and a row that was never here are the same
	// `null` to `get` alone.
	const target = log.get(want)
		?? log.fold().find(v => v.name === want)
		?? (storeRow ? log.get(storeRow.name) : null);

	if (target?.forgotten) {
		return { ok: false, already: true, key: target.id, name: target.name,
			reason: `记忆 ${target.name} 已被删除` };
	}
	// Neither side knows it — reporting ok would be a delete that deleted nothing.
	if (!target && !storeRow) { return { ok: false, reason: `未找到记忆 ${want}` }; }

	// A row only the collection has (imported or synced in without ever passing a write) is
	// still forgotten, from the store's own fields. That starts its trail at this event rather
	// than refusing to erase a row the user can plainly see.
	const src = target ?? storeRow!;
	const ev = log.append({
		kind: 'forget',
		key: target?.id ?? storeRow!.id,
		name: src.name,
		body: src.body,
		description: src.description,
		type: src.type ?? 'project',
		importance: src.importance ?? 0.5,
		scope: src.scope ?? 'user',
		workspace: src.workspace,
		source: 'tool',
		reason: 'forgetMemory',
	});
	// `append` refuses an empty name — such a row would fold into something keyed by nothing —
	// so say so instead of pretending the erase happened.
	if (!ev) { return { ok: false, reason: '记忆名称为空，无法记录遗忘事件' }; }
	await projectMemories(log, store);
	return { ok: true, key: ev.key, seq: ev.seq, name: ev.name };
}

export function registerPlanabilityTools(
	store: Store, undo: UndoStack, memDeps: MemoryToolDeps,
	/** Enabled domains, or null for all. */
	enabledDomains: Set<string> | null = null,
	/** Extend-domain tools (assistants / skills / MCP). Optional so the core registry still builds
	 *  without them; omitted means those tools simply are not registered. */
	extend: ExtendToolDeps | null = null
): ToolRegistry {
	const { memLog, workspaceRoot, settle } = memDeps;
	const settleCurrentTurn = settle;
	const reg = new ToolRegistry();
	const def = (
		name: string, risk: Risk, description: string, inputSchema: Record<string, unknown>,
		invoke: (input: any) => Promise<string>
	) => reg.register({ name, risk, description, inputSchema, invoke });

	// ---- schedules ----
	reg.setDomain('schedule');
	def('schedule_query', 'auto', '查询 PC 端 Planability 日程。range: today|week|all（today/week 会展开 repeat 重复日程）',
		{ type: 'object', properties: { range: { type: 'string', enum: ['today', 'week', 'all'] }, limit: { type: 'number' } } },
		async (input) => {
			const all = await store.live<any>('schedules');
			const t = today();
			const weekEnd = localDay(new Date(Date.now() + 7 * 864e5));
			const range = input?.range ?? 'today';
			// repeat 展开：today/week 视图里，重复日程按规则铺到每一天（手机端同语义）；
			// all 视图只列基行 + 重复短语，不然一个每日日程会铺满整个列表。
			const rows: { d: string; s: any }[] = [];
			// 展开上界：today 视图只看今天；week 视图到 weekEnd。上界跟 range 走，
			// 否则"每周五"会漏进今天的列表（测试抓到的真 bug）。
			const bound = range === 'today' ? t : weekEnd;
			for (const s of all) {
				const d = s.date ?? s.startIso?.slice(0, 10) ?? '';
				if (range === 'all') { rows.push({ d, s }); continue; }
				const rule = s.repeat ? parseRepeat(s.repeat) : null;
				if (rule?.repeats && d) {
					for (const od of occurrences(rule, d, t, bound)) { rows.push({ d: od, s }); }
					continue;
				}
				const hit = range === 'today' ? d === t : d >= t && d <= weekEnd;
				if (hit) { rows.push({ d, s }); }
			}
			const filtered = rows
				.sort((a, b) => a.d.localeCompare(b.d))
				.slice(0, input?.limit ?? 50);
			return filtered.map(({ d, s }) => `${fmtDate(d)} ${s.startIso?.slice(11, 16) ?? ''} ${s.title}${repeatText(s.repeat, s.date)}${s.note ? `（${s.note}）` : ''} [${s.id}]`).join('\n') || '（无日程）';
		});

	def('schedule_create', 'confirm',
		'创建 PC 端日程（写操作，需用户确认）。全天日程：allDay=true + date（YYYY-MM-DD）；带时间的日程：startIso（完整 ISO，如 2026-11-04T15:00:00+08:00）。不允许「没有时间的日程」。支持跨端 repeat 重复规则',
		{
			type: 'object',
			properties: {
				title: { type: 'string' },
				allDay: { type: 'boolean', description: '是否全天日程；true 时必须有 date' },
				date: { type: 'string', description: '日期 YYYY-MM-DD（allDay 时必填；带时间日程可由 startIso 推导）' },
				startIso: { type: 'string', description: '开始时间（完整 ISO 带时区）；allDay=false 时必填' },
				endIso: { type: 'string' },
				note: { type: 'string' },
				tag: { type: 'string' },
				labels: { type: 'string' },
				repeat: { type: 'string', description: REPEAT_SYNTAX },
				repeat_until: { type: 'string', description: 'yyyy-MM-dd，缺省=永久' },
			},
			required: ['title'],
		},
		async (input) => {
			// D5 of the 2026-10-29 alignment: "no start" used to be an implicit mode. Phone requires
			// start; PC's leniency made an ambiguous state out of a missing field. Now all-day is
			// EXPLICIT (allDay=true + date) and everything else must carry a start.
			const allDay = input?.allDay === true;
			const date = String(input?.date ?? '').trim();
			const startIso = String(input?.startIso ?? '').trim();
			if (allDay) {
				if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) { return '[FAIL] allDay=true 需要 date（YYYY-MM-DD）。'; }
			} else {
				if (!startIso) { return '[FAIL] 需要 startIso（完整 ISO，如 2026-11-04T15:00:00+08:00）；全天日程请传 allDay=true + date。'; }
				if (Number.isNaN(Date.parse(startIso))) { return '[FAIL] startIso 不是合法时间。'; }
			}
			const rep = repeatParamOf(input);
			if (rep.error) { return `[FAIL] ${rep.error}`; }
			const rec = await store.upsert('schedules', {
				id: '', createdAt: 0, updatedAt: 0,
				title: input.title,
				date: allDay ? date : (date || startIso.slice(0, 10)),
				startIso: allDay ? undefined : startIso,
				endIso: allDay ? undefined : input.endIso,
				note: input.note,
				tag: input.tag, labels: input.labels,
				repeat: rep.rule,
			});
			return `已创建${allDay ? '全天' : ''}日程 ${rec.id}：${fmtDate(rec.date ?? rec.startIso?.slice(0, 10))} ${rec.title}${repeatText(rec.repeat, rec.date)}`;
		});

	def('schedule_delete', 'danger', '删除一条 PC 端日程（danger，需用户确认），需 id',
		{ type: 'object', properties: { id: { type: 'string' } }, required: ['id'] },
		async (input) => {
			const ok = await store.softDelete('schedules', input.id);
			return ok ? `已删除日程 ${input.id}` : `未找到日程 ${input.id}`;
		});

	// ---- todos ----
	reg.setDomain('todo');
	def('todo_query', 'auto', '查询 PC 端待办。status: open|done|all',
		{ type: 'object', properties: { status: { type: 'string', enum: ['open', 'done', 'all'] }, limit: { type: 'number' } } },
		async (input) => {
			const all = await store.live<any>('todos');
			const status = input?.status ?? 'open';
			const filtered = all.filter(t => status === 'all' ? true : status === 'done' ? t.status === 'done' : t.status !== 'done')
				.slice(0, input?.limit ?? 50);
			return filtered.map(t => `${t.status === 'done' ? '✓' : '·'} ${fmtDate(t.date)} ${t.title}${repeatText(t.repeat, t.date)} [${t.id}]`).join('\n') || '（无待办）';
		});

	def('todo_create', 'confirm', '创建 PC 端待办（写操作，需用户确认）。支持跨端 repeat 重复规则与标签',
		{
			type: 'object',
			properties: {
				title: { type: 'string' },
				date: { type: 'string', description: '归属日 YYYY-MM-DD，缺省=今天' },
				due: { type: 'string', description: '时刻 HH:MM 或完整 ISO（手机 due parity，上首页时间轴）' },
				priority: { type: 'number', description: '1 高 / 2 中 / 3 低；不传=未设置' },
				note: { type: 'string' },
				place: { type: 'string' },
				tag: { type: 'string' },
				labels: { type: 'string' },
				repeat: { type: 'string', description: REPEAT_SYNTAX },
				repeat_until: { type: 'string', description: 'yyyy-MM-dd，缺省=永久' },
			},
			required: ['title'],
		},
		async (input) => {
			const title = String(input?.title ?? '').trim();
			if (!title) { return '[FAIL] 标题为空'; }
			const date = /^\d{4}-\d{2}-\d{2}$/.test(String(input?.date ?? '').trim()) ? String(input.date).trim() : today();
			const rep = repeatParamOf(input);
			if (rep.error) { return `[FAIL] ${rep.error}`; }
			let time: string | undefined;
			if (input?.due !== undefined && String(input.due).trim() !== '') {
				// 手机 due parity：PC 的 todo.time 就是同一概念（home timeline 的出现时刻）。
				const due = String(input.due).trim();
				time = /^\d{1,2}:\d{2}$/.test(due) ? due
					: (/^\d{4}-\d{2}-\d{2}T/.test(due) ? due.slice(11, 16) : undefined);
				if (!time) { return '[FAIL] due 要 HH:MM 或完整 ISO 时间。'; }
			}
			let priority: number | undefined;
			if (input?.priority !== undefined && input?.priority !== null && input?.priority !== '') {
				priority = Number(input.priority);
				if (![1, 2, 3].includes(priority)) { return '[FAIL] priority 要 1（高）/2（中）/3（低）。'; }
			}
			const rec = await store.upsert('todos', {
				id: '', createdAt: 0, updatedAt: 0,
				title, date, time, priority, status: 'open',
				note: input.note, place: input.place,
				tag: input.tag, labels: input.labels,
				repeat: rep.rule,
			});
			return `已创建待办 ${rec.id}：${fmtDate(rec.date)} ${rec.title}${repeatText(rec.repeat, rec.date)}`;
		});

	def('todo_complete', 'confirm', '勾一条待办标记为完成（写操作，需用户确认），需 id',
		{ type: 'object', properties: { id: { type: 'string' } }, required: ['id'] },
		async (input) => {
			const list = await store.live<any>('todos');
			const item = list.find(t => t.id === input.id);
			if (!item) { return `未找到待办 ${input.id}`; }
			// 重复待办不打 done——done 是"整个系列没了"，手机端对应语义是打卡流水（checkin）。
			// PC 的打卡数据在 sidecar（checkins.json），与 plan-tools 的 checkin_query 同一份。
			if (item.repeat) {
				const rule = parseRepeat(item.repeat);
				if (rule?.repeats) {
					await appendCheckin(path.join(store.dataDir, 'checkins.json'), {
						todoId: item.id, date: today(), at: Date.now(), kind: 'time', textValue: '完成',
					});
					return `已打卡「${item.title}」（重复待办不打 done，按 ${item.repeat} 继续出现）`;
				}
			}
			item.status = 'done';
			await store.upsert('todos', item);
			return `已完成：${item.title}`;
		});

	// ---- notes ----
	reg.setDomain('note');
	def('note_search', 'auto', '全文搜索 PC 端笔记（子串匹配）',
		{ type: 'object', properties: { query: { type: 'string' }, limit: { type: 'number' } }, required: ['query'] },
		async (input) => {
			const all = await store.live<any>('notes');
			const q = String(input.query).toLowerCase();
			return all.filter(n => (n.title + '\n' + n.body).toLowerCase().includes(q))
				.slice(0, input.limit ?? 20)
				.map(n => `# ${n.title} [${n.id}]\n${n.body.slice(0, 400)}`)
				.join('\n---\n') || '（无匹配笔记）';
		});

	def('note_create', 'confirm', '创建 PC 端笔记（写操作，需用户确认）。notebook 传笔记本名可归入',
		{
			type: 'object',
			properties: {
				title: { type: 'string' },
				body: { type: 'string' },
				notebook: { type: 'string', description: '笔记本名（可选；须已存在，notebook_list 查）' },
			},
			required: ['title'],
		},
		async (input) => {
			let notebookId: string | undefined;
			if (input?.notebook !== undefined && String(input.notebook).trim() !== '') {
				const nb = (await store.live<any>('notebooks')).find((x: any) => x.name === String(input.notebook).trim());
				if (!nb) { return `[FAIL] 没有叫「${String(input.notebook).trim()}」的笔记本（用 notebook_list 查）`; }
				notebookId = nb.id;
			}
			const rec = await store.upsert('notes', { id: '', createdAt: 0, updatedAt: 0, title: input.title, body: input.body ?? '', notebookId });
			return `已创建笔记 ${rec.id}：${rec.title}`;
		});

	// ---- memory ----
	reg.setDomain('memory');
	def('memory_search', 'auto', '检索 Planability 记忆库（打分：主题相关+新鲜度+重要度）',
		{ type: 'object', properties: { query: { type: 'string' }, limit: { type: 'number' } }, required: ['query'] },
		async (input) => {
			const scored = await searchMemories(store, String(input.query), input.limit ?? 8);
			if (!scored.length) { return '（无匹配记忆）'; }
			return scored.map(({ m, score }) => `- [${m.type ?? 'project'}] ${m.name}（${m.description}）得分 ${score.toFixed(2)}\n  ${m.body.slice(0, 300)}`).join('\n');
		});

	reg.setDomain('memory');
	// ---- memory (M19-3: writes go through the append-only log; `memories` is the view) ----
	//
	// These used to write straight to the store, which made the audit trail impossible: there
	// was no record of what a memory looked like before an update, or that a forget happened at
	// all. Now every write appends an event and the collection is re-derived from the log.
	//
	// The collection is STILL written, because the renderer and the dump/import path read it
	// directly. The log is the truth; the collection is the projection.

	def('memory_add', 'confirm', '写入/更新记忆（同 name 即更新；写操作需用户确认）。type: project|feedback|reference|handoff',
		{ type: 'object', properties: { name: { type: 'string' }, description: { type: 'string' }, body: { type: 'string' }, type: { type: 'string', enum: ['project', 'feedback', 'reference', 'handoff'] }, importance: { type: 'number' }, scope: { type: 'string', enum: ['user', 'workspace'] } }, required: ['name', 'body'] },
		async (input) => {
			const name = String(input.name ?? '').trim();
			if (!name) { return '[FAIL] name 不能为空。'; }
			const scope = input.scope === 'workspace' ? 'workspace' : 'user';
			const existing = memLog.get(name);
			const ev = memLog.append({
				kind: existing ? 'update' : 'add',
				key: name, name,
				body: String(input.body ?? ''),
				description: String(input.description ?? existing?.description ?? name),
				type: input.type ?? existing?.type ?? 'project',
				importance: input.importance ?? existing?.importance ?? 0.5,
				scope: existing?.scope ?? scope,
				workspace: existing?.workspace || (scope === 'workspace' ? workspaceRoot() : ''),
				source: 'tool',
				reason: 'memory_add 工具写入',
			});
			if (!ev) { return '[FAIL] 记忆被拒绝写入（name 为空）。'; }
			await projectMemories(memLog, store);
			return `已${existing ? '更新' : '写入'}记忆 ${ev.key}（事件 #${ev.seq}）：${ev.name}`;
		});

	def('memory_forget', 'danger', '删除一条记忆（danger，需用户确认），需 id 或 name。事件保留在审计日志中，可恢复。',
		{ type: 'object', properties: { id: { type: 'string' }, name: { type: 'string' } } },
		async (input) => {
			// Accept either id or name: the model usually has the name from search output, and
			// making it round-trip through an id lookup is a pointless failure mode.
			const key = String(input.id ?? input.name ?? '').trim();
			if (!key) { return '[FAIL] 需要 id 或 name。'; }
			// One erase path, shared with the page's delete button. They used to be two different
			// things wearing the same name: this one appended the forget event, the button only
			// marked the row deleted — so the projection wrote it straight back, and the page's
			// delete never took while this tool's did.
			const r = await forgetMemory(memLog, store, key);
			if (!r.ok) {
				return `[FAIL] ${r.reason}。` + (r.already ? '可用 memory_restore 恢复。' : '');
			}
			return `已删除记忆 ${r.name}（事件 #${r.seq}）。历史保留在审计日志中，可用 memory_restore 恢复。`;
		});

	def('memory_restore', 'confirm', '恢复一条被删除的记忆（按 id 或 name）。',
		{ type: 'object', properties: { id: { type: 'string' }, name: { type: 'string' } } },
		async (input) => {
			const key = String(input.id ?? input.name ?? '').trim();
			const target = memLog.get(key);
			if (!target) { return `未找到记忆 ${key}`; }
			if (!target.forgotten) { return `记忆 ${target.name} 未被删除，无需恢复。`; }
			const ev = memLog.append({
				kind: 'restore', key: target.id, name: target.name, body: target.body,
				description: target.description, type: target.type, importance: target.importance,
				scope: target.scope, workspace: target.workspace,
				source: 'tool', reason: 'memory_restore 工具恢复',
			});
			await projectMemories(memLog, store);
			return ev ? `已恢复记忆 ${target.name}（事件 #${ev.seq}）。` : `[FAIL] 恢复 ${key} 失败。`;
		});

	def('memory_settle', 'auto', '把本轮对话沉淀为记忆（自动沉淀，后台走 worker 槽）。不需要参数。',
		{ type: 'object', properties: {} },
		async () => {
			// Delegates to the agent, which owns the transcript. Keeping this a tool means the
			// model can ask for it explicitly AND the post-turn hook can call the same path,
			// so there is only one implementation of "what counts as a memory".
			const settled = await settleCurrentTurn();
			if (!settled.ok) { return `[FAIL] ${settled.reason}`; }
			if (!settled.applied.length) {
				return settled.note ?? '（本轮没有值得沉淀的新记忆）';
			}
			const lines = settled.applied.map(a => `  + ${a.key}（${a.kind}，事件 #${a.seq}）`);
			const skipped = settled.rejected.length
				? `\n跳过 ${settled.rejected.length} 条（${settled.rejected.map(r => `${r.name}:${r.reason}`).join('、')}）`
				: '';
			return `已沉淀 ${settled.applied.length} 条记忆：\n${lines.join('\n')}${skipped}`;
		});

	def('memory_history', 'auto', '查看一条记忆的完整修改历史（append-only 审计日志），按 id 或 name。',
		{ type: 'object', properties: { id: { type: 'string' }, name: { type: 'string' } }, required: [] },
		async (input) => {
			const key = String(input.id ?? input.name ?? '').trim();
			if (!key) { return '[FAIL] 需要 id 或 name。'; }
			const hist = memLog.history(key);
			if (!hist.length) { return `未找到记忆 ${key} 的历史。`; }
			// This is the whole point of the log: the user can see WHY a memory says what it says.
			return [`记忆 ${key} 的历史（${hist.length} 条事件）：`,
			...hist.map(e => `  #${e.seq} ${new Date(e.at).toISOString().slice(0, 16).replace('T', ' ')} ${e.kind}`
				+ ` [${e.source}] ${e.scope === 'workspace' ? `workspace=${e.workspace}` : 'user'}`
				+ `${e.reason ? ` · ${e.reason}` : ''}`
				+ `\n    ${e.body.slice(0, 200).replace(/\n/g, ' ')}`),
			].join('\n');
		});

	reg.setDomain('system');
	def('now', 'auto', '获取当前日期与星期（本地时区）。相对日期（今天/明天/下周）先查它，不要猜日期',
		{ type: 'object', properties: {} },
		async () => {
			const d = new Date();
			const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][d.getDay()];
			return `${localDay(d)} ${week}`;
		});
	def('undo_last_write', 'danger', '撤销最近一次写操作（恢复该操作前的集合快照，danger 需用户确认）',
		{ type: 'object', properties: {} },
		async () => {
			const r = await undo.undoLast();
			if (!r) { return '（没有可撤销的写操作）'; }
			return `已撤销：${r.tool} 对「${r.collection}」的修改已回滚，恢复 ${r.restored} 条记录`;
		});

	// ---- system ----
	def('data_history', 'auto',
		'统计 PC 端各类数据条数与近况。type 可选（todo|schedule|note|memory|project|all，缺省 all）；days 可选——只看最近 N 天有更新的记录（1-365，缺省=全部历史）',
		{
			type: 'object',
			properties: {
				type: { type: 'string', enum: ['todo', 'schedule', 'note', 'memory', 'project', 'all'] },
				days: { type: 'number', description: '最近多少天（1-365）；缺省=全部历史' },
			},
		},
		async (input) => {
			const type = input?.type ?? 'all';
			// D2 of the 2026-10-29 alignment: phone has `days`, PC did not. The filter is on
			// updatedAt (falling back to createdAt) so "最近 N 天" means "touched in that window",
			// which is what the model asks when the user says "这周新增了多少".
			const daysRaw = Number(input?.days);
			const days = Number.isFinite(daysRaw) && daysRaw >= 1 ? Math.min(365, Math.floor(daysRaw)) : null;
			const cutoff = days === null ? 0 : Date.now() - days * 864e5;
			const recent = <T,>(rows: T[]): T[] => {
				if (days === null) { return rows; }
				return rows.filter((x: any) => Number(x?.updatedAt ?? x?.createdAt ?? 0) >= cutoff);
			};
			const lines: string[] = [];
			if (days !== null) { lines.push(`只看最近 ${days} 天有更新的记录（缺省=全部历史）`); }
			if (type === 'all' || type === 'schedule') {
				const s = recent(await store.live<any>('schedules'));
				lines.push(`日程 ${s.length} 条，最近：${s.slice(-3).map((x: any) => x.title).join('、') || '无'}`);
			}
			if (type === 'all' || type === 'todo') {
				const t = recent(await store.live<any>('todos'));
				lines.push(`待办 ${t.length} 条（未完成 ${t.filter((x: any) => x.status !== 'done').length}）`);
			}
			if (type === 'all' || type === 'note') {
				const n = recent(await store.live('notes'));
				lines.push(`笔记 ${n.length} 条`);
			}
			if (type === 'all' || type === 'memory') {
				const m = recent(await store.live('memories'));
				lines.push(`记忆 ${m.length} 条（索引层 ${m.filter((x: any) => x.inIndex).length}）`);
			}
			if (type === 'all' || type === 'project') {
				const p = recent(await store.live<any>('projects'));
				lines.push(`项目 ${p.length} 条${p.length ? '：' + p.slice(-3).map((x: any) => x.name).join('、') : ''}`);
			}
			return lines.join('\n');
		});

	def('usage_report', 'auto', '本会话各工具调用统计（tools_audit 最近 50 条）',
		{ type: 'object', properties: {} },
		async () => {
			const file = path.join(store.dataDir, 'tools_audit.jsonl');
			try {
				const raw = await fs.readFile(file, 'utf8');
				const lines = raw.trim().split('\n').slice(-50);
				const stats = new Map<string, { ok: number; fail: number; ms: number }>();
				for (const line of lines) {
					try {
						const e = JSON.parse(line);
						const s = stats.get(e.tool) ?? { ok: 0, fail: 0, ms: 0 };
						e.ok ? s.ok++ : s.fail++;
						s.ms += e.ms ?? 0;
						stats.set(e.tool, s);
					} catch { /* skip */ }
				}
				return [...stats.entries()].map(([t, s]) => `${t}: 成功 ${s.ok} 失败 ${s.fail} 累计 ${s.ms}ms`).join('\n') || '（暂无审计记录）';
			} catch {
				return '（暂无审计记录）';
			}
		});

	// ---- note 域（A 类：PC 早就能读写笔记，只是没给模型入口）----
	reg.setDomain('note');

	def('note_read', 'auto', '读取一条 PC 端笔记的完整正文。需要 id（从 note_search 拿）',
		{ type: 'object', properties: { id: { type: 'string' } }, required: ['id'] },
		async (input) => {
			const list = await store.live<any>('notes');
			const n = list.find(x => x.id === String(input.id ?? ''));
			if (!n) { return '未找到该笔记（可能已删除）。'; }
			return `${n.title ?? '（无标题）'}\n\n${n.body ?? ''}`;
		});

	def('note_append', 'confirm', '向一条 PC 端笔记末尾追加内容（写操作，需用户确认）。不覆盖正文',
		{ type: 'object', properties: { id: { type: 'string' }, text: { type: 'string' } }, required: ['id', 'text'] },
		async (input) => {
			const id = String(input.id ?? '');
			const extra = String(input.text ?? '');
			if (!id || !extra.trim()) { return '缺少 id 或 text；追加内容为空时不做任何事。'; }
			const list = await store.live<any>('notes');
			const n = list.find(x => x.id === id);
			if (!n) { return '未找到该笔记（可能已删除）。'; }
			const merged = `${n.body ?? ''}${n.body ? '\n\n' : ''}${extra}`;
			const rec = await store.upsert('notes', { ...n, body: merged });
			return `已在「${rec.title ?? '（无标题）'}」末尾追加 ${extra.length} 字，当前共 ${merged.length} 字`;
		});

	// ---- schedule 域（A 类：PC 有 create/delete，缺 update）----
	reg.setDomain('schedule');

	def('schedule_update', 'confirm', '修改一条 PC 端日程（写操作，需用户确认，可撤销）。只改传入的字段',
		{ type: 'object', properties: {
			id: { type: 'string' },
			title: { type: 'string' }, date: { type: 'string' },
			startIso: { type: 'string' }, endIso: { type: 'string' }, note: { type: 'string' },
			project: { type: 'number' }, priority: { type: 'number' },
			tag: { type: 'string' }, labels: { type: 'string' },
			repeat: { type: 'string', description: REPEAT_SYNTAX + '；none=取消重复' },
			repeat_until: { type: 'string', description: 'yyyy-MM-dd，缺省=永久' },
		}, required: ['id'] },
		async (input) => {
			const id = String(input.id ?? '');
			const list = await store.live<any>('schedules');
			const cur = list.find(x => x.id === id);
			if (!cur) { return '未找到该日程（可能已删除）。'; }
			// repeat 先解析：失败早退（手机 scheduleUpdate 同款纪律）。
			const rep = repeatParamOf(input);
			if (rep.error) { return `[FAIL] ${rep.error}`; }
			// 只改传入字段：一个"改标题"不该把时间清空，那是修改而非替换。
			const patch: Record<string, unknown> = {};
			for (const k of ['title', 'date', 'startIso', 'endIso', 'note', 'project', 'priority', 'tag', 'labels']) {
				if (input[k] !== undefined) { patch[k] = input[k]; }
			}
			if (rep.rule) { patch.repeat = rep.rule; } else if (rep.clear) { patch.repeat = undefined; }
			if (!Object.keys(patch).length) { return '没有给出任何要改的字段。'; }
			const rec = await store.upsert('schedules', { ...cur, ...patch });
			return `已更新日程 ${rec.title ?? '（未命名）'}（${fmtDate(rec.date ?? rec.startIso?.slice(0, 10))}），改动字段：${Object.keys(patch).join('、')}`;
		});

	// ---- extend 域（A 类：assistants / skills / MCP，PC 的 UI 早就在管这些）----
	if (extend) {
		reg.setDomain('extend');

		def('assistant_list', 'auto', '列出 PC 端所有助手人格（id + key + 名称），供 assistant_edit/delete 选择',
			{ type: 'object', properties: {} },
			async () => {
				const list = await store.live<any>('assistants');
				if (!list.length) { return '（没有助手人格）'; }
				return list.map(a => `${a.name}｜id=${a.id}｜key=${a.key}${a.builtin ? '｜内置' : ''}`).join('\n');
			});

		def('assistant_create', 'confirm', '新建一个助手人格（写操作，需用户确认）。它会给后续对话追加系统提示',
			{ type: 'object', properties: {
				name: { type: 'string' }, key: { type: 'string' }, color: { type: 'string' },
				slogan: { type: 'string' }, systemPrompt: { type: 'string' }, temperature: { type: 'number' },
			}, required: ['name', 'systemPrompt'] },
			async (input) => {
				const name = String(input.name ?? '').trim();
				const systemPrompt = String(input.systemPrompt ?? '').trim();
				if (!name || !systemPrompt) { return '名称和 systemPrompt 都不能为空。'; }
				const key = String(input.key ?? '').trim() || `asst_${Date.now().toString(36)}`;
				const rec = await store.upsert('assistants', {
					id: '', createdAt: 0, updatedAt: 0,
					key, name, systemPrompt,
					color: String(input.color ?? '#5B8CFF'),
					slogan: input.slogan ? String(input.slogan) : undefined,
					temperature: input.temperature != null ? Number(input.temperature) : undefined,
				});
				await extend.onAssistantsChanged();
				return `已创建助手「${rec.name}」（key=${rec.key}）。在设置里选它即对后续对话生效。`;
			});

		def('assistant_edit', 'confirm', '修改一个助手人格（写操作，需用户确认）。只改传入字段',
			{ type: 'object', properties: {
				id: { type: 'string' }, name: { type: 'string' }, color: { type: 'string' },
				slogan: { type: 'string' }, systemPrompt: { type: 'string' }, temperature: { type: 'number' },
			}, required: ['id'] },
			async (input) => {
				const id = String(input.id ?? '');
				const list = await store.live<any>('assistants');
				const cur = list.find(a => a.id === id);
				if (!cur) { return '未找到该助手（可用 assistant_list 查看）。'; }
				const patch: Record<string, unknown> = {};
				for (const k of ['name', 'color', 'slogan', 'systemPrompt', 'temperature']) {
					if (input[k] !== undefined) { patch[k] = input[k]; }
				}
				if (!Object.keys(patch).length) { return '没有给出任何要改的字段。'; }
				// key 与 builtin 不可改：key 是配置里的引用，builtin 决定重启后是否重新播种，
				// 让模型改这两个等于让它能悄悄接管或永久改掉一个内置人格。
				const rec = await store.upsert('assistants', { ...cur, ...patch });
				await extend.onAssistantsChanged();
				return `已更新助手「${rec.name}」，改动字段：${Object.keys(patch).join('、')}`;
			});

		def('assistant_delete', 'danger', '删除一个助手人格（danger，需用户确认）。内置人格可删，重启后会重新播种',
			{ type: 'object', properties: { id: { type: 'string' } }, required: ['id'] },
			async (input) => {
				const id = String(input.id ?? '');
				const ok = await store.softDelete('assistants', id);
				if (!ok) { return '未找到该助手。'; }
				await extend.onAssistantsChanged();
				return `已删除助手 ${id}`;
			});

		def('skill_list', 'auto', '列出 PC 端可用的技能（内置 + 用户目录），供回答时参考',
			{ type: 'object', properties: {} },
			async () => {
				const skills = await extend.listSkills();
				if (!skills.length) { return '（没有可用技能）'; }
				return skills.map(s => `${s.name}｜${s.key}｜${s.origin}${s.description ? '｜' + s.description : ''}`).join('\n');
			});

		def('mcp_list', 'auto', '列出 PC 端已配置的 MCP 服务器及其启用状态',
			{ type: 'object', properties: {} },
			async () => {
				const list = await extend.mcp.list();
				if (!list.length) { return '（没有配置 MCP 服务器）'; }
				return list.map(m => `${m.name}｜${m.enabled ? '已启用' : '已停用'}｜${m.url}`).join('\n');
			});

		def('mcp_connect', 'confirm', '启用一个 MCP 服务器（写操作，需用户确认）。需要 id（从 mcp_list 拿）',
			{ type: 'object', properties: { id: { type: 'string' } }, required: ['id'] },
			async (input) => {
				const id = String(input.id ?? '');
				const target = (await extend.mcp.list()).find(m => m.id === id);
				if (!target) { return '未找到该 MCP 服务器（可用 mcp_list 查看）。'; }
				extend.mcp.setEnabled(id, true);
				return `已启用 MCP 服务器「${target.name}」`;
			});

		def('mcp_disconnect', 'confirm', '停用一个 MCP 服务器（写操作，需用户确认）。需要 id',
			{ type: 'object', properties: { id: { type: 'string' } }, required: ['id'] },
			async (input) => {
				const id = String(input.id ?? '');
				const target = (await extend.mcp.list()).find(m => m.id === id);
				if (!target) { return '未找到该 MCP 服务器（可用 mcp_list 查看）。'; }
				extend.mcp.setEnabled(id, false);
				return `已停用 MCP 服务器「${target.name}」`;
			});
	}

	// Last, so every tool above has already been counted by domain. Filtering per-register
	// would need the caller to know the filter before registering, which is exactly the
	// coupling that produced this gap in the first place: the switch had existed since Phase 2
	// and the registry never asked what was enabled.
	reg.applyDomainFilter(enabledDomains);
	return reg;
}