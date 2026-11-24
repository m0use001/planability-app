/* SSRF guard tests.
 *
 * `assertPublicHttps` is the thing standing between a model talked into fetching a URL and the
 * user's own network, and until this file it had NO test at all — `denied-row.test.cjs` stubs it
 * out, and neither `provider.ts` nor `mcp.ts` was even in `test:build`, so the module was never
 * compiled into the test tree.
 *
 * Section 1 is the part that matters: those three cases all PASSED the old regex, and each one
 * reads as correct at a glance. `new URL` returns the IPv6 literal, not the address inside it, so
 * a check for `127.` or for `169.254.` cannot see `::ffff:7f00:1` or `::ffff:a9fe:a9fe` — the
 * second of which is the cloud metadata service the guard names explicitly.
 *
 * Run: npm run test:ssrf
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-ssrf.txt');
const out = [];
let failed = 0;
function check(name, cond) {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name);
	if (!cond) { failed++; }
}
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { } };
process.on('exit', flush);

const base = path.join(__dirname, '..', '.test', 'src', 'main', 'ai');
const P = require(path.join(base, 'provider.js'));
const M = require(path.join(base, 'mcp.js'));

/** Did the guard refuse this address? A throw is the only acceptable answer. */
const refused = (fn) => {
	try { fn(); return false; } catch { return true; }
};

(async () => {
	// ---- 1. the bypasses. Each of these was ALLOWED before, and each looks right at a glance.
	{
		const bypasses = [
			// IPv4-mapped loopback. `new URL` renders the embedded address in hex, so the old
			// `/^(localhost|127\.|...)/` never matched.
			['https://[::ffff:127.0.0.1]/x', 'IPv4-mapped loopback, dotted form'],
			['https://[::ffff:7f00:1]/x', 'IPv4-mapped loopback, hex form (what URL actually gives)'],
			// IPv4-mapped link-local: the cloud metadata address, which leaks instance credentials.
			['https://[::ffff:169.254.169.254]/latest/meta-data', 'IPv4-mapped metadata endpoint'],
			['https://[::ffff:a9fe:a9fe]/latest/meta-data', 'IPv4-mapped metadata endpoint, hex form'],
			// RFC 6761 reserves the whole .localhost TLD to loopback; only .local was checked.
			['https://foo.localhost/x', 'a .localhost name'],
			['https://x.local/x', 'a .local name'],
		];
		for (const [url, why] of bypasses) {
			check('bypass: refused — ' + why, refused(() => P.assertPublicHttps(url)));
		}

		// The ordinary cases must still be refused, or "fixed" simply means "broken".
		for (const url of [
			'http://example.com/',            // plaintext at all
			'https://localhost/', 'https://127.0.0.1/', 'https://10.1.2.3/',
			'https://192.168.1.1/', 'https://169.254.169.254/',
			'https://172.16.0.1/', 'https://172.31.255.255/',
			'https://[::1]/', 'https://[fd00::1]/', 'https://[fe80::1]/',
			'https://0.0.0.0/',
		]) {
			check('private: refused — ' + url, refused(() => P.assertPublicHttps(url)));
		}

		// The boundary has to be a boundary. 172.16.0.0/12 is the private block; 172.15 and
		// 172.32 are somebody else's, and a guard that over-blocks trains people to ignore it.
		check('boundary: 172.15.x is public and must be allowed', !refused(() => P.assertPublicHttps('https://172.15.0.1/')));
		check('boundary: 172.32.x is public and must be allowed', !refused(() => P.assertPublicHttps('https://172.32.0.1/')));
		check('public: a normal https host passes', !refused(() => P.assertPublicHttps('https://example.com/mcp')));
		check('public: a public IPv6 literal passes', !refused(() => P.assertPublicHttps('https://[2606:4700::1111]/')));
	}

	// ---- 2. the predicate on its own, including the inputs that are not addresses at all
	{
		check('host: an empty host is not a public host', P.isPrivateHost('') === true);
		check('host: null is not a public host', P.isPrivateHost(null) === true);
		check('host: brackets are tolerated, so callers need not strip them',
			P.isPrivateHost('[::1]') === true && P.isPrivateHost('[::ffff:7f00:1]') === true);
		check('host: case does not matter', P.isPrivateHost('LOCALHOST') === true && P.isPrivateHost('Foo.LocalHost') === true);
		check('host: a public name is public', P.isPrivateHost('example.com') === false);
		check('host: a public IPv6 literal is public', P.isPrivateHost('[2606:4700::1111]') === false);
	}

	// ---- 3. assertMcpUrl: the same guard would be WRONG here
	// Not a copy of assertPublicHttps, on purpose — see the comment on the function. The point of
	// this section is that the deliberate difference is pinned down, so nobody "fixes" it into
	// uniformity later without noticing what they would be breaking.
	{
		const ok = (u) => !refused(() => M.assertMcpUrl(u));
		const no = (u) => refused(() => M.assertMcpUrl(u));

		// The deployment that matters most: a server on the user's own machine over plain http.
		check('mcp: a loopback http server is allowed — this is the common setup',
			ok('http://127.0.0.1:3000/mcp') && ok('http://localhost:3000/mcp'));
		check('mcp: a private-network http server is allowed', ok('http://192.168.1.50:8080/mcp'));
		check('mcp: a public https server is allowed', ok('https://mcp.example.com/mcp'));
		check('mcp: a public server over plain http is refused — tool calls would cross the wire in cleartext',
			no('http://mcp.example.com/mcp'));

		// What `new URL` accepts and nothing should.
		check('mcp: file:// is refused', no('file:///C:/secrets.txt'));
		check('mcp: javascript: is refused', no('javascript:alert(1)'));
		check('mcp: ftp:// is refused', no('ftp://example.com/'));
		check('mcp: garbage is refused with a message that names the address',
			no('not a url') && /地址/.test((() => { try { M.assertMcpUrl('not a url'); return ''; } catch (e) { return e.message; } })()));
		check('mcp: an empty address is refused', no('') && no('   '));
		check('mcp: surrounding whitespace is tolerated, since a pasted URL has it',
			ok('  http://127.0.0.1:3000/mcp  '));
	}

	// ---- 4. McpHub.add validates BEFORE writing, so a bad address leaves nothing behind
	{
		const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pcb-mcpurl-'));
		const file = path.join(dir, 'mcp_servers.json');
		const hub = new M.McpHub(dir);

		let threw = false;
		try { await hub.add('坏地址', 'file:///C:/x'); } catch { threw = true; }
		check('add: an invalid address is refused', threw);
		check('add: and nothing was written — no half-added row survives a rejected save',
			!fs.existsSync(file));

		const good = await hub.add('', 'http://127.0.0.1:3000/mcp');
		check('add: a valid loopback server is stored, named from its host, disabled by default',
			good.name === '127.0.0.1' && good.enabled === false && good.url === 'http://127.0.0.1:3000/mcp');
		check('add: the stored URL is the validated one, not the raw input',
			JSON.parse(fs.readFileSync(file, 'utf8'))[0].url === 'http://127.0.0.1:3000/mcp');
		check('add: it is now listed', (await hub.list()).length === 1);

		fs.rmSync(dir, { recursive: true, force: true });
	}

	out.push(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
	flush();
	process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
	out.push('THREW ' + (e && e.stack || e));
	flush();
	process.exit(1);
});
