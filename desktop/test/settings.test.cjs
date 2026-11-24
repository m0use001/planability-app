/* B5 settings-tool tests: capability_toggle + model_switch, plus the registry change they need.
 *
 * The assertion that matters most is the FIRST one in the switch section:
 *
 *   A switch must take effect on the NEXT TURN, not at the next launch.
 *
 * That is why applyDomainFilter used to stash defs instead of deleting them — a destructive filter
 * can only run once, so "flip the switch" and "flip it back" were unrepresentable, and the honest
 * fallback (tell the user to restart) is exactly the lying-switch behaviour this mechanism exists
 * to avoid. These tests pin the round trip: off -> tools gone from listForApi(); on -> back.
 *
 * The other thing pinned here is honesty under failure. A switch that writes a config key and
 * reports success while the tools are still there is worse than one that refuses, so
 * capability_toggle checks the filter's OWN report and says so when they disagree.
 *
 * Run: npm run test:settings
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-settings.txt');
const out = [];
let failed = 0;
function check(name, cond, detail) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name + (cond || detail === undefined ? '' : '  << ' + detail));
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { /* logging only */ } };
process.on('exit', flush);

const ai = path.join(__dirname, '..', '.test', 'src', 'main', 'ai');
const { ToolRegistry } = require(path.join(ai, 'tools.js'));
const { registerSettingsTools } = require(path.join(ai, 'settings-tools.js'));
const cat = require(path.join(__dirname, '..', '.test', 'shared', 'tool-domains.js'));

/** A stand-in config, plus the refresh callback the tools depend on. */
function makeEnv() {
	let cfg = { plugins: {}, mainModel: 'mimo-v2.6-flash' };
	const reg = new ToolRegistry();
	// Stand-in for the other domain groups the real index.ts registers. Using a probe name we
	// control is the point: it proves the filter acts on ANY domain, not on a hardcoded list.
	reg.setDomain('schedule');
	reg.register({ name: 'probe_schedule', risk: 'auto', description: 'p', inputSchema: {}, invoke: async () => 'x' });
	reg.setDomain('note');
	reg.register({ name: 'probe_note', risk: 'auto', description: 'p', inputSchema: {}, invoke: async () => 'x' });
	reg.register({ name: 'probe_note2', risk: 'auto', description: 'p', inputSchema: {}, invoke: async () => 'x' });
	reg.setDomain('system');
	reg.register({ name: 'probe_system', risk: 'auto', description: 'p', inputSchema: {}, invoke: async () => 'x' });

	registerSettingsTools(reg, {
		readConfig: () => cfg,
		writeConfig: (patch) => { cfg = { ...cfg, ...patch }; },
		refreshDomains: () => { reg.applyDomainFilter(cat.enabledDomains(cfg.plugins)); },
	});
	const api = () => reg.listForApi().map(t => t.function.name);
	return { reg, api, cfg: () => cfg, call: (n, i) => reg.get(n).invoke(i) };
}

(async () => {
	// ---- 1. the registry change: a filter can be re-applied, and undone ----
	{
		const reg = new ToolRegistry();
		reg.setDomain('schedule');
		reg.register({ name: 'a', risk: 'auto', description: 'p', inputSchema: {}, invoke: async () => '' });
		reg.setDomain('note');
		reg.register({ name: 'b', risk: 'auto', description: 'p', inputSchema: {}, invoke: async () => '' });
		reg.setDomain('system');
		reg.register({ name: 'c', risk: 'auto', description: 'p', inputSchema: {}, invoke: async () => '' });
		const names = () => reg.listForApi().map(t => t.function.name);

		reg.applyDomainFilter(cat.enabledDomains({ 'tool:schedule': false }));
		check('registry: the off domain is gone', !names().includes('a'), names().join(','));
		check('registry: other domains are untouched', names().includes('b') && names().includes('c'));

		// THE round trip. Without the stash this line is impossible and the tool can never undo
		// a switch — which is why the old delete-based filter made "toggle back on" unrepresentable.
		reg.applyDomainFilter(cat.enabledDomains({ 'tool:schedule': true }));
		check('registry: turning it back ON restores the tools', names().includes('a'), names().join(','));

		// And a second filter run must not accumulate skipped entries from the first.
		check('registry: skipped is recomputed, not accumulated',
			reg.skippedByDomain().size === 0, [...reg.skippedByDomain().keys()].join(','));
		reg.applyDomainFilter(cat.enabledDomains({ 'tool:note': false }));
		check('registry: skipped names only the domain now off',
			[...reg.skippedByDomain().keys()].join(',') === 'note',
			[...reg.skippedByDomain().keys()].join(','));
		check('registry: and schedule survived the second filter', names().includes('a'));
		check('registry: alwaysOn survives every filter pass', names().includes('c'));

		reg.applyDomainFilter(null);
		check('registry: null means everything is back', names().length === 3, names().join(','));
	}

	// ---- 2. capability_toggle: registration and shape ----
	{
		const e = makeEnv();
		check('capability_toggle: registered', typeof e.reg.get('capability_toggle')?.invoke === 'function');
		check('capability_toggle: asks before writing', e.reg.get('capability_toggle')?.risk === 'confirm');
		check('capability_toggle: lives in extend', e.reg.get('capability_toggle')?.domain === 'extend');
		check('model_switch: registered', typeof e.reg.get('model_switch')?.invoke === 'function');
		check('model_switch: asks before writing', e.reg.get('model_switch')?.risk === 'confirm');
		check('model_switch: lives in extend', e.reg.get('model_switch')?.domain === 'extend');
	}

	// ---- 3. the switch takes effect IMMEDIATELY (not next launch) ----
	{
		const e = makeEnv();
		check('switch: schedule tools start present', e.api().includes('probe_schedule'));
		const msg = await e.call('capability_toggle', { domain: 'schedule', enabled: false });
		check('switch: after the call the tools are gone from listForApi()',
			!e.api().includes('probe_schedule'), e.api().join(','));
		check('switch: it says the turn it takes effect on', msg.includes('下一轮'), msg);
		check('switch: it names what was lost, not just that something was',
			msg.includes('日程'), msg);

		const back = await e.call('capability_toggle', { domain: 'schedule', enabled: true });
		check('switch: turning it back on restores the tools in the same session',
			e.api().includes('probe_schedule'), e.api().join(','));
		check('switch: and says so', back.includes('已开启'), back);
	}

	// ---- 4. the config is written under the shared key ----
	{
		const e = makeEnv();
		await e.call('capability_toggle', { domain: 'note', enabled: false });
		check('persist: writes tool:<domain>, the key enabledDomains() reads',
			e.cfg().plugins['tool:note'] === false, JSON.stringify(e.cfg().plugins));
		check('persist: and did not clobber an unrelated key',
			!('tool:schedule' in e.cfg().plugins), JSON.stringify(e.cfg().plugins));
		await e.call('capability_toggle', { domain: 'note', enabled: true });
		check('persist: writes true the same way', e.cfg().plugins['tool:note'] === true);
	}

	// ---- 5. refusals: each one must be a refusal, not a silent no-op ----
	{
		const e = makeEnv();
		// Boolean('false') is true, so a model that sent the string "false" would switch a
		// capability ON while meaning to turn it OFF. This is the one coercion that inverts meaning.
		const strFalse = await e.call('capability_toggle', { domain: 'schedule', enabled: 'false' });
		check('refuses: a string "false" is not treated as false',
			strFalse.includes('[FAIL]') && strFalse.includes('布尔'), strFalse);
		check('refuses: and nothing was written', e.cfg().plugins['tool:schedule'] === undefined,
			JSON.stringify(e.cfg().plugins));
		check('refuses: 0 is not silently coerced either',
			(await e.call('capability_toggle', { domain: 'schedule', enabled: 0 })).includes('[FAIL]'));
		check('refuses: missing enabled', (await e.call('capability_toggle', { domain: 'schedule' })).includes('[FAIL]'));

		const unknown = await e.call('capability_toggle', { domain: 'nope', enabled: false });
		check('refuses: an unknown domain instead of inventing one',
			unknown.includes('[FAIL]') && unknown.includes('schedule'), unknown);
		check('refuses: and writes nothing for it', !('tool:nope' in e.cfg().plugins));

		// system is alwaysOn. Writing tool:system=false would be a config key that reads like it
		// worked and does nothing at all.
		const sys = await e.call('capability_toggle', { domain: 'system', enabled: false });
		check('refuses: the always-on domain', sys.includes('[FAIL]') && sys.includes('常开'), sys);
		check('refuses: and no config key was written for it', !('tool:system' in e.cfg().plugins),
			JSON.stringify(e.cfg().plugins));

		check('refuses: empty domain', (await e.call('capability_toggle', { domain: '', enabled: true })).includes('[FAIL]'));
	}

	// ---- 6. idempotence: saying "already like that" beats a fake change ----
	{
		const e = makeEnv();
		await e.call('capability_toggle', { domain: 'todo', enabled: false });
		const again = await e.call('capability_toggle', { domain: 'todo', enabled: false });
		check('idempotent: a no-op switch says so plainly',
			again.includes('已经') && again.includes('没有改动'), again);
	}

	// ---- 7. the self-defeating switch warns before the model discovers it ----
	{
		const e = makeEnv();
		const msg = await e.call('capability_toggle', { domain: 'extend', enabled: false });
		check('extend: turning off extend warns that the tool goes with it',
			msg.includes('扩展') && msg.includes('设置页'), msg);
		check('extend: and capability_toggle is genuinely gone afterwards',
			!e.api().includes('capability_toggle'), e.api().join(','));
	}

	// ---- 8. model_switch ----
	{
		const e = makeEnv();
		check('model: refuses empty', (await e.call('model_switch', { model: '  ' })).includes('[FAIL]'));
		check('model: refuses absurdly long', (await e.call('model_switch', { model: 'x'.repeat(200) })).includes('[FAIL]'));
		check('model: wrote nothing on refusal', e.cfg().mainModel === 'mimo-v2.6-flash');

		const same = await e.call('model_switch', { model: 'mimo-v2.6-flash' });
		check('model: switching to the current model says nothing changed',
			same.includes('已经') && same.includes('没有改动'), same);

		const sw = await e.call('model_switch', { model: 'gpt-4o-mini' });
		check('model: switches', e.cfg().mainModel === 'gpt-4o-mini', e.cfg().mainModel);
		check('model: names both the old and the new', sw.includes('mimo') && sw.includes('gpt-4o-mini'), sw);
		check('model: says which turn it starts on', sw.includes('下一轮'), sw);
		const padded = await e.call('model_switch', { model: ' abcd ' });
		check('model: trims whitespace rather than storing " abcd "',
			e.cfg().mainModel === 'abcd', e.cfg().mainModel);
		check('model: and the padded request is not mistaken for the current model',
			!padded.includes('没有改动'), padded);
	}

	// ---- 9. honesty when the filter did not actually take ----
	{
		// A refreshDomains that writes the config but forgets to re-filter: the exact shape of a
		// switch that lies. capability_toggle must notice and say so instead of claiming success.
		let cfg = { plugins: {} };
		const reg = new ToolRegistry();
		reg.setDomain('schedule');
		reg.register({ name: 'probe_schedule', risk: 'auto', description: 'p', inputSchema: {}, invoke: async () => '' });
		registerSettingsTools(reg, {
			readConfig: () => cfg,
			writeConfig: (p) => { cfg = { ...cfg, ...p }; },
			refreshDomains: () => { /* broken on purpose: writes nothing, filters nothing */ },
		});
		const msg = await reg.get('capability_toggle').invoke({ domain: 'schedule', enabled: false });
		check('honesty: when the filter did not take, it says 未生效',
			msg.includes('未生效'), msg);
		check('honesty: and it does NOT claim plain success',
			!msg.includes('已关闭「日程」能力域。下一轮对话起生效。'), msg);
		check('honesty: the tools are indeed still there, which is what it reported',
			reg.listForApi().some(t => t.function.name === 'probe_schedule'));
	}

	// ---- 10. the settings tools are themselves gated by extend ----
	{
		let cfg = { plugins: {} };
		const reg = new ToolRegistry();
		registerSettingsTools(reg, {
			readConfig: () => cfg,
			writeConfig: (p) => { cfg = { ...cfg, ...p }; },
			refreshDomains: () => { reg.applyDomainFilter(cat.enabledDomains(cfg.plugins)); },
		});
		reg.applyDomainFilter(cat.enabledDomains({ 'tool:extend': false }));
		check('gate: with extend off both settings tools are gone',
			!reg.get('capability_toggle') && !reg.get('model_switch'));
		reg.applyDomainFilter(cat.enabledDomains({ 'tool:extend': true }));
		check('gate: and they come back', !!reg.get('capability_toggle') && !!reg.get('model_switch'));
	}

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});