import { useEffect, useState } from 'react';
import { Plus, Trash2, UserRound } from 'lucide-react';
import type { Assistant } from '@shared/types';
import { Field, Modal, PageHeader } from '../components/ui';
import { FilledButtonM3, TextButtonM3 } from '../components/m3/buttons';

const COLORS = ['#8ab4f8', '#7ddba0', '#ffd666', '#ffb4ab', '#d4b4fa', '#80deea'];

const EMPTY: Assistant = {
	id: '', createdAt: 0, updatedAt: 0,
	key: '', name: '', color: '#8ab4f8', slogan: '', systemPrompt: '', temperature: 0.7
};

export default function AssistantsPage() {
	const [items, setItems] = useState<Assistant[]>([]);
	const [editing, setEditing] = useState<Assistant | null>(null);
	const [creating, setCreating] = useState(false);

	const refresh = () => window.planability.list<Assistant>('assistants')
		.then(list => setItems([...list].sort((a, b) => Number(b.builtin) - Number(a.builtin) || a.name.localeCompare(b.name))))
		.catch(console.error);
	useEffect(() => { refresh(); }, []);

	const open = editing ?? (creating ? EMPTY : null);
	const close = () => { setEditing(null); setCreating(false); };

	const save = async () => {
		if (!open || !open.name.trim()) { return; }
		const rec: Assistant = {
			...open,
			name: open.name.trim(),
			key: (open.key || open.name).trim().toLowerCase().replace(/\s+/g, '_'),
			slogan: open.slogan?.trim() || undefined
		};
		await window.planability.upsert('assistants', rec);
		close();
		await refresh();
	};

	const remove = async (a: Assistant) => {
		if (a.builtin) { window.alert('内置助手不可删除。'); return; }
		if (!window.confirm(`删除助手「${a.name}」？`)) { return; }
		await window.planability.softDelete('assistants', a.id);
		await refresh();
	};

	return (
		<>
			<PageHeader title="子智能体" action={<FilledButtonM3 onClick={() => setCreating(true)}>＋ 新建</FilledButtonM3>} />
			<p style={{ color: 'var(--text-dim)', fontSize: 12.5, marginTop: -8 }}>
				助手 = 人格化系统提示词 + 温度；在 AI 工作台头部切换，下一轮生效。
			</p>
			{items.map(a => (
				<div className="card" key={a.id || a.key}>
					<span className="asst-dot lg" style={{ background: a.color }}>
						<UserRound size={16} />
					</span>
					<div className="card-main">
						<div className="card-title">
							{a.name}
							{a.builtin && <span className="type-tag" style={{ marginLeft: 8, fontSize: 10 }}>内置</span>}
						</div>
						<div className="card-meta">{a.slogan || '未设置口号'}{a.temperature != null && ` · 温度 ${a.temperature}`}</div>
						{a.systemPrompt && <div className="card-body">{a.systemPrompt.slice(0, 140)}{a.systemPrompt.length > 140 ? '…' : ''}</div>}
					</div>
					<TextButtonM3 onClick={() => setEditing(a)}>编辑</TextButtonM3>
					{!a.builtin && (
						<TextButtonM3 className="m3-text-danger" onClick={() => remove(a)}>删除</TextButtonM3>
					)}
				</div>
			))}

			{open && (
				<Modal title={creating ? '新建助手' : `编辑 · ${open.name}`} onClose={close}>
					<Field label="名称">
						<input value={open.name} onChange={e => setEditingOrNew({ ...open, name: e.target.value })} autoFocus placeholder="例如：论文润色师" />
					</Field>
					<Field label="口号（一句话）">
						<input value={open.slogan ?? ''} onChange={e => setEditingOrNew({ ...open, slogan: e.target.value })} placeholder="例如：学术表达与格式" />
					</Field>
					<Field label="标识色">
						<div style={{ display: 'flex', gap: 8 }}>
							{COLORS.map(c => (
								<button key={c} className="asst-color" style={{ background: c, borderColor: open.color === c ? 'var(--text)' : 'transparent' }}
									onClick={() => setEditingOrNew({ ...open, color: c })} />
							))}
						</div>
					</Field>
					<Field label={`温度（0-2，当前 ${open.temperature ?? 0.7}）`}>
						<input type="number" step={0.1} min={0} max={2} value={open.temperature ?? 0.7}
							onChange={e => setEditingOrNew({ ...open, temperature: Number(e.target.value) })} />
					</Field>
					<Field label="系统提示词（附加在主提示词后）">
						<textarea value={open.systemPrompt} onChange={e => setEditingOrNew({ ...open, systemPrompt: e.target.value })}
							style={{ minHeight: 120 }} placeholder="例如：你擅长用表格对比方案，回答先给结论……" />
					</Field>
					<div className="form-actions">
						<TextButtonM3 onClick={close}>取消</TextButtonM3>
						<FilledButtonM3 onClick={save}>保存</FilledButtonM3>
					</div>
				</Modal>
			)}
		</>
	);

	function setEditingOrNew(a: Assistant): void {
		if (creating) { setCreating(false); setEditing(a); } else { setEditing(a); }
	}
}