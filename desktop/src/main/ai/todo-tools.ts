/* M17 待办表 tools (P2). Registered onto the same ToolRegistry as the domain tools so the
 * model can park and close items with ordinary tool calls.
 *
 * These are NOT part of the dump contract: the list is per-conversation scratch state for the
 * agent loop, so nothing here reaches pc-dump.json or the phone.
 */

import type { ToolRegistry } from './tools';
import type { TodoStore } from './todos';

export function registerTodoTools(reg: ToolRegistry, todos: TodoStore): void {
	const def = (
		name: string, risk: 'auto' | 'confirm' | 'danger', description: string,
		inputSchema: Record<string, unknown>, invoke: (input: any) => Promise<string>
	) => reg.register({ name, risk, description, inputSchema, invoke });

	def('agent_todo_add', 'auto',
		'把一条补充想法记进当前对话的待办表（不会打断正在进行的任务，之后会自动提醒你处理）。text 为待办内容',
		{ type: 'object', properties: { text: { type: 'string' } }, required: ['text'] },
		async (input) => {
			const t = todos.add(String(input?.text ?? ''));
			return t ? `已记入待办表 [${t.id}]：${t.text}` : '（内容为空，未记录）';
		});

	def('agent_todo_list', 'auto',
		'列出当前对话待办表里的未完成条目（含 id，供 agent_todo_done 关闭）',
		{ type: 'object', properties: {} },
		async () => {
			const open = todos.open();
			if (!open.length) { return '（待办表为空）'; }
			return open.map((t, i) => `${i + 1}. [${t.id}] ${t.text}`).join('\n');
		});

	def('agent_todo_done', 'auto',
		'把待办表里的一条标记为完成，需 id',
		{ type: 'object', properties: { id: { type: 'string' } }, required: ['id'] },
		async (input) => {
			const id = String(input?.id ?? '');
			return todos.done(id) ? `已完成待办 ${id}` : `未找到未完成的待办 ${id}`;
		});
}