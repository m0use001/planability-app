/* M20-1: WHERE workflows come from — the filesystem + registry half of the workflow kernel.
 *
 * Deliberately a separate module from ./workflow.ts, which is a zero-dependency pure kernel
 * (no fs, no electron, in-memory journals only). Keeping it that way is what lets `npm test`
 * compile the kernel standalone; an fs loader would have quietly ended that.
 *
 * WHAT THIS MODULE EXISTS TO GUARANTEE
 *
 * The bug it replaces: `workflowList` walked the SKILLS loader and JSON.parsed markdown bodies,
 * while `workflowRun` looked only in a hardcoded builtin array. Two resolvers, so the list and the
 * runnable set were different collections — every entry the UI showed failed to run with "unknown
 * workflow" on click, and the demo was in the runnable set but not in the list. The whole point of
 * one catalog is the invariant: if you can see it, you can run it.
 *
 * Three sources, in precedence order: builtins, user `*.json` files (shadowing them), then
 * assembled proposals from team assembly.
 *
 * PARSE AND VALIDATION FAILURES ARE REPORTED, NEVER DROPPED. A file that fails to parse is
 * returned as a placeholder with its reason in `problems`, because a workflow that disappears from
 * the list is indistinguishable from one that was never written — the user gets no hint they broke
 * something, only that their file is not there. `steps` alone cannot say "broken", only "empty".
 */

import * as fs from 'node:fs';
import * as path from 'node:path';
import { validateWorkflow, type Workflow } from './workflow';

export interface WorkflowCatalog {
	/** Every runnable workflow, deduplicated by id, in precedence order. */
	list: Workflow[];
	/** Ids where a user file shadows a built-in — the UI shows which one is live. */
	shadowed: Set<string>;
	/** Id -> reasons it cannot run. Those ids ARE in `list`, with whatever steps parsed. */
	problems: Map<string, string[]>;
}

/**
 * Read `<userDir>/*.json` into workflows, keeping every file visible regardless of health.
 *
 * The id comes from the FILENAME, not the body: two files that both declare `id: "demo"` would
 * otherwise collide silently and one would win by directory order. `{ ...wf, id: key }` means one
 * spelling of the answer, and a body's own `id` cannot disagree at runtime.
 */
export function readUserWorkflows(userDir: string): { workflows: Workflow[]; problems: Map<string, string[]> } {
	const problems = new Map<string, string[]>();
	const workflows: Workflow[] = [];
	let files: string[] = [];
	try {
		files = fs.readdirSync(userDir).filter(f => f.toLowerCase().endsWith('.json'));
	} catch {
		// No directory yet is the normal first-run case, not an error worth surfacing.
		return { workflows, problems };
	}
	for (const f of files) {
		const key = f.replace(/\.json$/i, '');
		let wf: Workflow;
		let parseProblem: string | null = null;
		try {
			wf = JSON.parse(fs.readFileSync(path.join(userDir, f), 'utf8')) as Workflow;
		} catch (e) {
			parseProblem = `不是合法 JSON：${(e as Error)?.message ?? String(e)}`;
			wf = { id: key, name: key, task: '', steps: [] };
		}
		if (parseProblem) { problems.set(key, [parseProblem]); }
		else {
			const v = validateWorkflow({ ...wf, id: key });
			if (v.ok === false) { problems.set(key, v.errors); }
		}
		workflows.push({ ...wf, id: key, name: wf?.name?.trim() || key, task: wf?.task ?? '' });
	}
	return { workflows, problems };
}

/** Merge the three sources into one collection. See the module docstring for the invariant. */
export function buildWorkflowCatalog(opts: {
	builtin: readonly Workflow[];
	userDir: string;
	assembled?: Iterable<Workflow>;
}): WorkflowCatalog {
	const byId = new Map<string, Workflow>();
	for (const w of opts.builtin) { byId.set(w.id, w); }

	const { workflows: userOnes, problems } = readUserWorkflows(opts.userDir);
	const shadowed = new Set<string>();
	for (const w of userOnes) {
		if (byId.has(w.id)) { shadowed.add(w.id); }
		byId.set(w.id, w);
	}
	for (const w of opts.assembled ?? []) { byId.set(w.id, w); }

	return { list: [...byId.values()], shadowed, problems };
}
