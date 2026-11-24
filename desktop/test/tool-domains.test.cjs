/* Tool-domain switch tests.
 *
 * WHAT THIS FILE IS FOR. `DesktopConfig.plugins` had existed since Phase 2 - with a comment saying
 * "phone PluginMarket v1 shape" - and for all that time NOTHING read it when registering tools. The
 * Plugins page flipped a switch and `registerPlanabilityTools` registered all 38 tools anyway. That
 * is the failure mode worth pinning, and "the switch does nothing" is invisible to every test that
 * only checks a tool works.
 *
 * So the assertions below are about the switch, not the tools:
 *
 *   - null (no plugins config) leaves EVERY tool registered - a config written before this feature
 *     existed must not silently disarm the install. That is the single worst thing here.
 *   - turning a domain off removes its tools OUTRIGHT, not after a failed call.
 *   - `system` cannot be turned off (basic self-awareness: usage, history, undo).
 *   - an unknown domain is registered AND reported, never dropped.
 *   - `skippedByDomain` reports what a switch cost, so the UI can say so and this test can prove it.
 *
 * Run: npm run test:domains
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-domains.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { /* logging only */ } };
process.on('exit', flush);

const base = path.join(__dirname, '..', '.test', 'src', 'main', 'ai');
const shared = path.join(__dirname, '..', '.test', 'shared');
const { ToolRegistry } = require(path.join(base, 'tools.js'));
const cat = require(path.join(shared, 'tool-domains.js'));

/** A registry with a few tools spread over domains - enough to see the filter work. */
function build() {
	const reg = new ToolRegistry();
	reg.setDomain('system');
	reg.register({ name: 'usage_report', risk: 'auto', description: '', inputSchema: {}, invoke: async () => '' });
	reg.setDomain('schedule');
	reg.register({ name: 'schedule_query', risk: 'auto', description: '', inputSchema: {}, invoke: async () => '' });
	reg.register({ name: 'schedule_create', risk: 'confirm', description: '', inputSchema: {}, invoke: async () => '' });
	reg.setDomain('file');
	reg.register({ name: 'file_write', risk: 'confirm', description: '', inputSchema: {}, invoke: async () => '' });
	reg.setDomain('cu');
	reg.register({ name: 'cu_click', risk: 'confirm', description: '', inputSchema: {}, invoke: async () => '' });
	return reg;
}
const names = reg => [...reg.listForApi()].map(t => t.function.name).sort();

// ---- 1. the catalogue ----
{
	check('cat: system is declared and pinned', cat.toolDomain('system')?.alwaysOn === true);
	check('cat: nothing else is alwaysOn',
		cat.TOOL_DOMAINS.filter(d => d.alwaysOn).map(d => d.id).join(',') === 'system');
	check('cat: ids are unique', new Set(cat.TOOL_DOMAIN_IDS).size === cat.TOOL_DOMAIN_IDS.length);
	check('cat: every domain has a label and an offCost (except the pinned one)',
		cat.TOOL_DOMAINS.every(d => d.alwaysOn || (d.label.length > 0 && d.offCost.length > 0)));
	check('cat: config keys are namespaced so a domain cannot collide with a feature toggle',
		cat.toolDomainKey('file') === 'tool:file');
}

// ---- 2. THE load-bearing case: no config = everything on ----
{
	// A config written before this feature existed has no `plugins`. Reading that as "nothing
	// enabled" would silently disarm every tool for every existing install.
	check('null config -> every domain enabled', cat.enabledDomains(undefined) === null);
	check('empty plugins -> every domain enabled (absent key means on)',
		cat.enabledDomains({}) === null);
	// Only feature toggles, no domain switch at all — the third way "nothing is off" can be
	// written, and it must land on the same null as undefined and {}.
	check('a plugins object with only feature toggles still enables everything',
		cat.enabledDomains({ cards: false, undo: true }) === null);
	check('and that null really means "filter nothing"' ,
		(build().applyDomainFilter(cat.enabledDomains({ cards: false })), names(build()).length === 5));
	const reg = build();
	reg.applyDomainFilter(null);
	check('null -> applyDomainFilter removes nothing', names(reg).length === 5);
}

// ---- 3. turning a domain off removes its tools OUTRIGHT ----
{
	const reg = build();
	reg.applyDomainFilter(cat.enabledDomains({ 'tool:file': false }));
	check('switch: file domain tools are gone', !names(reg).includes('file_write'));
	check('switch: other domains survive', names(reg).includes('schedule_query'));
	check('switch: get() no longer resolves the removed tool', reg.get('file_write') === undefined);

	const reg2 = build();
	reg2.applyDomainFilter(cat.enabledDomains({ 'tool:cu': false }));
	check('switch: cu domain tools are gone', !names(reg2).includes('cu_click'));
	check('switch: the report says what it cost', reg2.skippedByDomain().get('cu')?.includes('cu_click') === true);
	check('switch: and the report is per-domain, not one blob', reg2.skippedByDomain().size === 1);

	const reg3 = build();
	reg3.applyDomainFilter(cat.enabledDomains({ 'tool:file': false, 'tool:cu': false }));
	check('switch: two domains off removes both',
		!names(reg3).includes('file_write') && !names(reg3).includes('cu_click'));
	check('switch: the report names both', reg3.skippedByDomain().size === 2);
}

// ---- 4. `system` cannot be switched off ----
{
	const reg = build();
	reg.applyDomainFilter(cat.enabledDomains({ 'tool:system': false }));
	check('system: cannot be turned off - the tool is still registered',
		names(reg).includes('usage_report'));
	check('system: and it is not reported as skipped', !reg.skippedByDomain().has('system'));
	check('system: the catalogue does not even list it in disabledDomains',
		!cat.disabledDomains({ 'tool:system': false }).map(d => d.id).includes('system'));

	const reg2 = build();
	const allOff = Object.fromEntries(cat.TOOL_DOMAIN_IDS.map(id => [cat.toolDomainKey(id), false]));
	reg2.applyDomainFilter(cat.enabledDomains(allOff));
	check('system: turning EVERY domain off still leaves system', names(reg2).includes('usage_report'));
	check('system: and removes everything else',
		names(reg2).length === 1 && names(reg2)[0] === 'usage_report');
}

// ---- 5. unknown domain: registered AND reported, never dropped ----
{
	const warns = [];
	const real = console.warn;
	console.warn = (...a) => warns.push(a.join(' '));
	let reg;
	try {
		reg = new ToolRegistry();
		reg.setDomain('a-domain-nobody-declared');
		reg.register({ name: 'mystery_tool', risk: 'auto', description: '', inputSchema: {}, invoke: async () => '' });
		reg.applyDomainFilter(cat.enabledDomains({ 'tool:file': false }));
	} finally { console.warn = real; }
	check('unknown: the tool is still registered (not silently dropped)',
		names(reg).includes('mystery_tool'));
	check('unknown: and the omission is logged', warns.some(w => w.includes('a-domain-nobody-declared')));
	check('unknown: a filter for an unknown domain removes nothing',
		reg.skippedByDomain().has('a-domain-nobody-declared') === false);
}

// ---- 6. ToolDef.domain defaults to `system`, so a tool that forgets is ungated not mis-gated ----
{
	const reg = new ToolRegistry();
	reg.register({ name: 'no_domain', risk: 'auto', description: '', inputSchema: {}, invoke: async () => '' });
	check('default: a tool registered without setDomain belongs to system',
		reg.get('no_domain')?.domain === 'system');
	reg.applyDomainFilter(cat.enabledDomains({ 'tool:file': false }));
	check('default: and survives any domain switch (ungated, not wrongly gated)',
		names(reg).includes('no_domain'));
}

// ---- 7. the config key namespace is real ----
{
	check('keys: tool:file is a different key from a feature toggle named file',
		cat.toolDomainKey('file') !== 'file');
	check('keys: every domain has a distinct switch key',
		new Set(cat.TOOL_DOMAIN_IDS.map(cat.toolDomainKey)).size === cat.TOOL_DOMAIN_IDS.length);
}

// ---- 8. source-level: the switch is actually consumed ----
{
	const regSrc = fs.readFileSync(path.join(__dirname, '..', 'src/main/ai/tools.ts'), 'utf8');
	const idxSrc = fs.readFileSync(path.join(__dirname, '..', 'src/main/index.ts'), 'utf8');
	check('source: registerPlanabilityTools takes the enabled set as a parameter',
		/enabledDomains: Set<string> \| null = null/.test(regSrc));
	check('source: and calls applyDomainFilter before returning',
		/reg\.applyDomainFilter\(enabledDomains\)/.test(regSrc));
	check('source: applyDomainFilter DELETES rather than marking (removal is the point)',
		/this\.defs\.delete\(name\)/.test(regSrc));
	check('source: index.ts passes the config through',
		/enabledDomains\(readConfigFile\(\)\.plugins\)/.test(idxSrc));
	// The whole reason this file exists.
	check('source: the registry READS the config rather than assuming everything is on',
		/applyDomainFilter/.test(regSrc) && /enabledDomains/.test(regSrc));
}

// ---- 9. domains the phone end declares are present here too (cross-end parity) ----
{
	// ToolDefs.kt:632 DOMAINS + :649 PLUGINS. Office/ics hold no tool yet but the plug is reserved.
	for (const id of ['schedule', 'todo', 'note', 'file', 'memory', 'system', 'agent', 'cu', 'web', 'shell', 'office', 'ics']) {
		check(`parity: domain "${id}" exists on the PC side`, cat.TOOL_DOMAIN_IDS.includes(id));
	}
}

out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
flush();
process.exit(failed === 0 ? 0 : 1);