/* M19-3 extraction: turning a finished turn into candidate memories.
 *
 * Runs on the WORKER slot. The prompt is written to produce FACTS, because the failure mode of
 * auto-extraction is a memory library full of narrative ("the user asked about deployments and
 * we discussed pnpm") which is useless to search and impossible to correct.
 *
 * It returns '' on every failure — see the note in makeSummarizer: context-budget reports
 * `degraded`, and an exception here would only skip past that.
 */

import { callNonStream, type SlotConfig } from './provider';
import { LIMITS, stageProposals, type MemoryProposal } from './memory-log';
import type { GuardMessage } from './guards';

export const EXTRACT_LIMITS = {
	/** Only the recent tail is offered; the middle was already summarised or folded. */
	maxTurns: 8,
	/** Longest turn handed to the extractor. */
	maxCharsPerTurn: 1200,
	/** Total input cap. */
	maxInputChars: 12_000,
} as const;

const EXTRACT_SYSTEM = [
	'你在从一段人机对话里抽取**值得长期记住的事实**。只有通过下面全部检验的内容才值得记：',
	'',
	'1. 三个月后仍然成立（技术选型、约定、偏好、约束），而不是一次性状态（"今天在装依赖"）。',
	'2. 换一个人接手也能用上，而不是"用户说他刚才那个文件在桌面"。',
	'3. 只记事实与约束，不记过程与情绪。',
	'',
	'严格输出，每行一条，格式为：',
	'name | type | importance | body',
	'- name：≤20 字的名词短语，具体可检索',
	'- type：project（项目约定）/ feedback（用户偏好或纠正）/ reference（外部资料指针）/ handoff（交接要点）',
	'- importance：0 到 1，越重要越接近 1',
	'- body：一到两句，说清事实与约束',
	'',
	'**没有值得记的就输出空**，不要为了凑数把闲聊当记忆。以下都不该记：',
	'寒暄、道谢、一次性的任务进度、你无法确认的猜测、泛泛的通用知识。',
	'不要输出代码块、markdown 标题或任何解释性文字。',
].join('\n');

/** Render the recent turns for the extractor, clipping so the input fits. */
export function renderForExtract(messages: GuardMessage[]): { text: string; clipped: boolean } {
	const tail = messages.slice(-EXTRACT_LIMITS.maxTurns);
	let total = 0;
	let clipped = false;
	const lines: string[] = [];
	for (const m of tail) {
		let content = typeof m.content === 'string' ? m.content : '';
		// Tool output is not a memory source: "read file X" is an action, not a fact. Dropping
		// it here is what stops the library filling with operational noise.
		if (m.role === 'tool') { continue; }
		if (content.length > EXTRACT_LIMITS.maxCharsPerTurn) {
			content = content.slice(0, EXTRACT_LIMITS.maxCharsPerTurn) + '…（本条已截断）';
			clipped = true;
		}
		const label = m.role === 'assistant' ? '助手' : '用户';
		const line = `【${label}】${content}`;
		if (total + line.length > EXTRACT_LIMITS.maxInputChars) {
			lines.push('…（更早的内容因长度限制未纳入抽取）');
			clipped = true;
			break;
		}
		total += line.length;
		lines.push(line);
	}
	return { text: lines.join('\n'), clipped };
}

const EMPTY_RESULT = '（无值得长期记住的新事实）';

/** Parse the extractor's reply, tolerating fences and stray prose. */
export function parseExtract(raw: string): MemoryProposal[] {
	let text = String(raw ?? '').trim();
	// Models like to wrap output in a fence even when told not to. Strip it rather than
	// rejecting the whole batch — the content inside is usually fine.
	text = text.replace(/^```[a-z]*\s*/i, '').replace(/```\s*$/, '').trim();
	if (!text || text === EMPTY_RESULT || /没有值得|无值得|暂无/.test(text.slice(0, 40))) { return []; }
	return stageProposals(text).map(p => ({
		...p,
		body: p.body.slice(0, LIMITS.maxBodyChars),
	}));
}

/**
 * Build the settler for the agent.
 *
 * Returns a function that resolves to a result object and NEVER throws, because it runs after
 * the user has been told the turn is done — an exception there would be a crash on a turn that
 * already succeeded.
 */
export function makeMemorySettler(slot: () => SlotConfig): (messages: GuardMessage[]) => Promise<{
	ok: boolean; reason?: string;
	applied: { key: string; kind: string; seq: number }[];
	rejected: { name: string; reason: string }[];
	proposals: MemoryProposal[];
}> {
	return async (messages: GuardMessage[]) => {
		const empty = { ok: true, applied: [], rejected: [], proposals: [] };
		try {
			const { text, clipped } = renderForExtract(messages);
			// Nothing but tool output means nothing to learn from; do not spend a round trip.
			if (!text.trim()) { return { ...empty, reason: '本轮没有可抽取的对话内容' }; }
			const prompt = clipped
				? `以下是最近对话（部分已截断）：\n\n${text}\n\n请按格式输出值得记住的事实，没有就输出「${EMPTY_RESULT}」。`
				: `以下是最近对话：\n\n${text}\n\n请按格式输出值得记住的事实，没有就输出「${EMPTY_RESULT}」。`;
			const out = await callNonStream(slot(), EXTRACT_SYSTEM, prompt);
			const proposals = parseExtract(out);
			return { ok: true, applied: [], rejected: [], proposals };
		} catch (e) {
			// Reported, not swallowed: the user should know their memory was NOT updated, rather
			// than assuming it was.
			return { ...empty, ok: false, reason: (e as Error)?.message ?? String(e) };
		}
	};
}