/* M18 shell domain (plan §3 M18 item 3): the three-layer safety net.
 *
 * Layer 1 HARD DENY   — a command that matches never runs, no override, no approval can
 *                       unlock it. (system shutdown, disk format, Win+L, fork bombs…)
 * Layer 2 READ-ONLY ALLOWLIST — matching commands run with NO approval card.
 * Layer 3 EVERYTHING ELSE — always asks, and the card shows the exact command line.
 *
 * The deny list is enforced HERE, in the tool, rather than in the system prompt: a prompt is
 * advisory and an injected instruction can talk straight past it. Layer 1 is the one place
 * that must not be advisory.
 */

import { spawn } from 'node:child_process';
import type { ToolRegistry } from './tools';

export const SHELL_LIMITS = {
	timeoutMs: 30_000,
	maxOutput: 20_000,
} as const;

/**
 * Layer 1. Matched as substrings against the LOWERCASED command line.
 * Kept intentionally broad and blunt: a false positive costs the user one command, a false
 * negative can cost the machine.
 */
export const HARD_DENY: readonly string[] = [
	// power / session
	'shutdown', 'restart-computer', 'stop-computer', 'logoff',
	'format ', 'diskpart', 'cipher /w', 'bcdedit', 'diskdefrag',
	// destructive filesystem
	'rm -rf /', 'rm -rf ~', 'rmdir /s', 'del /s /q', 'rd /s /q',
	// registry / system
	'reg delete', 'regedit', 'takeown', 'icacls', 'cipher /k',
	// network / firewall (an agent has no business here)
	'netsh', 'firewall add', 'set-executionpolicy',
	// credential exfiltration
	'cmdkey', 'net use', 'net user', 'runas',
	// fork bomb + other classics
	':(){', 'mkfs', 'dd if=', 'chmod 777 /', 'chown -R',
];

/** Layer 2. First token (or full command when it starts with a path) decides. */
export const READ_ONLY_ALLOW: ReadonlySet<string> = new Set([
	'ls', 'dir', 'pwd', 'cd', 'echo', 'type', 'cat', 'head', 'tail',
	'wc', 'which', 'where', 'whoami', 'hostname', 'date',
	'git status', 'git log', 'git diff', 'git show',
	'node --version', 'npm ls', 'npm view', 'npx --version',
	'grep', 'find', 'rg', 'tree', 'stat', 'du', 'df',
	// Windows read-only equivalents
	'more', 'tasklist', 'systeminfo', 'ver', 'ipconfig',
	// Deliberately NOT here:
	//   'git branch' / 'git remote' — matched as a two-word PREFIX, so they also allowed
	//     `git branch -D main` and `git remote remove origin` to run without a card.
	//   'env' / 'printenv' — read-only in the filesystem sense and still leak PLANABILITY_AI_MAIN_KEY
	//     (provider.ts) to the model with no confirmation step.
]);

/**
 * Read-only commands that must never be read-only in THIS app's eyes. `type`/`cat` legitimately
 * read files, which is exactly why they cannot be allowed to read the file holding the LLM key:
 * `type %APPDATA%\planability-desktop\config.json` was a one-line exfiltration with no card.
 */
const SENSITIVE_FILE = /config\.json|apikey|api[-_]?key|id_rsa|credentials|\.env\b/;

export type ShellLayer = 'deny' | 'allow' | 'ask';

export interface ShellVerdict {
	layer: ShellLayer;
	reason?: string;
}

/** Classify a command line into the three layers. Pure — directly unit-testable. */
export function classifyShell(cmdRaw: string): ShellVerdict {
	const cmd = String(cmdRaw ?? '').trim();
	if (!cmd) { return { layer: 'deny', reason: '命令为空。' }; }

	// Layer 1 first: a denied command must never be classified as anything else, even if it
	// also contains an allowlisted word ("echo ok && shutdown").
	//
	// Match the raw lowercase line AND the same line with every `^` stripped. cmd.exe treats `^`
	// as its escape character, so `shut^down /s` and `fo^rmat C:` ARE the denied commands —
	// matching only the raw string let one caret break the promise written above HARD_DENY that
	// the hard list cannot be unlocked by approval.
	const lower = cmd.toLowerCase();
	const deescaped = lower.replace(/\^/g, '');
	for (const bad of HARD_DENY) {
		if (lower.includes(bad) || deescaped.includes(bad)) {
			return { layer: 'deny', reason: `命中硬禁表（${bad.trim()}）：该类命令一律禁止执行，无法通过审批解锁。` };
		}
	}

	// A file that is read-only in the filesystem sense can still hand over this app's secrets.
	if (SENSITIVE_FILE.test(lower)) {
		return { layer: 'ask', reason: '涉及密钥/配置文件，需要确认。' };
	}

	// Layer 2: only when the command is a SINGLE simple command that neither writes nor leaves
	// the machine. Piping/&&/; chains mean the second half could be anything; a REDIRECT means
	// the command produces a file (`echo x > 启动目录\x.bat` had `echo` allowing it straight
	// past this layer); a UNC path means an outbound SMB/NTLM handshake from a "read-only" dir.
	// All three always fall through to asking.
	if (!/[;&|]|\$\(|`|[<>]|\\\\/.test(cmd)) {
		const first = cmd.split(/\s+/)[0].toLowerCase().replace(/\.(exe|cmd|bat)$/, '');
		const full = lower.replace(/\.(exe|cmd|bat)\b/g, '');
		if (READ_ONLY_ALLOW.has(first) || READ_ONLY_ALLOW.has(full.split(/\s+/).slice(0, 2).join(' '))) {
			return { layer: 'allow' };
		}
	}
	return { layer: 'ask' };
}

/** Run a command, capturing output. Never throws — returns a printable result. */
export function runShell(cmd: string, cwd: string): Promise<{ ok: boolean; code: number | null; output: string; timedOut: boolean }> {
	return new Promise(resolve => {
		const isWin = process.platform === 'win32';
		let child;
		try {
			child = spawn(isWin ? 'cmd.exe' : '/bin/sh', isWin ? ['/d', '/s', '/c', cmd] : ['-c', cmd], {
				cwd, windowsHide: true, env: process.env,
			});
		} catch (e) {
			resolve({ ok: false, code: null, output: String(e), timedOut: false });
			return;
		}
		let out = '';
		let timedOut = false;
		const timer = setTimeout(() => { timedOut = true; child.kill(); }, SHELL_LIMITS.timeoutMs);
		child.stdout?.on('data', d => { if (out.length < SHELL_LIMITS.maxOutput) { out += String(d); } });
		child.stderr?.on('data', d => { if (out.length < SHELL_LIMITS.maxOutput) { out += String(d); } });
		child.on('error', e => { clearTimeout(timer); resolve({ ok: false, code: null, output: out + String(e), timedOut }); });
		child.on('close', code => {
			clearTimeout(timer);
			resolve({ ok: code === 0, code, output: (out || '(无输出)').slice(0, SHELL_LIMITS.maxOutput), timedOut });
		});
	});
}

export function registerShellTools(reg: ToolRegistry, getCwd: () => string, run: typeof runShell = runShell): void {
	const def = (
		name: string, risk: 'auto' | 'confirm' | 'danger', description: string,
		inputSchema: Record<string, unknown>, invoke: (input: any) => Promise<string>,
	) => reg.register({ name, risk, description, inputSchema, invoke });

	// risk=confirm: layer-2 commands short-circuit inside the tool (no card), everything else
	// reaches the user. Layer 1 never gets that far.
	def('shell_run', 'confirm',
		'在项目目录执行一条 shell 命令。只读命令（ls/dir/git status/grep 等）直接执行；其余需用户确认；' +
		'关机/格式化/删盘/改注册表/改网络等危险命令一律禁止，无法解锁。',
		{ type: 'object', properties: { command: { type: 'string', description: '要执行的命令行' } }, required: ['command'] },
		async (input) => {
			const cmd = String(input?.command ?? '');
			const v = classifyShell(cmd);
			if (v.layer === 'deny') { return `[DENIED] ${v.reason}`; }
			const r = await run(cmd, getCwd());
			if (r.timedOut) { return `[TIMEOUT] 命令超过 ${SHELL_LIMITS.timeoutMs / 1000}s 已被终止。\n${r.output}`; }
			return `${r.ok ? '[OK]' : `[EXIT ${r.code}]`}\n${r.output}`;
		});

	// Read-only classifier exposed as a tool so the model can ask "will this need approval?"
	// without spending an approval on the question.
	def('shell_check', 'auto',
		'判断一条命令会落在三层安全网的哪一层（deny 禁止 / allow 免审批 / ask 需审批），不实际执行。',
		{ type: 'object', properties: { command: { type: 'string' } }, required: ['command'] },
		async (input) => {
			const v = classifyShell(String(input?.command ?? ''));
			return `层级=${v.layer}${v.reason ? `；${v.reason}` : ''}`;
		});
}