import { useEffect, useMemo, useRef, useState } from 'react';
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

	// 夜间空小时压缩（手机「只压夜间空小时，白天恒 64dp」口径）：6–22 恒 64，
	// 0–5/23 仅当该小时没有事件块才压 22——有块的小时保持 64 不压块。
	const hasBlockIn = useMemo(() => {
		const set = new Set<number>();
		for (const b of blocks) {
			for (let h = Math.floor(b.startMin / 60); h <= Math.floor((b.endMin - 1) / 60); h++) { set.add(h); }
		}
		return set;
	}, [blocks]);
	const hourH = useMemo(
		() => Array.from({ length: 24 }, (_, h) => ((h >= 6 && h <= 22) || hasBlockIn.has(h) ? HOUR_H : 22)),
		[hasBlockIn]
	);
	const hourTop = useMemo(() => {
		const tops: number[] = [];
		let acc = 0;
		for (let h = 0; h < 24; h++) { tops.push(acc); acc += hourH[h]; }
		tops.push(acc); // 索引 24 = 全天总高（原来缺这一个 → hourTop[24]=undefined → height=NaN 的根因）
		return tops;
	}, [hourH]);
	// +底部留白：手机口径要把现在线滚到板内上部，需要足够下行行程（否则最大滚动量截断在中间）
	const height = hourTop[24] + 8 + 180;

	// 定位到当前时刻（手机口径：现在线落在板内上部，约离滚动容器顶 168px；非正中间）
	const nowRef = useRef<HTMLDivElement | null>(null);
	useEffect(() => {
		// 现在线落到滚动容器顶部下 168px（手机口径，约满高 23%；.tl 底部留白保证滚动行程足够）
		const t = window.setTimeout(() => {
			const el = nowRef.current;
			if (!el) { return; }
			let sc: HTMLElement | null = el.parentElement;
			while (sc) {
				const oy = getComputedStyle(sc).overflowY;
				if ((oy === 'auto' || oy === 'scroll') && sc.scrollHeight > sc.clientHeight + 4) { break; }
				sc = sc.parentElement;
			}
			if (!sc) { return; }
			const elTop = el.getBoundingClientRect().top - sc.getBoundingClientRect().top + sc.scrollTop;
			// 现在线落到「滚动容器顶 + 117px」——与手机平板实测同尺寸(1600×1000)下现在线绝对 y≈229 对齐。
			// 行程不够（时刻越晚 elTop 越大）就按需补 .tl 高度，否则会被 maxScroll 截住、现在线越走越低。
			const needed = elTop - 117 + sc.clientHeight;
			const deficit = needed - sc.scrollHeight;
			const tl = el.closest('.tl') as HTMLElement | null;
			if (deficit > 0 && tl) { tl.style.height = `${tl.offsetHeight + deficit}px`; }
			sc.scrollTop = Math.max(0, elTop - 117);
		}, 80);
		return () => window.clearTimeout(t);
	}, []);

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
							style={{ top: hourTop[h] + (hourH[h] - 16) / 2 }}
						>{String(h).padStart(2, '0')}</div>
					))}
				</div>

				{/* 网格线 + 事件块 */}
				<div className="tl-col">
					{Array.from({ length: 24 }, (_, h) => (
						<div key={h} className="tl-line" style={{ top: hourTop[h] }} />
					))}

					{laid.map(({ block, lane, lanes }) => {
						const isPoint = !!block.point;
						const h0 = Math.floor(block.startMin / 60);
						const top = hourTop[h0] + (hourH[h0] * (block.startMin - h0 * 60)) / 60 + (isPoint ? 2 : 1);
						const h1 = Math.min(23, Math.floor((block.endMin - 1) / 60));
						const bottom = hourTop[h1] + (hourH[h1] * (block.endMin - h1 * 60)) / 60;
						const rawH = bottom - top - (isPoint ? 2 : 1);
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
				<div className="tl-now" ref={nowRef} style={{ top: hourTop[Math.floor(nowMin / 60)] + hourH[Math.floor(nowMin / 60)] * ((nowMin % 60) / 60) }}>
					<span className="tl-now-dot" />
				</div>
				</div>
			</div>
		);
}
