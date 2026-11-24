import { useEffect, useState } from 'react';
import { ChevronUp, FolderSearch } from 'lucide-react';
import type { StDeviceConfig, StFolderConfig } from '@shared/ipc';
import { Field, Modal } from './ui';

/** Syncthing dialogs — full web-GUI parity inside the app (no browser). */

const DEVICE_ID_RE = /^[A-Za-z0-9]{7}(-[A-Za-z0-9]{7}){7}$/;
const FOLDER_ID_RE = /^[A-Za-z0-9._-]+$/;

function ErrorLine({ text }: { text: string }) {
	return <div style={{ color: 'var(--danger)', fontSize: 12.5, whiteSpace: 'pre-wrap' }}>{text}</div>;
}

function errText(e: unknown): string {
	return e instanceof Error ? e.message : String(e);
}

/** Browse box shared by path fields: server-side directory listing via /rest/system/browse. */
function PathField({ label, value, onChange }: { label: string; value: string; onChange: (v: string) => void }) {
	const [list, setList] = useState<string[] | null>(null);
	const [busy, setBusy] = useState(false);

	const browse = async (target: string) => {
		setBusy(true);
		try { setList(await window.planability.syncBrowse(target)); } catch (e) { setList([]); void e; }
		setBusy(false);
	};
	const up = () => {
		const next = value.replace(/[\\/][^\\/]+$/, '') || value;
		void browse(next);
		onChange(next);
	};

	return (
		<Field label={label}>
			<div style={{ display: 'flex', gap: 6 }}>
				<input value={value} onChange={e => onChange(e.target.value)} placeholder="绝对路径，如 C:\Users\...\synced" />
				<button type="button" className="btn-ghost" disabled={busy} onClick={() => void browse(value || 'C:\\')} title="浏览目录">
					<FolderSearch size={14} />
				</button>
			</div>
			{list && (
				<div style={{
					maxHeight: 150, overflowY: 'auto', marginTop: 6, borderRadius: 'var(--shape-sm)',
					border: '1px solid var(--outline-dim)', background: 'var(--surface-2)', padding: 4
				}}>
					<button type="button" className="nc-link" onClick={up} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
						<ChevronUp size={12} /> 上级目录
					</button>
					{list.length === 0 && <div style={{ fontSize: 12, color: 'var(--text-faint)', padding: '4px 6px' }}>（此目录下没有子目录）</div>}
					{list.map(p => (
						<button
							key={p}
							className="nc-link"
							style={{ display: 'block', textAlign: 'left', width: '100%', fontSize: 12, padding: '4px 6px' }}
							onClick={() => { onChange(p); void browse(p); }}
						>{p}</button>
					))}
				</div>
			)}
		</Field>
	);
}

/** Add / edit a device: name, addresses, compression, introducer, paused. */
export function DeviceDialog({ initial, onClose, onSaved }: {
	initial: StDeviceConfig | null; // null = add
	onClose: () => void;
	onSaved: () => void | Promise<void>;
}) {
	const isNew = !initial;
	const [base, setBase] = useState<StDeviceConfig | null>(initial ?? null);
	const [deviceID, setDeviceID] = useState(initial?.deviceID ?? '');
	const [name, setName] = useState(initial?.name ?? '');
	const [addresses, setAddresses] = useState((initial?.addresses ?? ['dynamic']).join(', '));
	const [compression, setCompression] = useState(initial?.compression ?? 'metadata');
	const [introducer, setIntroducer] = useState(!!initial?.introducer);
	const [paused, setPaused] = useState(!!initial?.paused);
	const [err, setErr] = useState('');
	const [saving, setSaving] = useState(false);

	useEffect(() => {
		if (initial) { setBase(initial); return; }
		window.planability.syncDefaults('device')
			.then(d => setBase(d as StDeviceConfig))
			.catch(e => setErr(`读取默认值失败：${errText(e)}`));
	}, [initial]);

	const save = async () => {
		setErr('');
		const id = deviceID.trim().toUpperCase();
		if (isNew && !DEVICE_ID_RE.test(id)) { setErr('设备 ID 格式应为 8 组、每组 7 位字母数字（如 XXXXXXX-XXXXXXX-…）'); return; }
		if (!base) { return; }
		setSaving(true);
		try {
			await window.planability.syncPutDevice({
				...base,
				deviceID: id,
				name: name.trim(),
				addresses: addresses.split(',').map(s => s.trim()).filter(Boolean),
				compression,
				introducer,
				paused
			});
			await onSaved();
			onClose();
		} catch (e) { setErr(errText(e)); } finally { setSaving(false); }
	};

	return (
		<Modal title={isNew ? '添加设备' : `编辑设备 · ${initial?.name || initial?.deviceID?.slice(0, 8)}`} onClose={onClose}>
			{isNew && (
				<Field label="设备 ID（对方同步中心显示的 63 位 ID）">
					<input value={deviceID} onChange={e => setDeviceID(e.target.value.toUpperCase())} placeholder="XXXXXXX-XXXXXXX-…" autoFocus />
				</Field>
			)}
			<Field label="名称">
				<input value={name} onChange={e => setName(e.target.value)} placeholder="如 我的手机" />
			</Field>
			<Field label="地址（逗号分隔，dynamic=自动发现）">
				<input value={addresses} onChange={e => setAddresses(e.target.value)} placeholder="dynamic" />
			</Field>
			<Field label="压缩">
				<select value={compression} onChange={e => setCompression(e.target.value)}>
					<option value="metadata">仅元数据（默认）</option>
					<option value="always">始终压缩</option>
					<option value="never">不压缩</option>
				</select>
			</Field>
			<label style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 13, color: 'var(--text-dim)' }}>
				<input type="checkbox" checked={introducer} onChange={e => setIntroducer(e.target.checked)} />
				引入设备（自动接受该设备引入的其它设备）
			</label>
			<label style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 13, color: 'var(--text-dim)' }}>
				<input type="checkbox" checked={paused} onChange={e => setPaused(e.target.checked)} />
				暂停该设备
			</label>
			{err && <ErrorLine text={err} />}
			<div className="form-actions">
				<button className="btn-ghost" onClick={onClose}>取消</button>
				<button className="btn" disabled={saving || !base} onClick={() => void save()}>{saving ? '保存中…' : '保存'}</button>
			</div>
		</Modal>
	);
}

const FOLDER_TYPES: { v: string; label: string }[] = [
	{ v: 'sendreceive', label: '双向同步（默认）' },
	{ v: 'sendonly', label: '仅发送（本机为准）' },
	{ v: 'receiveonly', label: '仅接收（对方为准）' }
];

/** Add / edit a folder: path (with server-side browse), type, scan/watch, sharing,
 *  ignore patterns, versioning — everything the web GUI exposes, in-app. */
export function FolderDialog({ initial, selfId, devices, onClose, onSaved }: {
	initial: StFolderConfig | null; // null = add
	selfId: string;
	/** all config devices (for the sharing checkboxes) */
	devices: StDeviceConfig[];
	onClose: () => void;
	onSaved: () => void | Promise<void>;
}) {
	const isNew = !initial;
	const [base, setBase] = useState<StFolderConfig | null>(initial ?? null);
	const [folderId, setFolderId] = useState(initial?.id ?? '');
	const [label, setLabel] = useState(initial?.label ?? '');
	const [path, setPath] = useState(initial?.path ?? '');
	const [type, setType] = useState(initial?.type ?? 'sendreceive');
	const [paused, setPaused] = useState(!!initial?.paused);
	const [rescanMin, setRescanMin] = useState(String(Math.round((initial?.rescanIntervalS ?? 3600) / 60)));
	const [watcher, setWatcher] = useState(initial?.fsWatcherEnabled !== false);
	const [shared, setShared] = useState<Set<string>>(
		() => new Set((initial?.devices ?? []).map(d => d.deviceID).filter(id => id && id !== selfId))
	);
	const [verType, setVerType] = useState(initial?.versioning?.type && initial.versioning.type !== 'none' ? initial.versioning.type : 'none');
	const [verKeep, setVerKeep] = useState(initial?.versioning?.params?.keep ?? '5');
	const [verPath, setVerPath] = useState(initial?.versioning?.params?.path ?? '');
	const [verCmd, setVerCmd] = useState(initial?.versioning?.params?.cmd ?? '');
	const [ignores, setIgnores] = useState<string[] | null>(null);
	const [ignoresDirty, setIgnoresDirty] = useState(false);
	const [err, setErr] = useState('');
	const [saving, setSaving] = useState(false);

	useEffect(() => {
		if (initial) { setBase(initial); } else {
			window.planability.syncDefaults('folder')
				.then(f => setBase(f as StFolderConfig))
				.catch(e => setErr(`读取默认值失败：${errText(e)}`));
		}
		const loadIgnores = async (id: string) => {
			try { setIgnores(await window.planability.syncIgnoresGet(id)); } catch { setIgnores([]); }
		};
		if (initial?.id) { void loadIgnores(initial.id); } else { setIgnores([]); }
	}, [initial]);

	const toggleShare = (id: string) => {
		const next = new Set(shared);
		if (next.has(id)) { next.delete(id); } else { next.add(id); }
		setShared(next);
	};

	const save = async () => {
		setErr('');
		const id = folderId.trim();
		if (isNew && !FOLDER_ID_RE.test(id)) { setErr('文件夹 ID 只能用字母、数字、点、下划线和横线'); return; }
		if (!path.trim()) { setErr('请填写本机路径'); return; }
		if (!base) { return; }
		setSaving(true);
		try {
			const deviceList = [{ deviceID: selfId }, ...[...shared].filter(x => x !== selfId).map(x => ({ deviceID: x }))];
			const cfg: StFolderConfig = {
				...base,
				id,
				label: label.trim() || id,
				path: path.trim(),
				type,
				paused,
				rescanIntervalS: Math.max(0, Math.round(Number(rescanMin) || 0)) * 60,
				fsWatcherEnabled: watcher,
				devices: deviceList
			};
			if (verType && verType !== 'none') {
				const params: Record<string, string> = {};
				if (verType === 'external') {
					if (verPath.trim()) { params.path = verPath.trim(); }
					if (verCmd.trim()) { params.cmd = verCmd.trim(); }
				}
				if (verKeep.trim()) { params.keep = verKeep.trim(); }
				cfg.versioning = { type: verType, params };
			}
			await window.planability.syncPutFolder(cfg);
			if (ignoresDirty && ignores) { await window.planability.syncIgnoresPut(id, ignores); }
			await onSaved();
			onClose();
		} catch (e) { setErr(errText(e)); } finally { setSaving(false); }
	};

	return (
		<Modal title={isNew ? '添加同步文件夹' : `编辑文件夹 · ${initial?.label || initial?.id}`} onClose={onClose}>
			{isNew && (
				<Field label="文件夹 ID（同 id 才能对端匹配，建议 plan-时间戳）">
					<input value={folderId} onChange={e => setFolderId(e.target.value)} placeholder={`plan-${Math.floor(Date.now() / 1000)}`} autoFocus />
				</Field>
			)}
			<Field label="名称">
				<input value={label} onChange={e => setLabel(e.target.value)} placeholder="如 大学文件" />
			</Field>
			<PathField label="本机路径" value={path} onChange={setPath} />
			<Field label="同步类型">
				<select value={type} onChange={e => setType(e.target.value)}>
					{FOLDER_TYPES.map(t => <option key={t.v} value={t.v}>{t.label}</option>)}
				</select>
			</Field>
			<div style={{ display: 'flex', gap: 10 }}>
				<Field label="自动扫描间隔（分钟，0=关闭）">
					<input type="number" min={0} value={rescanMin} onChange={e => setRescanMin(e.target.value)} />
				</Field>
				<label style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 13, color: 'var(--text-dim)', paddingTop: 22 }}>
					<input type="checkbox" checked={watcher} onChange={e => setWatcher(e.target.checked)} />
					实时监控变更
				</label>
			</div>
			<label style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 13, color: 'var(--text-dim)' }}>
				<input type="checkbox" checked={paused} onChange={e => setPaused(e.target.checked)} />
				暂停该文件夹
			</label>

			<div className="field-label">共享给设备（本机始终包含）</div>
			<div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
				{devices.filter(d => d.deviceID && d.deviceID !== selfId).length === 0 && (
					<div style={{ fontSize: 12.5, color: 'var(--text-faint)' }}>还没有配对设备，先在「设备」区添加</div>
				)}
				{devices.filter(d => d.deviceID && d.deviceID !== selfId).map(d => (
					<label key={d.deviceID} style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 13, color: 'var(--text-dim)' }}>
						<input
							type="checkbox"
							checked={shared.has(d.deviceID)}
							onChange={() => toggleShare(d.deviceID)}
						/>
						{d.name || d.deviceID.slice(0, 8)}
						<span style={{ color: 'var(--text-faint)', fontFamily: 'monospace', fontSize: 11 }}>{d.deviceID.slice(0, 8)}</span>
					</label>
				))}
			</div>

			<Field label="忽略模式（每行一条，如 *.tmp、!重要/）">
				<textarea
					value={(ignores ?? []).join('\n')}
					placeholder="留空=不忽略"
					onChange={e => { setIgnores(e.target.value.split('\n')); setIgnoresDirty(true); }}
					style={{ minHeight: 90 }}
				/>
			</Field>

			<Field label="版本控制（被覆盖/删除的文件保留历史）">
				<select value={verType} onChange={e => setVerType(e.target.value)}>
					<option value="none">无</option>
					<option value="simple">简单（按份数）</option>
					<option value="trash">回收站（按份数）</option>
					<option value="external">外部命令</option>
				</select>
			</Field>
			{verType !== 'none' && verType !== 'external' && (
				<Field label="保留份数">
					<input type="number" min={1} value={verKeep} onChange={e => setVerKeep(e.target.value)} />
				</Field>
			)}
			{verType === 'external' && (
				<div style={{ display: 'flex', gap: 10 }}>
					<Field label="外部路径">
						<input value={verPath} onChange={e => setVerPath(e.target.value)} placeholder="D:\backup" />
					</Field>
					<Field label="保留份数">
						<input type="number" min={1} value={verKeep} onChange={e => setVerKeep(e.target.value)} />
					</Field>
				</div>
			)}

			{err && <ErrorLine text={err} />}
			<div className="form-actions">
				<button className="btn-ghost" onClick={onClose}>取消</button>
				<button className="btn" disabled={saving || !base} onClick={() => void save()}>{saving ? '保存中…' : '保存'}</button>
			</div>
		</Modal>
	);
}

/** Incoming folder offer: pick where to put it, or decline. */
export function AcceptFolderDialog({ folderId, offeredBy, defaultPath, onClose, onAccept, onDecline }: {
	folderId: string;
	offeredBy: string;
	defaultPath: string;
	onClose: () => void;
	onAccept: (path: string) => Promise<void>;
	onDecline: () => Promise<void>;
}) {
	const [path, setPath] = useState(defaultPath);
	const [busy, setBusy] = useState(false);
	const [err, setErr] = useState('');

	return (
		<Modal title={`接受共享文件夹 · ${folderId}`} onClose={onClose}>
			<p style={{ fontSize: 12.5, color: 'var(--text-dim)', margin: 0 }}>
				{offeredBy} 想把文件夹「{folderId}」共享给你。选择本机保存位置后接受，两边开始双向同步；拒绝则不接收。
			</p>
			<PathField label="本机路径" value={path} onChange={setPath} />
			{err && <ErrorLine text={err} />}
			<div className="form-actions">
				<button className="btn-ghost btn-danger" disabled={busy} onClick={() => {
					setBusy(true);
					onDecline().catch(e => setErr(errText(e))).finally(() => setBusy(false));
				}}>拒绝</button>
				<button className="btn-ghost" onClick={onClose}>取消</button>
				<button className="btn" disabled={busy || !path.trim()} onClick={() => {
					setBusy(true);
					onAccept(path.trim()).catch(e => setErr(errText(e))).finally(() => setBusy(false));
				}}>{busy ? '处理中…' : '接受'}</button>
			</div>
		</Modal>
	);
}
