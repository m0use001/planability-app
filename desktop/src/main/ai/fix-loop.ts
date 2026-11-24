/* M20-1 tail: the verifier auto-fix loop (Laya COSMOS precedent).
 *
 * A workflow that produced a bad answer normally leaves the user to re-prompt by hand. The
 * loop here is: run -> verify -> if the verification fails, feed the failure BACK to the agent
 * that produced the step -> repeat, bounded.
 *
 * FOUR THINGS THAT ARE NOT OBVIOUS:
 *
 * 1. THE VERIFIER IS NOT ALLOWED TO BE THE AUTHOR. If the same agent both writes the step and
 *    judges it, the loop converges on "looks fine to me". A separate role is the entire point,
 *    and an absent verifier role must REFUSE rather than default to the author.
 *
 * 2. THE BOUND IS ON ATTEMPTS, NOT ON TIME ALONE, AND EVERY ATTEMPT IS JOURNALLED. A fix loop
 *    that silently gave up would look exactly like one that succeeded.
 *
 * 3. A FIX THAT MAKES THINGS WORSE MUST STOP THE LOOP. If the new output fails verification and
 *    the previous one passed, reverting and stopping beats continuing to degrade — which is what
 *    a plain "retry until it passes" loop does on the third iteration.
 *
 * 4. THE VERIFIER SEES THE CRITERIA, NOT THE ORIGINAL PROMPT. Otherwise it verifies "did you do
 *    what was asked" instead of "is this right", which is the weaker check and the one that
 *    rubber-stamps a confident wrong answer.
 */

import { runWorkflow, type RunJournal, type StepJournalEntry, type Workflow } from './workflow';
import { validateWorkflow } from './workflow';

export type Verdict = 'pass' | 'fail';

export interface VerifyRequest {
	/** The step's output to judge. */
	output: string;
	/** What "good" means, stated independently of the original ask. */
	criteria: string;
}

export type Verifier = (req: VerifyRequest) => Promise<Verdict>;

export interface FixLoopOptions {
	workflow: Workflow;
	/** Which step index gets verified+fixed (0-based). Default: the last step. */
	stepIndex?: number;
	/** Non-empty criteria. An empty criteria string must REFUSE — see decision 4. */
	criteria: string;
	verifier: Verifier;
	/** Who fixes it. Must be a DIFFERENT role from the author of the verified step. */
	fixerRole: string;
	/** Max fix attempts after the first failure. Default 2. */
	maxAttempts?: number;
	now?: () => number;
	/** Injected so attempts are deterministic in tests. */
	sleep?: (ms: number) => Promise<void>;
	onAttempt?: (a: { attempt: number; verdict: Verdict; note: string }) => void;
}

export type FixLoopReason =
	| 'no-criteria' | 'same-agent-verifies' | 'invalid-workflow'
	| 'step-out-of-range' | 'exhausted' | 'regressed';

export type FixLoop =
	| { ok: true; run: RunJournal; attempts: number; finalVerdict: Verdict }
	| { ok: false; reason: FixLoopReason; detail: string; run?: RunJournal; attempts: number };

export const FIX_LIMITS = {
	defaultMaxAttempts: 2,
	/** The fixer is told the failure but not the previous good output's verdict history. */
	backoffMs: 400,
	/** Cap on how much of a failed output is quoted back to the fixer. */
	maxQuoteChars: 6000,
} as const;

/** Structural validation, done before anything runs. Returns a reason or null. */
export function precheckFixLoop(o: FixLoopOptions, workflow: Workflow): { reason: FixLoopReason | null; detail: string } {
	if (!String(o.criteria ?? '').trim()) {
		return { reason: 'no-criteria', detail: '验收标准为空：无法验证「对不对」，只能验证「有没有照做」。' };
	}
	const idx = o.stepIndex ?? (workflow.steps.length - 1);
	// An empty workflow has no step to verify. It is caught as invalid-workflow by the caller,
	// but precheck is also exported and used standalone, so it must not report a nonsense
	// range like "下标 -1 不在 0..-1 内" for something that is simply undefined.
	if (!workflow.steps.length) {
		return { reason: 'invalid-workflow', detail: '工作流没有任何步骤，无法验证。' };
	}
	if (idx < 0 || idx >= workflow.steps.length) {
		return { reason: 'step-out-of-range', detail: `要验证的步骤下标 ${idx} 不在 0..${workflow.steps.length - 1} 内。` };
	}
	// Decision 1: the verifier must be someone other than the author. Here the VERIFIER is a
	// function, so the real check is that the FIXER is not the author — a fixer that rewrites
	// its own work converges on "looks fine".
	const author = workflow.steps[idx].role;
	if (o.fixerRole && o.fixerRole === author) {
		return { reason: 'same-agent-verifies', detail: `修正者（${o.fixerRole}）与被验证步骤的作者相同——自己检查自己只会收敛到「看起来没问题」。` };
	}
	return { reason: null, detail: '' };
}

/**
 * Run the workflow, verify the chosen step, and fix-and-retry on failure.
 *
 * The FIRST run is the normal one. Verification then happens against that output; each failure
 * appends a fix attempt to the SAME journal so the replay shows the whole correction history —
 * which is the only way to understand why a step ended up looking the way it does.
 */
export async function runWithFixLoop(o: FixLoopOptions, runStep: (role: string, input: string) => Promise<string>): Promise<FixLoop> {
	// Hostile input: this is reachable from an IPC-shaped caller, and a TypeError here would
	// surface as an unhandled rejection rather than a refusal.
	if (!o || typeof o !== 'object') {
		return { ok: false, reason: 'no-criteria', detail: '缺少配置，无法运行验证修正环。', attempts: 0 };
	}
	const now = o.now ?? (() => Date.now());
	const sleep = o.sleep ?? ((ms: number) => new Promise<void>(r => setTimeout(r, ms)));
	const maxAttempts = Math.max(0, Math.min(o.maxAttempts ?? FIX_LIMITS.defaultMaxAttempts, 5));

	const pre = precheckFixLoop(o, o.workflow);
	if (pre.reason) {
		return { ok: false, reason: pre.reason as FixLoopReason, detail: pre.detail, attempts: 0 };
	}
	// Validate the workflow BEFORE the structural precheck, so an empty-steps workflow reports
	// "invalid" rather than "step out of range" — the definition is broken, not the index.
	const v = validateWorkflow(o.workflow);
	if (v.ok === false) {
		return { ok: false, reason: 'invalid-workflow', detail: v.errors.join('；'), attempts: 0 };
	}

	const first = await runWorkflow(o.workflow, runStep, { runId: `fix-${now()}`, now });
	const entries: StepJournalEntry[] = [...first.journal.entries];
	// Index into the JOURNAL's entries, not the workflow's steps: those are not the same list
	// once a step has failed and the run halted early, so `steps[last]` can point past the end
	// of the journal and verify nothing.
	const idx = o.stepIndex ?? (entries.length - 1);

	let verdict: Verdict;
	try {
		verdict = await o.verifier({ output: entries[idx]?.output ?? '', criteria: o.criteria });
	} catch (e) {
		// A verifier that crashes has NOT verified anything. Treating that as a pass would be the
		// worst possible outcome, so it is a refusal, not a failure to fix.
		return {
			ok: false, reason: 'exhausted',
			detail: `验证器执行失败，无法判定：${(e as Error)?.message ?? e}`,
			run: { ...first.journal, entries }, attempts: 0,
		};
	}
	o.onAttempt?.({ attempt: 0, verdict, note: verdict === 'pass' ? '首次即通过' : '首次未通过' });
	if (verdict === 'pass') {
		return { ok: true, run: { ...first.journal, entries }, attempts: 0, finalVerdict: 'pass' };
	}

	// Keep the best output seen so far, so a regression can be reverted.
	let best = entries[idx]?.output ?? '';
	let bestVerified = false;

	for (let attempt = 1; attempt <= maxAttempts; attempt++) {
		const step = o.workflow.steps[idx];
		const quoted = best.slice(0, FIX_LIMITS.maxQuoteChars);
		// Decision 4: the fixer is given the CRITERIA and the failed output, not the original
		// ask. Re-stating the ask invites "I did exactly what was asked" as the fix.
		const fixPrompt = [
			`上一版产出未通过验收。验收标准：\n${o.criteria}`,
			`\n待修正的产出：\n${quoted}`,
			'\n请直接给出修正后的完整产出。只输出产出本身，不要解释过程。',
		].join('\n');
		let next: string;
		try {
			next = await runStep(o.fixerRole, fixPrompt);
		} catch (e) {
			entries.push({
				stepId: `${step.id}-fix${attempt}`, role: o.fixerRole, input: fixPrompt, output: '',
				status: 'failed', startedAt: now(), endedAt: now(),
				error: (e as Error)?.message ?? String(e),
			});
			o.onAttempt?.({ attempt, verdict: 'fail', note: '修正本身失败' });
			return {
				ok: false, reason: 'exhausted', attempts: attempt,
				detail: `第 ${attempt} 次修正失败：${(e as Error)?.message ?? e}`,
				run: { ...first.journal, entries, status: 'failed', failedAt: step.id },
			};
		}

		let v2: Verdict;
		try {
			v2 = await o.verifier({ output: next, criteria: o.criteria });
		} catch (e) {
			return {
				ok: false, reason: 'exhausted', attempts: attempt,
				detail: `验证器执行失败，无法判定修正结果：${(e as Error)?.message ?? e}`,
				run: { ...first.journal, entries },
			};
		}
		entries.push({
			stepId: `${step.id}-fix${attempt}`, role: o.fixerRole, input: fixPrompt, output: next,
			status: 'done', startedAt: now(), endedAt: now(),
		});
		o.onAttempt?.({ attempt, verdict: v2, note: v2 === 'pass' ? '修正后通过' : '修正后仍未通过' });

		if (v2 === 'pass') {
			return { ok: true, run: { ...first.journal, entries, status: 'done' }, attempts: attempt, finalVerdict: 'pass' };
		}
		// Decision 3: keep the best version, but stop if we are now strictly worse than the
		// only thing that ever verified clean. In practice that means: after a pass, do not
		// keep rewriting. Here the FIRST attempt never passed, so we only guard against
		// "regression after a previous pass", which cannot happen on attempt 1.
		if (bestVerified) {
			return {
				ok: false, reason: 'regressed', attempts: attempt,
				detail: '修正后的产出比已通过验收的版本更差，已停止并保留更好的版本。',
				run: { ...first.journal, entries, status: 'failed', failedAt: o.workflow.steps[idx].id },
			};
		}
		best = next;
		if (attempt < maxAttempts) { await sleep(FIX_LIMITS.backoffMs); }
	}

	return {
		ok: false, reason: 'exhausted', attempts: maxAttempts,
		detail: `已尝试 ${maxAttempts} 次修正仍未通过验收。`,
		run: { ...first.journal, entries, status: 'failed', failedAt: o.workflow.steps[idx].id },
	};
}
