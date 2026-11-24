/**
 * Settings the model is allowed to change: `capability_toggle` and `model_switch` (B5).
 *
 * Both are `confirm`, and both are in the `extend` domain — which carries a consequence worth
 * stating out loud rather than discovering: turning `extend` off removes `capability_toggle`
 * itself, so after that the model can no longer change ANY setting, including turning `extend`
 * back on. Only the Plugins page can undo it. That matches phone, and the reply says so, because
 * a model that keeps trying to re-enable a capability it just disabled looks broken.
 *
 * `plugin_toggle` is deliberately NOT implemented. Phone has two independent axes — DOMAINS
 * (core capabilities) and PLUGINS (installable packs: office/web/ics/phone) — and PC flattened
 * both into one list of domains. Re-adding `plugin_toggle` here would give one switch two names,
 * and "which one did the model use" would become a second thing to keep in sync. Recorded in
 * planability-cross-end-feature-gap.md rather than built.
 *
 * `digest_query` is also not implemented: it reads phone's group-chat distillation records, a
 * feature PC does not have. That is platform-only (C class), not a gap.
 */

import type { ToolRegistry } from './tools';
import { TOOL_DOMAINS, toolDomain, toolDomainKey } from '../../../shared/tool-domains';

export interface SettingsConfigSlice {
	mainModel?: string;
	plugins?: Record<string, boolean>;
}

export interface SettingsToolDeps {
	readConfig: () => SettingsConfigSlice;
	writeConfig: (patch: SettingsConfigSlice) => void;
	/**
	 * Re-apply the domain filter AFTER the write. Without it the switch would only take effect on
	 * the next launch — the model would answer "done" and then keep using the capability it just
	 * turned off, which is the same lying switch this whole mechanism was built to avoid.
	 */
	refreshDomains: () => void;
}

export function registerSettingsTools(reg: ToolRegistry, deps: SettingsToolDeps): void {
	// Own its domain here rather than trusting the caller: an unset domain defaults to `system`,
	// which is alwaysOn — the tool would be un-switchable and nothing would say a switch ignored it.
	reg.setDomain('extend');

	const def = (
		name: string, risk: 'auto' | 'confirm' | 'danger', description: string,
		inputSchema: Record<string, unknown>, invoke: (input: any) => Promise<string>,
	) => reg.register({ name, risk, description, inputSchema, invoke });

	def('capability_toggle', 'confirm',
		'开启或关闭某个 AI 能力域（写操作，需用户确认）。域：' +
		TOOL_DOMAINS.filter(d => !d.alwaysOn).map(d => d.id).join('/') +
		'。关闭后该域的工具在下一轮对话里就不再出现。',
		{
			type: 'object',
			properties: {
				domain: { type: 'string', description: '域 key' },
				enabled: { type: 'boolean', description: 'true=开启，false=关闭' },
			},
			required: ['domain', 'enabled'],
		},
		async (input) => {
			const domain = String(input?.domain ?? '').trim();
			// `enabled` must be a real boolean. `Boolean('false')` is true, so a model that sent
			// the STRING "false" would switch a capability ON while meaning to turn it off.
			const raw = input?.enabled;
			if (typeof raw !== 'boolean') {
				return `[FAIL] enabled 必须是布尔值 true/false，收到的是 ${JSON.stringify(raw)}。`;
			}
			if (!domain) { return '[FAIL] 缺少 domain。'; }

			const known = toolDomain(domain);
			if (!known) {
				return `[FAIL] 没有名为「${domain}」的能力域。可用的有：` +
					TOOL_DOMAINS.map(d => d.id).join('、');
			}
			if (known.alwaysOn) {
				// Writing `tool:system=false` would be a config key that reads like it worked and
				// does nothing. Refusing is the only honest answer.
				return `[FAIL]「${domain}」是常开域（${known.label}），不能关闭。`;
			}

			const before = deps.readConfig().plugins ?? {};
			const key = toolDomainKey(domain);
			if (before[key] === raw) {
				return `「${known.label}」已经${raw ? '开启' : '关闭'}了，没有改动。`;
			}

			const next = { ...before, [key]: raw };
			deps.writeConfig({ plugins: next });
			// Make it real now, not at the next launch.
			deps.refreshDomains();

			// Verify against the filter's OWN report rather than trusting the write. A switch that
			// answers "done" while the tools are still in the next turn's tool list is worse than
			// one that refuses outright. skippedByDomain() was just recomputed by refreshDomains,
			// so it is the registry's own account of what it withheld.
			const withheld = reg.skippedByDomain().get(domain);
			const reallyGone = Array.isArray(withheld) && withheld.length > 0;

			const verb = raw ? '已开启' : '已关闭';
			let reply = `${verb}「${known.label}」能力域（${domain}）。`;
			if (raw) {
				reply += `现在 AI 可以${known.label}相关的操作。`;
			} else {
				reply += `现在${known.offCost}。`;
			}
			reply += '下一轮对话起生效。';

			if (!raw) {
				// Say the awkward part before the model discovers it the hard way.
				if (domain === 'extend') {
					reply += '\n注意：扩展域里包含本工具，关闭它之后你也无法再修改任何设置' +
						'（包括把它重新打开），只能请用户在设置页里操作。';
				} else if (!reallyGone) {
					// The config says off but the filter kept the tools. Say THAT, do not claim success.
					reply += '\n注意：设置已写入，但该域工具仍在本轮工具表里' +
						'（本次切换未生效）。请让用户在设置页确认后再试。';
				}
			}
			return reply;
		});

	def('model_switch', 'confirm',
		'切换默认对话模型（写操作，需用户确认）。下一轮对话起生效。',
		{
			type: 'object',
			properties: { model: { type: 'string', description: '模型名，如 mimo-v2.6-flash' } },
			required: ['model'],
		},
		async (input) => {
			const model = String(input?.model ?? '').trim();
			if (!model) { return '[FAIL] model 不能为空。'; }
			if (model.length > 120) {
				return `[FAIL] 模型名过长（${model.length} 字），不像是一个模型名。`;
			}
			const cur = String(deps.readConfig().mainModel ?? '').trim();
			if (cur === model) { return `默认模型已经是 ${model}，没有改动。`; }

			deps.writeConfig({ mainModel: model });
			return cur
				? `默认对话模型已从 ${cur} 切到 ${model}，下一轮对话起生效。`
				: `默认对话模型已设为 ${model}，下一轮对话起生效。`;
		});
}