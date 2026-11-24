import { useMemo, useState } from 'react';
import { Pencil, Trash2 } from 'lucide-react';
import type { Todo } from '@shared/types';
import { useCollection } from '../hooks';
import { Empty, Field, Modal, PageHeader } from '../components/ui';
import { ListRow } from '../components/m3/chrome';
import { CheckboxM3 } from '../components/m3/controls';
import { FilledButtonM3, TextButtonM3 } from '../components/m3/buttons';

const EMPTY: Todo = { id: '', createdAt: 0, updatedAt: 0, title: '', status: 'open' };
export const EMPTY_TODO = EMPTY;

/** 待办表单体（窄屏 Modal 与宽屏右板共用一份）。 */
export function TodoForm({ value, onChange }: { value: Todo; onChange: (t: Todo) => void }) {
	const open = value;
	return (
		<>
			<Field label="标题">
				<input value={open.title} onChange={e => onChange({ ...open, title: e.target.value })} autoFocus placeholder="例如：提交周报" />
			</Field>
			<Field label="日期（YYYY-MM-DD，可空）">
				<input type="date" value={open.date ?? ''} onChange={e => onChange({ ...open, date: e.target.value || undefined })} />
			</Field>
			<Field label="时间（HH:MM，可空——填了会出现在首页时间轴）">
				<input type="time" value={open.time ?? ''} onChange={e => onChange({ ...open, time: e.target.value || undefined })} />
			</Field>
			<Field label="优先级">
				<select value={open.priority ?? ''} onChange={e => onChange({ ...open, priority: e.target.value ? Number(e.target.value) : undefined })}>
					<option value="">未设置</option>
					<option value="1">高</option>
					<option value="2">中</option>
					<option value="3">低</option>
				</select>
			</Field>
		</>
	);
}

/** 待办排序（进行中在前、按优先级）——窄屏列表与宽屏左板共用。 */
export function splitTodos(items: Todo[]): [Todo[], Todo[]] {
	const sorted = [...items].sort((a, b) => (a.priority ?? 9) - (b.priority ?? 9) || b.updatedAt - a.updatedAt);
	return [sorted.filter(t => t.status !== 'done'), sorted.filter(t => t.status === 'done')];
}

export default function TodosPage() {
	const { items, upsert, remove, error } = useCollection<Todo>('todos');
	const [editing, setEditing] = useState<Todo | null>(null);
	const [creating, setCreating] = useState(false);

	const open = editing ?? (creating ? EMPTY : null);
	const close = () => { setEditing(null); setCreating(false); };

	const save = async () => {
		if (!open || !open.title.trim()) { return; }
		await upsert(open);
		close();
	};

	const [openTodos, doneTodos] = useMemo(() => splitTodos(items), [items]);

	const toggle = (t: Todo) => upsert({ ...t, status: t.status === 'done' ? 'open' : 'done' });
	const prioLabel = (p?: number) => (p === 1 ? '高' : p === 2 ? '中' : p === 3 ? '低' : null);
	const prioClass = (p?: number) => (p === 1 ? 'p1' : p === 2 ? 'p2' : 'p3');

	/** P3：card 行 → ListRow；删除保留原 confirm 语义（与 DeleteButton 同文案） */
	const renderRow = (t: Todo) => (
		<ListRow key={t.id} muted={t.status === 'done'}
			leading={<CheckboxM3 checked={t.status === 'done'} onClick={() => toggle(t)} aria-label="完成待办" />}
			title={<span className="card-title">{t.title}</span>}
			meta={<>
				{(t.date || t.time) && <span>{[t.date, t.time].filter(Boolean).join(' · ')}</span>}
				{t.priority != null && <span className={`prio ${prioClass(t.priority)}`}>{prioLabel(t.priority)}</span>}
			</>}
			onClick={() => setEditing(t)}
			actions={[
				{ label: '编辑', icon: <Pencil size={13} />, onClick: () => setEditing(t) },
				{ label: '删除', icon: <Trash2 size={13} />, danger: true,
					onClick: () => { if (window.confirm('删除这条记录？（软删除，数据仍在文件里）')) { remove(t.id); } } },
			]}
		/>
	);

	return (
		<>
			<PageHeader title="待办" action={<FilledButtonM3 onClick={() => setCreating(true)}>＋ 新建</FilledButtonM3>} />
			{items.length === 0 && <Empty text={error ? '待办读取失败——稍后重试' : '还没有待办，点右上角「新建」添加'} />}
			{openTodos.length > 0 && <div className="group-title">进行中（{openTodos.length}）</div>}
			{openTodos.map(renderRow)}
			{doneTodos.length > 0 && <div className="group-title">已完成（{doneTodos.length}）</div>}
			{doneTodos.map(renderRow)}

			{open && (
				<Modal title={creating ? '新建待办' : '编辑待办'} onClose={close}>
					<TodoForm value={open} onChange={setEditing} />
					<div className="form-actions">
						<TextButtonM3 onClick={close}>取消</TextButtonM3>
						<FilledButtonM3 onClick={save}>保存</FilledButtonM3>
					</div>
				</Modal>
			)}
		</>
	);
}
