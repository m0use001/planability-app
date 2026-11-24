import { useEffect, useState } from 'react';
import { Plus, Trash2, PlugZap, LoaderCircle, Wrench, CircleX } from 'lucide-react';
import type { McpServerDef, McpTestResult } from '@shared/ipc';
import { Empty, Field, Modal, PageHeader } from '../components/ui';
import { FilledButtonM3, TextButtonM3 } from '../components/m3/buttons';

export default function McpServersPage() {
	const [items, setItems] = useState<McpServerDef[]>([]);
	const [creating, setCreating] = useState(false);
	const [draft, setDraft] = useState({ name: '', url: '' });
	const [testing, setTesting] = useState<string | null>(null);
	const [testResult, setTestResult] = useState<{ id: string; res: McpTestResult } | null>(null);
	// G 类 MEDIUM：window.alert 会卡死渲染层（styles.css/ChatPieces 已注明）——校验走行内红字。
	const [formErr, setFormErr] = useState('');

	const refresh = () => window.planability.mcpList().then(setItems).catch(console.error);
	useEffect(() => { refresh(); }, []);

	const closeCreate = () => { setCreating(false); setDraft({ name: '', url: '' }); setFormErr(''); };

	const add = async () => {
		if (!draft.url.trim()) { return; }
		if (!draft.url.startsWith('https://')) { setFormErr('仅支持 https 的 Streamable HTTP 端点。'); return; }
		await window.planability.mcpAdd(draft.name, draft.url.trim());
		closeCreate();
		await refresh();
	};

	const toggle = async (s: McpServerDef) => {
		await window.planability.mcpSetEnabled(s.id, !s.enabled);
		await refresh();
		if (!s.enabled) { window.location.reload(); } // reconnect tools at boot — next run merges them
	};

	const remove = async (s: McpServerDef) => {
		if (!window.confirm(`移除 MCP 服务器「${s.name}」？`)) { return; }
		await window.planability.mcpRemove(s.id);
		await refresh();
	};

	const test = async (s: McpServerDef) => {
		setTesting(s.id);
		try {
			const res = await window.planability.mcpTest(s.url);
			setTestResult({ id: s.id, res });
		} catch (e) {
			// 通道拒绝必须落成可见结果：不 finally 清 testing 就是「永久转圈」。
			setTestResult({ id: s.id, res: { ok: false, error: String((e as Error)?.message ?? e).slice(0, 200) } });
		} finally {
			setTesting(null);
		}
	};

	return (
		<>
			<PageHeader title="MCP 服务器" action={<FilledButtonM3 onClick={() => setCreating(true)}>＋ 添加</FilledButtonM3>} />
			<p style={{ color: 'var(--text-dim)', fontSize: 12.5, marginTop: -8 }}>
				Streamable HTTP 端点；启用后其工具并入 AI 工作台（写操作按「变更前确认」审批）。启用生效于下次启动。
			</p>
			{items.length === 0 && <Empty text="还没有 MCP 服务器。可添加任意提供 /mcp 端点的服务。" />}
			{items.map(s => (
				<div className="card" key={s.id}>
					<span className="asst-dot lg" style={{ background: s.enabled ? 'var(--md-sys-color-ok)' : 'var(--surface-4)' }}>
						<PlugZap size={16} />
					</span>
					<div className="card-main">
						<div className="card-title">{s.name} <span className="type-tag" style={{ marginLeft: 6, fontSize: 10 }}>{s.enabled ? '已启用' : '停用'}</span></div>
						<div className="card-meta" style={{ wordBreak: 'break-all' }}>{s.url}</div>
						{testResult?.id === s.id && (
							<div className={testResult.res.ok ? 'card-meta' : 'chat-error'} style={{ marginTop: 6 }}>
								{testResult.res.ok
									? `连接成功 · ${testResult.res.toolNames?.length ?? 0} 个工具：${(testResult.res.toolNames ?? []).slice(0, 6).join('、')}`
									: `连接失败：${testResult.res.error}`}
							</div>
						)}
					</div>
					<TextButtonM3 onClick={() => void test(s)} disabled={testing === s.id}>
						{testing === s.id ? <LoaderCircle size={13} className="spin" style={{ verticalAlign: -2 }} /> : '测试连接'}
					</TextButtonM3>
					<TextButtonM3 onClick={() => toggle(s)}>{s.enabled ? '停用' : '启用'}</TextButtonM3>
					<TextButtonM3 className="m3-text-danger" onClick={() => remove(s)}>移除</TextButtonM3>
				</div>
			))}

			{creating && (
				<Modal title="添加 MCP 服务器" onClose={closeCreate} dirty={draft.name !== '' || draft.url !== ''}>
					<Field label="名称">
						<input value={draft.name} onChange={e => setDraft({ ...draft, name: e.target.value })} autoFocus placeholder="例如：filesystem" />
					</Field>
					<Field label="端点（https://…）">
						<input value={draft.url} onChange={e => { setDraft({ ...draft, url: e.target.value }); setFormErr(''); }} placeholder="https://example.com/mcp" />
					</Field>
					{formErr && <div style={{ color: 'var(--danger)', fontSize: 12.5, margin: '4px 0' }}>{formErr}</div>}
					<div className="form-actions">
						<TextButtonM3 onClick={closeCreate}>取消</TextButtonM3>
						<FilledButtonM3 onClick={add}>添加</FilledButtonM3>
					</div>
				</Modal>
			)}
		</>
	);
}