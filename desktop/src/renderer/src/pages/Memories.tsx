import { useEffect, useRef, useState } from 'react';
import type { Memory, MemoryEvent } from '@shared/types';
import { useCollection } from '../hooks';
import { Empty, Field, Modal, PageHeader } from '../components/ui';
import { FilledButtonM3, TextButtonM3 } from '../components/m3/buttons';
import { BoardHeader, BoardSectionTitle, SplitBoards } from '../components/m3/board';
import { PaneStatRow, PaneSectionBlock, PaneEmptyInline } from '../components/m3/paneblocks';
import { useContainerWidth } from '../hooks/useContainerWidth';
import { EXPANDED_WIDTH_BREAKPOINT } from '../lib/sizing';
import './hub.css';
import './plans.css'; /* plans-flat-row / plans-detail：Hub 列表行与右板内衬与计划页共用同一套 */

/* M19 记忆页（pc-a renderer 线）——「可查可删」的完整语义：
 * - 遗忘走 memoryForget（追加 forget 事件 + 投影/集合统一擦除），不再是会复活的软删除；
 * - 每条记忆可开审计轨迹（memoryHistory，oldest first），遗忘后轨迹仍在、可随时让 AI 用
 *   memory_restore 恢复——契约硬要求：UI 绝不能把遗忘显示成"已永久删除"；
 * - 页首有全局事件流（memoryEvents），append-only 的核心价值就是这页看得见它。 */

const TYPE_LABEL: Record<string, string> = {
	project: '项目', feedback: '反馈', reference: '参考', handoff: '交接'
};

const KIND_LABEL: Record<string, string> = {
	add: '新增', update: '更新', forget: '遗忘', restore: '恢复'
};

const EMPTY: Memory = { id: '', createdAt: 0, updatedAt: 0, name: '', description: '', body: '', type: 'project' };

function fmtTime(ts: number): string {
	return ts ? new Date(ts).toLocaleString('zh-CN') : '—';
}

/** 记忆表单体（窄屏 Modal 与宽屏右板共用一份——spec §9 反两处各写一套）。 */
function MemoryForm({ value, onChange }: { value: Memory; onChange: (m: Memory) => void }) {
	return (
		<>
			<Field label="名称（slug，唯一）">
				<input value={value.name} onChange={e => onChange({ ...value, name: e.target.value })} placeholder="例如：pc-build-notes" />
			</Field>
			<Field label="摘要（索引行展示）">
				<input value={value.description} onChange={e => onChange({ ...value, description: e.target.value })} />
			</Field>
			<Field label="类型">
				<select value={value.type} onChange={e => onChange({ ...value, type: e.target.value as Memory['type'] })}>
					<option value="project">项目</option>
					<option value="feedback">反馈</option>
					<option value="reference">参考</option>
					<option value="handoff">交接</option>
				</select>
			</Field>
			<Field label="正文">
				<textarea value={value.body} onChange={e => onChange({ ...value, body: e.target.value })} />
			</Field>
		</>
	);
}

export default function MemoriesPage() {
	const { items, upsert, refresh, error } = useCollection<Memory>('memories');
	const [expandedId, setExpandedId] = useState<string | null>(null);
	const [creating, setCreating] = useState(false);
	const [draft, setDraft] = useState<Memory>(EMPTY);
	const [trailOf, setTrailOf] = useState<{ name: string; events: MemoryEvent[] } | null>(null);
	const [recent, setRecent] = useState<{ events: MemoryEvent[]; total: number } | null>(null);

	const loadRecent = () => window.planability.memoryEvents(30).then(setRecent).catch(() => undefined);
	useEffect(() => { void loadRecent(); }, []);

	const forget = async (m: Memory) => {
		if (!window.confirm(`遗忘「${m.name}」？\n\n从列表移除；审计轨迹永久保留，可随时让 AI 用 memory_restore 恢复。`)) { return; }
		const r = await window.planability.memoryForget(m.id).catch(() => ({ ok: false, reason: '通道调用失败' }));
		if (!r.ok && r.reason) { window.alert(`遗忘失败：${r.reason}`); }
		await refresh();
		void loadRecent();
	};

	const showTrail = async (m: Memory) => {
		// key 有两种写法：种子事件按 name、forget 事件按 store row id（tools.ts forgetMemory）。
		// 只查一个会漏——按 name 和 id 双查、按 seq 去重合并。
		const [byName, byId] = await Promise.all([
			window.planability.memoryHistory(m.name).catch(() => [] as MemoryEvent[]),
			window.planability.memoryHistory(m.id).catch(() => [] as MemoryEvent[]),
		]);
		const seen = new Set<number>();
		const events = [...byName, ...byId]
			.filter(e => (seen.has(e.seq) ? false : (seen.add(e.seq), true)))
			.sort((a, b) => a.seq - b.seq);
		setTrailOf({ name: m.name, events });
	};

	const create = async () => {
		if (!draft.name.trim()) { return; }
		await upsert({ ...draft, name: draft.name.trim() });
		setCreating(false);
		setDraft(EMPTY);
		void loadRecent();
	};

	// ---- A2 宽屏（spec §5 Hub：常驻 SplitBoards + paneOf）：左列表 / 右概览或详情；轨迹内联 ----
	const [pageRef, pageWidth] = useContainerWidth<HTMLDivElement>();
	const wide = pageWidth >= EXPANDED_WIDTH_BREAKPOINT;
	const [selId, setSelId] = useState<string | null>(null);
	const sel = items.find(m => m.id === selId) ?? null;
	// 滑出路上内容不蒸（spec §9.2）
	const lastSelRef = useRef<Memory | null>(null);
	if (sel) { lastSelRef.current = sel; }
	const deckSel = sel ?? lastSelRef.current;
	// 选中项的审计轨迹（宽屏内联；窄屏仍走 Modal）
	const [trailEvents, setTrailEvents] = useState<MemoryEvent[] | null>(null);
	useEffect(() => {
		if (!wide || !selId) { setTrailEvents(null); return; }
		const m = items.find(x => x.id === selId);
		if (!m) { setTrailEvents(null); return; }
		let cancelled = false;
		void (async () => {
			const [byName, byId] = await Promise.all([
				window.planability.memoryHistory(m.name).catch(() => [] as MemoryEvent[]),
				window.planability.memoryHistory(m.id).catch(() => [] as MemoryEvent[]),
			]);
			if (cancelled) { return; }
			const seen = new Set<number>();
			const events = [...byName, ...byId]
				.filter(e => (seen.has(e.seq) ? false : (seen.add(e.seq), true)))
				.sort((a, b) => a.seq - b.seq);
			setTrailEvents(events);
		})();
		return () => { cancelled = true; };
	}, [wide, selId, items]);

	// Esc 返回（宽屏）：退选中/退新建（桌面替代预测手势）
	useEffect(() => {
		if (!wide) { return; }
		const onKey = (e: KeyboardEvent) => {
			if (e.key !== 'Escape') { return; }
			if (creating) { setCreating(false); return; }
			if (selId) { e.preventDefault(); setSelId(null); }
		};
		document.addEventListener('keydown', onKey);
		return () => document.removeEventListener('keydown', onKey);
	}, [wide, selId, creating]);

	if (wide) {
		return (
			// ref 宽窄两分支都挂（只挂宽屏分支会死锁在窄版——Home 批实测坑）
			<div ref={pageRef} className="hub-wide">
				<SplitBoards
					twoPane
					primary={<>
						<BoardHeader title="记忆库" subtitle={`${items.length} 条`}
							actions={<TextButtonM3 onClick={() => { setCreating(true); setDraft(EMPTY); }}>＋ 新建</TextButtonM3>} />
						<div className="hub-main-body">
							{items.length === 0 && <Empty text={error ? '记忆读取失败——稍后重试' : '还没有记忆条目'} />}
							{items.map(m => (
								<button key={m.id} type="button" className={`plans-flat-row ${selId === m.id ? 'active' : ''}`}
									onClick={() => setSelId(m.id)}>
									<span className="plans-flat-row__main">
										<span className="plans-flat-row__title">
											<span className="type-tag" style={{ marginRight: 8 }}>{TYPE_LABEL[m.type ?? 'project'] ?? m.type}</span>
											{m.name}
										</span>
										<span className="plans-flat-row__meta">{m.description}</span>
									</span>
									{m.inIndex && <span className="pane-chip">工作层</span>}
								</button>
							))}
						</div>
					</>}
					secondary={creating ? (
						<>
							<BoardHeader title="新建记忆" leading={
								<button type="button" className="board-back" onClick={() => { setCreating(false); setDraft(EMPTY); }} aria-label="返回">←</button>
							} />
							<div className="plans-detail">
								<MemoryForm value={draft} onChange={setDraft} />
								<div className="plans-detail-actions">
									<FilledButtonM3 disabled={!draft.name.trim()} onClick={() => void create()}>保存</FilledButtonM3>
								</div>
							</div>
						</>
					) : deckSel ? (
						<>
							<BoardHeader title={deckSel.name} leading={sel ? (
								<button type="button" className="board-back" onClick={() => setSelId(null)} aria-label="返回">←</button>
							) : undefined}
								actions={<TextButtonM3 className="m3-text-danger" onClick={() => { void forget(deckSel); setSelId(null); }}>遗忘</TextButtonM3>}
							/>
							<div className="plans-detail">
								<div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 12 }}>
									<span className="type-tag">{TYPE_LABEL[deckSel.type ?? 'project'] ?? deckSel.type}</span>
									<span className="type-tag">{deckSel.scope === 'workspace' ? `仅工作区 ${deckSel.workspace ?? ''}` : '跨项目'}</span>
									{deckSel.inIndex && <span className="type-tag" style={{ borderColor: 'var(--primary)', color: 'var(--primary)' }}>工作层</span>}
								</div>
								<PaneSectionBlock title="正文" flush>
									{deckSel.body
										? <div style={{ whiteSpace: 'pre-wrap', fontSize: 13.5, lineHeight: 1.7 }}>{deckSel.body}</div>
										: <PaneEmptyInline text="（无正文）" />}
								</PaneSectionBlock>
								<BoardSectionTitle text={`审计轨迹（${trailEvents?.length ?? 0}，最早在前 · 可用 memory_restore 恢复）`} />
								{(trailEvents?.length ?? 0) === 0 && <PaneEmptyInline text="这条记忆还没有事件" />}
								{(trailEvents ?? []).map(e => (
									<div key={e.seq} style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', padding: '4px 0' }}>
										<span className="ag-msg-kind">#{e.seq}</span>
										<span className={`guard-chip ${e.kind === 'forget' ? 'trip' : ''}`}>{KIND_LABEL[e.kind] ?? e.kind}</span>
										<span style={{ color: 'var(--text-faint)', fontSize: 12 }}>{fmtTime(e.at)}</span>
										<span className="pane-chip">{e.source === 'auto' ? '自动沉淀' : e.source === 'tool' ? '工具' : '导入'}</span>
										{e.reason && <span className="plans-flat-row__meta">依据：{e.reason}</span>}
									</div>
								))}
							</div>
						</>
					) : (
						<>
							<BoardHeader title="概览" subtitle={`${recent?.total ?? 0} 事件 · append-only`} />
							<div className="plans-detail">
								<PaneStatRow
									items={[
										{ value: items.length, label: '记忆条目' },
										{ value: items.filter(m => m.inIndex).length, label: '工作层' },
										{ value: recent?.total ?? 0, label: '事件总数' },
									]}
								/>
								<PaneSectionBlock title="审计事件流（最近 30）" flush>
									{(recent?.events.length ?? 0) === 0
										? <PaneEmptyInline text="还没有事件" />
										: (recent?.events ?? []).slice().reverse().map(e => (
											<div key={e.seq} style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', padding: '4px 0' }}>
												<span className="ag-msg-kind">#{e.seq}</span>
												<span className={`guard-chip ${e.kind === 'forget' ? 'trip' : ''}`}>{KIND_LABEL[e.kind] ?? e.kind}</span>
												<span className="plans-flat-row__meta" style={{ flex: 1, minWidth: 0 }}>
													{e.name} · {fmtTime(e.at)}
												</span>
											</div>
										))}
								</PaneSectionBlock>
							</div>
						</>
					)}
				/>
				{trailOf && <TrailModal trailOf={trailOf} onClose={() => setTrailOf(null)} />}
			</div>
		);
	}

	return (
		<div ref={pageRef}>
			<PageHeader title="记忆库" action={<FilledButtonM3 onClick={() => setCreating(true)}>＋ 新建</FilledButtonM3>} />
			<p style={{ color: 'var(--text-dim)', fontSize: 12.5, marginTop: -8 }}>
				记忆由 AI 自动沉淀（事件日志的物化视图）。「遗忘」= 追加一条 forget 事件：从列表移除，
				<b>审计轨迹永久保留</b>，可随时让 AI 用 memory_restore 恢复——不是永久删除。
			</p>
			{items.length === 0 && <Empty text={error ? '记忆读取失败——稍后重试' : '还没有记忆条目'} />}
			{items.map(m => (
				<div className="card" key={m.id} style={{ display: 'block' }} onClick={() => setExpandedId(expandedId === m.id ? null : m.id)}>
					<div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
						<span className="type-tag">{TYPE_LABEL[m.type ?? 'project'] ?? m.type}</span>
						<span className="type-tag" title={m.scope === 'workspace' ? `仅工作区 ${m.workspace ?? ''} 可见` : '跨项目可见（默认）'}>
							{m.scope === 'workspace' ? '本工作区' : '跨项目'}
						</span>
						<div className="card-main">
							<div className="card-title">{m.name}</div>
							<div className="card-meta">{m.description}</div>
						</div>
						{m.inIndex && <span className="type-tag" style={{ borderColor: 'var(--primary)', color: 'var(--primary)' }}>工作层</span>}
						<div onClick={e => e.stopPropagation()} style={{ display: 'flex', gap: 6 }}>
							<TextButtonM3 title="查看这条记忆的审计轨迹（append-only）" onClick={() => void showTrail(m)}>轨迹</TextButtonM3>
							<TextButtonM3 className="m3-text-danger"
								title="追加 forget 事件：列表移除、轨迹保留、可恢复"
								onClick={() => void forget(m)}
							>遗忘</TextButtonM3>
						</div>
					</div>
					{expandedId === m.id && <div className="card-body">{m.body}</div>}
				</div>
			))}

			<section className="ag-section" style={{ marginTop: 22 }}>
				<div className="ag-head">
					审计事件流（最近 {recent?.events.length ?? 0} 条 / 共 {recent?.total ?? 0} 条 · append-only）
				</div>
				{(recent?.events.length ?? 0) === 0 && <div className="ag-dim">还没有事件</div>}
				{(recent?.events ?? []).slice().reverse().map(e => (
					<div key={e.seq} className={`ag-msg ${e.kind === 'forget' ? 'unread' : 'read'}`}>
						<span className="ag-msg-kind">#{e.seq}</span>
						<span className={`guard-chip ${e.kind === 'forget' ? 'trip' : ''}`}>{KIND_LABEL[e.kind] ?? e.kind}</span>
						<span className="ag-msg-body" title={e.reason ? `依据：${e.reason}` : e.key}>
							{e.name} · {e.source === 'auto' ? '自动沉淀' : e.source === 'tool' ? '工具' : '导入'} · {fmtTime(e.at)}
						</span>
					</div>
				))}
			</section>

			{trailOf && <TrailModal trailOf={trailOf} onClose={() => setTrailOf(null)} />}

			{creating && (
				<Modal title="新建记忆" onClose={() => { setCreating(false); setDraft(EMPTY); }}>
					<MemoryForm value={draft} onChange={setDraft} />
					<div className="form-actions">
						<TextButtonM3 onClick={() => { setCreating(false); setDraft(EMPTY); }}>取消</TextButtonM3>
						<FilledButtonM3 onClick={create}>保存</FilledButtonM3>
					</div>
				</Modal>
			)}
		</div>
	);
}

/** 审计轨迹弹层（窄屏；宽屏轨迹内联在右板 Detail）。 */
function TrailModal({ trailOf, onClose }: { trailOf: { name: string; events: MemoryEvent[] }; onClose: () => void }) {
	return (
		<Modal title={`审计轨迹 · ${trailOf.name}（${trailOf.events.length} 条，最早在前）`} onClose={onClose}>
			{trailOf.events.length === 0 && <div className="ag-dim">这条记忆还没有事件（可能只存在于集合里，尚未经过日志写入）</div>}
			{trailOf.events.map(e => (
				<div key={e.seq} style={{
					background: 'var(--surface)', border: '1px solid var(--outline-dim)',
					borderRadius: 'var(--shape-sm)', padding: '8px 10px', fontSize: 12, marginBottom: 6,
				}}>
					<div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
						<span className="ag-msg-kind">#{e.seq}</span>
						<span className={`guard-chip ${e.kind === 'forget' ? 'trip' : ''}`}>{KIND_LABEL[e.kind] ?? e.kind}</span>
						<span style={{ color: 'var(--text-faint)' }}>{fmtTime(e.at)}</span>
						<span className="type-tag">{e.scope === 'workspace' ? '工作区' : '跨项目'}</span>
						<span className="ag-dim" style={{ padding: 0 }}>{e.source === 'auto' ? '自动沉淀' : e.source === 'tool' ? '工具' : '导入'}</span>
					</div>
					{e.reason && <div style={{ color: 'var(--text-dim)', marginTop: 4 }}>依据：{e.reason}</div>}
					{e.kind !== 'forget' && <div style={{ whiteSpace: 'pre-wrap', marginTop: 4, maxHeight: 120, overflow: 'auto' }}>{e.body}</div>}
				</div>
			))}
			<div className="form-actions">
				<FilledButtonM3 onClick={onClose}>关闭</FilledButtonM3>
			</div>
		</Modal>
	);
}