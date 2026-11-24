/**
 * `checkpoint_push` / `checkpoint_rewind` / `checkpoint_list` (two of the last three N-class gaps).
 *
 * WHAT THIS IS. `CheckpointStack` (ai/guards.ts) is an in-memory, bounded (10) stack of session
 * snapshots: `push` copies the whole history, `rewindTo` restores one AND PERSISTS it. The IPC
 * handlers exist and the UI drives them; the model had no way in.
 *
 * WHY `rewind` IS `danger` AND NOT `confirm`:
 *
 * `undo_last_write` reverts one write and is `confirm`. Rewinding is a different order of thing:
 * it replaces the live history with a snapshot, so EVERY MESSAGE AFTER THE CHECKPOINT IS GONE —
 * not soft-deleted, not undoable, gone from disk. That is the same class as clearing a session,
 * and `danger` is the level that says so. The tool also reports exactly how many turns died, so
 * the approval card and the reply both carry the cost.
 *
 * THE ONE THING A MODEL NEEDS THAT A USER DOES NOT:
 *
 * The stack is BOUNDED (10) and `push` shifts the oldest out SILENTLY. A user pushes one at a
 * time and never notices. A model that pushes every turn evicts the user's own manual checkpoints
 * — degrading the user's ability to recover without the user doing anything. So the tool reports
 * eviction instead of pushing quietly, and stamps `model:` on the label so a human reading the
 * list can tell their own checkpoints from the model's.
 */

import type { ToolRegistry } from './tools';

export interface CheckpointInfo {
	seq: number;
	label: string;
	at: number;
	turns: number;
}

export interface CheckpointToolDeps {
	/** Push a snapshot. Returns its seq. */
	push: (label: string) => number;
	/** Rewind + persist. False when no such checkpoint. */
	rewindTo: (seq: number) => Promise<boolean>;
	list: () => CheckpointInfo[];
}

export const CHECKPOINT_LIMITS = {
	/** Matches CheckpointStack's own default; surfaced so the reply can name the ceiling. */
	stackCeiling: 10,
	maxLabelChars: 60,
} as const;

const hhmm = (ms: number): string => new Date(ms).toTimeString().slice(0, 5);

function rowOf(c: CheckpointInfo): string {
	return `#${c.seq}  ${hhmm(c.at)}  ${c.label}  ${c.turns} 轮`;
}

export function registerCheckpointTools(reg: ToolRegistry, deps: CheckpointToolDeps): void {
	// Own the domain. An unset domain lands in `system`, which is alwaysOn, and a tool that can
	// discard a conversation would be un-switchable with nothing saying a switch ignored it.
	reg.setDomain('system');

	const def = (
		name: string, risk: 'auto' | 'confirm' | 'danger', description: string,
		inputSchema: Record<string, unknown>, invoke: (input: any) => Promise<string>,
	) => reg.register({ name, risk, description, inputSchema, invoke });

	def('checkpoint_list', 'auto',
		'列出手里的会话检查点（只读）：编号、时间、标签、当时有多少轮。checkpoint_rewind 需要这里的编号。',
		{ type: 'object', properties: {} },
		async () => {
			const list = deps.list();
			if (!list.length) {
				return '（还没有任何检查点。用 checkpoint_push 打一个，之后就能回滚到它）';
			}
			const ceiling = CHECKPOINT_LIMITS.stackCeiling;
			const full = list.length >= ceiling;
			return list.map(rowOf).join('\n')
				+ (full ? `\n\n（已到上限 ${ceiling} 个，再 push 会把最早那个挤掉）` : `\n（${list.length}/${ceiling}）`);
		});

	def('checkpoint_push', 'auto',
		'给当前会话打一个检查点（快照，不需要确认）。之后可以用 checkpoint_rewind 回到这里。'
		+ `注意最多保留 ${CHECKPOINT_LIMITS.stackCeiling} 个，新的会把最早的挤掉。`,
		{
			type: 'object',
			properties: { label: { type: 'string', description: '标签，缺省自动生成' } },
		},
		async (input) => {
			const raw = String(input?.label ?? '').trim();
			if (raw.length > CHECKPOINT_LIMITS.maxLabelChars) {
				return `[FAIL] 标签过长（上限 ${CHECKPOINT_LIMITS.maxLabelChars} 字）。`;
			}
			const before = deps.list().length;
			// Stamped so a human reading checkpoint_list can tell their own from the model's.
			const label = raw || `model:${hhmm(Date.now())}`;
			const stamped = raw ? label : `model:${label}`;
			const seq = deps.push(stamped);
			const after = deps.list().length;
			const turns = deps.list().find(c => c.seq === seq)?.turns;

			let reply = `已打检查点 #${seq}（${stamped}，当前 ${turns ?? '?'} 轮）。` +
				`用 checkpoint_rewind 回到这里。`;
			// Eviction is silent in CheckpointStack.push(); the tool is where it stops being silent.
			if (before >= CHECKPOINT_LIMITS.stackCeiling) {
				const evicted = deps.list()[0];
				reply += `\n注意：检查点已满（${CHECKPOINT_LIMITS.stackCeiling} 个），最早那个被挤掉了` +
					(evicted ? `（现在最早的是 #${evicted.seq} ${evicted.label}）` : '') +
					'。用户自己打的检查点被挤掉，等于替用户关了一扇回滚的门。';
			}
			return reply;
		});

	def('checkpoint_rewind', 'danger',
		'把当前会话回滚到一个检查点（danger，需要用户确认）。检查点之后的每一轮都会被丢弃，不可恢复。'
		+ '需要编号，来自 checkpoint_list。',
		{
			type: 'object',
			properties: { seq: { type: 'number', description: '检查点编号' } },
			required: ['seq'],
		},
		async (input) => {
			const seq = Number(input?.seq);
			if (!Number.isFinite(seq) || !Number.isInteger(seq) || seq < 0) {
				return '[FAIL] seq 必须是一个非负整数（来自 checkpoint_list 的 #编号）。';
			}
			const list = deps.list();
			const target = [...list].reverse().find(c => c.seq <= seq);
			if (!target) {
				const avail = list.length ? `现有：${list.map(rowOf).join('；')}` : '（一个都没有）';
				return `[FAIL] 没有编号 <= ${seq} 的检查点。${avail}`;
			}
			// How many turns are about to die. Reported BEFORE and AFTER, because the approval
			// card shows the cost and the reply confirms what was actually paid.
			const liveTurns = deps.list()[deps.list().length - 1]?.turns ?? 0;
			const ok = await deps.rewindTo(target.seq);
			if (!ok) { return `[FAIL] 回滚到 #${target.seq} 失败（可能刚被挤掉）。`; }
			const lost = Math.max(0, liveTurns - target.turns);
			return `已回滚到检查点 #${target.seq}（${hhmm(target.at)} ${target.label}，${target.turns} 轮）。` +
				(lost > 0
					? `之后的 ${lost} 轮已丢弃，不可恢复。`
					: '之后没有轮次被丢弃。');
		});
}