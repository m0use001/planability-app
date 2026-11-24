import { useEffect, useRef, useState } from 'react';
import { FolderOpen, Download, Upload, FileJson, ScrollText, FolderSync, ChevronRight, ArrowLeft } from 'lucide-react';
import type { AutomationDef } from '@shared/ipc';
import { Empty, PageHeader } from '../components/ui';
import { useNavigate } from '../router';
import { FilledButtonM3, TextButtonM3 } from '../components/m3/buttons';
import { IconM3 } from '../components/m3/icons';
import { BoardHeader, BoardSectionTitle, SplitBoards } from '../components/m3/board';
import { PaneStatRow, PaneActionBlock, PaneActionRow, PaneEmptyInline } from '../components/m3/paneblocks';
import { useContainerWidth } from '../hooks/useContainerWidth';
import { EXPANDED_WIDTH_BREAKPOINT } from '../lib/sizing';
import './files.css';

export default function FilesPage() {
	const nav = useNavigate();
	const [automations, setAutomations] = useState<AutomationDef[]>([]);
	const [logOf, setLogOf] = useState<{ id: number; title: string; text: string } | null>(null);
	const [msg, setMsg] = useState('');

	useEffect(() => {
		window.planability.automationList().then(setAutomations).catch(() => undefined);
	}, []);

	const flash = (t: string) => { setMsg(t); setTimeout(() => setMsg(''), 4000); };

	const doExport = async () => {
		try {
			const dump = await window.planability.exportDump();
			const path = await window.planability.fileSaveJson(JSON.stringify(dump, null, 1),
				`planability-export-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.json`);
			if (path) { flash(`已导出到：${path}`); } else { flash('已取消'); }
		} catch (e) { flash(`导出失败：${String(e)}`); }
	};

	const doImport = async () => {
		// G 类 HIGH-1：全页唯一没有二次确认的写入操作。确认放在选文件【之前】——
		// 合并语义（新者胜会覆盖本地同 id 记录）本就该先说清，再让人花时间挑文件；
		// 放选文件之后还有个隐性坑：确认弹在原生文件对话框后面，用户根本看不见。
		if (!window.confirm('导入会把文件中的记录合并进当前数据；与本地同 id 冲突时【新者胜】，可能覆盖本地改动。\n下一步选择导出文件。确定继续？')) {
			flash('已取消');
			return;
		}
		try {
			const dump = await window.planability.fileOpenJson();
			if (!dump) { flash('已取消或文件无法解析'); return; }
			const n = await window.planability.importDump(dump as never);
			flash(`导入完成：合并 ${n} 条（新者胜）`);
		} catch (e) { flash(`导入失败：${String(e)}`); }
	};

	const showLog = async (a: AutomationDef) => {
		if (logOf?.id === a.id) { setLogOf(null); return; }
		const text = await window.planability.automationLog(a.id);
		setLogOf({ id: a.id, title: a.name, text: text || '（还没有执行日志）' });
	};

	// ---- A2 宽屏两板：左=入口块+自动化日志列表，右=选中日志详情/概览（paneOf 口径） ----
	const [pageRef, pageWidth] = useContainerWidth<HTMLDivElement>();
	const wide = pageWidth >= EXPANDED_WIDTH_BREAKPOINT;
	const lastLogRef = useRef<{ id: number; title: string; text: string } | null>(null);
	if (logOf) { lastLogRef.current = logOf; }
	const deckLog = logOf ?? lastLogRef.current;

	if (wide) {
		return (
			<div ref={pageRef} className="files-wide">
				<SplitBoards
					twoPane
					primary={<>
						<BoardHeader title="文件" subtitle="JSON dump 备份 · 跨设备同步" />
						<div className="files-main-body">
							<div className="files-list-col">
								{/* v72.2：多个入口并进一枚「快捷」矩形，行间细线——不许四块背靠背 */}
								<PaneActionRow
									actions={[
										{ key: 'export', icon: <FileJson size={15} />, title: '数据备份', meta: '导出（dump v1 兼容，新者胜合并）', onClick: doExport },
										{ key: 'import', icon: <Upload size={15} />, title: '导入备份', meta: '从 JSON 文件合并进当前数据', onClick: doImport },
										{ key: 'dir', icon: <FolderOpen size={15} />, title: '数据目录', meta: '在资源管理器打开', onClick: () => void window.planability.openDataDir() },
										{ key: 'sync', icon: <FolderSync size={15} />, title: '同步中心 · Syncthing', meta: '跨设备文件夹同步', onClick: () => nav('sync') },
									]}
								/>
								<BoardSectionTitle text={`自动化执行日志（${automations.length}）`} />
								{automations.length === 0 && <PaneEmptyInline text="还没有自动化" />}
								{automations.map(a => (
									<button key={a.id} type="button"
										className={`pane-action-block ${logOf?.id === a.id ? '' : ''}`}
										style={{ background: 'transparent', padding: '10px 2px' }}
										onClick={() => void showLog(a)}>
										<span className="pane-action-block__text">
											<span className="pane-action-block__title">{a.name}</span>
											<span className="pane-action-block__meta">上次执行 {a.lastRun ? new Date(a.lastRun).toLocaleString('zh-CN') : '未执行过'}</span>
										</span>
										<ChevronRight size={14} style={{ color: 'var(--text-faint)' }} />
									</button>
								))}
								{msg && <div className="home-empty" style={{ textAlign: 'left', padding: '8px 0' }}>{msg}</div>}
							</div>
						</div>
					</>}
					secondary={<>
						{deckLog ? (
							<>
								<BoardHeader
									title={deckLog.title}
									leading={logOf ? (
										<button type="button" className="board-back" onClick={() => setLogOf(null)} aria-label="返回">
											<ArrowLeft size={15} />
										</button>
									) : undefined}
								/>
								<div className="files-detail">
									<pre>{deckLog.text}</pre>
								</div>
							</>
						) : (
							<>
								<BoardHeader title="概览" subtitle={`${automations.length} 条自动化`} />
								<div className="files-detail">
									<PaneStatRow
										items={[
											{ value: automations.length, label: '自动化' },
											{ value: automations.filter(a => a.lastRun).length, label: '执行过' },
										]}
									/>
									<PaneActionBlock icon={<FileJson size={15} />} title="数据备份" meta="导出 JSON dump（与手机端兼容）" onClick={doExport} />
									<PaneEmptyInline text="左侧选一条自动化看执行日志" />
								</div>
							</>
						)}
					</>}
				/>
			</div>
		);
	}

	return (
		<>
			<div ref={pageRef}>
				<PageHeader title="文件" />
			<p style={{ color: 'var(--text-dim)', fontSize: 12.5, marginTop: -8 }}>
				备份以 JSON 交换格式导出（与手机端 dump v1 兼容，记录级 updatedAt 新者胜合并）。
			</p>

			<div className="stat-grid" style={{ marginBottom: 8 }}>
				<div className="stat-card" style={{ minWidth: 200 }}>
					<div className="stat-label" style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
						<FileJson size={14} /> 数据备份
					</div>
					<div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
						<FilledButtonM3 onClick={doExport}>
							<IconM3 slot="icon"><Download size={13} /></IconM3> 导出
						</FilledButtonM3>
						<TextButtonM3 onClick={doImport}>
							<IconM3 slot="icon"><Upload size={13} /></IconM3> 导入
						</TextButtonM3>
					</div>
				</div>
				<div className="stat-card" style={{ minWidth: 200 }}>
					<div className="stat-label" style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
						<FolderOpen size={14} /> 数据目录
					</div>
					<TextButtonM3 style={{ marginTop: 12 }} onClick={() => void window.planability.openDataDir()}>
						在资源管理器打开
					</TextButtonM3>
				</div>
				<div className="stat-card" style={{ minWidth: 200 }}>
					<div className="stat-label" style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
						<FolderSync size={14} /> 跨设备同步
					</div>
					<FilledButtonM3 style={{ marginTop: 12 }} onClick={() => nav('sync')}>
						同步中心 · Syncthing
					</FilledButtonM3>
				</div>
			</div>
			{msg && <div className="home-empty" style={{ textAlign: 'left', padding: '6px 0' }}>{msg}</div>}

			<div className="home-row-head" style={{ marginTop: 18 }}>
				<span><ScrollText size={14} /> 自动化执行日志</span>
			</div>
			{automations.length === 0 && <Empty text="还没有自动化" />}
			{automations.map(a => (
				<div className="card" key={a.id} style={{ display: 'block' }}>
					<div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
						<div className="card-main">
							<div className="card-title">{a.name}</div>
							<div className="card-meta">上次执行 {a.lastRun ? new Date(a.lastRun).toLocaleString('zh-CN') : '未执行过'}</div>
						</div>
						<TextButtonM3 onClick={() => void showLog(a)}>{logOf?.id === a.id ? '收起' : '查看日志'}</TextButtonM3>
					</div>
					{logOf?.id === a.id && (
						<pre style={{
							background: 'var(--surface)', border: '1px solid var(--outline-dim)',
							borderRadius: 'var(--shape-sm)', padding: '10px 12px', fontSize: 12,
							maxHeight: 260, overflow: 'auto', whiteSpace: 'pre-wrap', marginTop: 10
						}}>{logOf.text}</pre>
					)}
				</div>
			))}
			</div>
		</>
	);
}
