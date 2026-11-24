import { useEffect, useState } from 'react';
import type { DataStats } from '@shared/types';
import type { UsageSummary as Usage } from '@shared/ipc';
import { PageHeader } from '../components/ui';
import { TextButtonM3 } from '../components/m3/buttons';

export default function StatsPage() {
	const [stats, setStats] = useState<DataStats | null>(null);
	const [usage, setUsage] = useState<Usage | null>(null);
	// G 类 MEDIUM：原先 !stats 直接 return null —— 加载中与加载失败都是纯白屏，
	// 用户分不清「还没好」和「坏了」。三个态分开：加载中、失败可重试、正常。
	const [err, setErr] = useState(false);

	const load = () => {
		setErr(false);
		window.planability.stats().then(setStats).catch(e => { console.error(e); setErr(true); });
		window.planability.usageSummary().then(setUsage).catch(e => { console.error(e); setErr(true); });
	};

	useEffect(() => { load(); }, []);

	if (!stats && err) {
		return (
			<>
				<PageHeader title="统计" />
				<div className="empty">
					数据读取失败 <TextButtonM3 onClick={load}>重试</TextButtonM3>
				</div>
			</>
		);
	}
	if (!stats) {
		return (
			<>
				<PageHeader title="统计" />
				<div className="empty">加载中…</div>
			</>
		);
	}

	const cards: { label: string; num: number; sub?: string }[] = [
		{ label: '日程', num: stats.schedules },
		{ label: '待办', num: stats.todos, sub: `未完成 ${stats.todosOpen}` },
		{ label: '笔记', num: stats.notes },
		{ label: '记忆', num: stats.memories },
		{ label: '项目', num: stats.projects }
	];

	return (
		<>
			<PageHeader title="统计" />
			<p style={{ color: 'var(--text-dim)', fontSize: 13, marginTop: -8 }}>Planability 数据：{cards.map(c => `${c.label} ${c.num}`).join(' · ')}</p>
			<div className="stat-grid">
				{cards.map(c => (
					<div className="stat-card" key={c.label}>
						<div className="stat-num">{c.num}</div>
						<div className="stat-label">{c.label}{c.sub ? ` · ${c.sub}` : ''}</div>
					</div>
				))}
			</div>

			<div className="group-title">AI 工具调用（tools_audit）</div>
			{!usage && err && (
				<div className="empty">工具用量读取失败 <TextButtonM3 onClick={load}>重试</TextButtonM3></div>
			)}
			{usage && usage.totalCalls === 0 && <div className="empty">还没有工具调用记录</div>}
			{usage && usage.totalCalls > 0 && (
				<>
					<div className="stat-grid">
						<div className="stat-card">
							<div className="stat-num">{usage.totalCalls}</div>
							<div className="stat-label">累计调用</div>
						</div>
						{usage.byTool.slice(0, 5).map(t => (
							<div className="stat-card" key={t.tool} style={{ minWidth: 150 }}>
								<div className="stat-num" style={{ fontSize: 24 }}>{t.ok}<span style={{ fontSize: 14, color: t.fail ? 'var(--danger)' : 'var(--text-faint)' }}>/{t.fail}✗</span></div>
								<div className="stat-label">{t.tool} · {t.ms}ms</div>
							</div>
						))}
					</div>
					{usage.recent.length > 0 && (
						<div className="card" style={{ display: 'block', marginTop: 14 }}>
							<div className="card-meta" style={{ marginBottom: 8 }}>最近调用</div>
							{usage.recent.slice(0, 8).map((r, i) => (
								<div key={i} style={{ display: 'flex', gap: 10, fontSize: 12.5, padding: '3px 0', color: 'var(--text-dim)' }}>
									<span>{r.ok ? '✓' : '✗'}</span>
									<code style={{ color: 'var(--primary)' }}>{r.tool}</code>
									<span style={{ marginLeft: 'auto' }}>{r.ms}ms · {new Date(r.at).toLocaleTimeString('zh-CN')}</span>
								</div>
							))}
						</div>
					)}
				</>
			)}
		</>
	);
}
