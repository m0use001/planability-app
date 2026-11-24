import { useEffect, useMemo, useState } from 'react';
import type { AutomationDef } from '@shared/ipc';
import type { Schedule, Todo } from '@shared/types';

/** 单日时间轴 — PC port of the phone TimelineGrid: 24h grid, hour gutter,
 *  overlap lane packing, now-line, point-style automation markers. */

const HOUR_H = 64;
const GUTTER = 52;
const GRID_END_INSET = 14;
const MIN_BLOCK = 26;

export type TimelineKind = 'schedule' | 'todo' | 'automation';

interface Block {
	kind: TimelineKind;
	refId: string;
	title: string;
	startMin: number;
	endMin: number;
	color: string;
	done?: boolean;
	subtitle?: string;
	/** point event (automation): 24px chip glued to its trigger line */
	point?: boolean;
}

interface Laid { block: Block; lane: number; lanes: number }

/** 优先级语义色（高红/中黄/低蓝，dark scheme；desktop priority 1高/2中/3低） */
function priorityColor(p?: number): string {
	if (p === 1) { return 'hsl(4 72% 64%)'; }
	if (p === 3) { return 'hsl(215 66% 66%)'; }
	return 'hsl(38 80% 62%)';
}

/** 重叠分列：按开始排序，逐簇贪心泳道（簇=彼此重叠的连续事件段），簇内共用列数 */
function layoutBlocks(blocks: Block[]): Laid[] {
	if (!blocks.length) { return []; }
	const sorted = [...blocks].sort((a, b) => a.startMin - b.startMin);
	const out: Laid[] = [];
	let laneEnds: number[] = [];
	let clusterLanes = 0;
	let prevEnd = -1;
	let tmp: { block: Block; lane: number }[] = [];
	for (const b of sorted) {
		if (b.startMin >= prevEnd) {
			// 新簇：收束上一簇（列数=该簇峰值）
			for (const t of tmp) { out.push({ ...t, lanes: clusterLanes }); }
			tmp = [];
			laneEnds = [];
			clusterLanes = 0;
		}
		let lane = 0;
		while (lane < laneEnds.length && laneEnds[lane] > b.startMin) { lane++; }
		if (lane >= laneEnds.length) { laneEnds.push(b.endMin); } else { laneEnds[lane] = b.endMin; }
		clusterLanes = Math.max(clusterLanes, lane + 1);
		tmp.push({ block: b, lane });
		prevEnd = Math.max(prevEnd, b.endMin);
	}
	for (const t of tmp) { out.push({ ...t, lanes: clusterLanes }); }
	return out;
}

function hhmm(min: number): string {
	const h = Math.floor(min / 60);
	const m = min % 60;
	return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

function minutesOf(iso?: string): number {
	if (!iso || iso.length < 16) { return -1; }
	return Number(iso.slice(11, 13)) * 60 + Number(iso.slice(14, 16));
}

function todayKey(): string {
	const d = new Date();
	return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

export default function Timeline({
	schedules, todos, automations, onOpen
}: {
	schedules: Schedule[];
	todos: Todo[];
	automations: AutomationDef[];
	onOpen: (kind: TimelineKind) => void;
}) {
	const today = todayKey();
	const [nowMin, setNowMin] = useState(() => { const d = new Date(); return d.getHours() * 60 + d.getMinutes(); });

	// now-line ticks every 30s (phone LaunchedEffect parity)
	useEffect(() => {
		const t = window.setInterval(() => {
			const d = new Date();
			setNowMin(d.getHours() * 60 + d.getMinutes());
		}, 30_000);
		return () => window.clearInterval(t);
	}, []);

	const { blocks, allDay } = useMemo(() => {
		const list: Block[] = [];
		const day: Schedule[] = [];
		for (const s of schedules) {
			const date = s.date ?? s.startIso?.slice(0, 10);
			if (date !== today) { continue; }
			const sm = minutesOf(s.startIso);
			if (sm < 0) { day.push(s); continue; } // 全天/无时刻 → 顶部条
			let em = minutesOf(s.endIso);
			if (em <= sm) { em = sm + 30; }
			list.push({
				kind: 'schedule', refId: s.id, title: s.title,
				startMin: Math.max(0, Math.min(sm, 1439)),
				endMin: Math.min(em, 1440),
				color: priorityColor(s.priority),
				subtitle: s.note
			});
		}
		for (const t of todos) {
			if (t.date !== today || !t.time) { continue; }
			const sm = Math.max(0, Math.min(Number(t.time.slice(0, 2)) * 60 + Number(t.time.slice(3, 5)), 1410));
			list.push({
				kind: 'todo', refId: t.id, title: t.title,
				startMin: sm, endMin: Math.min(sm + 30, 1440),
				color: priorityColor(t.priority),
				done: t.status === 'done'
			});
		}
		for (const a of automations) {
			if (!a.enabled) { continue; }
			let startMin: number | null = null;
			if (a.dailyHour != null && a.dailyHour >= 0) {
				startMin = a.dailyHour * 60 + Math.max(0, Math.min(a.dailyMinute ?? 0, 59));
			} else if (a.intervalMinutes && a.intervalMinutes > 0) {
				const next = new Date((a.lastRun || Date.now()) + a.intervalMinutes * 60_000);
				if (`${next.getFullYear()}-${String(next.getMonth() + 1).padStart(2, '0')}-${String(next.getDate()).padStart(2, '0')}` === today) {
					startMin = next.getHours() * 60 + next.getMinutes();
				}
			}
			if (startMin != null) {
				list.push({
					kind: 'automation', refId: String(a.id), title: a.name,
					startMin: Math.max(0, Math.min(startMin, 1439)),
					endMin: Math.min(startMin + 10, 1440),
					color: 'hsl(42 96% 70%)',
					point: true
				});
			}
		}
		return { blocks: list, allDay: day };
	}, [schedules, todos, automations, today]);

	const laid = useMemo(() => layoutBlocks(blocks), [blocks]);
	const height = 24 * HOUR_H + 8;

	return (
		<div className="tl-wrap">
			{allDay.length > 0 && (
				<div className="tl-allday">
					<span className="tl-allday-tag">全天</span>
					{allDay.map(s => (
						<button key={s.id} className="tl-allday-chip" onClick={() => onOpen('schedule')}>{s.title}</button>
					))}
				</div>
			)}

			<div className="tl" style={{ height }}>
				{/* 刻度 */}
				<div className="tl-gutter">
					{Array.from({ length: 24 }, (_, h) => (
						<div
							key={h}
							className={`tl-hour-label ${h === Math.floor(nowMin / 60) ? 'now' : ''}`}
							style={{ top: h * HOUR_H }}
						>{String(h).padStart(2, '0')}</div>
					))}
				</div>

				{/* 网格线 + 事件块 */}
				<div className="tl-col">
					{Array.from({ length: 24 }, (_, h) => (
						<div key={h} className="tl-line" style={{ top: h * HOUR_H }} />
					))}

					{laid.map(({ block, lane, lanes }) => {
						const isPoint = !!block.point;
						const top = HOUR_H * (block.startMin / 60) + (isPoint ? 2 : 1);
						const rawH = HOUR_H * ((block.endMin - block.startMin) / 60);
						const h = isPoint ? 24 : Math.max(MIN_BLOCK, rawH) - 2;
						const laneW = `((100% - ${GRID_END_INSET}px) / ${lanes})`;
						const left = `calc(${laneW} * ${lane} + 2px)`;
						const width = `calc(${laneW} - 5px)`;
						const showTime = !isPoint && h >= 46;
						return (
							<div
								key={`${block.kind}-${block.refId}`}
								className={`tl-block ${isPoint ? 'point' : ''} ${block.done ? 'dim' : ''}`}
								style={{ top, height: h, left, width, background: isPoint ? 'hsl(42 96% 30% / .95)' : block.color }}
								onClick={() => onOpen(block.kind)}
								title={`${hhmm(block.startMin)}–${hhmm(block.endMin)} ${block.title}`}
							>
								<div className="tl-block-title">{block.done ? `✓ ${block.title}` : block.title}</div>
								{!isPoint && h >= 64 && block.subtitle && <div className="tl-block-sub">{block.subtitle}</div>}
								{showTime && (
									<div className="tl-block-time">
										{hhmm(block.startMin)}{h >= 76 ? `–${hhmm(block.endMin)}` : ''}
									</div>
								)}
							</div>
						);
					})}
				</div>

				{/* 现在线 */}
				<div className="tl-now" style={{ top: HOUR_H * (nowMin / 60) }}>
					<span className="tl-now-dot" />
				</div>
			</div>
		</div>
	);
}
