/* Card actions (stage 3 of the card plan): a card button becomes a REAL, GATED tool call.
 *
 * The status quo it replaces: proposal cards (schedule/checklist/…) "interact" by stitching
 * a user message back into the chat, which costs a full model round to do what is already
 * decided — the model wrote the card, the user clicked the button, and the tool's own schema
 * is the only schema involved. That round-trip also BYPASSES nothing today (the model then
 * calls the tool through the normal gate), but it burns latency and tokens on a decision the
 * user already made in the UI.
 *
 * The hard requirement from the plan, honoured by construction here:
 *   - an action can ONLY name a tool the spec declared (shared/cards.ts CardSpec.actions),
 *     and the tool is resolved through the SAME registry the model's calls go through —
 *     so a domain switch that hides `schedule_create` refuses the card button too;
 *   - risk comes from the TOOL, never from the card: confirm/danger go through the same
 *     approval card (with its arg editing), auto runs directly;
 *   - there is no second write path: invoke() is the registry tool's own invoke, so undo
 *     pushes and audits happen exactly as they do for a model-initiated call.
 *
 * Argument building lives HERE and not in shared/cards.ts because it is behaviour: it
 * validates card content and maps card field names onto tool field names (the schedule
 * card's {start,end,location} is NOT schedule_create's {startIso,endIso,note}). A raw
 * "pass the card JSON through as args" would silently create schedules missing `date`
 * — the exact failure mode the cross-end gap doc documents for the phone dialect.
 */
import { cardSpec, type CardAction } from '../../../shared/cards';
import type { CardActionRequest, CardActionRequestResult } from '../../../shared/chat';

/** The declared action, resolved. Null = the card kind or action id is not in the catalogue. */
export function resolveCardAction(kind: string, actionId: string): (CardAction & { perItem: boolean }) | null {
	const spec = cardSpec(kind);
	const a = spec?.actions?.find(x => x.id === actionId);
	return a ? { ...a, perItem: !!a.perItem } : null;
}

type Built = { args: Record<string, unknown> } | { error: string };

function asRecord(card: unknown): Record<string, unknown> | null {
	return card && typeof card === 'object' && !Array.isArray(card) ? card as Record<string, unknown> : null;
}

/**
 * Card content -> tool args, per (kind, actionId). CLOSED: an action declared in the spec
 * without a builder here refuses at runtime rather than inventing a mapping — that refusal
 * is what forces the two declarations to be added together.
 */
export function buildCardActionArgs(kind: string, actionId: string, card: unknown, itemIndex?: number): Built {
	const c = asRecord(card);
	if (!c) { return { error: '卡片内容不是对象，无法取参数' }; }

	if (kind === 'schedule' && actionId === 'create') {
		const items = Array.isArray(c.items) ? c.items : null;
		if (!items?.length) { return { error: '这张日程卡没有 items' }; }
		if (itemIndex === undefined || !Number.isInteger(itemIndex) || itemIndex < 0 || itemIndex >= items.length) {
			return { error: `需要指定第几项（0-${items.length - 1}）` };
		}
		const it = asRecord(items[itemIndex]);
		const title = String(it?.title ?? '').trim();
		if (!title) { return { error: `第 ${itemIndex + 1} 项缺少 title，无法创建` }; }
		const start = it?.start !== undefined && it?.start !== null && String(it.start) !== '' ? String(it.start) : undefined;
		const end = it?.end !== undefined && it?.end !== null && String(it.end) !== '' ? String(it.end) : undefined;
		const loc = String(it?.location ?? '').trim();
		// The card's `start` is the tool's `startIso`; `date` is derived so「今日视图」finds it —
		// a schedule without date is invisible to schedule_query(range=today), which is how
		// this mapping failure presents when it is done by hand.
		return {
			args: {
				title,
				...(start ? { startIso: start, date: start.slice(0, 10) } : {}),
				...(end ? { endIso: end } : {}),
				...(loc ? { note: loc } : {}),
			},
		};
	}

	if (kind === 'checklist' && actionId === 'create') {
		const items = Array.isArray(c.items) ? c.items : null;
		if (!items?.length) { return { error: '这张清单卡没有 items' }; }
		if (itemIndex === undefined || !Number.isInteger(itemIndex) || itemIndex < 0 || itemIndex >= items.length) {
			return { error: `需要指定第几项（0-${items.length - 1}）` };
		}
		const title = String(items[itemIndex] ?? '').trim();
		if (!title) { return { error: `第 ${itemIndex + 1} 项是空的，无法建成待办` }; }
		return { args: { title } };
	}

	return { error: `卡片动作 ${kind}/${actionId} 没有参数组装器（声明与实现没配对）` };
}

/** Minimal registry surface this module needs — satisfied by the real ToolRegistry. */
export interface CardActionRegistry {
	get(name: string): { name: string; risk: 'auto'; invoke: (input: any) => Promise<string> }
		| { name: string; risk: 'confirm' | 'danger'; invoke: (input: any) => Promise<string> }
		| undefined;
}

export interface CardActionDeps {
	registry: CardActionRegistry;
	/**
	 * The SAME approval funnel the model's calls use: show the card, resolve with the
	 * user's decision (possibly with EDITED args — that edit is the parameter control).
	 * Resolves { allow:false } on reject or the 120s dead-man timeout.
	 */
	requestApproval: (toolName: string, risk: 'confirm' | 'danger', argsJson: string) =>
		Promise<{ allow: boolean; args?: Record<string, unknown> }>;
}

/**
 * Resolve -> build -> gate -> invoke. One place, so there is exactly one answer to
 * "what happens when a card button is clicked", testable without Electron.
 */
export async function runCardAction(deps: CardActionDeps, req: CardActionRequest): Promise<CardActionRequestResult> {
	const kind = String(req?.kind ?? '');
	const actionId = String(req?.actionId ?? '');
	const action = resolveCardAction(kind, actionId);
	if (!action) {
		return { ok: false, error: `卡片没有名为 ${actionId} 的动作（kind=${kind || '空'}）` };
	}
	// Registry FIRST: a tool hidden by a domain switch must refuse the button, not be
	// invoked by a back path that predates the switch.
	const tool = deps.registry.get(action.tool);
	if (!tool) {
		return { ok: false, tool: action.tool, error: `工具 ${action.tool} 当前不可用（可能被能力域开关关闭）` };
	}
	const built = buildCardActionArgs(kind, actionId, req.card, req.itemIndex);
	if ('error' in built) { return { ok: false, tool: action.tool, error: built.error }; }

	let args = built.args;
	if (tool.risk !== 'auto') {
		const decision = await deps.requestApproval(tool.name, tool.risk, JSON.stringify(args));
		if (!decision.allow) {
			// Denied is a FIRST-CLASS outcome, not an error to swallow: the user said no
			// (or nobody answered within 120s), and the UI must be able to show that.
			return { ok: false, tool: tool.name, denied: true, error: '未获批准，动作未执行' };
		}
		// The approval card may carry edited args — same capability a model-initiated
		// confirm card already has. Only a plain object is accepted.
		if (decision.args && typeof decision.args === 'object' && !Array.isArray(decision.args)) {
			args = decision.args as Record<string, unknown>;
		}
	}
	const result = await tool.invoke(args);
	return { ok: true, tool: tool.name, result };
}
