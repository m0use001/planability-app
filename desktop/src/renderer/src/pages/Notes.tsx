import { useEffect, useMemo, useRef, useState } from 'react';
import { Plus } from 'lucide-react';
import type { Note, Notebook } from '@shared/types';
import { useCollection } from '../hooks';
import { Empty, Field, Modal, PageHeader } from '../components/ui';
import { ListRow } from '../components/m3/chrome';
import { FilterChipM3 } from '../components/m3/chips';
import { FilledButtonM3, TextButtonM3 } from '../components/m3/buttons';
import { BoardHeader, BoardSectionTitle, BoardSubHeader } from '../components/m3/board';
import { DeckBoard } from '../components/m3/deck';
import { useContainerWidth } from '../hooks/useContainerWidth';
import { EXPANDED_WIDTH_BREAKPOINT } from '../lib/sizing';
import NotebookCover from '../components/NotebookCover';
import './notes.css';

/** 笔记页 — phone NotesScreen parity: 笔记本（封面网格）→ 笔记 → 编辑器 三层关系树。
 *  宽屏（内容宽 ≥840）：v69d「三级滑动」A2 试点 —— DeckBoard 四板纸条
 *  [最近笔记 | 笔记本网格 | 笔记列表 | 编辑器]，level 由 bookId/selectedId 推导
 *  （1 网格+最近 / 2 列表+网格 / 3 编辑器+列表），返回逐级左移（返回钮/Esc）。
 *  窄窗：保留原三列布局（零回归）。 */

const STYLE_NAMES = [
	'素色', '陆家嘴晨光', '霓虹东京', '曼哈顿日落', '维港夜色', '银河雪山',
	'行星月球', '深空星云', '海岸灯塔', '沙漠月牙', '雨林彩虹'
];
const LAYOUT_NAMES = ['网格', '错行', '斜向', '居中'];

interface BookDraft {
	id?: string;
	name: string;
	coverStyle: number;
	coverHue: number;
	coverText: string;
	coverLayout: number;
}

export default function NotesPage() {
	const books = useCollection<Notebook>('notebooks');
	const notes = useCollection<Note>('notes');
	const [bookId, setBookId] = useState<string | null>(null);
	const [selectedId, setSelectedId] = useState<string | null>(null);
	const [draft, setDraft] = useState<{ title: string; body: string } | null>(null);
	const [dirty, setDirty] = useState(false);
	const [editingBook, setEditingBook] = useState<BookDraft | null>(null);

	// ---- A2 宽屏判据：内容容器实测宽（ResizeObserver），≥840 上纸条钻入 ----
	const [pageRef, pageWidth] = useContainerWidth<HTMLDivElement>();
	const wide = pageWidth >= EXPANDED_WIDTH_BREAKPOINT;

	const bookList = useMemo(
		() => [...books.items].sort((a, b) => (b.updatedAt) - (a.updatedAt)),
		[books.items]
	);
	const activeBook = bookList.find(b => b.id === bookId) ?? bookList[0] ?? null;
	const bookNotes = useMemo(() => {
		if (!activeBook) { return []; }
		return notes.items
			.filter(n => n.notebookId === activeBook.id)
			.sort((a, b) => Number(!!b.pinned) - Number(!!a.pinned) || b.updatedAt - a.updatedAt);
	}, [notes.items, activeBook]);

	const selected = bookNotes.find(n => n.id === selectedId) ?? null;

	// ---- heal: every install gets one notebook; legacy notes get adopted ----
	useEffect(() => {
		if (books.loading || notes.loading) { return; }
		let cancelled = false;
		void (async () => {
			let list = bookList;
			if (!list.length) {
				const rec = await books.upsert({ id: '', createdAt: 0, updatedAt: 0, name: '默认笔记本', coverStyle: 0, coverHue: 212 });
				list = [rec];
			}
			for (const n of notes.items) {
				if (!n.notebookId) { await notes.upsert({ ...n, notebookId: list[0].id }); }
			}
			// 窄屏保持「自动选中第一本」的旧行为；宽屏不自动选（手机同款：level1 停在网格+最近）
			if (!cancelled && !bookId && !wide) { setBookId(list[0].id); }
		})();
		return () => { cancelled = true; };
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [books.loading, notes.loading]);

	// keep selection inside the active notebook
	useEffect(() => {
		if (selectedId && !bookNotes.some(n => n.id === selectedId)) { setSelectedId(null); }
	}, [bookNotes, selectedId]);

	// 窄屏：always show something in the editor（宽屏不自动开编辑器——手机同款，点笔记才钻 level3）
	useEffect(() => {
		if (wide) { return; }
		if (!selectedId && bookNotes.length > 0) { void selectNote(bookNotes[0].id); }
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [bookNotes, selectedId, wide]);

	useEffect(() => {
		if (selected) {
			setDraft({ title: selected.title, body: selected.body });
			setDirty(false);
		} else {
			setDraft(null);
		}
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [selectedId]);

	// ---- 滑出路上内容不蒸（手机 BoardDeck 约定：板内容自己保留最后一次非空数据） ----
	const lastBookRef = useRef<{ id: string; name: string } | null>(null);
	if (activeBook) { lastBookRef.current = { id: activeBook.id, name: activeBook.name }; }
	const deckBook = activeBook ?? lastBookRef.current;
	const deckBookNotes = useMemo(() => {
		if (!deckBook) { return []; }
		return notes.items
			.filter(n => n.notebookId === deckBook.id)
			.sort((a, b) => Number(!!b.pinned) - Number(!!a.pinned) || b.updatedAt - a.updatedAt);
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [notes.items, deckBook?.id]);
	const lastDraftRef = useRef<{ title: string; body: string } | null>(null);
	if (draft) { lastDraftRef.current = draft; }
	const deckNote = selected;
	const deckDraft = draft ?? lastDraftRef.current;

	const flush = async () => {
		if (selected && dirty && draft) {
			await notes.upsert({ ...selected, title: draft.title.trim() || '未命名笔记', body: draft.body });
			setDirty(false);
		}
	};

	const selectNote = async (id: string) => {
		await flush();
		setSelectedId(id);
	};

	const selectBook = async (id: string) => {
		await flush();
		setBookId(id);
		setSelectedId(null);
	};

	const createNote = async () => {
		if (!activeBook) { return; }
		await flush();
		const rec = await notes.upsert({ id: '', createdAt: 0, updatedAt: 0, title: '未命名笔记', body: '', notebookId: activeBook.id });
		setSelectedId(rec.id);
	};

	const save = async () => {
		if (!selected || !draft) { return; }
		await notes.upsert({ ...selected, title: draft.title.trim() || '未命名笔记', body: draft.body });
		setDirty(false);
	};

	const del = async () => {
		if (!selected) { return; }
		if (!window.confirm(`删除笔记「${selected.title}」？（软删除）`)) { return; }
		await notes.remove(selected.id);
		setSelectedId(null);
	};

	const moveTo = async (targetId: string) => {
		if (!selected) { return; }
		await flush();
		await notes.upsert({ ...selected, notebookId: targetId });
		setSelectedId(null);
	};

	// ---- 宽屏层级：1 网格+最近 / 2 列表+网格 / 3 编辑器+列表（手机同款推导） ----
	// 「点笔记直接钻到编辑器级」：最近笔记/列表入口都把 bookId 与 selectedId 同置。
	const level = selectedId != null ? 3 : bookId != null ? 2 : 1;
	const deckBack = () => {
		if (selectedId != null) { setSelectedId(null); }
		else { setBookId(null); setSelectedId(null); }
	};

	// 桌面没有预测返回手势：Esc 触发带动画逐级返回（DeckBoard 弹簧照播）
	useEffect(() => {
		if (!wide) { return; }
		const onKey = (e: KeyboardEvent) => {
			if (e.key !== 'Escape') { return; }
			if (selectedId != null || bookId != null) {
				e.preventDefault();
				if (selectedId != null) { void flush().then(() => setSelectedId(null)); }
				else { setBookId(null); setSelectedId(null); }
			}
		};
		document.addEventListener('keydown', onKey);
		return () => document.removeEventListener('keydown', onKey);
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [wide, selectedId, bookId]);

	// ---- 宽屏：最近笔记（板0，根层副框）数据 ----
	const recentNotes = useMemo(
		() => [...notes.items].sort((a, b) => b.updatedAt - a.updatedAt).slice(0, 12),
		[notes.items]
	);

	// 从「最近笔记」直达编辑器：找到所属笔记本，bookId+selectedId 同置（手机 openNoteEntry 同款）
	const openNoteEntry = async (id: string) => {
		const n = notes.items.find(x => x.id === id);
		if (!n) { return; }
		await flush();
		if (n.notebookId) { setBookId(n.notebookId); }
		setSelectedId(id);
	};

	if (wide) {
		return (
			<div ref={pageRef} className="notes-deck-page">
				<DeckBoard
					level={level}
					panes={[
						// 板 0：最近笔记（根层副框；钻入后滑出右缘）——参照=节标题+卡片行（spec §5 笔记行）
						() => (
							<>
								<BoardSectionTitle text="最近笔记" />
								<div className="notes-board-body" style={{ paddingTop: 4 }}>
									{recentNotes.length === 0 && <Empty text="还没有笔记" />}
									{recentNotes.map(n => (
										<ListRow key={n.id}
											title={<span className="list-item-title">{n.pinned ? '📌 ' : ''}{n.title}</span>}
											meta={<span>{new Date(n.updatedAt).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</span>}
											onClick={() => void openNoteEntry(n.id)}
										/>
									))}
								</div>
							</>
						),
						// 板 1：笔记本网格（根层主框；钻入后滑进副框当「选本器」）
						() => (
							<>
								<BoardHeader
									title="笔记"
									subtitle={`${bookList.length} 本`}
									actions={
										<TextButtonM3 onClick={() => setEditingBook({ name: '', coverStyle: 0, coverHue: 212, coverText: '', coverLayout: 0 })}>
											<Plus size={14} /> 新建
										</TextButtonM3>
									}
								/>
								<div className="notes-board-body">
									<div className="notes-book-grid">
										{bookList.map(b => (
											<button key={b.id} type="button"
												className={`notes-book-cell ${bookId === b.id ? 'active' : ''}`}
												onClick={() => void selectBook(b.id)}>
												<NotebookCover
													coverStyle={b.coverStyle ?? 0}
													coverHue={b.coverHue ?? 210}
													coverText={b.coverText ?? ''}
													coverLayout={b.coverLayout ?? 0}
													className="nb-cover"
												/>
												<span className="notes-book-name">{b.name}</span>
												<span className="notes-book-count">{notes.items.filter(n => n.notebookId === b.id).length} 篇</span>
											</button>
										))}
									</div>
								</div>
							</>
						),
						// 板 2：笔记列表（level2 主框 / level3 副框；返回箭头只在主框显示）
						() => (
							<>
								{level === 2 ? (
									<BoardSubHeader
										title={deckBook?.name ?? '—'}
										onBack={deckBack}
										actions={<TextButtonM3 onClick={() => void createNote()} disabled={!activeBook}><Plus size={14} /> 新建</TextButtonM3>}
									/>
								) : (
									<BoardHeader title={deckBook?.name ?? '—'} subtitle={`${deckBookNotes.length} 篇`} />
								)}
								<div className="notes-board-body">
									{deckBookNotes.length === 0 && <Empty text="这个笔记本还没有笔记" />}
									{deckBookNotes.map(n => (
										<ListRow key={n.id} active={n.id === selectedId}
											title={<span className="list-item-title">{n.pinned ? '📌 ' : ''}{n.title}</span>}
											meta={<span>{new Date(n.updatedAt).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</span>}
											onClick={() => void selectNote(n.id)}
										/>
									))}
								</div>
							</>
						),
						// 板 3：编辑器（level3 主框）
						() => (
							<>
								<BoardSubHeader
									title={deckDraft?.title || '未命名笔记'}
									onBack={() => { void flush().then(() => setSelectedId(null)); }}
									actions={
										<TextButtonM3 onClick={() => void save()} disabled={!dirty || !selected}>
											{dirty ? '保存' : '已保存'}
										</TextButtonM3>
									}
								/>
								{deckDraft ? (
									<div className="notes-editor-body">
										<div className="board-header" style={{ padding: '0 0 4px' }}>
											<input
												className="notes-editor-title"
												value={deckDraft.title}
												onChange={e => { setDraft({ ...deckDraft, title: e.target.value }); setDirty(true); }}
												placeholder="未命名笔记"
											/>
											<select
												className="nb-move"
												value={deckNote?.notebookId ?? ''}
												onChange={e => { if (deckNote) { void moveTo(e.target.value); } }}
												title="移动到笔记本"
											>
												{bookList.map(b => <option key={b.id} value={b.id}>{b.name}</option>)}
											</select>
										</div>
										<div className="notes-editor-actions">
											<TextButtonM3 disabled={!selected} onClick={() => { if (selected) { void notes.upsert({ ...selected, pinned: !selected.pinned }); } }}>
												{selected?.pinned ? '取消置顶' : '置顶'}
											</TextButtonM3>
											<TextButtonM3 className="m3-text-danger" disabled={!selected} onClick={() => void del()}>删除</TextButtonM3>
										</div>
										<textarea
											value={deckDraft.body}
											placeholder="开始写…（Markdown 纯文本）"
											disabled={!selected}
											onChange={e => { if (selected) { setDraft({ ...deckDraft, body: e.target.value }); setDirty(true); } }}
										/>
									</div>
								) : (
									<Empty text="从列表选择一条笔记，或新建" />
								)}
							</>
						),
					]}
				/>
				{editingBook && <BookDialog draft={editingBook} onClose={() => setEditingBook(null)} onConfirm={confirmBook} />}
			</div>
		);
	}

	async function confirmBook(d: BookDraft) {
		if (!d.name.trim()) { return; }
		if (d.id) {
			const cur = bookList.find(b => b.id === d.id);
			if (cur) {
				await books.upsert({
					...cur, name: d.name.trim(), coverStyle: d.coverStyle,
					coverHue: d.coverHue, coverText: d.coverText.trim(), coverLayout: d.coverLayout
				});
			}
		} else {
			const rec = await books.upsert({
				id: '', createdAt: 0, updatedAt: 0, name: d.name.trim(),
				coverStyle: d.coverStyle, coverHue: d.coverHue,
				coverText: d.coverText.trim(), coverLayout: d.coverLayout
			});
			setBookId(rec.id);
		}
		setEditingBook(null);
	}

	return (
		<>
			<div ref={pageRef}>
				<PageHeader
					title="笔记"
					action={<FilledButtonM3 onClick={() => void createNote()} disabled={!activeBook}>＋ 新建</FilledButtonM3>}
				/>

				<div className="editor-wrap notes-panes">
						{/* ---- 层 1：笔记本 ---- */}
						<div className="pane-col nb-col">
							<div className="pane-head">
								<span>笔记本</span>
								<span className="pane-count">{bookList.length}</span>
								<button className="nc-link" onClick={() => setEditingBook({ name: '', coverStyle: 0, coverHue: 212, coverText: '', coverLayout: 0 })}>
									＋ 新建
								</button>
							</div>
							<div className="nb-list">
								{bookList.length === 0 && (
									<div className="pane-empty">还没有笔记本<br />点上方「＋ 新建」创建</div>
								)}
								{bookList.map(b => (
										<div key={b.id} className={`nb-item ${activeBook?.id === b.id ? 'active' : ''}`}>
											<button className="nb-cover-btn" onClick={() => void selectBook(b.id)} title={b.name}>
												<NotebookCover
													coverStyle={b.coverStyle ?? 0}
													coverHue={b.coverHue ?? 210}
													coverText={b.coverText ?? ''}
													coverLayout={b.coverLayout ?? 0}
													className="nb-cover"
												/>
											</button>
											<div className="nb-row">
												<span className="nb-name" title={b.name}>{b.name}</span>
												<button
													className="nb-edit"
													title="管理笔记本"
													onClick={() => setEditingBook({
														id: b.id, name: b.name, coverStyle: b.coverStyle ?? 0,
														coverHue: b.coverHue ?? 210, coverText: b.coverText ?? '', coverLayout: b.coverLayout ?? 0
													})}
												>⋯</button>
											</div>
									<div className="nb-count">{notes.items.filter(n => n.notebookId === b.id).length} 篇</div>
									</div>
								))}
							</div>
						</div>

						{/* ---- 层 2：该笔记本下的笔记 ---- */}
						<div className="pane-col notes-col">
							<div className="pane-head">
								<span className="pane-title">{activeBook?.name ?? '—'}</span>
								<span className="pane-count">{bookNotes.length} 篇</span>
								<button className="nc-link" onClick={() => void createNote()}>＋ 新建</button>
							</div>
							<div className="editor-list">
								{bookNotes.length === 0 && <Empty text="这个笔记本还没有笔记" />}
								{bookNotes.map(n => (
									<ListRow key={n.id} active={n.id === selectedId}
										title={<span className="list-item-title">{n.pinned ? '📌 ' : ''}{n.title}</span>}
										meta={<span>{new Date(n.updatedAt).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</span>}
										onClick={() => void selectNote(n.id)}
									/>
								))}
							</div>
						</div>

						{/* ---- 层 3：编辑器 ---- */}
						<div className="editor-pane">
							{selected && draft ? (
								<>
									<div className="editor-toolbar">
										<input
											className="editor-title-input"
											value={draft.title}
											onChange={e => { setDraft({ ...draft, title: e.target.value }); setDirty(true); }}
										/>
										<select
											className="nb-move"
											value={selected.notebookId ?? ''}
											onChange={e => void moveTo(e.target.value)}
											title="移动到笔记本"
										>
											{bookList.map(b => <option key={b.id} value={b.id}>{b.name}</option>)}
										</select>
									</div>
									<div className="editor-actions">
										<TextButtonM3 onClick={() => void notes.upsert({ ...selected, pinned: !selected.pinned })}>
											{selected.pinned ? '取消置顶' : '置顶'}
										</TextButtonM3>
										{/* danger 文本钮 = token 作用域技巧：把该元素内部的 primary 指到 error */}
										<TextButtonM3 className="m3-text-danger" onClick={() => void del()}>删除</TextButtonM3>
										<FilledButtonM3 onClick={() => void save()} disabled={!dirty}>
											{dirty ? '保存' : '已保存'}
										</FilledButtonM3>
									</div>
									<textarea
										value={draft.body}
										placeholder="开始写…（Markdown 纯文本）"
										onChange={e => { setDraft({ ...draft, body: e.target.value }); setDirty(true); }}
									/>
								</>
							) : (
								<Empty text="从左侧选择一条笔记，或新建" />
							)}
							</div>
						</div>
			</div>

				{editingBook && (
					<BookDialog
					draft={editingBook}
					onClose={() => setEditingBook(null)}
					onConfirm={confirmBook}
				/>
			)}
		</>
	);
}

/** 新建/管理笔记本：封面预览 + 11 款场景 + 自定义文字 + 换色（phone NotebookDialog 对齐） */
function BookDialog({ draft, onClose, onConfirm }: {
	draft: BookDraft;
	onClose: () => void;
	onConfirm: (d: BookDraft) => void | Promise<void>;
}) {
	const [d, setD] = useState<BookDraft>(draft);
	const isEdit = !!d.id;

	return (
		<Modal title={isEdit ? '管理笔记本' : '新建笔记本'} onClose={onClose} dirty={JSON.stringify(d) !== JSON.stringify(draft)}>
			<div className="book-dialog">
				<div className="book-preview">
					<NotebookCover
						coverStyle={d.coverStyle}
						coverHue={d.coverHue}
						coverText={d.coverText}
						coverLayout={d.coverLayout}
						style={{ width: 120, height: 150 }}
					/>
				</div>

				<div style={{ display: 'flex', gap: 8, justifyContent: 'center' }}>
					<span className="field-label" style={{ alignSelf: 'center' }}>封面</span>
					<TextButtonM3 onClick={() => setD({ ...d, coverHue: (d.coverHue + 47) % 360 })}>换色</TextButtonM3>
				</div>

				<div className="cover-grid">
					{STYLE_NAMES.map((name, st) => (
						<button
							key={st}
							className={`cover-cell ${d.coverStyle === st ? 'active' : ''}`}
							onClick={() => setD({ ...d, coverStyle: st })}
							title={name}
						>
							<NotebookCover coverStyle={st} coverHue={d.coverHue} coverText={d.coverText} coverLayout={d.coverLayout} className="cover-thumb" />
							<span className="cover-cell-name">{name}</span>
						</button>
					))}
					<button
						className={`cover-cell ${d.coverStyle === 90 ? 'active' : ''}`}
						onClick={() => setD({ ...d, coverStyle: 90 })}
						title="自定义文字"
					>
						<NotebookCover coverStyle={90} coverHue={d.coverHue} coverText={d.coverText} coverLayout={d.coverLayout} className="cover-thumb" />
						<span className="cover-cell-name">自定义</span>
					</button>
				</div>

				{d.coverStyle === 90 && (
					<>
						<Field label="封面文字">
							<input value={d.coverText} onChange={e => setD({ ...d, coverText: e.target.value })} placeholder="留空显示「笔记本」" />
						</Field>
						<div style={{ display: 'flex', gap: 6 }}>
								{LAYOUT_NAMES.map((n, i) => (
									<FilterChipM3 key={n} selected={d.coverLayout === i} onClick={() => setD({ ...d, coverLayout: i })}>{n}</FilterChipM3>
								))}
						</div>
					</>
				)}

				<Field label="名称">
					<input value={d.name} onChange={e => setD({ ...d, name: e.target.value })} placeholder="例如：课程笔记" />
				</Field>
			</div>
			<div className="form-actions">
				<TextButtonM3 onClick={onClose}>取消</TextButtonM3>
				<FilledButtonM3 disabled={!d.name.trim()} onClick={() => void onConfirm(d)}>确定</FilledButtonM3>
			</div>
		</Modal>
	);
}
