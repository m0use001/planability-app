import { useEffect, useState } from 'react';
import type { AppInfo, DesktopConfig } from '@shared/ipc';
import { Field, PageHeader } from '../components/ui';
import { FilledButtonM3, TextButtonM3 } from '../components/m3/buttons';
import { Segmented } from '../components/m3/chrome';
import { applyTheme, getTheme, type ThemeMode } from '../styles/theme';

/** 设置四节的共享状态（窄屏整页与宽屏右板 Detail 共用——一张表单不许两处各写一套）。 */
export interface SettingsCtx {
	info: AppInfo | null;
	cfg: DesktopConfig;
	setCfg: (c: DesktopConfig) => void;
	saved: boolean;
	setSaved: (v: boolean) => void;
	save: () => Promise<void>;
	clearRoot: () => Promise<void>;
	theme: ThemeMode;
	setTheme: (t: ThemeMode) => void;
}

/** 我的页宽屏分组列表（spec §5：设置分组列表 + 右板详情；门槛 820 见 lib/board WIDE.profileTwoPaneMinWidth）。 */
export const SETTINGS_GROUPS: { key: string; label: string; meta: string }[] = [
	{ key: 'appearance', label: '外观', meta: '深色 / 浅色主题' },
	{ key: 'data', label: '数据与目录', meta: '版本 · 集合文件' },
	{ key: 'workspace', label: '工作区根目录', meta: '文件工具开关' },
	{ key: 'model', label: '模型（二期）', meta: '主槽配置' },
];

export function useSettingsCtx(): SettingsCtx {
	const [info, setInfo] = useState<AppInfo | null>(null);
	const [cfg, setCfg] = useState<DesktopConfig>({});
	const [saved, setSaved] = useState(false);
	const [theme, setTheme] = useState<ThemeMode>(getTheme());

	useEffect(() => {
		window.planability.appInfo().then(setInfo).catch(console.error);
		window.planability.configGet().then(setCfg).catch(console.error);
	}, []);

	const save = async () => {
		await window.planability.configSet(cfg);
		setSaved(true);
		setTimeout(() => setSaved(false), 1500);
	};

	// 清空也是立即写盘：文件域的开/关是安全状态，不该和别的设置一起攒着等「保存」。
	const clearRoot = async () => {
		// G 类 LOW：即改即存的按钮也该有一次确认——文件域整体停用是可见的状态切换，
		// 误触后用户会发现工具「突然全没了」。数据不动，但值得一次「确定」。
		if (!window.confirm('清空工作区根目录？文件工具将整体停用（数据不受影响，填回路径即恢复）。')) { return; }
		const next = { ...cfg, workspaceRoot: '' };
		setCfg(next);
		await window.planability.configSet(next);
		setSaved(true);
		setTimeout(() => setSaved(false), 1500);
	};

	return { info, cfg, setCfg, saved, setSaved, save, clearRoot, theme, setTheme };
}

/** 单节内容（group = SETTINGS_GROUPS[].key）。窄屏整页与宽屏右板共用。 */
export function SettingsSection({ group, ctx }: { group: string; ctx: SettingsCtx }) {
	const { info, cfg, setCfg, saved, setSaved, save, clearRoot, theme, setTheme } = ctx;
	const rootOn = !!cfg.workspaceRoot?.trim();
	if (group === 'appearance') {
		return (
			<section style={{ background: 'var(--surface-2)', borderRadius: 'var(--shape-lg)', padding: '18px 22px', marginBottom: 18 }}>
				<div style={{ fontSize: 13, fontWeight: 650, marginBottom: 8 }}>外观</div>
				<div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
					<Segmented<ThemeMode> value={theme} onChange={m => { setTheme(m); applyTheme(m); }}
						ariaLabel="界面主题" options={[
							{ value: 'dark', label: '深色' },
							{ value: 'light', label: '浅色' },
						]} />
					<span style={{ fontSize: 12, color: 'var(--text-faint)' }}>
						即点即生效并记住；浅色为第一版——部分旧样式仍在逐页跟进
					</span>
				</div>
			</section>
		);
	}
	if (group === 'data') {
		return (
			<section style={{ background: 'var(--surface-2)', borderRadius: 'var(--shape-lg)', padding: '18px 22px', marginBottom: 18 }}>
				<div style={{ fontSize: 13, fontWeight: 650, marginBottom: 8 }}>数据目录</div>
				<code style={{ fontSize: 12.5, color: 'var(--text-faint)', wordBreak: 'break-all' }}>{info?.dataDir ?? '…'}</code>
				<div style={{ fontSize: 12, color: 'var(--text-faint)', marginTop: 8 }}>
					版本 {info?.appVersion ?? '0.1.0'} · 集合为 JSON 文件，可直接备份；导入导出接口已内置（二期接 AI 工作台）
				</div>
			</section>
		);
	}
	if (group === 'workspace') {
		return (
			<section style={{ background: 'var(--surface-2)', borderRadius: 'var(--shape-lg)', padding: '18px 22px', marginBottom: 18 }}>
				<div style={{ fontSize: 13, fontWeight: 650, marginBottom: 4 }}>工作区根目录（M18 文件工具）</div>
				<div style={{ fontSize: 12, color: 'var(--text-faint)', marginBottom: 14 }}>
					agent 的 file_list / file_read / file_search / file_write 只能访问这个目录树；
					留空则整个文件域停用——不会回落到用户目录或当前目录（这是防只读工具变成数据外传工具的边界）。
				</div>
				<Field label="根目录路径">
					<input
						value={cfg.workspaceRoot ?? ''}
						placeholder="例如 D:\Workspace（留空 = 文件工具已停用）"
						onChange={e => { setCfg({ ...cfg, workspaceRoot: e.target.value }); setSaved(false); }}
					/>
				</Field>
				<div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 10, flexWrap: 'wrap' }}>
					<span style={{ fontSize: 12.5, fontWeight: 600, color: rootOn ? 'var(--ok)' : 'var(--danger)' }}>
						● {rootOn ? '文件工具已启用' : '文件工具已停用'}
					</span>
					<div className="form-actions" style={{ marginLeft: 'auto', marginTop: 0, gap: 8 }}>
						<TextButtonM3 onClick={() => void clearRoot()} disabled={!rootOn}>清空</TextButtonM3>
						<FilledButtonM3 onClick={() => void save()}>{saved ? '已保存 ✓' : '保存'}</FilledButtonM3>
					</div>
				</div>
				<div style={{ fontSize: 12, color: 'var(--text-faint)', marginTop: 8 }}>
					保存后下次工具调用即生效（主进程每次调用都重读配置，无需重启）。
				</div>
			</section>
		);
	}
	return (
		<section style={{ background: 'var(--surface-2)', borderRadius: 'var(--shape-lg)', padding: '18px 22px' }}>
			<div style={{ fontSize: 13, fontWeight: 650, marginBottom: 4 }}>模型（二期预留）</div>
			<div style={{ fontSize: 12, color: 'var(--text-faint)', marginBottom: 14 }}>
				当前版本未接入 chat；此处仅保存配置，二期 AI 工作台直接读取。
			</div>
			<Field label="主槽模型">
				<input value={cfg.mainModel ?? ''} placeholder="mimo-v2.6-flash" onChange={e => setCfg({ ...cfg, mainModel: e.target.value })} />
			</Field>
			<Field label="主槽 API Key">
				<input type="password" value={cfg.mainKey ?? ''} placeholder="仅存本机 config.json" onChange={e => setCfg({ ...cfg, mainKey: e.target.value })} />
			</Field>
			<div className="form-actions">
				<FilledButtonM3 onClick={() => void save()}>{saved ? '已保存 ✓' : '保存'}</FilledButtonM3>
			</div>
		</section>
	);
}

export default function SettingsPage() {
	const ctx = useSettingsCtx();
	return (
		<>
			<PageHeader title="设置" />
			{SETTINGS_GROUPS.map(g => <SettingsSection key={g.key} group={g.key} ctx={ctx} />)}
		</>
	);
}
