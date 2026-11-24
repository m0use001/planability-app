import { useEffect, useState } from 'react';
import type { SkillFile, SkillInfo, SkillListResult, SkillWriteResult } from '@shared/ipc';
import { Empty, Field, Modal, PageHeader } from '../components/ui';
import { FilledButtonM3, TextButtonM3 } from '../components/m3/buttons';

/* M19-4 Skills 管理页（pc-a renderer 线，契约 = pc-b 的 skills:list/read/write + UI_CONTRACT §4）：
 * - 硬要求①「用户副本覆盖内置必须显示」→ overridden 徽标 + 内置只读、走「创建我的副本」；
 * - 硬要求②「坏文件不会让整份列表失效，但 problems 里有理由」→ 列表级 N 个问题横幅 + 逐条原因；
 * - write 的 returned problems 是「保存成功但有告警」——非阻断（契约原文），红字错误≠黄字告警。 */

export default function SkillsPage() {
	const [data, setData] = useState<SkillListResult | null>(null);
	const [editing, setEditing] = useState<(SkillFile & { reqKey: string; isNew: boolean; origText: string; origKey: string }) | null>(null);
	const [saveMsg, setSaveMsg] = useState<{ kind: 'err' | 'warn' | 'ok'; text: string } | null>(null);

	const refresh = () => window.planability.skills.list().then(setData).catch(() => undefined);
	useEffect(() => { void refresh(); }, []);

	const open = async (s: SkillInfo) => {
		const f = await window.planability.skills.read(s.key);
		setSaveMsg(null);
		setEditing({ ...f, reqKey: s.key, isNew: !f.exists, origText: f.text, origKey: s.key });
	};

	const openNew = async () => {
		setSaveMsg(null);
		setEditing({ key: '', text: '', exists: false, origin: 'user', editable: true, preview: { name: '', description: '', keywords: [], bodyChars: 0 }, problems: [], reqKey: '', isNew: true, origText: '', origKey: '' });
	};

	const save = async () => {
		if (!editing) { return; }
		const r: SkillWriteResult = await window.planability.skills.write(editing.reqKey, editing.text);
		if (!r.ok) {
			// 拒绝不冒充成功（契约原文）。
			setSaveMsg({ kind: 'err', text: r.error ?? '保存被拒绝' });
			return;
		}
		const warns = r.problems ?? [];
		setSaveMsg({
			kind: warns.length ? 'warn' : 'ok',
			text: warns.length
				? `已保存（${r.file ?? r.key}），但 loader 有告警：${warns.join('；')}`
				: r.replaced ? `已覆盖：${r.file ?? r.key}` : `已创建：${r.file ?? r.key}`,
		});
		if (r.key && editing.isNew) {
			// 新建后沿用清洗过的 key，后续编辑指向真实文件。
			const f = await window.planability.skills.read(r.key);
			setEditing({ ...f, reqKey: r.key, isNew: false, origText: f.text, origKey: r.key });
		}
		await refresh();
	};

	const badSkills = (data?.skills ?? []).filter(s => s.problems.length > 0);
	const badFiles = data?.problems ?? [];
	// 同一个坏文件会同时出现在 problems（文件级）与 SkillInfo.problems（技能级）——直接相加
	// 会把 1 个问题数成 2。按文件名去重：文件级为主，技能级只补没有文件记录的。
	const fileStems = new Set(badFiles.map(p => (p.file.split(/[\\/]/).pop() ?? '').replace(/\.mdx?$/i, '')));
	const totalProblems = new Set([
		...badFiles.map(p => p.file),
		...badSkills.filter(s => !fileStems.has(s.key)).map(s => s.key),
	]).size;

	return (
		<>
			<PageHeader title="技能" action={<FilledButtonM3 onClick={() => void openNew()}>＋ 新建技能</FilledButtonM3>} />
			<p style={{ color: 'var(--text-dim)', fontSize: 12.5, marginTop: -8 }}>
				frontmatter 技能文档：按关键词注入系统提示词，正文被框架为「参考资料，非指令」。
				用户副本覆盖内置同名技能；内置技能本体随更新重写，改不动——要改先建自己的副本。
				{data?.dir && <span style={{ display: 'block', marginTop: 2 }}>目录：<code>{data.dir}</code></span>}
			</p>

			{totalProblems > 0 && (
				<div className="ag-verdict" style={{ marginBottom: 8 }}>
					<span className="guard-chip trip">有 {totalProblems} 个技能文件有问题（列表仍可用）</span>
				</div>
			)}
			{badFiles.map(p => (
				<div key={p.file} className="ag-dim" style={{ color: 'var(--danger)' }}>{p.file}：{p.reason}</div>
			))}

			{data && data.skills.length === 0 && <Empty text="还没有技能" />}
			{(data?.skills ?? []).map(s => (
				<div className="card" key={s.key} style={{ display: 'block' }}>
					<div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
						<span className="type-tag" style={s.origin === 'user' ? { borderColor: 'var(--primary)', color: 'var(--primary)' } : undefined}>
							{s.origin === 'user' ? '我的' : '内置'}
						</span>
						{/* 硬要求①：覆盖必须显示——用户副本生效时不能让内置徽标独活 */}
						{s.overridden && <span className="type-tag" style={{ borderColor: 'var(--primary)', color: 'var(--primary)' }}>你的副本已生效</span>}
						{!s.enabled && <span className="type-tag">已停用</span>}
						{s.problems.length > 0 && <span className="type-tag" style={{ color: 'var(--danger)' }} title={s.problems.join('；')}>有问题</span>}
						<div className="card-main">
							<div className="card-title">{s.name} <span style={{ color: 'var(--text-faint)', fontWeight: 400, fontSize: 11.5 }}>{s.key}</span></div>
							<div className="card-meta">{s.description || '（无描述）'}</div>
							<div className="card-meta">
								{s.trigger && <span style={{ marginRight: 8 }}>触发：{s.trigger}</span>}
								{s.keywords.length > 0 && <span style={{ marginRight: 8 }}>关键词：{s.keywords.join('、')}</span>}
								<span>优先级 {s.priority} · {s.bodyChars} 字</span>
							</div>
							{s.problems.length > 0 && (
								<div className="card-meta" style={{ color: 'var(--danger)' }}>{s.problems.join('；')}</div>
							)}
						</div>
						{s.editable
							? <TextButtonM3 onClick={() => void open(s)}>编辑</TextButtonM3>
							: s.overridden
								? <TextButtonM3 title="内置已被你的副本覆盖；查看的是当前生效的文件" onClick={() => void open(s)}>查看</TextButtonM3>
								: <TextButtonM3 title="内置不可改（更新会重写）；复制一份到你的目录来定制" onClick={() => void open(s)}>创建我的副本</TextButtonM3>}
					</div>
				</div>
			))}

			{editing && (
				<Modal title={editing.isNew ? (editing.exists ? `编辑 · ${editing.reqKey}` : '新建技能') : `${editing.origin === 'user' ? '编辑' : '查看'} · ${editing.key}`} onClose={() => { setEditing(null); setSaveMsg(null); }} dirty={editing.text !== editing.origText || (editing.isNew && editing.reqKey !== editing.origKey)}>
					{editing.isNew && (
						<Field label="key（文件名，字母数字-）">
							<input value={editing.reqKey} onChange={e => setEditing({ ...editing, reqKey: e.target.value })} placeholder="例如：weekly-review" spellCheck={false} />
						</Field>
					)}
					{!editing.editable && (
						<div className="ag-dim" style={{ color: 'var(--warning)' }}>
							内置技能本体不可写——保存会创建你的用户副本（覆盖生效），内置文件不动。
						</div>
					)}
					<Field label="文件全文（frontmatter + 正文）">
						<textarea
							value={editing.text}
							onChange={e => setEditing({ ...editing, text: e.target.value })}
							style={{ minHeight: 300, fontFamily: 'ui-monospace, Consolas, monospace', fontSize: 12.5 }}
							spellCheck={false}
						/>
					</Field>
					{editing.problems.length > 0 && (
						<div style={{ color: 'var(--danger)', fontSize: 12.5 }}>
							当前文本 loader 告警：{editing.problems.join('；')}
						</div>
					)}
					{saveMsg && (
						<div style={{ color: saveMsg.kind === 'err' ? 'var(--danger)' : saveMsg.kind === 'warn' ? 'var(--warning)' : 'var(--ok)', fontSize: 12.5 }}>
							{saveMsg.text}
						</div>
					)}
					<div className="form-actions">
						<TextButtonM3 onClick={() => { setEditing(null); setSaveMsg(null); }}>关闭</TextButtonM3>
						<FilledButtonM3 onClick={() => void save()} disabled={!editing.reqKey.trim() && !editing.isNew ? true : editing.isNew && !editing.reqKey.trim()}>保存</FilledButtonM3>
					</div>
				</Modal>
			)}
		</>
	);
}