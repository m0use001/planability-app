import { useMemo, useState } from 'react';
import type { Schedule } from '@shared/types';
import { useCollection } from '../hooks';
import { Empty, Field, Modal, PageHeader } from '../components/ui';
import { ListRow } from '../components/m3/chrome';
import { FilledButtonM3, TextButtonM3 } from '../components/m3/buttons';
import { Trash2, Pencil } from 'lucide-react';
import { localDayKey, nextDayKey } from '../daykey';

export const EMPTY_SCHEDULE: Schedule = { id: '', createdAt: 0, updatedAt: 0, title: '' };
const EMPTY = EMPTY_SCHEDULE;

function dayKey(s: Schedule): string {
	return s.date ?? s.startIso?.slice(0, 10) ?? '未安排';
}

/** 分组（日期桶 + 桶内按开始时间）——窄屏列表与宽屏左板共用同一排序口径。 */
export function groupSchedules(items: Schedule[]): [string, Schedule[]][] {
	const map = new Map<string, Schedule[]>();
	for (const s of items) {
		const k = dayKey(s);
		if (!map.has(k)) { map.set(k, []); }
		map.get(k)!.push(s);
	}
	return [...map.entries()]
		.sort((a, b) => a[0].localeCompare(b[0]))
		.map(([k, list]) => [k, list.sort((a, b) => (a.startIso ?? '').localeCompare(b.startIso ?? ''))] as const);
}

export function dayLabel(key: string): string {
	if (key === '未安排') { return key; }
	// G 类 MEDIUM：toISOString 是 UTC——08:00 前「今天」会算成昨天（Home 同款 bug 已修过一次，
	// 两处各写各的正是审查点）。统一走 daykey helper。
	const today = localDayKey();
	const tomorrow = nextDayKey();
	if (key === today) { return `今天（${key}）`; }
	if (key === tomorrow) { return `明天（${key}）`; }
	return key;
}

/** 日程表单体（窄屏 Modal 与宽屏右板共用一份——同一张表单不许两处各写一套，spec §9 反模式）。 */
export function ScheduleForm({ value, onChange }: { value: Schedule; onChange: (s: Schedule) => void }) {
	const open = value;
	return (
		<>
			<Field label="标题">
				<input value={open.title} onChange={e => onChange({ ...open, title: e.target.value })} autoFocus placeholder="例如：团队周会" />
			</Field>
			<Field label="日期（YYYY-MM-DD，可空）">
				<input type="date" value={open.date ?? ''} onChange={e => onChange({ ...open, date: e.target.value || undefined })} />
			</Field>
			<div style={{ display: 'flex', gap: 10 }}>
				<Field label="开始时间">
					<input type="time" value={open.startIso?.slice(11, 16) ?? ''} onChange={e => {
						const t = e.target.value;
						const d = open.date ?? new Date().toISOString().slice(0, 10);
						onChange({ ...open, startIso: t ? `${d}T${t}:00` : undefined });
					}} />
				</Field>
				<Field label="结束时间">
					<input type="time" value={open.endIso?.slice(11, 16) ?? ''} onChange={e => {
						const t = e.target.value;
						const d = open.date ?? new Date().toISOString().slice(0, 10);
						onChange({ ...open, endIso: t ? `${d}T${t}:00` : undefined });
					}} />
				</Field>
			</div>
			<Field label="优先级（时间轴配色：高红/中黄/低蓝）">
				<select value={open.priority ?? ''} onChange={e => onChange({ ...open, priority: e.target.value ? Number(e.target.value) : undefined })}>
					<option value="">未设置（中）</option>
					<option value="1">高</option>
					<option value="2">中</option>
					<option value="3">低</option>
				</select>
			</Field>
			<Field label="备注">
				<input value={open.note ?? ''} onChange={e => onChange({ ...open, note: e.target.value || undefined })} placeholder="可空" />
			</Field>
		</>
	);
}

export default function SchedulesPage() {
	const { items, upsert, remove, error } = useCollection<Schedule>('schedules');
	const [editing, setEditing] = useState<Schedule | null>(null);
	const [creating, setCreating] = useState(false);

	const groups = useMemo(() => groupSchedules(items), [items]);

	const open = editing ?? (creating ? EMPTY : null);
	const close = () => { setEditing(null); setCreating(false); };

	const save = async () => {
		if (!open || !open.title.trim()) { return; }
		await upsert(open);
		close();
	};

	return (
		<>
			<PageHeader title="日程" action={<FilledButtonM3 onClick={() => setCreating(true)}>＋ 新建</FilledButtonM3>} />
			{groups.length === 0 && <Empty text={error ? '日程读取失败——稍后重试' : '还没有日程，点右上角「新建」添加'} />}
			{groups.map(([key, list]) => (
				<div key={key}>
					<div className="group-title">{dayLabel(key)}</div>
					{list.map(s => (
						<ListRow key={s.id}
							title={<span className="card-title">{s.title}</span>}
							meta={<>
								{s.startIso && <span className="time-badge">{s.startIso.slice(11, 16)}{s.endIso ? `–${s.endIso.slice(11, 16)}` : ''}</span>}
								{(s.note || (!s.startIso && s.date)) && <span>{[!s.startIso && s.date, s.note].filter(Boolean).join(' · ')}</span>}
							</>}
							onClick={() => setEditing(s)}
							actions={[
								{ label: '编辑', icon: <Pencil size={13} />, onClick: () => setEditing(s) },
								{ label: '删除', icon: <Trash2 size={13} />, danger: true,
									onClick: () => { if (window.confirm('删除这条记录？（软删除，数据仍在文件里）')) { remove(s.id); } } },
							]}
						/>
					))}
				</div>
			))}

			{open && (
				<Modal title={creating ? '新建日程' : '编辑日程'} onClose={close}>
					<ScheduleForm value={open} onChange={setEditing} />
					<div className="form-actions">
						<TextButtonM3 onClick={close}>取消</TextButtonM3>
						<FilledButtonM3 onClick={save}>保存</FilledButtonM3>
					</div>
				</Modal>
			)}
		</>
	);
}
