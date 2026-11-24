import { useEffect, useState } from 'react';
import { Puzzle, Zap, Layers, Undo2, Cable, BrainCircuit } from 'lucide-react';
import type { DesktopConfig } from '@shared/ipc';
import { PageHeader } from '../components/ui';

interface Capability {
	key: string;
	name: string;
	desc: string;
	icon: typeof Zap;
	boot?: boolean;
}

const CAPABILITIES: Capability[] = [
	{ key: 'automation', name: '自动化引擎', desc: '60 秒轮询执行到期指令（含数据快照注入）', icon: Zap, boot: true },
	{ key: 'cards', name: '四卡组件渲染', desc: 'plan/qa/schedule/checklist 围栏渲染为原生交互卡', icon: Layers },
	{ key: 'undo', name: '写操作撤销', desc: '写工具执行前自动打快照，支持 undo_last_write 回滚', icon: Undo2 },
	{ key: 'mcpTools', name: 'MCP 工具合并', desc: '启动时连接已启用的 MCP 服务器并并入工具箱', icon: Cable, boot: true }
];

export default function PluginsPage() {
	const [cfg, setCfg] = useState<DesktopConfig>({});

	useEffect(() => { window.planability.configGet().then(setCfg).catch(() => undefined); }, []);

	const isOn = (key: string) => cfg.plugins?.[key] !== false;

	const toggle = async (key: string) => {
		const next = { ...(cfg.plugins ?? {}), [key]: !isOn(key) };
		setCfg(prev => ({ ...prev, plugins: next }));
		await window.planability.configSet({ plugins: next }).catch(() => undefined);
	};

	return (
		<>
			<PageHeader title="插件" action={<span className="type-tag" style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}><Puzzle size={12} /> 内置能力</span>} />
			<p style={{ color: 'var(--text-dim)', fontSize: 12.5, marginTop: -8 }}>
				内置能力以插件形式管理；标注「重启生效」的开关在下次启动时应用。
			</p>
			{CAPABILITIES.map(c => (
				<div className="card" key={c.key}>
					<span className="asst-dot lg" style={{ background: isOn(c.key) ? 'var(--md-sys-color-primary)' : 'var(--surface-4)' }}>
						<c.icon size={16} />
					</span>
					<div className="card-main">
						<div className="card-title">
							{c.name}
							{c.boot && <span className="type-tag" style={{ marginLeft: 8, fontSize: 10 }}>重启生效</span>}
						</div>
						<div className="card-meta">{c.desc}</div>
					</div>
					<label className="switch" title={isOn(c.key) ? '点击停用' : '点击启用'}>
						<input type="checkbox" checked={isOn(c.key)} onChange={() => toggle(c.key)} />
						<span className="switch-track"><span className="switch-thumb" /></span>
					</label>
				</div>
			))}

			<div className="card" style={{ opacity: 0.55 }}>
				<span className="asst-dot lg" style={{ background: 'var(--surface-4)' }}><BrainCircuit size={16} /></span>
				<div className="card-main">
					<div className="card-title">记忆注入</div>
					<div className="card-meta">在系统提示词中提示模型检索长期记忆（「设置」页或上下文面板开关）</div>
				</div>
			</div>
		</>
	);
}
