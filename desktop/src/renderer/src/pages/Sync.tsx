import { useCallback, useEffect, useRef, useState } from 'react';
import {
	ArrowLeft, Braces, Copy, DatabaseZap, Download, ExternalLink, FolderSync, HardDrive, MonitorSmartphone,
	Pause, Pencil, Play, Plus, RefreshCw, RotateCcw, ScanLine, Settings2,
	Square, Terminal, Trash2, TriangleAlert
} from 'lucide-react';
import { FilledButtonM3, TextButtonM3 } from '../components/m3/buttons';
import { FilterChipM3 } from '../components/m3/chips';
import type { DataSyncStatus, StConfig, StDeviceConfig, StError, StFolderConfig, StLogLine, StPending, StSystemInfo, SyncSummary } from '@shared/ipc';
import { Empty, PageHeader } from '../components/ui';
import { AcceptFolderDialog, DeviceDialog, FolderDialog } from '../components/SyncDialogs';
import { useNavigate } from '../router';

/** 同步中心 · Syncthing — layout follows the reference (engine card → devices → folders →
 *  usage → log), extended with full in-app management: pending offers, data sync,
 *  system controls and the raw config editor. No browser needed. */
export default function SyncPage() {
	const nav = useNavigate();
	const [sum, setSum] = useState<SyncSummary | null>(null);
	const [busy, setBusy] = useState<string | null>(null);
	const [msg, setMsg] = useState('');
	const [pending, setPending] = useState<StPending | null>(null);
	const [cfg, setCfg] = useState<StConfig | null>(null);
	const [dataSync, setDataSync] = useState<DataSyncStatus | null>(null);
	const [sysInfo, setSysInfo] = useState<StSystemInfo | null>(null);
	const [autoStart, setAutoStart] = useState(true);

	const [logOpen, setLogOpen] = useState(false);
	const [logLines, setLogLines] = useState<StLogLine[]>([]);
	const [errOpen, setErrOpen] = useState(false);
	const [errs, setErrs] = useState<StError[]>([]);
	const [rawOpen, setRawOpen] = useState(false);
	const [rawText, setRawText] = useState('');

	const [deviceDlg, setDeviceDlg] = useState<{ initial: StDeviceConfig | null } | null>(null);
	const [folderDlg, setFolderDlg] = useState<{ initial: StFolderConfig | null } | null>(null);
	const [acceptDlg, setAcceptDlg] = useState<{ id: string; from: string; offeredBy: string } | null>(null);

	const timer = useRef<number | null>(null);

	const flash = (t: string) => { setMsg(t); window.setTimeout(() => setMsg(''), 5000); };

	const refresh = useCallback(async () => {
		try {
			const s = await window.planability.syncStatus();
			setSum(s);
			if (s.running) {
				const [p, d, info] = await Promise.all([
					window.planability.syncPendingGet().catch(() => null),
					window.planability.syncDataGet().catch(() => null),
					window.planability.syncSystemInfo().catch(() => null)
				]);
				if (p) { setPending(p); }
				if (d) { setDataSync(d); }
				if (info) { setSysInfo(info); }
			}
		} catch (e) { flash(`状态读取失败：${String(e)}`); }
	}, []);

	useEffect(() => { void refresh(); }, [refresh]);

	useEffect(() => {
		window.planability.configGet().then(c => setAutoStart(c.syncAutoStart !== false)).catch(() => undefined);
	}, []);

	// 4s polling while the kernel is up (phone LaunchedEffect parity)
	useEffect(() => {
		if (timer.current) { window.clearInterval(timer.current); timer.current = null; }
		if (sum?.running) {
			timer.current = window.setInterval(() => { void refresh(); }, 4000);
		}
		return () => { if (timer.current) { window.clearInterval(timer.current); timer.current = null; } };
	}, [sum?.running, refresh]);

	const run = async (label: string, fn: () => Promise<unknown>, done?: string) => {
		setBusy(label);
		try {
			await fn();
			if (done) { flash(done); }
		} catch (e) {
			flash(`${label}失败：${e instanceof Error ? e.message : String(e)}`);
		} finally {
			setBusy(null);
			await refresh();
		}
	};

	const toggleAutoStart = async () => {
		const next = !autoStart;
		setAutoStart(next);
		try { await window.planability.configSet({ syncAutoStart: next }); } catch { /* keep UI state */ }
	};

	const pickBinary = async () => {
		try {
			const p = await window.planability.syncPickBinary();
			if (p) { flash(`已指定内核：${p}`); await refresh(); }
		} catch (e) { flash(`选择失败：${String(e)}`); }
	};

	const copyId = async () => {
		if (!sum?.myId) { return; }
		try { await navigator.clipboard.writeText(sum.myId); flash('设备 ID 已复制'); } catch { flash('复制失败，请手动选择文本'); }
	};

	/**
	 * 完整模式 = 打开 Syncthing 原版 GUI。
	 *
	 * 不是 `window.open`：原版 GUI 的 REST 调用要 X-API-Key，而这个 key 只在主进程里
	 * （页面里没有、preload 也不下放）。所以这里只发一条 IPC，由主进程开窗并注入请求头。
	 */
	const openGui = async () => {
		try {
			const url = await window.planability.syncOpenGui();
			flash(`已在「完整模式」窗口打开 ${url}`);
		} catch (e) {
			flash(`完整模式打不开：${e instanceof Error ? e.message : String(e)}`);
		}
	};

	const loadCfg = async (): Promise<StConfig> => {
		const c = await window.planability.syncConfigGet();
		setCfg(c);
		return c;
	};

	// ---- devices ----
	const openAddDevice = () => setDeviceDlg({ initial: null });
	const openEditDevice = async (id: string) => {
		try {
			const c = await loadCfg();
			const d = c.devices?.find(x => x.deviceID === id);
			if (d) { setDeviceDlg({ initial: d }); } else { flash('未在配置中找到该设备'); }
		} catch (e) { flash(`读取配置失败：${String(e)}`); }
	};
	const toggleDevicePaused = (id: string) => run('切换暂停', async () => {
		const c = await loadCfg();
		const d = c.devices?.find(x => x.deviceID === id);
		if (!d) { throw new Error('设备不存在'); }
		await window.planability.syncPutDevice({ ...d, paused: !d.paused });
	}, '已更新');
	const deleteDevice = (id: string, name: string) => {
		if (!window.confirm(`删除设备「${name}」？（该设备将无法再连接，共享关系一并移除）`)) { return; }
		void run('删除设备', () => window.planability.syncDeleteDevice(id), '设备已删除');
	};

	// ---- folders ----
	const openAddFolder = () => setFolderDlg({ initial: null });
	const openEditFolder = async (id: string) => {
		try {
			const c = await loadCfg();
			const f = c.folders?.find(x => x.id === id);
			if (f) { setFolderDlg({ initial: f }); } else { flash('未在配置中找到该文件夹'); }
		} catch (e) { flash(`读取配置失败：${String(e)}`); }
	};
	const toggleFolderPaused = (id: string) => run('切换暂停', async () => {
		const c = await loadCfg();
		const f = c.folders?.find(x => x.id === id);
		if (!f) { throw new Error('文件夹不存在'); }
		await window.planability.syncPutFolder({ ...f, paused: !f.paused });
	}, '已更新');
	const deleteFolder = (id: string, label: string) => {
		if (!window.confirm(`移除文件夹「${label}」？（本机文件保留，只是不再同步）`)) { return; }
		void run('移除', () => window.planability.syncRemoveFolder(id), '已移除');
	};
	const scanFolder = (id: string) => run('重新扫描', () => window.planability.syncScan(id), '已触发扫描');

	// ---- pending offers ----
	const pendingFolderEntries = Object.entries(pending?.folders ?? {});
	const pendingDeviceEntries = Object.entries(pending?.devices ?? {});
	const deviceNameOf = (id: string): string =>
		cfg?.devices?.find(d => d.deviceID === id)?.name || (sum?.devices.find(d => d.id === id)?.name) || id.slice(0, 8);

	const declineFolder = (folderId: string, offerers: string[]) => run('拒绝', async () => {
		for (const from of offerers) { await window.planability.syncDecline({ folder: folderId, from }); }
		if (!offerers.length) { await window.planability.syncDecline({ folder: folderId }); }
	}, '已拒绝该共享');

	const acceptFolder = async (folderId: string, offerers: string[], path: string) => {
		const c = await loadCfg();
		const ids = new Set<string>([sum?.myId ?? '', ...offerers]);
		const existing = c.folders?.find(f => f.id === folderId);
		await window.planability.syncPutFolder({
			id: folderId,
			label: folderId,
			type: 'sendreceive',
			rescanIntervalS: 60,
			fsWatcherEnabled: true,
			...(existing ?? {}),
			path,
			devices: [...ids].filter(Boolean).map(deviceID => ({ deviceID }))
		});
		setAcceptDlg(null);
		flash('已接受，开始同步');
		await refresh();
	};

	const acceptPendingDevice = (id: string, name: string) => run('接受设备', () =>
		window.planability.syncPutDevice({ deviceID: id, name, addresses: ['dynamic'], compression: 'metadata', introducer: false, paused: false }),
		'已接受，等待对方确认');
	const declinePendingDevice = (id: string) => run('拒绝', () => window.planability.syncDecline({ device: id }), '已拒绝');

	// ---- system ----
	const restart = async () => {
		if (!window.confirm('重启 Syncthing 内核？连接会短暂中断，随后自动恢复。')) { return; }
		setBusy('重启');
		try {
			await window.planability.syncRestart();
			for (let i = 0; i < 40; i++) {
				await new Promise(r => setTimeout(r, 1500));
				const s = await window.planability.syncStatus();
				setSum(s);
				if (s.running) { flash('内核已重启'); break; }
				if (i === 39) { flash('重启后 60 秒仍未恢复，请查看系统日志'); }
			}
		} catch (e) { flash(`重启失败：${e instanceof Error ? e.message : String(e)}`); }
		finally { setBusy(null); await refresh(); }
	};

	const toggleLog = async () => {
		if (logOpen) { setLogOpen(false); return; }
		try { setLogLines(await window.planability.syncSystemLog(300)); } catch { setLogLines([]); }
		setLogOpen(true);
	};
	const toggleErrors = async () => {
		if (errOpen) { setErrOpen(false); return; }
		try { setErrs(await window.planability.syncErrorsGet()); } catch { setErrs([]); }
		setErrOpen(true);
	};
	const clearErrors = () => run('清空错误', async () => {
		await window.planability.syncErrorsClear();
		setErrs([]);
	}, '错误列表已清空');

	const toggleRaw = async () => {
		if (rawOpen) { setRawOpen(false); return; }
		try {
			const c = await loadCfg();
			setRawText(JSON.stringify(c, null, 2));
			setRawOpen(true);
		} catch (e) { flash(`读取配置失败：${String(e)}`); }
	};
	const saveRaw = async () => {
		try {
			const parsed = JSON.parse(rawText) as StConfig;
			await window.planability.syncConfigPut(parsed);
			flash('完整配置已保存并生效');
			await loadCfg();
		} catch (e) { flash(`保存失败：${e instanceof Error ? e.message : String(e)}`); }
	};

	// ---- data sync ----
	const dataToggle = () => run('数据同步', async () => {
		const next = !(dataSync?.enabled);
		setDataSync(await window.planability.syncDataSet(next));
	}, dataSync?.enabled ? '数据同步已关闭' : '数据同步已开启（文件夹已创建并共享给全部配对设备）');
	const dataRun = (kind: 'export' | 'import') => run(kind === 'export' ? '导出' : '导入', async () => {
		setDataSync(await window.planability.syncDataRun(kind));
	}, kind === 'export' ? '已导出到同步文件夹' : (dataSync?.lastImportCount ? `已合并 ${dataSync.lastImportCount} 条` : '没有新的手机数据'));

	const running = !!sum?.running;
	const attached = sum?.mode === 'attached';
	const owned = sum?.mode === 'own';
	const modeLabel = owned ? '随应用运行' : attached ? '已接入本机现有实例' : '';
	const hasPending = pendingFolderEntries.length > 0 || pendingDeviceEntries.length > 0;

	const fmtUptime = (s: number): string => {
		const h = Math.floor(s / 3600);
		const m = Math.floor((s % 3600) / 60);
		return h > 0 ? `${h} 小时 ${m} 分` : `${m} 分`;
	};
	const fmtWhen = (t: number): string => (t ? new Date(t).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—');

	return (
		<>
			<PageHeader
				title="同步中心 · Syncthing"
				action={
					<div style={{ display: 'flex', gap: 8 }}>
						<TextButtonM3
							disabled={!running}
							title={running ? '打开 Syncthing 原版界面（发现/中继/GUI TLS/文件夹高级项都在那里）' : '内核没启动，完整模式连的是同一个内核'}
							onClick={() => void openGui()}
						>
							<ExternalLink size={14} /> 完整模式
						</TextButtonM3>
						<TextButtonM3 onClick={() => void refresh()}><RefreshCw size={14} /> 刷新</TextButtonM3>
						<TextButtonM3 onClick={() => nav('files')}><ArrowLeft size={14} /> 返回文件</TextButtonM3>
					</div>
				}
			/>

			{/* ---------- 引擎 ---------- */}
			<div className="card" style={{ display: 'block' }}>
				<div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
					<span style={{
						width: 10, height: 10, borderRadius: '50%', flex: 'none',
						background: running ? 'var(--primary)' : 'var(--outline-dim)'
					}} />
					<div className="card-main">
						<div className="card-title">
							{running
								? `内核运行中 · ${sum?.version || sysInfo?.version || 'Syncthing'}${modeLabel ? ` · ${modeLabel}` : ''}`
								: '内核未启动'}
						</div>
						{attached ? (
							<div className="card-meta">检测到独立版 Syncthing 正在运行，本页暂借用它的接口（不会停止它）。删除独立版后本页自动改用内置内核——数据无需迁移，重新配对设备即可。</div>
						) : sum?.binaryPath ? (
							<div className="card-meta" style={{ fontFamily: 'monospace', wordBreak: 'break-all' }}>{sum.binaryPath}</div>
						) : !running ? (
							<div className="card-meta">还没有内核——点右侧启动会自动下载（约 11MB，仅一次）</div>
						) : null}
					</div>
					{owned ? (
						<TextButtonM3 className="m3-text-danger" disabled={busy !== null} onClick={() => void run('停止', () => window.planability.syncStop())}>
							<Square size={13} /> 停止
						</TextButtonM3>
					) : running ? (
						<span className="card-meta">外部实例</span>
					) : (
						<FilledButtonM3 disabled={busy !== null} onClick={() => void run('启动', () => window.planability.syncStart())}>
							<Play size={13} /> {busy === '启动'
								? (sum?.binaryPath ? '启动中…' : '下载并启动中…')
								: (sum?.binaryPath ? '启动' : '下载内核并启动')}
						</FilledButtonM3>
					)}
				</div>

				{!running && (
					<div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
						<TextButtonM3 onClick={pickBinary}><HardDrive size={13} /> 指定内核路径</TextButtonM3>
					</div>
				)}

				<div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 12 }}>
					<span className="card-meta">随 Planability 启动（有内核即自动拉起，取代独立版 Syncthing）</span>
					<FilterChipM3 selected={autoStart} onClick={() => void toggleAutoStart()}>
						{autoStart ? '已开启' : '已关闭'}
					</FilterChipM3>
				</div>

				{owned && running && (
					<div className="card-meta" style={{ marginTop: 8 }}>
						首次启动若弹出 Windows 防火墙询问请选择「允许」（否则会走中继，速度较慢）。
					</div>
				)}

				{running && sum?.myId && (
					<div style={{ marginTop: 12 }}>
						<div className="card-meta">本机设备 ID —— 在手机端 Syncthing 的「添加设备」里粘贴它</div>
						<div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 6 }}>
							<code style={{
								flex: 1, fontFamily: 'monospace', fontSize: 12, wordBreak: 'break-all',
								background: 'var(--surface)', border: '1px solid var(--outline-dim)',
								borderRadius: 'var(--shape-sm)', padding: '7px 10px'
							}}>{sum.myId}</code>
							<TextButtonM3 onClick={() => void copyId()}><Copy size={13} /></TextButtonM3>
						</div>
					</div>
				)}

				{busy === '启动' && running === false && <div className="card-meta" style={{ marginTop: 10 }}>内核准备中（下载/启动），等待设备 ID…</div>}
			</div>

			{!running && (
				<p style={{ color: 'var(--text-dim)', fontSize: 12.5 }}>
					启动后可：查看本机设备 ID、与手机配对、添加双向同步文件夹并查看实时进度。内核由 Planability 自己托管（配置与索引存应用目录），
					「随 Planability 启动」开启时开机即自动运行；本机若还装着独立版 Syncthing，会先借用它——删除独立版后自动切换。
				</p>
			)}

			{msg && <div className="home-empty" style={{ textAlign: 'left', padding: '6px 0' }}>{msg}</div>}

			{running && sum && (
				<>
					{/* ---------- 待接受 ---------- */}
					{hasPending && (
						<>
							<div className="home-row-head" style={{ marginTop: 14 }}>
								<span><TriangleAlert size={14} /> 待接受</span>
							</div>
							{pendingFolderEntries.map(([fid, info]) => {
								const offerers = Object.keys(info.offeredBy ?? {});
								const names = offerers.map(deviceNameOf).join('、') || '未知设备';
								return (
									<div className="card" key={`pf-${fid}`} style={{ display: 'block' }}>
										<div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
											<div className="card-main">
												<div className="card-title">{fid}</div>
												<div className="card-meta">{names} 共享给本机</div>
											</div>
											<TextButtonM3 onClick={() => setAcceptDlg({ id: fid, from: offerers[0] ?? '', offeredBy: names })}>接受</TextButtonM3>
											<TextButtonM3 className="m3-text-danger" onClick={() => void declineFolder(fid, offerers)}>拒绝</TextButtonM3>
										</div>
									</div>
								);
							})}
							{pendingDeviceEntries.map(([key, info]) => {
								const id = info.deviceID ?? key;
								const name = info.name || id.slice(0, 8);
								return (
									<div className="card" key={`pd-${id}`} style={{ display: 'block' }}>
										<div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
											<div className="card-main">
												<div className="card-title">{name}</div>
												<div className="card-meta" style={{ fontFamily: 'monospace' }}>{id}</div>
											</div>
											<TextButtonM3 onClick={() => void acceptPendingDevice(id, name)}>接受</TextButtonM3>
											<TextButtonM3 className="m3-text-danger" onClick={() => void declinePendingDevice(id)}>拒绝</TextButtonM3>
										</div>
									</div>
								);
							})}
						</>
					)}

					{/* ---------- 设备 ---------- */}
					<div className="home-row-head" style={{ marginTop: 14 }}>
						<span><MonitorSmartphone size={14} /> 设备</span>
						<TextButtonM3 onClick={openAddDevice}>
							<Plus size={12} /> 添加设备
						</TextButtonM3>
					</div>
					{sum.devices.length === 0 && <Empty text="还没有配对设备" />}
					{sum.devices.map(d => (
						<div className="card" key={d.id}>
							<span style={{
								width: 8, height: 8, borderRadius: '50%', flex: 'none',
								background: d.connected ? 'var(--primary)' : 'var(--outline-dim)'
							}} />
							<div className="card-main">
								<div className="card-title">{d.name}</div>
								<div className="card-meta" style={{ fontFamily: 'monospace' }}>{d.id}</div>
							</div>
							<span className="card-meta">{d.id === sum.myId ? '本机' : d.connected ? '已连接' : '未连接'}</span>
							<TextButtonM3 title="编辑" onClick={() => void openEditDevice(d.id)}><Pencil size={13} /></TextButtonM3>
							<TextButtonM3 title="暂停/恢复" onClick={() => void toggleDevicePaused(d.id)}>
								<Pause size={13} />
							</TextButtonM3>
							<TextButtonM3 className="m3-text-danger" title="删除" onClick={() => deleteDevice(d.id, d.name)}>
								<Trash2 size={13} />
							</TextButtonM3>
						</div>
					))}

					{/* ---------- 文件夹 ---------- */}
					<div className="home-row-head" style={{ marginTop: 14 }}>
						<span><FolderSync size={14} /> 同步文件夹</span>
						<TextButtonM3 onClick={openAddFolder}>
							<Plus size={12} /> 添加文件夹
						</TextButtonM3>
					</div>
					{sum.folders.length === 0 && <Empty text="暂无同步文件夹" />}
					{sum.folders.map(f => (
						<div className="card" key={f.id} style={{ display: 'block' }}>
							<div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
								<div className="card-main">
									<div className="card-title">{f.label}</div>
									<div className="card-meta" style={{ fontFamily: 'monospace', wordBreak: 'break-all' }}>{f.path}</div>
								</div>
								<span className="card-meta" style={{ color: 'var(--primary)' }}>{f.state}</span>
								<TextButtonM3 title="编辑" onClick={() => void openEditFolder(f.id)}><Pencil size={13} /></TextButtonM3>
								<TextButtonM3 title="立即扫描" onClick={() => void scanFolder(f.id)}><ScanLine size={13} /></TextButtonM3>
								<TextButtonM3 title="暂停/恢复" onClick={() => void toggleFolderPaused(f.id)}><Pause size={13} /></TextButtonM3>
								<TextButtonM3 className="m3-text-danger" title="移除" onClick={() => deleteFolder(f.id, f.label)}>
									<Trash2 size={13} />
								</TextButtonM3>
							</div>
							<div style={{ height: 6, borderRadius: 3, background: 'var(--surface-3)', marginTop: 10, overflow: 'hidden' }}>
								<div style={{ height: '100%', width: `${Math.round(f.progress * 100)}%`, background: 'var(--primary)', borderRadius: 3, transition: 'width .4s' }} />
							</div>
						</div>
					))}

					{/* ---------- Planability 数据同步 ---------- */}
					<div className="home-row-head" style={{ marginTop: 14 }}>
						<span><DatabaseZap size={14} /> Planability 数据同步</span>
					</div>
					<div className="card" style={{ display: 'block' }}>
						<div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
							<span style={{
								width: 8, height: 8, borderRadius: '50%', flex: 'none',
								background: dataSync?.enabled ? 'var(--primary)' : 'var(--outline-dim)'
							}} />
							<div className="card-main">
								<div className="card-title">{dataSync?.enabled ? '已开启' : '未开启'}</div>
								<div className="card-meta" style={{ wordBreak: 'break-all' }}>
									{dataSync ? `${dataSync.folderId} · ${dataSync.dir}` : '读取中…'}
								</div>
							</div>
							<FilterChipM3
								selected={dataSync?.enabled ?? false}
								disabled={busy !== null}
								onClick={() => void dataToggle()}
							>{dataSync?.enabled ? '关闭' : '开启'}</FilterChipM3>
						</div>
						<div className="card-meta" style={{ marginTop: 10 }}>
							上次导出 {fmtWhen(dataSync?.lastExportAt ?? 0)} · 上次导入 {fmtWhen(dataSync?.lastImportAt ?? 0)}
							{(dataSync?.lastImportCount ?? 0) > 0 && `（最近一次合并 ${dataSync?.lastImportCount} 条）`}
						</div>
						<div className="card-meta" style={{ marginTop: 6 }}>
							机制：本机数据自动写成 pc-dump.json，手机写入 phone-dump.json，两边经 Syncthing 互传后**按记录新者胜合并**（与「文件 → 导入」同一套规则）。
							{dataSync && !dataSync.shared && ' 开启后会自动创建并共享该文件夹。'}
						</div>
						{dataSync?.error && <div className="card-meta" style={{ color: 'var(--danger)', marginTop: 6 }}>{dataSync.error}</div>}
						<div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
							<TextButtonM3 disabled={!dataSync?.enabled || busy !== null} onClick={() => void dataRun('export')}>
								<DatabaseZap size={13} /> 立即导出
							</TextButtonM3>
							<TextButtonM3 disabled={!dataSync?.enabled || busy !== null} onClick={() => void dataRun('import')}>
								<Download size={13} /> 立即导入
							</TextButtonM3>
						</div>
					</div>

					{/* ---------- 系统 ---------- */}
					<div className="home-row-head" style={{ marginTop: 14 }}>
						<span><Settings2 size={14} /> 系统</span>
						<TextButtonM3 onClick={() => void toggleRaw()}>
							<Braces size={12} /> {rawOpen ? '收起完整配置' : '完整配置'}
						</TextButtonM3>
					</div>
					<div className="card" style={{ display: 'block' }}>
						<div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
							<div className="card-main">
								<div className="card-title">Syncthing {sysInfo?.version || sum.version || ''}</div>
								<div className="card-meta">
									{sysInfo ? `已运行 ${fmtUptime(sysInfo.uptime)} · 启动于 ${sysInfo.startTime || '—'}` : '读取系统状态…'}
								</div>
							</div>
							<TextButtonM3 disabled={busy !== null} onClick={() => void restart()}>
								<RotateCcw size={13} /> 重启内核
							</TextButtonM3>
							<TextButtonM3 onClick={() => void toggleErrors()}>
								<TriangleAlert size={13} /> 错误{errs.length > 0 ? ` (${errs.length})` : ''}
							</TextButtonM3>
						</div>

						{errOpen && (
							<div style={{ marginTop: 10, borderTop: '1px dashed var(--outline-dim)', paddingTop: 8 }}>
								{errs.length === 0
									? <div className="card-meta">没有错误</div>
									: errs.map((e, i) => (
										<div className="card-meta" key={`${i}-${e.when}`} style={{ color: 'var(--danger)' }}>
											{e.when} · {e.device || '—'} · {e.message}
										</div>
									))}
								{errs.length > 0 && (
									<TextButtonM3 style={{ marginTop: 6 }} onClick={() => void clearErrors()}>清空错误列表</TextButtonM3>
								)}
							</div>
						)}

						{rawOpen && (
							<div style={{ marginTop: 10, borderTop: '1px dashed var(--outline-dim)', paddingTop: 10 }}>
								<textarea
									value={rawText}
									onChange={e => setRawText(e.target.value)}
									style={{
										width: '100%', minHeight: 260, background: 'var(--surface-2)', color: 'var(--text)',
										border: '1px solid var(--outline-dim)', borderRadius: 'var(--shape-sm)',
										fontFamily: 'monospace', fontSize: 12, padding: '8px 10px', resize: 'vertical'
									}}
								/>
								<div className="form-actions">
									<TextButtonM3 onClick={() => void toggleRaw()}>取消</TextButtonM3>
									<FilledButtonM3 onClick={() => void saveRaw()}>保存完整配置</FilledButtonM3>
								</div>
								<div className="card-meta">完整配置 = 内核的全部设置（发现/中继/GUI/文件夹高级项…），改错可删除本段文字重开恢复为当前生效值。</div>
							</div>
						)}
					</div>

					<p style={{ color: 'var(--text-dim)', fontSize: 12, marginTop: 12 }}>
						用法：手机端装好 Planability → 同步中心启动内核 → 把手机的设备 ID「添加设备」粘贴到这里（或把这里的 ID 粘到手机）→ 双方接受 →
						「添加文件夹」填本机绝对路径并共享给对方 → 手机端接受。之后两边文件自动双向同步。
					</p>

					{/* ---------- 系统日志 ---------- */}
					<div className="home-row-head" style={{ marginTop: 6 }}>
						<span><Terminal size={14} /> 系统日志</span>
						<TextButtonM3 onClick={() => void toggleLog()}>{logOpen ? '收起' : '查看'}</TextButtonM3>
					</div>
					{logOpen && (
						<pre style={{
							background: 'var(--surface-2)', border: '1px solid var(--outline-dim)',
							borderRadius: 'var(--shape-sm)', padding: '10px 12px', fontSize: 12,
							maxHeight: 280, overflow: 'auto', whiteSpace: 'pre-wrap'
						}}>
							{logLines.length === 0
								? '（日志为空）'
								: logLines.map((l, i) => `${(l.when ?? '').slice(11, 19)} [${l.level ?? 'INF'}] ${l.message ?? ''}`).join('\n')}
						</pre>
					)}
				</>
			)}

			{/* ---------- dialogs ---------- */}
			{deviceDlg && (
				<DeviceDialog
					initial={deviceDlg.initial}
					onClose={() => setDeviceDlg(null)}
					onSaved={async () => { await refresh(); await loadCfg().catch(() => undefined); flash('设备已保存'); }}
				/>
			)}
			{folderDlg && sum?.myId && (
				<FolderDialog
					initial={folderDlg.initial}
					selfId={sum.myId}
					devices={cfg?.devices ?? sum.devices.map(d => ({ deviceID: d.id, name: d.name }))}
					onClose={() => setFolderDlg(null)}
					onSaved={async () => { await refresh(); await loadCfg().catch(() => undefined); flash('文件夹已保存'); }}
				/>
			)}
			{acceptDlg && sum?.myId && (
				<AcceptFolderDialog
					folderId={acceptDlg.id}
					offeredBy={acceptDlg.offeredBy}
					defaultPath={`${(dataSync?.dir ?? '').replace(/[\\/]sync-data$/, '')}\\synced-${acceptDlg.id}`}
					onClose={() => setAcceptDlg(null)}
					onAccept={path => acceptFolder(acceptDlg.id, [acceptDlg.from].filter(Boolean), path)}
					onDecline={() => declineFolder(acceptDlg.id, [acceptDlg.from].filter(Boolean))}
				/>
			)}
		</>
	);
}
