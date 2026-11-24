/* Card actions (stage 3 of the card plan): a card button IS a gated tool call.
 *
 * What this file actually guards, beyond the happy path:
 *
 *   - EVERY tool a spec declares must exist in the REAL registry (registerPlanabilityTools
 *     against a real Store, aclass-style). A spec naming a tool that was never registered
 *     is a dead button that only fails when a user clicks it.
 *   - Argument mapping is behaviour with a closed shape: the schedule card's
 *     {start,end,location} is NOT schedule_create's {startIso,endIso,note}, and `date`
 *     must be derived from `start` or the created schedule is invisible to today's view.
 *     An action declared in the spec but without a builder here REFUSES rather than
 *     passing the card JSON through raw.
 *   - The approval gate is the SAME one the model's calls use: confirm pops the card,
 *     deny (or the 120s dead-man) must NOT invoke, and args EDITED on the approval card
 *     are what actually gets invoked — that edit is the parameter control.
 *   - A tool hidden from the registry refuses the button; there is no back path.
 *   - index.ts/preload wiring is asserted at source level (comments stripped first).
 *
 * Run: npm run test:cardaction
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-cardaction.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { /* logging only */ } };
process.on('exit', flush);

const ROOT = path.join(__dirname, '..');
const ai = path.join(ROOT, '.test', 'src', 'main', 'ai');
const { resolveCardAction, buildCardActionArgs, runCardAction } = require(path.join(ai, 'card-actions.js'));
const { CARD_SPECS } = require(path.join(ROOT, '.test', 'shared', 'cards.js'));
const { IPC } = require(path.join(ROOT, '.test', 'shared', 'chat.js'));
const { registerPlanabilityTools } = require(path.join(ai, 'tools.js'));
const { Store } = require(path.join(ROOT, '.test', 'src', 'main', 'store', 'store.js'));

// ---- real registry (aclass harness): both declared tools must actually be in it ----
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-cardaction-'));
const store = new Store(dir);
const undo = { push: async () => { }, undoLast: async () => null };
const memLog = { append: async () => ({ seq: 1 }), project: async () => ({ applied: 0, rejected: 0 }) };
const realReg = registerPlanabilityTools(store, undo, {
	memLog, workspaceRoot: () => dir, settle: async () => ({ ok: true, applied: [], rejected: [] }),
}, null, {
	onAssistantsChanged: async () => { },
	listSkills: async () => [],
	mcp: { list: async () => [], setEnabled: () => { } },
});

// A tiny fake registry for gate tests: records what was invoked, per-tool risk.
function fakeReg(tools) {
	const invoked = [];
	return {
		invoked,
		get: name => tools[name] && {
			name,
			risk: tools[name].risk,
			invoke: async args => { invoked.push({ name, args }); return `did:${name}`; },
		},
	};
}
// requestApproval stubs: capture the card shown, decide on demand.
function approver(decision) {
	const shown = [];
	return {
		shown,
		fn: async (toolName, risk, argsJson) => { shown.push({ toolName, risk, argsJson }); return decision(); },
	};
}

(async () => {
	// ---- 0. every tool named by a spec action exists in the REAL registry ----
	{
		const declared = CARD_SPECS.flatMap(s => (s.actions ?? []).map(a => `${s.kind}/${a.id}->${a.tool}`));
		check('specs declare at least one action', declared.length >= 2);
		for (const d of declared) {
			const tool = d.split('->')[1];
			check('declared tool registered: ' + d, typeof realReg.get(tool)?.invoke === 'function');
		}
		// Risk comes from the tool: both current actions are writes, so both must be
		// confirm — an `auto` write here would mean a card button can write unasked.
		for (const d of declared) {
			const tool = d.split('->')[1];
			check('declared tool is a gated write: ' + tool, realReg.get(tool)?.risk === 'confirm');
		}
		check('IPC channel is chat:cardAction', IPC.cardAction === 'chat:cardAction');
	}

	// ---- 1. resolveCardAction ----
	{
		check('resolve: schedule/create resolves to schedule_create',
			resolveCardAction('schedule', 'create')?.tool === 'schedule_create');
		check('resolve: perItem flag survives', resolveCardAction('schedule', 'create')?.perItem === true);
		check('resolve: unknown kind -> null', resolveCardAction('nope', 'create') === null);
		check('resolve: unknown action id -> null', resolveCardAction('schedule', 'delete') === null);
		// list has no actions; asking for one must not invent it.
		check('resolve: kind without actions -> null', resolveCardAction('list', 'create') === null);
	}

	// ---- 2. buildCardActionArgs: the mapping that raw pass-through would get wrong ----
	{
		const card = {
			items: [
				{ title: '评审', start: '2026-10-05T14:00:00+08:00', end: '2026-10-05T15:00:00+08:00', location: '三楼会议室' },
				{ title: '只写标题' },
			],
		};
		const a = buildCardActionArgs('schedule', 'create', card, 0);
		check('schedule: no error', !('error' in a));
		check('schedule: start maps to startIso', a.args?.startIso === '2026-10-05T14:00:00+08:00');
		check('schedule: date DERIVED from start (else invisible to today view)',
			a.args?.date === '2026-10-05');
		check('schedule: end maps to endIso', a.args?.endIso === '2026-10-05T15:00:00+08:00');
		check('schedule: location maps to note', a.args?.note === '三楼会议室');
		check('schedule: title kept', a.args?.title === '评审');
		// A title-only item must NOT carry empty startIso/endIso/note keys.
		const b = buildCardActionArgs('schedule', 'create', card, 1);
		check('schedule: absent fields are absent, not empty strings',
			!('startIso' in (b.args ?? {})) && !('endIso' in (b.args ?? {})) && !('note' in (b.args ?? {})));
		// The empty-title check catches a missing index too, so refusing ALONE does not pin the
		// guard down. The MESSAGE is what differs — and what the renderer acts on.
		check('schedule: no itemIndex refuses (perItem needs the index)',
			'error' in buildCardActionArgs('schedule', 'create', card));
		check('schedule: and it says the INDEX is what is missing, not the title',
			/需要指定第几项/.test(String((buildCardActionArgs('schedule', 'create', card) || {}).error ?? '')));
		check('schedule: out-of-range index refuses',
			'error' in buildCardActionArgs('schedule', 'create', card, 9));
		check('schedule: out-of-range also names the index, not the title',
			/需要指定第几项/.test(String((buildCardActionArgs('schedule', 'create', card, 9) || {}).error ?? '')));
		check('schedule: empty title refuses',
			('error' in buildCardActionArgs('schedule', 'create', { items: [{ title: '  ' }] }, 0)));
		check('schedule: card without items refuses',
			('error' in buildCardActionArgs('schedule', 'create', {}, 0)));
		check('schedule: non-object card refuses',
			('error' in buildCardActionArgs('schedule', 'create', '文本', 0)));

		const cl = buildCardActionArgs('checklist', 'create', { items: ['买牛奶', '写周报'] }, 1);
		check('checklist: string item -> {title}', cl.args?.title === '写周报' && Object.keys(cl.args).length === 1);
		check('checklist: missing index refuses', 'error' in buildCardActionArgs('checklist', 'create', { items: ['x'] }));

		// Declared in the spec but no builder here → refuse, never pass the card through raw.
		check('unmapped pair refuses instead of raw pass-through',
			('error' in buildCardActionArgs('list', 'export', { items: ['x'] }, 0)));
	}

	// ---- 3. the gate: auto runs directly, confirm/danger pop the SAME approval card ----
	{
		// auto: no card, straight through.
		const reg = fakeReg({ ping_auto: { risk: 'auto' } });
		const ap = approver(() => ({ allow: false, allowAlways: false }));
		// resolveCardAction won't find ping_auto — the gate only runs declared actions,
		// so exercise the gate through the real declared pair with a fake registry instead.
		const reg2 = fakeReg({ schedule_create: { risk: 'auto' } });
		const ap2 = approver(() => ({ allow: false, allowAlways: false }));
		void reg; void ap;
		const autoRun = await runCardAction({ registry: reg2, requestApproval: ap2.fn },
			{ kind: 'schedule', actionId: 'create', card: { items: [{ title: '晨会' }] }, itemIndex: 0 });
		check('auto: runs without any approval card', autoRun.ok === true && ap2.shown.length === 0);
		check('auto: tool got the BUILT args (title only)', reg2.invoked[0]?.args?.title === '晨会');

		// confirm: approval card pops with the BUILT args as JSON.
		const reg3 = fakeReg({ schedule_create: { risk: 'confirm' } });
		const ap3 = approver(() => ({ allow: true, allowAlways: false }));
		const okRun = await runCardAction({ registry: reg3, requestApproval: ap3.fn },
			{ kind: 'schedule', actionId: 'create', card: { items: [{ title: '评审', start: '2026-10-05T14:00:00+08:00' }] }, itemIndex: 0 });
		check('confirm: approval card shown once, named tool + risk', ap3.shown.length === 1
			&& ap3.shown[0].toolName === 'schedule_create' && ap3.shown[0].risk === 'confirm');
		check('confirm: card carries the built args JSON (user sees what will run)', !!ap3.shown[0]
			&& JSON.parse(ap3.shown[0].argsJson).startIso === '2026-10-05T14:00:00+08:00');
		check('confirm + allow: invoke happened, ok result returned', okRun.ok === true && okRun.result === 'did:schedule_create');

		// deny: the tool must NOT be invoked; denied is a first-class outcome.
		const reg4 = fakeReg({ schedule_create: { risk: 'confirm' } });
		const ap4 = approver(() => ({ allow: false, allowAlways: false }));
		const denied = await runCardAction({ registry: reg4, requestApproval: ap4.fn },
			{ kind: 'schedule', actionId: 'create', card: { items: [{ title: '不该建' }] }, itemIndex: 0 });
		check('deny: result says denied', denied.ok === false && denied.denied === true);
		check('deny: tool was NOT invoked', reg4.invoked.length === 0);

		// edited args on the approval card are what actually runs.
		const reg5 = fakeReg({ schedule_create: { risk: 'confirm' } });
		const ap5 = approver(() => ({ allow: true, allowAlways: false, args: { title: '改过的标题', date: '2026-10-06' } }));
		const edited = await runCardAction({ registry: reg5, requestApproval: ap5.fn },
			{ kind: 'schedule', actionId: 'create', card: { items: [{ title: '原标题' }] }, itemIndex: 0 });
		check('edit: invoke received the EDITED args, not the built ones',
			edited.ok === true && reg5.invoked[0]?.args?.title === '改过的标题' && reg5.invoked[0]?.args?.date === '2026-10-06');

		// A tool hidden from the registry refuses; there is no routing around it.
		const reg6 = fakeReg({});
		const ap6 = approver(() => ({ allow: true, allowAlways: false }));
		const hidden = await runCardAction({ registry: reg6, requestApproval: ap6.fn },
			{ kind: 'schedule', actionId: 'create', card: { items: [{ title: 'x' }] }, itemIndex: 0 });
		check('hidden tool: refused, no approval card, no invoke',
			hidden.ok === false && ap6.shown.length === 0 && reg6.invoked.length === 0
			&& String(hidden.error).includes('schedule_create'));

		// Unknown action: refused before any gate.
		const reg7 = fakeReg({ schedule_create: { risk: 'confirm' } });
		const ap7 = approver(() => ({ allow: true, allowAlways: false }));
		const unknown = await runCardAction({ registry: reg7, requestApproval: ap7.fn },
			{ kind: 'schedule', actionId: 'nuke', card: {}, itemIndex: 0 });
		check('unknown action: refused with no card and no invoke',
			unknown.ok === false && ap7.shown.length === 0 && reg7.invoked.length === 0);
	}

	// ---- 4. end to end against the REAL registry: the store actually changes ----
	{
		const ap = approver(() => ({ allow: true, allowAlways: false }));
		const r = await runCardAction({ registry: realReg, requestApproval: ap.fn },
			{ kind: 'schedule', actionId: 'create', card: { items: [{ title: '真实日程', start: '2026-10-07T09:00:00+08:00' }] }, itemIndex: 0 });
		check('real: schedule_create ran and answered ok', r.ok === true && String(r.result).includes('已创建日程'));
		const created = (await store.live('schedules')).find(s => s.title === '真实日程');
		check('real: schedule row exists with derived date', !!created && created.date === '2026-10-07');

		const r2 = await runCardAction({ registry: realReg, requestApproval: ap.fn },
			{ kind: 'checklist', actionId: 'create', card: { items: ['真实待办项'] }, itemIndex: 0 });
		const todo = (await store.live('todos')).find(t => t.title === '真实待办项');
		check('real: todo_create row exists', r2.ok === true && !!todo && todo.status === 'open');
	}

	// ---- 5. wiring at source level (strip // comments BEFORE matching) ----
	{
		const strip = f => fs.readFileSync(path.join(ROOT, f), 'utf8').replace(/\/\/[^\n]*/g, '');
		const main = strip('src/main/index.ts');
		check('index: ipcMain.handle(IPC_CHAT.cardAction wired', /ipcMain\.handle\(IPC_CHAT\.cardAction,/.test(main));
		const at = main.indexOf('ipcMain.handle(IPC_CHAT.cardAction');
		const body = main.slice(at, at + 1600);
		check('index: goes through runCardAction', body.includes('runCardAction('));
		check('index: uses the REAL registry', /registry,/.test(body));
		check('index: approvals land in the SAME approvalResolvers map', body.includes('approvalResolvers.set('));
		check('index: broadcast on the approval channel', body.includes('broadcast(CHAT_EVENTS.approval'));
		check('index: 120s dead-man auto-deny present', body.includes('120_000'));

		const pre = strip('src/preload/index.ts');
		check('preload: chatApi.cardAction bridge exists', /cardAction:/.test(pre) && pre.includes('IPC_CHAT.cardAction'));
	}

	// Exit code IS the contract here: a red run that exits 0 makes every chain link and
	// injection round look green. (Caught by injection round 1 of this very file.)
	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})();
