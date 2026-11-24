/**
 * `list_query` — the last of the N-class gaps.
 *
 * WHAT IT IS. `chat:listQuery` already ran a CLOSED-schema query against the live store for the
 * renderer (that is what makes the `list` card re-renderable without a model round trip: the card
 * carries a query, not content, and main answers it). The model had no way to ask the same
 * question, so it could write a `list` card but could not itself see what the card would show.
 *
 * THAT IS THE WHOLE POINT OF THIS TOOL, AND IT IS WHY THE ARGS ARE THE CARD'S, NOT MINE:
 *
 * A `list` card's `query` field is ALREADY the schema (collection/where/order/limit), and
 * `normalizeListQuery` is the one place that validates it — unknown key refused, limit clamped,
 * the applied limit echoed back. Reusing it means a model query and a card query cannot drift:
 * the model asks exactly what the card asks, through exactly the same gate.
 *
 * WHY IT IS READ-ONLY, AND WHY THE REPLY SAYS SO:
 *
 * `runListQuery` calls `store.live(...)` and nothing else. No write path exists here, which is
 * also why this is `auto` — but the reply still says it is read-only, because the collection
 * names look like the write tools' subjects (schedules/todos/notes) and a model reading
 * "schedules" without that note might assume it created one.
 */

import type { ToolRegistry } from './tools';
import { normalizeListQuery, type NormalizedListQuery } from './list-card';
import type { ListCardResult } from '../../../shared/chat';

export interface ListQueryToolDeps {
	/** Run the already-normalized query. index.ts owns the store. */
	run: (q: NormalizedListQuery) => Promise<ListCardResult>;
}

const pad2 = (n: number): string => String(n).padStart(2, '0');

const whenOf = (iso?: string): string => {
	if (!iso) { return ''; }
	const d = new Date(iso);
	if (Number.isNaN(d.getTime())) { return ''; }
	return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
};

export function registerListQueryTool(reg: ToolRegistry, deps: ListQueryToolDeps): void {
	// Own the domain: an unset domain lands in `system` (alwaysOn) and the tool becomes
	// un-switchable with nothing saying a switch ignored it.
	reg.setDomain('system');

	const def = (
		name: string, risk: 'auto' | 'confirm' | 'danger', description: string,
		inputSchema: Record<string, unknown>, invoke: (input: any) => Promise<string>,
	) => reg.register({ name, risk, description, inputSchema, invoke });

	def('list_query', 'auto',
		'只读地查一遍实时数据（日程/待办/笔记/笔记本/项目），用于回答前核实或画图表，不写入。'
		+ '查询结构和 list 卡片的 query 完全一致 —— 模型问的就是卡片会显示的。',
		{
			type: 'object',
			properties: {
				collection: { type: 'string', description: 'schedules / todos / notes / notebooks / projects' },
				where: {
					type: 'object',
					description: '过滤条件，全部可选；status 只对 todos，date 只对 schedules/todos，pinned 只对 notes，project 是数字 id',
				},
				order: { type: 'string', enum: ['updated', 'created'], description: '排序，缺省 updated' },
				limit: { type: 'number', description: '条数（上限由主进程收紧）' },
			},
			required: ['collection'],
		},
		async (input) => {
			const n = normalizeListQuery(input);
			if (!n.ok) { return `[FAIL] ${n.error}`; }
			let res: ListCardResult;
			try {
				res = await deps.run(n.query);
			} catch (e) {
				return `[FAIL] 查询失败：${(e as Error)?.message ?? String(e)}`;
			}
			if (!res.rows.length) {
				return `查询了 ${res.total} 条，命中 0 条（集合 ${n.query.collection}）。`;
			}
			const body = res.rows.map((r: import('../../../shared/chat').ListCardRow) =>
				`${r.label}${r.when ? '  ' + whenOf(r.when) : ''}${r.detail ? ' — ' + r.detail : ''}`).join('\n');
			// total > rows means the limit clipped it: say both, or the model will report a
			// partial view as the whole answer.
			const head = `只读查询 ${n.query.collection}：命中 ${res.total} 条，返回前 ${res.rows.length} 条`
				+ `（按 ${n.query.order === 'created' ? '创建时间' : '更新时间'} 倒序）`;
			return head + '\n' + body;
		});
}