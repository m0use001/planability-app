import { useEffect, useState, type ReactNode } from 'react';
import { ListChecks, CircleHelp, CalendarClock, SquareCheckBig, Rows3, type LucideIcon } from 'lucide-react';
import { CARD_FENCE_BLOCK } from '@shared/cards';
import type { ListCardResult } from '@shared/chat';

/* Native component cards for the fence protocol (phone ChatComponents parity, PC v1).
 * The renderer receives RAW fences from the stream; a closed fence becomes an interactive
 * card. Buttons construct a deterministic user message and hand it back via onSend —
 * schedule/checklist sends ride the normal tool+approval chain.
 *
 * 语言槽清单从 shared/cards 的 CARD_SPECS 派生（CARD_FENCE_BLOCK，pc-b 4c9b4b4）——
 * 这里曾是手写第四份清单，模型被提示词教了发 ```list 而此处不认，UI 只会画出代码块。 */

export type Segment = { type: 'md'; text: string } | { type: 'fence'; lang: string; raw: string; body: string };

const FENCE_RE = CARD_FENCE_BLOCK;

/** Split assistant content into markdown runs and closed component fences. */
export function parseSegments(content: string): Segment[] {
	const segs: Segment[] = [];
	let last = 0;
	for (const m of content.matchAll(FENCE_RE)) {
		const index = m.index ?? 0;
		if (index > last) { segs.push({ type: 'md', text: content.slice(last, index) }); }
		segs.push({ type: 'fence', lang: m[1], raw: m[0], body: m[2] });
		last = index + m[0].length;
	}
	if (last < content.length) { segs.push({ type: 'md', text: content.slice(last) }); }
	return segs;
}

function CardShell({ icon: Icon, label, status, children, actions }: {
	icon: LucideIcon; label: string; status?: string; children: ReactNode; actions?: ReactNode;
}) {
	return (
		<div className="native-card">
			<div className="nc-head">
				<span className="nc-icon"><Icon size={16} /></span>
				<span className="nc-label">{label}</span>
				{status && <span className="nc-status">{status}</span>}
			</div>
			<div className="nc-body">{children}</div>
			{actions && <div className="nc-actions">{actions}</div>}
		</div>
	);
}

function PlanCard({ json, onSend }: { json: any; onSend: (text: string) => void }) {
	const [open, setOpen] = useState(false);
	const steps: any[] = json.steps ?? [];
	const nameOf = (s: any) => (typeof s === 'string' ? s : s?.name ?? '');
	const detailOf = (s: any) => (typeof s === 'string' ? '' : s?.detail ?? '');
	const shown = open ? steps : steps.slice(0, 3);
	const exec = () => {
		const lines = steps.map((s, i) => `${i + 1}. ${nameOf(s)}${detailOf(s) ? ` — ${detailOf(s)}` : ''}`);
		onSend(`请按计划执行「${json.title ?? '计划'}」，共 ${steps.length} 步，逐步推进：\n${lines.join('\n')}\n全部完成后汇报每步结果。`);
	};
	return (
		<CardShell icon={ListChecks} label={`计划 · ${json.title ?? '未命名'}`} status={`${steps.length} 步`}
			actions={steps.length > 0 && (
				<button className="btn nc-send" onClick={exec}>按此执行</button>
			)}>
			{json.goal && <div className="nc-goal">目标：{json.goal}</div>}
			<ol className="nc-steps">
				{shown.map((s, i) => (
					<li key={i}><strong>{nameOf(s)}</strong>{detailOf(s) && <span className="nc-detail">{detailOf(s)}</span>}</li>
				))}
			</ol>
			{steps.length > 3 && (
				<button className="nc-link" onClick={() => setOpen(!open)}>
					{open ? '收起计划 ↑' : `查看完整计划（共 ${steps.length} 步）→`}
				</button>
			)}
			{json.note && <div className="nc-note">备注：{json.note}</div>}
		</CardShell>
	);
}

function QaCard({ json, onSend }: { json: any; onSend: (text: string) => void }) {
	const questions: { q?: string; options?: string[] }[] = (json.questions ?? []).slice(0, 3);
	const [picked, setPicked] = useState<Record<number, string>>({});
	const allDone = questions.length > 0 && questions.every((_, i) => picked[i] != null);
	const answer = () => {
		const lines = questions.map((q, i) => `${i + 1}. ${q.q} → ${picked[i]}`);
		onSend(`我回答这组问答：\n${lines.join('\n')}`);
	};
	return (
		<CardShell icon={CircleHelp} label="问答" status={allDone ? '已回答' : '待回答'}>
			{questions.map((q, i) => (
				<div className="nc-question" key={i}>
					<div className="nc-q-text">{i + 1}. {q.q}</div>
					<div className="nc-options">
						{(q.options ?? []).map(opt => (
							<button
								key={opt}
								className={`nc-option ${picked[i] === opt ? 'picked' : ''}`}
								onClick={() => setPicked(prev => ({ ...prev, [i]: opt }))}
							>
								{picked[i] === opt ? '● ' : '○ '}{opt}
							</button>
						))}
					</div>
				</div>
			))}
			{allDone && <button className="btn nc-send" onClick={answer}>发送回答</button>}
		</CardShell>
	);
}

function ScheduleCard({ json, onSend }: { json: any; onSend: (text: string) => void }) {
	const items: { title?: string; start?: string; end?: string; location?: string }[] = (json.items ?? []).slice(0, 20);
	const request = () => {
		const lines = items.map(it => `- ${it.start ?? ''}${it.end ? `~${it.end}` : ''} ${it.title ?? ''}${it.location ? `（${it.location}）` : ''}`);
		onSend(`请创建以下日程（需要时先核对日期年份）：\n${lines.join('\n')}`);
	};
	return (
		<CardShell icon={CalendarClock} label="日程提案" status={`${items.length} 条`}
			actions={<button className="btn nc-send" onClick={request}>发送创建请求</button>}>
			<table className="nc-table">
				<thead><tr><th>时间</th><th>事项</th><th>地点</th></tr></thead>
				<tbody>
					{items.map((it, i) => (
						<tr key={i}>
							<td>{it.start ?? ''}{it.end ? `~${it.end}` : ''}</td>
							<td>{it.title ?? ''}</td>
							<td>{it.location ?? ''}</td>
						</tr>
					))}
				</tbody>
			</table>
		</CardShell>
	);
}

function ChecklistCard({ json, onSend }: { json: any; onSend: (text: string) => void }) {
	const items: string[] = (json.items ?? []).slice(0, 50);
	const [checked, setChecked] = useState<Set<number>>(new Set());
	const toggle = (i: number) => setChecked(prev => {
		const next = new Set(prev);
		if (next.has(i)) { next.delete(i); } else { next.add(i); }
		return next;
	});
	const send = () => {
		const lines = [...checked].sort().map(i => `- ${items[i]}`);
		onSend(`把以下勾选项创建为待办：\n${lines.join('\n')}`);
	};
	return (
		<CardShell icon={SquareCheckBig} label={`清单 · ${json.title ?? '未命名'}`} status={`${checked.size}/${items.length} 已勾`}
			actions={<button className="btn nc-send" disabled={!checked.size} style={{ opacity: checked.size ? 1 : 0.45 }} onClick={send}>
				把勾选项转为待办
			</button>}>
			{items.map((it, i) => (
				<label className="nc-check-row" key={i}>
					<input className="check" type="checkbox" checked={checked.has(i)} onChange={() => toggle(i)} />
					<span className={checked.has(i) ? 'struck' : ''}>{it}</span>
				</label>
			))}
		</CardShell>
	);
}

/**
 * 活的数据视图卡（list，M20 卡片计划阶段 1）：围栏正文只是**查询**，内容由主进程跑真查询。
 * 与提案类卡的本质区别：它渲染的是用户 store 里真实的东西，刷新 = 重发同一个已执行查询
 * （ListCardResult.query 的回显就是为此——显示与存储不可能分歧），永不过期。
 * 错误必须显形：主进程对坏查询是**抛错**而不是返回空列表——「没有匹配项」和「读不懂你的
 * 筛选」对用户一模一样，吞掉错误就是拿假空态骗人。
 */
function ListCard({ json }: { json: unknown }) {
	const [res, setRes] = useState<ListCardResult | null>(null);
	const [err, setErr] = useState('');
	const [busy, setBusy] = useState(false);
	const [sent, setSent] = useState<unknown>(json);

	const load = async (q: unknown) => {
		setBusy(true);
		setErr('');
		try {
			const r = await window.planability.chat.listQuery(q);
			setRes(r);
			setSent(r.query);
		} catch (e) {
			setErr(String((e as Error)?.message ?? e).slice(0, 200));
		} finally {
			setBusy(false);
		}
	};

	useEffect(() => { void load(json); }, []); // eslint-disable-line react-hooks/exhaustive-deps

	const coll = res?.query.collection ?? (json as { collection?: string } | null)?.collection ?? '?';
	const status = err ? '查询失败' : busy ? '刷新中…' : res ? `显示 ${res.rows.length} / 共 ${res.total}` : '加载中…';
	return (
		<CardShell icon={Rows3} label={`数据 · ${coll}`} status={status}
			actions={<button className="btn nc-send" disabled={busy} onClick={() => void load(sent)}>刷新</button>}>
			{err && <div className="nc-goal" style={{ color: 'var(--danger)' }}>查询失败：{err}</div>}
			{!err && !res && <div className="nc-note">读取中…</div>}
			{!err && res && res.rows.length === 0 && <div className="nc-note">没有匹配项</div>}
			{!err && res && res.rows.length > 0 && (
				<table className="nc-table">
					<thead><tr><th>条目</th><th>详情</th><th style={{ textAlign: 'right' }}>时间</th></tr></thead>
					<tbody>
						{res.rows.map(r => (
							<tr key={r.id}>
								<td>{r.label}</td>
								<td>{r.detail ?? ''}</td>
								<td style={{ textAlign: 'right' }}>{r.when ?? ''}</td>
							</tr>
						))}
					</tbody>
				</table>
			)}
		</CardShell>
	);
}

/** Render one closed fence as its native card (invalid JSON falls back to a code block). */
export function FenceBlock({ seg, onSend }: { seg: Segment & { type: 'fence' }; onSend: (text: string) => void }) {
	let json: any;
	try {
		json = JSON.parse(seg.body.trim());
	} catch {
		return <pre className="md-pre-fallback">{seg.raw}</pre>;
	}
	switch (seg.lang) {
		case 'plan': return <PlanCard json={json} onSend={onSend} />;
		case 'qa': return <QaCard json={json} onSend={onSend} />;
		case 'schedule': return <ScheduleCard json={json} onSend={onSend} />;
		case 'checklist': return <ChecklistCard json={json} onSend={onSend} />;
		case 'list': return <ListCard json={json} />;
		default: return <pre className="md-pre-fallback">{seg.raw}</pre>;
	}
}
