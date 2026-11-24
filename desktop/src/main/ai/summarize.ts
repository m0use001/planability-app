/* M19-1 worker-slot summariser.
 *
 * Runs on the WORKER slot, not the main one. That is the whole reason it is a separate file:
 * summarising competes for the same quota as answering, and a summary that blocks the answer
 * makes the user wait longer to get LESS context. The plan's "worker 槽分担摘要/抽取" is exactly
 * this split.
 *
 * TWO THINGS THAT ARE NOT OBVIOUS:
 *
 * 1. A FAILED SUMMARY MUST NEVER COST THE USER THEIR CONVERSATION. The budget falls back to
 *    folding when this returns '' or throws, but the prompt is written so the model produces a
 *    summary of the WORK DONE rather than a summary of the text — otherwise the model dutifully
 *    summarises "the user pasted a file_read dump" and we spend a round trip for nothing.
 *
 * 2. THE INPUT IS TRUNCATED BEFORE IT IS SENT. A 27k-token middle cannot itself be summarised
 *    inside a 128k budget without eating the headroom the budget exists to protect. So we clip
 *    per turn and cap the total, and we say so in the prompt when the input was clipped.
 */

import { callNonStream, type SlotConfig } from './provider';
import type { GuardMessage } from './guards';

export const SUMMARY_LIMITS = {
	/** Per-turn clip before sending. Same rationale as compaction: a file dump is not the point. */
	maxCharsPerTurn: 1500,
	/** Total chars of transcript handed to the summariser. */
	maxInputChars: 24_000,
	/** Upper bound on the summary we will accept; longer is mostly restatement. */
	maxSummaryChars: 2000,
} as const;

const SUMMARY_SYSTEM = [
	'你在为一个长对话做压缩摘要。目标是让另一个 AI 在**看不到原始记录**的情况下仍能继续工作。',
	'',
	'要求：',
	'1. 只写做过什么、决定了什么、当前卡在哪、用户明确要求过什么。',
	'2. 保留具体的标识符、文件路径、数字、未完成事项。',
	'3. 不要复述工具输出的原文，也不要写「用户粘贴了一个文件」这类无信息量的句子。',
	'4. 用中文，不超过 400 字。',
].join('\n');

/** Render a transcript for the summariser, clipping so the input fits. */
export function renderForSummary(messages: GuardMessage[]): { text: string; clipped: boolean } {
	let total = 0;
	let clipped = false;
	const lines: string[] = [];
	for (const m of messages) {
		let content = typeof m.content === 'string' ? m.content : '';
		if (content.length > SUMMARY_LIMITS.maxCharsPerTurn) {
			content = content.slice(0, SUMMARY_LIMITS.maxCharsPerTurn) + '…（本条已截断）';
			clipped = true;
		}
		// Tool results are the bulk and the least useful; keep a short marker for them so the
		// summary can tell "we read a file" from "we wrote one".
		const label = m.role === 'tool' ? '工具结果' : m.role === 'assistant' ? '助手' : '用户';
		const line = `【${label}】${content}`;
		if (total + line.length > SUMMARY_LIMITS.maxInputChars) {
			lines.push('…（更早的内容因长度限制未纳入摘要）');
			clipped = true;
			break;
		}
		total += line.length;
		lines.push(line);
	}
	return { text: lines.join('\n'), clipped };
}

/** The prompt, including the note that the input was clipped. */
export function summaryPrompt(transcript: string, clipped: boolean): string {
	return clipped
		? `以下是本次对话的较早部分（部分内容因长度限制被截断）：\n\n${transcript}\n\n请按要求输出摘要。`
		: `以下是本次对话的较早部分：\n\n${transcript}\n\n请按要求输出摘要。`;
}

/**
 * Build the summariser for the context budget.
 *
 * Returns a function that resolves to '' on any failure. It never throws, because the budget
 * already handles the fallback and an exception here would only skip straight past the
 * "summariser failed" report that tells the user their context was degraded.
 */
export function makeSummarizer(slot: () => SlotConfig): (messages: GuardMessage[]) => Promise<string> {
	return async (messages: GuardMessage[]): Promise<string> => {
		try {
			const { text, clipped } = renderForSummary(messages);
			if (!text.trim()) { return ''; }
			const out = await callNonStream(slot(), SUMMARY_SYSTEM, summaryPrompt(text, clipped));
			const trimmed = (out || '').trim();
			if (!trimmed) { return ''; }
			return trimmed.slice(0, SUMMARY_LIMITS.maxSummaryChars);
		} catch {
			// Deliberately silent. context-budget reports `degraded` and tells the user; a stack
			// trace here would be noise about a fallback that is working as designed.
			return '';
		}
	};
}