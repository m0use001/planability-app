import { useState } from 'react';
import type { Project } from '@shared/types';
import { useCollection } from '../hooks';
import { Empty, Field, Modal, PageHeader } from '../components/ui';
import { ListRow } from '../components/m3/chrome';
import { FilledButtonM3, TextButtonM3 } from '../components/m3/buttons';
import { Trash2 } from 'lucide-react';
import { FilterChipM3 } from '../components/m3/chips';

const EMOJIS = ['📁', '💻', '🖥', '📚', '🎯', '🧪', '🏠', '✈️'];

export default function ProjectsPage() {
	const { items, upsert, remove, error } = useCollection<Project>('projects');
	const [creating, setCreating] = useState(false);
	const [draft, setDraft] = useState<{ name: string; emoji: string }>({ name: '', emoji: '📁' });

	const create = async () => {
		if (!draft.name.trim()) { return; }
		await upsert({ id: '', createdAt: 0, updatedAt: 0, name: draft.name.trim(), emoji: draft.emoji });
		setCreating(false);
		setDraft({ name: '', emoji: '📁' });
	};

	return (
		<>
			<PageHeader title="项目" action={<FilledButtonM3 onClick={() => setCreating(true)}>＋ 新建</FilledButtonM3>} />
			{items.length === 0 && <Empty text={error ? '项目读取失败——稍后重试' : '还没有项目。项目可作为日程/待办/笔记的归属维度'} />}
			{items.map(p => (
				<ListRow key={p.id}
					leading={<span style={{ fontSize: 22 }}>{p.emoji ?? '📁'}</span>}
					title={<span className="card-title">{p.name}</span>}
					meta={<span>创建于 {new Date(p.createdAt).toLocaleDateString('zh-CN')}</span>}
					actions={[
						{ label: '删除', icon: <Trash2 size={13} />, danger: true,
							onClick: () => { if (window.confirm('删除这条记录？（软删除，数据仍在文件里）')) { remove(p.id); } } },
					]}
				/>
			))}

			{creating && (
				<Modal title="新建项目" onClose={() => { setCreating(false); setDraft({ name: '', emoji: '📁' }); }} dirty={draft.name !== '' || draft.emoji !== '📁'}>
					<Field label="名称">
						<input value={draft.name} onChange={e => setDraft({ ...draft, name: e.target.value })} autoFocus placeholder="例如：线性代数课设" />
					</Field>
					<Field label="图标">
						<div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
							{EMOJIS.map(e => (
								<FilterChipM3 key={e} selected={draft.emoji === e} onClick={() => setDraft({ ...draft, emoji: e })}>
									{e}
								</FilterChipM3>
							))}
						</div>
					</Field>
					<div className="form-actions">
						<TextButtonM3 onClick={() => { setCreating(false); setDraft({ name: '', emoji: '📁' }); }}>取消</TextButtonM3>
						<FilledButtonM3 onClick={create}>保存</FilledButtonM3>
					</div>
				</Modal>
			)}
		</>
	);
}
