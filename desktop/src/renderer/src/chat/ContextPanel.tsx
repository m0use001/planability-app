import { useEffect, useRef, useState } from 'react';
import type { ContextKey, ContextManifest } from '@shared/chat';

/* M19-1 上下文面板（pc-a）。数字一律来自主进程的 contextManifest——请求还带着系统提示与
 * 全部工具定义，渲染层按可见文本数出来的数和账单对不上，"不要自己算 token" 是写进契约的
 * 禁令。逐项开关走 contextTogglesSet（主进程落配置，下次请求生效）。 */

const TIER_LABEL: Record<string, string> = {
	none: '', micro: '微剪 · 剪工具结果', fold: '折叠中段', summary: '摘要中段',
};

export function ContextBudgetPanel() {
	const [manifest, setManifest] = useState<ContextManifest | null>(null);
	const [toggles, setToggles] = useState<Partial<Record<ContextKey, boolean>>>({});
	const [history, setHistory] = useState<ContextManifest[]>([]);
	const [loaded, setLoaded] = useState(false);
	const aliveRef = useRef(true);

	const refresh = async () => {
		try {
			const f = window.planability.chat;
			const [m, t, h] = await Promise.all([
				f.contextManifest(), f.contextTogglesGet(), f.contextHistory(6),
			]);
			if (!aliveRef.current) { return; }
			setManifest(m); setToggles(t); setHistory(h); setLoaded(true);
		} catch { /* non-fatal */ }
	};

	useEffect(() => {
		aliveRef.current = true;
		void refresh();
		// A settled turn rewrites the manifest; keep the panel live if it stays open.
		// The trigger is the row_upsert that CLOSES an assistant segment (settleAssistant) —
		// that is the signal main actually broadcasts. `ChatEvent done` sits in the contract
		// but nothing in main ever sends it (the agent's done is an AgentEvent consumed by the
		// row bridge), so listening for it alone left this refresh dead — the exact "面板滞后"
		// the manual 刷新 button was papering over. Kept the done check too, in case main
		// starts honoring that half of the contract later.
		const off = window.planability.chat.onChat(e => {
			if (e.type === 'done') { void refresh(); return; }
			if (e.type === 'row_upsert' && e.row.kind === 'assistant' && e.row.done) { void refresh(); }
		});
		return () => { aliveRef.current = false; off(); };
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, []);

	const setToggle = async (key: ContextKey, on: boolean) => {
		try { setToggles(await window.planability.chat.contextTogglesSet({ ...toggles, [key]: on })); } catch { /* non-fatal */ }
	};

	if (!loaded) { return <div className="ctx-panel"><div className="ctx-empty">读取中…</div></div>; }

	const over = !!manifest && manifest.ratio > 1;
	return (
		<div className="ctx-panel">
			{!manifest ? (
				<div className="ctx-empty">
					还没有请求记录：发第一条消息后，这里显示主进程按真实请求算出的逐项 token 账单。
					<button className="fv-toggle" onClick={() => void refresh()}>刷新</button>
				</div>
			) : (
				<>
					<div className={`ctx-head${over ? ' over' : ''}`}>
						<strong>{manifest.total.toLocaleString()}</strong>
						<span> / {manifest.limit.toLocaleString()} tokens</span>
						<span className="ctx-ratio">{Math.round(manifest.ratio * 100)}%</span>
						<button className="fv-toggle" title="重新读取主进程账单（验收口径：账本为准，面板可能滞后）"
							onClick={() => void refresh()}>刷新</button>
					</div>
					<div className="ctx-bar" title={`行合计 ${manifest.sum.toLocaleString()}（应与总数一致）`}>
						{manifest.rows.map(r => (
							<span key={r.key} className={`ctx-seg k-${r.key}`}
								style={{ width: `${Math.max(1, Math.round(r.share * 100))}%` }} />
						))}
					</div>
					{(manifest.compacted || manifest.degraded) && (
						<div className={`ctx-tier${manifest.degraded ? ' degraded' : ''}`}>
							{manifest.compacted
								? `已压缩：${TIER_LABEL[manifest.tier] || manifest.tier} · 省 ${manifest.savedTokens.toLocaleString()} tokens`
								: '压缩未生效'}
							{manifest.degraded ? ' · 摘要失败已回落折叠' : ''}
						</div>
					)}
					<div className="ctx-items">
						{[...manifest.rows].sort((a, b) => b.tokens - a.tokens).map(r => {
							const on = toggles[r.key] !== false;
							return (
								<div key={r.key} className={`ctx-item${on ? '' : ' disabled'}`}>
									<span className="ctx-label">
										{r.label}
										{r.compacted && <i className="ctx-cut">已剪</i>}
									</span>
									<span className="ctx-tokens">{r.tokens.toLocaleString()} · {Math.round(r.share * 100)}%</span>
									<label className="switch" title={on ? '已启用 · 点击关闭（下次请求生效）' : '已关闭 · 点击开启（下次请求生效）'}>
										<input type="checkbox" checked={on} onChange={e => void setToggle(r.key, e.target.checked)} />
										<span className="switch-track"><span className="switch-thumb" /></span>
									</label>
								</div>
							);
						})}
					</div>
					<div className="ctx-note">开关下次请求生效 · 数字由主进程按真实请求算出</div>
				</>
			)}
			{history.length > 0 && (
				<details className="ctx-history">
					<summary>最近 {history.length} 次请求</summary>
					{[...history].reverse().map((h, i) => (
						<div key={i} className="ctx-hrow">
							<span className="ctx-htotal">{h.total.toLocaleString()} tk</span>
							<span className={h.ratio > 1 ? 'over' : ''}>{Math.round(h.ratio * 100)}%</span>
							<span className="ctx-htier">{h.tier !== 'none' ? (TIER_LABEL[h.tier] || h.tier) : '—'}{h.degraded ? ' · 降级' : ''}</span>
						</div>
					))}
				</details>
			)}
		</div>
	);
}
