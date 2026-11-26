import type { CSSProperties } from 'react';

/**
 * 笔记本封面（Compose Canvas → React SVG 移植）
 * 源：app/src/main/java/io/github/m0use001/planability/ui/notes/NotesScreen.kt :: NotebookCover（164–1236 行）
 * Kotlin 用 X(p)=p/1000*w、Y(p)=p/1120*h、R(p)=p/1000*w 归一化；
 * viewBox="0 0 1000 1120" + preserveAspectRatio="none" 让每个设计坐标 1:1 映射，
 * 因此下面直接书写原始设计数值。
 * 分支：1..10（场景画）、90（自定义文字）、else（素色渐变）+ 共享装订线。
 */

const FONT = 'system-ui, "Microsoft YaHei", sans-serif';

/* ---------------- 颜色工具 ---------------- */

/** HSL(h 0–360, s 0–1, l 0–1) → #RRGGBB（与 CSS hsl(212 55% 60%) 等价） */
function hslHex(h: number, s: number, l: number): string {
	const a = s * Math.min(l, 1 - l);
	const f = (n: number): string => {
		const k = (n + h / 30) % 12;
		const c = l - a * Math.max(-1, Math.min(k - 3, Math.min(9 - k, 1)));
		return Math.round(255 * c).toString(16).padStart(2, '0');
	};
	return `#${f(0)}${f(8)}${f(4)}`;
}

/** SeedColors.artPalette（Color.kt 49–52 行） */
const ART_PALETTE: readonly string[] = [
	hslHex(212, 0.55, 0.6), // hsl(212 55% 60%)
	hslHex(152, 0.48, 0.55), // hsl(152 48% 55%)
	hslHex(28, 0.72, 0.58), // hsl(28 72% 58%)
	hslHex(348, 0.62, 0.62), // hsl(348 62% 62%)
	hslHex(262, 0.48, 0.62), // hsl(262 48% 62%)
	hslHex(188, 0.52, 0.55) // hsl(188 52% 55%)
];

function hexToRgb(hex: string): [number, number, number] {
	const n = parseInt(hex.slice(1), 16);
	return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

/** Compose lerp(colorA, colorB, t)：0–255 sRGB 线性插值 */
function lerpHex(a: string, b: string, t: number): string {
	const ra = hexToRgb(a);
	const rb = hexToRgb(b);
	const ch = (i: number): string => Math.round(ra[i] + (rb[i] - ra[i]) * t).toString(16).padStart(2, '0');
	return `#${ch(0)}${ch(1)}${ch(2)}`;
}

/* ---------------- SVG 小工具 ---------------- */

/** Brush.verticalGradient：竖直线性渐变（objectBoundingBox 单位） */
function vGrad(id: string, stops: Array<[number, string]>): JSX.Element {
	return (
		<linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
			{stops.map(([offset, color], i) => (
				<stop key={i} offset={offset} stopColor={color} />
			))}
		</linearGradient>
	);
}

/** 标题安全区三条横线；rows = [[y, height, radius], ...] */
function titleBars(color: string, opacity: [number, number, number], rows: Array<[number, number, number]>): JSX.Element {
	return (
		<>
			<rect x={160} y={rows[0][0]} width={680} height={rows[0][1]} rx={rows[0][2]} ry={rows[0][2]} fill={color} fillOpacity={opacity[0]} />
			<rect x={230} y={rows[1][0]} width={540} height={rows[1][1]} rx={rows[1][2]} ry={rows[1][2]} fill={color} fillOpacity={opacity[1]} />
			<rect x={320} y={rows[2][0]} width={360} height={rows[2][1]} rx={rows[2][2]} ry={rows[2][2]} fill={color} fillOpacity={opacity[2]} />
		</>
	);
}

const TITLE_STD: Array<[number, number, number]> = [[1042, 17, 9], [1074, 13, 7], [1100, 11, 6]];
const TITLE_HK: Array<[number, number, number]> = [[1092, 11, 6], [1106, 9, 5], [1117, 7, 4]];
const TITLE_DESERT: Array<[number, number, number]> = [[1076, 15, 8], [1098, 11, 6], [1115, 9, 5]];

/* ---------------- 窗户短横线阵列（3 个变体，算术与 Kotlin 一致） ---------------- */

function winLines2(x0: number, top: number, bw: number, seed: number): JSX.Element[] {
	const cols = Math.max(1, Math.floor((bw - 18) / 26));
	const rows = Math.max(1, Math.floor((1050 - top - 16) / 34));
	const out: JSX.Element[] = [];
	for (let r = 0; r < rows; r++) {
		for (let col = 0; col < cols; col++) {
			if ((r * 13 + col * 7 + seed) % 3 === 0) { continue; }
			const lx = x0 + 10 + col * 26;
			const ly = top + 14 + r * 34;
			out.push(
				<line key={`w${seed}-${r}-${col}`} x1={lx} y1={ly} x2={lx + 13} y2={ly} stroke="#F9F871" strokeOpacity={0.8} strokeWidth={5} strokeLinecap="round" />
			);
		}
	}
	return out;
}

function winLines3(x0: number, top: number, bw: number, seed: number): JSX.Element[] {
	const cols = Math.max(1, Math.floor((bw - 18) / 26));
	const rows = Math.max(1, Math.floor((1040 - top - 16) / 36));
	const out: JSX.Element[] = [];
	for (let r = 0; r < rows; r++) {
		for (let col = 0; col < cols; col++) {
			if ((r * 11 + col * 5 + seed) % 3 === 0) { continue; }
			const lx = x0 + 10 + col * 26;
			const ly = top + 15 + r * 36;
			out.push(
				<line key={`w${seed}-${r}-${col}`} x1={lx} y1={ly} x2={lx + 13} y2={ly} stroke="#F2C14E" strokeOpacity={0.65} strokeWidth={5} strokeLinecap="round" />
			);
		}
	}
	return out;
}

function winLines4(x0: number, top: number, bw: number, bottom: number, seed: number): JSX.Element[] {
	const cols = Math.max(1, Math.floor((bw - 18) / 26));
	const rows = Math.max(1, Math.floor((bottom - top - 16) / 34));
	const out: JSX.Element[] = [];
	for (let r = 0; r < rows; r++) {
		for (let col = 0; col < cols; col++) {
			if ((r * 11 + col * 5 + seed) % 3 === 0) { continue; }
			const lx = x0 + 10 + col * 26;
			const ly = top + 14 + r * 34;
			out.push(
				<line key={`w${seed}-${r}-${col}`} x1={lx} y1={ly} x2={lx + 13} y2={ly} stroke="#F9F871" strokeOpacity={0.7} strokeWidth={5} strokeLinecap="round" />
			);
		}
	}
	return out;
}

/* ================= style 1：晨光中的上海浦东陆家嘴 ================= */

function CoverStyle1(): JSX.Element {
	const clouds: Array<[number, number, number]> = [[160, 285, 0.95], [430, 165, 0.75], [210, 500, 0.8], [880, 430, 0.7]];
	const birds: Array<[number, number, number]> = [[170, 160, 1], [330, 250, 0.8], [520, 140, 1.1], [610, 300, 0.7], [300, 430, 0.9]];
	const refl: Array<[number, number, number]> = [[160, 992, 200], [450, 999, 150], [740, 994, 220], [280, 1007, 170], [590, 1013, 190], [840, 1006, 140]];
	// 远景城市剪影（Kotlin while 循环，算术原样保留）
	let far = 'M0 980';
	let fx = 0;
	let fi = 0;
	while (fx < 1000) {
		const cw = 55 + (fi % 3) * 20;
		const top = 780 + ((fi * 37) % 200);
		far += ` L${fx} ${top} L${fx + cw} ${top}`;
		fx += cw;
		fi++;
	}
	far += ' L1000 980 Z';
	return (
		<>
			{vGrad('s1sky', [[0, '#0B1F33'], [0.55, '#1E4D6B'], [1, '#F7E7CE']])}
			<rect width={1000} height={1120} fill="url(#s1sky)" />
			{/* 太阳（右上，四层柔光晕） */}
			<circle cx={760} cy={230} r={170} fill="#F2C14E" fillOpacity={0.1} />
			<circle cx={760} cy={230} r={135} fill="#F2C14E" fillOpacity={0.16} />
			<circle cx={760} cy={230} r={100} fill="#F2C14E" fillOpacity={0.28} />
			<circle cx={760} cy={230} r={72} fill="#F2C14E" />
			{/* 云层 4 组（圆角矩形 + 椭圆） */}
			{clouds.map(([cx, cy, s], i) => (
				<g key={`cl${i}`} fill="#F7E7CE" fillOpacity={0.75}>
					<rect x={cx - 90 * s} y={cy - 20 * s} width={180 * s} height={46 * s} rx={23 * s} ry={23 * s} />
					<ellipse cx={cx - 7.5 * s} cy={cy - 27 * s} rx={47.5 * s} ry={31 * s} />
					<ellipse cx={cx + 35 * s} cy={cy - 19 * s} rx={40 * s} ry={26 * s} />
				</g>
			))}
			{/* 飞鸟 5 只（V 形描线） */}
			{birds.map(([cx, cy, s], i) => (
				<path key={`bi${i}`} d={`M${cx - 26 * s} ${cy} L${cx} ${cy + 16 * s} L${cx + 26 * s} ${cy}`} fill="none" stroke="#F7E7CE" strokeOpacity={0.9} strokeWidth={7 * s} strokeLinecap="round" strokeLinejoin="round" />
			))}
			{/* 远景城市剪影 */}
			<path d={far} fill="#1E4D6B" fillOpacity={0.55} />
			{/* 东方明珠：三角斜腿 + 双竖线塔身 + 双球 + 天线 */}
			<path d="M270 980 L340 660 L410 980 Z" fill="#1E4D6B" />
			<line x1={328} y1={720} x2={328} y2={400} stroke="#1E4D6B" strokeWidth={10} strokeLinecap="round" />
			<line x1={352} y1={720} x2={352} y2={400} stroke="#1E4D6B" strokeWidth={10} strokeLinecap="round" />
			<circle cx={340} cy={700} r={42} fill="#1E4D6B" />
			<circle cx={340} cy={420} r={30} fill="#1E4D6B" />
			<line x1={340} y1={392} x2={340} y2={300} stroke="#1E4D6B" strokeWidth={8} strokeLinecap="round" />
			{/* 上海中心：12 段阶梯多边形（渐收 + 左右摆动） */}
			{Array.from({ length: 12 }, (_, i) => {
				const t = i / 11;
				const halfW = 62 - t * 46;
				const cx = 580 + (i % 2 === 0 ? 1 : -1) * t * 30;
				const y0 = 980 - (i + 1) * 55;
				return <rect key={`seg${i}`} x={cx - halfW} y={y0} width={halfW * 2} height={56} fill="#1E4D6B" />;
			})}
			{/* 环球金融中心：梯形顶 + 圆孔（even-odd 挖孔） */}
			<path
				d="M660 980 L660 360 L678 300 L722 300 L740 360 L740 980 Z M682 330 A18 18 0 0 1 718 330 A18 18 0 0 1 682 330 Z"
				fill="#1E4D6B"
				fillRule="evenodd"
			/>
			{/* 金茂大厦：收分塔身 + 细长三角尖 */}
			<path d="M430 980 L442 430 L498 430 L510 980 Z" fill="#1E4D6B" />
			<path d="M452 430 L470 260 L488 430 Z" fill="#1E4D6B" />
			{/* 黄浦江倒影 6 条 */}
			{refl.map(([cx, cy, len], i) => (
				<line key={`rf${i}`} x1={cx - len / 2} y1={cy} x2={cx + len / 2} y2={cy} stroke="#4FA3C7" strokeOpacity={0.4} strokeWidth={9} strokeLinecap="round" />
			))}
			{titleBars('#FFFFFF', [0.9, 0.6, 0.35], TITLE_STD)}
		</>
	);
}

/* ================= style 2：霓虹东京夜景 ================= */

function CoverStyle2(): JSX.Element {
	const buildings: Array<[number, number, number, number]> = [
		[0, 105, 690, 0], [95, 80, 780, 1], [165, 75, 620, 0],
		[365, 90, 860, 1], [445, 70, 810, 0], [505, 85, 875, 1], [580, 80, 830, 0],
		[770, 90, 720, 1], [850, 75, 800, 0], [915, 85, 690, 1]
	];
	return (
		<>
			{vGrad('s2sky', [[0, '#0A0A1A'], [0.55, '#1B1B3A'], [1, '#2D1B4E']])}
			<rect width={1000} height={1120} fill="url(#s2sky)" />
			{/* 富士山剪影 + 白色雪帽 */}
			<path d="M180 900 L440 768 L470 760 L505 768 L780 900 Z" fill="#14142B" />
			<path d="M440 768 L470 760 L505 768 L488 784 L472 772 L456 786 Z" fill="#FFFFFF" fillOpacity={0.85} />
			{/* 建筑群（双色，中央留低让富士山露头） */}
			{buildings.map(([x, bw, top, ci], i) => (
				<rect key={`b${i}`} x={x} y={top} width={bw} height={1050 - top} fill={ci === 0 ? '#1B1B3A' : '#24244A'} />
			))}
			{/* 窗户阵列 */}
			{winLines2(0, 690, 105, 1)}
			{winLines2(95, 780, 80, 2)}
			{winLines2(165, 620, 75, 3)}
			{winLines2(445, 730, 70, 4)}
			{winLines2(580, 760, 80, 5)}
			{winLines2(770, 720, 90, 6)}
			{winLines2(850, 800, 75, 7)}
			{/* 东京塔（红三角桁架 + 横线） */}
			<line x1={245} y1={1050} x2={295} y2={430} stroke="#FF3D81" strokeWidth={12} strokeLinecap="round" />
			<line x1={355} y1={1050} x2={295} y2={430} stroke="#FF3D81" strokeWidth={12} strokeLinecap="round" />
			{[950, 870, 790, 710, 630, 550, 480].map((y, i) => {
				const t = (1050 - y) / 620;
				const half = 55 * (1 - t) + 8;
				return <line key={`tb${i}`} x1={295 - half} y1={y} x2={295 + half} y2={y} stroke="#FF3D81" strokeWidth={8} strokeLinecap="round" />;
			})}
			<rect x={262} y={600} width={66} height={34} rx={6} ry={6} fill="#FF3D81" />
			<rect x={277} y={455} width={36} height={22} rx={5} ry={5} fill="#FF3D81" />
			<line x1={295} y1={430} x2={295} y2={320} stroke="#FF3D81" strokeWidth={9} strokeLinecap="round" />
			<line x1={275} y1={360} x2={315} y2={360} stroke="#FF3D81" strokeWidth={7} strokeLinecap="round" />
			{/* 晴空塔（青色细长塔身 + 三层观景台） */}
			<line x1={672} y1={1050} x2={700} y2={310} stroke="#00E5FF" strokeWidth={12} strokeLinecap="round" />
			<line x1={738} y1={1050} x2={710} y2={310} stroke="#00E5FF" strokeWidth={12} strokeLinecap="round" />
			{[720, 560, 440].map((y, i) => {
				const t = (1050 - y) / 740;
				const half = 36 * (1 - t) + 20;
				return <ellipse key={`dk${i}`} cx={705} cy={y - 1} rx={half} ry={13} fill="#00E5FF" />;
			})}
			<line x1={705} y1={310} x2={705} y2={215} stroke="#00E5FF" strokeWidth={8} strokeLinecap="round" />
			{/* 街道（透视梯形）+ 霓虹反光 */}
			<path d="M340 1050 L660 1050 L880 1160 L120 1160 Z" fill="#0A0A1A" />
			<line x1={490} y1={1050} x2={380} y2={1160} stroke="#FF3D81" strokeOpacity={0.8} strokeWidth={8} strokeLinecap="round" />
			<line x1={520} y1={1050} x2={640} y2={1160} stroke="#00E5FF" strokeOpacity={0.8} strokeWidth={8} strokeLinecap="round" />
			{Array.from({ length: 4 }, (_, i) => {
				const y = 1066 + i * 24;
				const half = 60 + i * 55;
				return (
					<g key={`ne${i}`}>
						<line x1={505 - half} y1={y} x2={505 - half + 42} y2={y} stroke="#00E5FF" strokeOpacity={0.3} strokeWidth={6} strokeLinecap="round" />
						<line x1={505 + half - 42} y1={y} x2={505 + half} y2={y} stroke="#FF3D81" strokeOpacity={0.3} strokeWidth={6} strokeLinecap="round" />
					</g>
				);
			})}
			{/* 雨丝 24 条 */}
			{Array.from({ length: 24 }, (_, i) => {
				const rx = (i * 61) % 1000;
				const ry = (i * 173) % 900;
				const len = 70 + (i % 3) * 25;
				return <line key={`rn${i}`} x1={rx} y1={ry} x2={rx - 22} y2={ry + len} stroke="#FFFFFF" strokeOpacity={0.24} strokeWidth={5} strokeLinecap="round" />;
			})}
			{titleBars('#FFFFFF', [0.9, 0.6, 0.35], TITLE_STD)}
		</>
	);
}

/* ================= style 3：纽约曼哈顿日落天际线 ================= */

function CoverStyle3(): JSX.Element {
	const midB: Array<[number, number, number, number]> = [
		[0, 90, 620, 0], [80, 75, 540, 1], [150, 85, 660, 0], [225, 70, 580, 1],
		[395, 70, 600, 0], [555, 90, 560, 1],
		[745, 80, 700, 0], [815, 90, 940, 1], [895, 75, 880, 0], [955, 45, 920, 1]
	];
	const birds: Array<[number, number, number]> = [
		[150, 330, 1], [260, 420, 0.8], [380, 300, 1.1],
		[600, 260, 0.9], [700, 380, 0.7], [840, 320, 1], [460, 470, 0.75]
	];
	const clouds: Array<[number, number, number]> = [[200, 220, 1], [520, 150, 0.8], [770, 310, 0.9]];
	const river: Array<[number, number, number]> = [
		[120, 1058, 260], [430, 1072, 300], [760, 1052, 240],
		[200, 1100, 340], [600, 1115, 380], [880, 1090, 200]
	];
	return (
		<>
			{vGrad('s3sky', [[0, '#2B1B4E'], [0.55, '#E85D3F'], [1, '#F2C14E']])}
			<rect width={1000} height={1120} fill="url(#s3sky)" />
			{/* 太阳（右下，双层光晕） */}
			<circle cx={800} cy={980} r={146} fill="#FFB347" fillOpacity={0.3} />
			<circle cx={800} cy={980} r={112} fill="#FFB347" fillOpacity={0.55} />
			<circle cx={800} cy={980} r={86} fill="#FFB347" />
			{/* 扁平云 3 组 */}
			{clouds.map(([cx, cy, s], i) => (
				<g key={`fc${i}`} fill="#F7E7CE">
					<rect x={cx - 110 * s} y={cy} width={220 * s} height={26 * s} rx={13 * s} ry={13 * s} fillOpacity={0.9} />
					<rect x={cx - 60 * s} y={cy - 20 * s} width={120 * s} height={20 * s} rx={10 * s} ry={10 * s} fillOpacity={0.7} />
				</g>
			))}
			{/* 飞鸟 7 只（暗色剪影） */}
			{birds.map(([cx, cy, s], i) => (
				<path key={`bi${i}`} d={`M${cx - 26 * s} ${cy} L${cx} ${cy + 16 * s} L${cx + 26 * s} ${cy}`} fill="none" stroke="#2B1B4E" strokeOpacity={0.85} strokeWidth={7 * s} strokeLinecap="round" strokeLinejoin="round" />
			))}
			{/* 远景低矮楼群剪影（限左半区给落日留空） */}
			{Array.from({ length: 9 }, (_, i) => {
				const x = i * 85;
				const top = 880 + ((i * 53) % 120);
				return <rect key={`fb${i}`} x={x} y={top} width={72} height={1040 - top} fill="#3A2A4A" fillOpacity={0.5} />;
			})}
			{/* 中景建筑群 */}
			{midB.map(([x, bw, top, ci], i) => (
				<rect key={`mb${i}`} x={x} y={top} width={bw} height={1040 - top} fill={ci === 0 ? '#1A1A2E' : '#2D2D44'} />
			))}
			{/* 帝国大厦（三段递减矩形尖塔） */}
			<rect x={310} y={500} width={70} height={540} fill="#1A1A2E" />
			<rect x={318} y={460} width={54} height={40} fill="#2D2D44" />
			<rect x={326} y={420} width={38} height={40} fill="#1A1A2E" />
			<rect x={334} y={384} width={22} height={36} fill="#2D2D44" />
			<line x1={345} y1={384} x2={345} y2={310} stroke="#1A1A2E" strokeWidth={6} strokeLinecap="round" />
			{/* 克莱斯勒大厦（半圆穹顶 + 三角尖） */}
			<rect x={478} y={480} width={64} height={560} fill="#2D2D44" />
			<path d="M478 461 A32 26 0 0 1 542 461 Z" fill="#1A1A2E" />
			<path d="M492 442 L510 380 L528 442 Z" fill="#1A1A2E" />
			{/* 世贸一号楼 */}
			<rect x={660} y={400} width={70} height={640} fill="#1A1A2E" />
			<path d="M660 400 L695 362 L730 400 Z" fill="#2D2D44" />
			<line x1={695} y1={362} x2={695} y2={275} stroke="#2D2D44" strokeWidth={5} strokeLinecap="round" />
			{/* 窗户短横线阵列 */}
			{winLines3(0, 620, 90, 11)}
			{winLines3(150, 660, 85, 12)}
			{winLines3(395, 600, 70, 13)}
			{winLines3(555, 560, 90, 14)}
			{winLines3(745, 520, 80, 15)}
			{winLines3(895, 540, 75, 16)}
			{winLines3(310, 500, 70, 17)}
			{/* 东河水面：水平线段 + 倒影矩形 */}
			{river.map(([cx, cy, len], i) => (
				<line key={`rv${i}`} x1={cx - len / 2} y1={cy} x2={cx + len / 2} y2={cy} stroke="#E85D3F" strokeOpacity={0.25} strokeWidth={10} strokeLinecap="round" />
			))}
			<rect x={745} y={1044} width={110} height={16} fill="#E85D3F" fillOpacity={0.25} />
			<rect x={762} y={1076} width={76} height={14} fill="#E85D3F" fillOpacity={0.16} />
			<rect x={180} y={1068} width={140} height={12} fill="#E85D3F" fillOpacity={0.16} />
			{titleBars('#F7E7CE', [0.9, 0.6, 0.35], TITLE_STD)}
		</>
	);
}

/* ================= style 4：香港维多利亚港夜色 ================= */

function CoverStyle4(): JSX.Element {
	const buildings: Array<[number, number, number, number]> = [
		[0, 95, 640, 0], [85, 80, 560, 1], [155, 90, 700, 0], [225, 70, 600, 1],
		[425, 40, 560, 0], [585, 70, 620, 1],
		[765, 85, 540, 0], [840, 80, 640, 1], [910, 90, 580, 0]
	];
	const ripple: Array<[number, number, number]> = [
		[140, 1000, 220], [430, 1012, 260], [730, 1004, 240],
		[250, 1030, 200], [570, 1046, 280], [850, 1035, 220],
		[430, 1070, 300], [770, 1085, 240], [200, 1108, 260]
	];
	return (
		<>
			{vGrad('s4sky', [[0, '#061A2B'], [0.55, '#0F3B4C'], [1, '#145C63']])}
			<rect width={1000} height={1120} fill="url(#s4sky)" />
			{/* 12 颗小圆星 */}
			{Array.from({ length: 12 }, (_, i) => {
				const fx = 30 + ((i * 67) % 940);
				const fy = 40 + ((i * 113) % 420);
				return <circle key={`st${i}`} cx={fx} cy={fy} r={6 + (i % 3) * 3} fill="#FFFFFF" fillOpacity={0.8} />;
			})}
			{/* 太平山轮廓 */}
			<path
				d="M0 880 Q120 770 240 730 Q340 700 460 750 Q560 790 660 740 Q780 700 880 780 Q940 825 1000 800 L1000 880 L0 880 Z"
				fill="#0B2A35"
			/>
			{/* 建筑群（基线 980） */}
			{buildings.map(([x, bw, top, ci], i) => (
				<rect key={`b${i}`} x={x} y={top} width={bw} height={980 - top} fill={ci === 0 ? '#0B1F33' : '#12384A'} />
			))}
			{/* 中银大厦（三角棱面 + 菱形描线） */}
			<path d="M300 980 L365 430 L420 980 Z" fill="#7FD1E8" />
			<line x1={365} y1={430} x2={365} y2={980} stroke="#061A2B" strokeWidth={6} strokeLinecap="round" />
			<line x1={300} y1={980} x2={365} y2={600} stroke="#061A2B" strokeWidth={6} strokeLinecap="round" />
			<line x1={420} y1={980} x2={365} y2={600} stroke="#061A2B" strokeWidth={6} strokeLinecap="round" />
			<path d="M365 470 L400 560 L365 650 L330 560 Z" fill="none" stroke="#7FD1E8" strokeWidth={6} strokeLinecap="round" strokeLinejoin="round" />
			{/* 汇丰大厦（矩形模块堆叠） */}
			{[920, 850, 780, 710].map((top, i) => (
				<rect key={`hs${i}`} x={470} y={top} width={110} height={58} fill={i % 2 === 0 ? '#12384A' : '#0B1F33'} />
			))}
			{/* 国际金融中心 */}
			<rect x={665} y={400} width={90} height={580} fill="#12384A" />
			<path d="M665 400 L710 330 L755 400 Z" fill="#0B1F33" />
			{/* 窗户 2px 短横线 */}
			{winLines4(0, 640, 95, 980, 21)}
			{winLines4(155, 700, 90, 980, 22)}
			{winLines4(225, 600, 70, 980, 23)}
			{winLines4(585, 620, 70, 980, 24)}
			{winLines4(765, 540, 85, 980, 25)}
			{winLines4(840, 640, 80, 980, 26)}
			{winLines4(470, 710, 110, 970, 27)}
			{winLines4(665, 400, 90, 980, 28)}
			{/* 水面波纹线 */}
			{ripple.map(([cx, cy, len], i) => (
				<line key={`rp${i}`} x1={cx - len / 2} y1={cy} x2={cx + len / 2} y2={cy} stroke="#4FA3C7" strokeOpacity={0.35} strokeWidth={9} strokeLinecap="round" />
			))}
			{/* 天星小轮（船形多边形 + 双烟囱） */}
			<path d="M185 1058 L295 1058 L280 1090 L200 1090 Z" fill="#7FD1E8" />
			<rect x={210} y={1040} width={70} height={20} fill="#7FD1E8" fillOpacity={0.85} />
			<rect x={222} y={1022} width={14} height={20} fill="#7FD1E8" />
			<rect x={252} y={1022} width={14} height={20} fill="#7FD1E8" />
			{Array.from({ length: 4 }, (_, c) => (
				<circle key={`fc${c}`} cx={221 + c * 16} cy={1050} r={4} fill="#FFFFFF" fillOpacity={0.9} />
			))}
			<line x1={148} y1={1092} x2={208} y2={1092} stroke="#4FA3C7" strokeOpacity={0.5} strokeWidth={8} strokeLinecap="round" />
			<line x1={278} y1={1094} x2={338} y2={1094} stroke="#4FA3C7" strokeOpacity={0.5} strokeWidth={8} strokeLinecap="round" />
			{titleBars('#FFFFFF', [0.9, 0.6, 0.35], TITLE_HK)}
		</>
	);
}

/* ================= style 5：银河雪山星轨 ================= */

function CoverStyle5(): JSX.Element {
	const milky: Array<[number, number, string, number]> = [
		[-70, 44, '#7A5AF8', 0.22], [-24, 70, '#4FA3C7', 0.24], [20, 40, '#F7E7CE', 0.28],
		[66, 62, '#7A5AF8', 0.22], [108, 38, '#4FA3C7', 0.2]
	];
	const mounts: Array<[number, number, number]> = [[140, 790, 200], [415, 760, 235], [645, 800, 195], [880, 772, 215]];
	const rad = Math.PI / 180;
	return (
		<>
			{vGrad('s5sky', [[0, '#050510'], [0.55, '#0F1B4A'], [1, '#2B1B4E']])}
			<rect width={1000} height={1120} fill="url(#s5sky)" />
			{/* 星轨：右上角为圆心的 8 条同心圆弧（95°→250°） */}
			{Array.from({ length: 8 }, (_, k) => {
				const r = 260 + k * 70;
				const sx = 1000 + r * Math.cos(95 * rad);
				const sy = r * Math.sin(95 * rad);
				const ex = 1000 + r * Math.cos(250 * rad);
				const ey = r * Math.sin(250 * rad);
				return <path key={`tr${k}`} d={`M${sx} ${sy} A${r} ${r} 0 0 1 ${ex} ${ey}`} fill="none" stroke="#FFFFFF" strokeOpacity={0.14} strokeWidth={2.5} />;
			})}
			{/* 银河带：左下→右上 5 条半透明波浪 */}
			{milky.map(([off, sw, c, a], i) => (
				<path
					key={`mk${i}`}
					d={`M180 1000 Q340 ${830 + off} 470 ${740 - off * 0.5} Q600 ${650 + off * 0.5} 680 ${510 - off * 0.4} Q760 ${390 + off * 0.4} 820 260`}
					fill="none"
					stroke={c}
					strokeOpacity={a}
					strokeWidth={sw}
					strokeLinecap="round"
					strokeLinejoin="round"
				/>
			))}
			{/* 星 40 颗（白/黄交替） */}
			{Array.from({ length: 40 }, (_, i) => {
				const fx = ((i * 71) % 97) * 10 + 15;
				const fy = ((i * 37) % 88) * 10 + 20;
				return (
					<circle
						key={`st${i}`}
						cx={fx}
						cy={fy}
						r={1.5 + (i % 3) * 0.8}
						fill={i % 3 === 0 ? '#F9F871' : '#FFFFFF'}
						fillOpacity={0.5 + (i % 4) * 0.12}
					/>
				);
			})}
			{/* 流星 2 条（斜线 + 圆点） */}
			{([[560, 150, 130, 90, '#FFFFFF'], [240, 330, 120, 95, '#F9F871']] as Array<[number, number, number, number, string]>).map(
				([x, y, dx, dy, c], i) => (
					<g key={`mt${i}`}>
						<line x1={x - dx * 1.6} y1={y - dy * 1.6} x2={x} y2={y} stroke={c} strokeOpacity={0.25} strokeWidth={3} strokeLinecap="round" />
						<line x1={x} y1={y} x2={x + dx} y2={y + dy} stroke={c} strokeOpacity={0.7} strokeWidth={5} strokeLinecap="round" />
						<circle cx={x} cy={y} r={9} fill={c} />
					</g>
				)
			)}
			{/* 远景雪山 4 座（尖锐三角 + 山顶高光） */}
			{mounts.map(([px, py, hw], i) => (
				<g key={`mn${i}`}>
					<path d={`M${px - hw} 1000 L${px} ${py} L${px + hw} 1000 Z`} fill="#1B1B3A" />
					<path d={`M${px} ${py} L${px - hw * 0.18} ${py + 58} L${px - hw * 0.05} ${py + 52} Z`} fill="#BFE3E0" fillOpacity={0.9} />
				</g>
			))}
			{/* 中景湖面：水平线 + 倒影星点 */}
			{[1015, 1040, 1065, 1090, 1115].map((y, i) => {
				const half = 420 + i * 40;
				return <line key={`lk${i}`} x1={500 - half} y1={y} x2={500 + half} y2={y} stroke="#0F1B4A" strokeOpacity={0.85} strokeWidth={7} strokeLinecap="round" />;
			})}
			{Array.from({ length: 12 }, (_, i) => {
				const fx = 60 + ((i * 83) % 880);
				const fy = 1010 + ((i * 47) % 130);
				return <circle key={`rs${i}`} cx={fx} cy={fy} r={2.5} fill={i % 2 === 0 ? '#FFFFFF' : '#F9F871'} fillOpacity={0.7} />;
			})}
			{titleBars('#FFFFFF', [0.9, 0.6, 0.35], TITLE_STD)}
		</>
	);
}

/* ================= style 6：行星轨道与月球 ================= */

function CoverStyle6(): JSX.Element {
	const pcx = 620;
	const pcy = 520;
	const pr = 160;
	const orbitPt = (rx: number, ry: number, tilt: number, theta: number): [number, number] => {
		const th = theta * Math.PI / 180;
		const tx = rx * Math.cos(th);
		const ty = ry * Math.sin(th);
		const td = tilt * Math.PI / 180;
		const ct = Math.cos(td);
		const st = Math.sin(td);
		return [pcx + tx * ct - ty * st, pcy + tx * st + ty * ct];
	};
	const asteroids: Array<[number, number, number, number, number, number]> = [
		[300, 92, -20, 35, 18, 0],
		[390, 118, 0, 205, 24, 1],
		[480, 145, 20, 320, 14, 2],
		[390, 118, 0, 120, 12, 2],
		[480, 145, 20, 75, 21, 0]
	];
	const craters: Array<[number, number, number]> = [
		[255, 275, 22], [305, 262, 15], [285, 320, 18],
		[240, 325, 12], [315, 305, 11], [272, 352, 13]
	];
	return (
		<>
			{vGrad('s6sky', [[0, '#03030A'], [0.55, '#0A0A1A'], [1, '#1B1B3A']])}
			<rect width={1000} height={1120} fill="url(#s6sky)" />
			{/* 星 50 颗 */}
			{Array.from({ length: 50 }, (_, i) => {
				const fx = ((i * 83) % 97) * 10 + 10;
				const fy = ((i * 41) % 90) * 10 + 15;
				return (
					<circle
						key={`st${i}`}
						cx={fx}
						cy={fy}
						r={1.5 + (i % 3) * 0.8}
						fill={i % 4 === 0 ? '#F9F871' : '#FFFFFF'}
						fillOpacity={0.4 + (i % 4) * 0.13}
					/>
				);
			})}
			{/* 3 条椭圆轨道（倾斜 -20/0/20 度） */}
			{([[300, 92, -20], [390, 118, 0], [480, 145, 20]] as Array<[number, number, number]>).map(([rx, ry, tilt], i) => (
				<ellipse
					key={`or${i}`}
					cx={pcx}
					cy={pcy}
					rx={rx}
					ry={ry}
					fill="none"
					stroke="#FFFFFF"
					strokeOpacity={0.25}
					strokeWidth={4}
					transform={`rotate(${tilt} ${pcx} ${pcy})`}
				/>
			))}
			{/* 大行星（底色 + 横向条纹，圆内裁剪） */}
			<defs>
				<clipPath id="s6planet">
					<ellipse cx={pcx} cy={pcy} rx={pr} ry={pr} />
				</clipPath>
			</defs>
			<g clipPath="url(#s6planet)">
				<circle cx={pcx} cy={pcy} r={pr} fill="#4FA3C7" />
				{([[-0.45, '#7A5AF8'], [-0.1, '#F2C14E'], [0.25, '#7A5AF8'], [0.55, '#F2C14E']] as Array<[number, string]>).map(([t, c], i) => (
					<rect
						key={`sp${i}`}
						x={pcx - pr}
						y={pcy + pr * t - pr * 0.07}
						width={pr * 2}
						height={pr * 0.14}
						rx={20}
						ry={20}
						fill={c}
						fillOpacity={0.75}
					/>
				))}
			</g>
			{/* 5 颗小行星（落在轨道上） */}
			{asteroids.map((e, i) => {
				const [cx, cy] = orbitPt(e[0], e[1], e[2], e[3]);
				const c = e[5] === 0 ? '#E85D3F' : e[5] === 1 ? '#F9F871' : '#7FD1E8';
				return <circle key={`as${i}`} cx={cx} cy={cy} r={e[4]} fill={c} />;
			})}
			{/* 月球 + 6 个月坑 */}
			<circle cx={280} cy={300} r={80} fill="#D9E8E3" />
			{craters.map(([cx, cy, cr], i) => (
				<circle key={`cr${i}`} cx={cx} cy={cy} r={cr} fill="#A8B8B8" />
			))}
			{/* 极简宇航员剪影（scale 1.12，枢轴 760,980） */}
			<g transform="translate(760 980) scale(1.12) translate(-760 -980)">
				<circle cx={760} cy={884} r={21} fill="#FFFFFF" />
				<rect x={747} y={875} width={26} height={15} rx={7} ry={7} fill="#4FA3C7" />
				<rect x={743} y={906} width={34} height={46} rx={10} ry={10} fill="#FFFFFF" />
				<line x1={752} y1={952} x2={748} y2={980} stroke="#FFFFFF" strokeWidth={9} strokeLinecap="round" />
				<line x1={768} y1={952} x2={772} y2={980} stroke="#FFFFFF" strokeWidth={9} strokeLinecap="round" />
				<line x1={743} y1={916} x2={728} y2={938} stroke="#FFFFFF" strokeWidth={8} strokeLinecap="round" />
				<line x1={777} y1={916} x2={792} y2={938} stroke="#FFFFFF" strokeWidth={8} strokeLinecap="round" />
				<rect x={779} y={908} width={13} height={34} rx={4} ry={4} fill="#FFFFFF" fillOpacity={0.85} />
			</g>
			{titleBars('#FFFFFF', [0.9, 0.6, 0.35], TITLE_STD)}
		</>
	);
}

/* ================= style 7：深空星云与星际列车剪影 ================= */

function CoverStyle7(): JSX.Element {
	const nebs: Array<[number, number, number, string]> = [
		[210, 220, 1.2, '#7A5AF8'], [140, 340, 0.8, '#4FA3C7'], [310, 150, 0.7, '#FF3D81'],
		[870, 740, 1.1, '#7A5AF8'], [790, 850, 0.8, '#FF3D81'], [930, 660, 0.7, '#4FA3C7']
	];
	const comets: Array<[number, number, number, number]> = [[640, 120, 150, 60], [360, 90, 140, 70], [945, 390, 130, 80]];
	const carW = 118;
	const carH = 112;
	const carY = 896;
	return (
		<>
			{vGrad('s7sky', [[0, '#050510'], [0.55, '#2B1B4E'], [1, '#0F1B4A']])}
			<rect width={1000} height={1120} fill="url(#s7sky)" />
			{/* 星云 6 团（四层椭圆叠加） */}
			{nebs.map(([cx, cy, s, c], i) => (
				<g key={`nb${i}`} fill={c}>
					<ellipse cx={cx} cy={cy} rx={175 * s} ry={100 * s} fillOpacity={0.12} />
					<ellipse cx={cx} cy={cy} rx={130 * s} ry={70 * s} fillOpacity={0.22} />
					<ellipse cx={cx + 15 * s} cy={cy - 45 * s} rx={75 * s} ry={60 * s} fillOpacity={0.2} />
					<ellipse cx={cx + 30 * s} cy={cy + 25 * s} rx={60 * s} ry={45 * s} fillOpacity={0.16} />
				</g>
			))}
			{/* 星 70 颗 */}
			{Array.from({ length: 70 }, (_, i) => {
				const fx = ((i * 89) % 98) * 10 + 8;
				const fy = ((i * 47) % 92) * 10 + 10;
				return (
					<circle
						key={`st${i}`}
						cx={fx}
						cy={fy}
						r={1 + (i % 3) * 1}
						fill={i % 4 === 0 ? '#F9F871' : '#FFFFFF'}
						fillOpacity={0.35 + (i % 4) * 0.14}
					/>
				);
			})}
			{/* 带环行星：椭圆环（旋转 -16°）+ 行星 + 亮弧 */}
			<ellipse cx={855} cy={185} rx={125} ry={30} fill="none" stroke="#F2C14E" strokeOpacity={0.9} strokeWidth={10} transform="rotate(-16 855 185)" />
			<circle cx={855} cy={185} r={70} fill="#4FA3C7" />
			<path d="M980 185 A125 30 0 0 1 730 185" fill="none" stroke="#F2C14E" strokeWidth={12} transform="rotate(-16 855 185)" />
			{/* 流星 3 条（渐变尾巴多边形） */}
			<defs>
				{comets.map(([, , dx, dy], i) => (
					<linearGradient
						key={`cg${i}`}
						id={`s7comet${i}`}
						gradientUnits="userSpaceOnUse"
						x1={comets[i][0]}
						y1={comets[i][1]}
						x2={comets[i][0] - dx}
						y2={comets[i][1] - dy}
					>
						<stop offset="0" stopColor="#FFFFFF" stopOpacity="0.85" />
						<stop offset="1" stopColor="#FFFFFF" stopOpacity="0" />
					</linearGradient>
				))}
			</defs>
			{comets.map(([x, y, dx, dy], i) => (
				<g key={`cm${i}`}>
					<path d={`M${x} ${y} L${x - dx} ${y - dy * 0.72} L${x - dx * 0.82} ${y - dy * 1.1} Z`} fill={`url(#s7comet${i})`} />
					<circle cx={x} cy={y} r={9} fill="#FFFFFF" />
				</g>
			))}
			{/* 轨道两条平行曲线 */}
			<path d="M120 1030 Q500 1014 880 1030" fill="none" stroke="#7FD1E8" strokeOpacity={0.5} strokeWidth={7} strokeLinecap="round" strokeLinejoin="round" />
			<path d="M120 1046 Q500 1030 880 1046" fill="none" stroke="#7FD1E8" strokeOpacity={0.5} strokeWidth={5} strokeLinecap="round" strokeLinejoin="round" />
			{/* 星际列车剪影（4 车厢 + 连接 + 流线车头） */}
			{Array.from({ length: 4 }, (_, i) => {
				const cx = 185 + i * (carW + 14);
				return (
					<g key={`car${i}`}>
						<rect x={cx} y={carY} width={carW} height={carH} rx={18} ry={18} fill="#0A0A1A" />
						{Array.from({ length: 3 }, (_, k) => (
							<g key={`w${k}`}>
								<rect x={cx + 10 + k * 34} y={carY + 20} width={32} height={36} fill="#F9F871" fillOpacity={0.25} />
								<rect x={cx + 14 + k * 34} y={carY + 24} width={24} height={28} fill="#F9F871" />
							</g>
						))}
					</g>
				);
			})}
			{Array.from({ length: 4 }, (_, i) => {
				const lx = 185 + carW + i * (carW + 14);
				return <rect key={`lk${i}`} x={lx} y={carY + 44} width={14} height={16} fill="#0A0A1A" />;
			})}
			<path d="M699 1008 L699 896 Q788 900 822 962 Q832 988 824 1008 Z" fill="#0A0A1A" />
			<rect x={742} y={916} width={34} height={20} fill="#F9F871" />
			{titleBars('#FFFFFF', [0.9, 0.6, 0.35], TITLE_STD)}
		</>
	);
}

/* ================= style 8：海岸灯塔与浪花 ================= */

function CoverStyle8(): JSX.Element {
	const birds: Array<[number, number, number]> = [[180, 260, 1], [300, 330, 0.8], [470, 250, 1.1], [560, 370, 0.7], [390, 430, 0.9]];
	const splashes: Array<[number, number]> = [[150, 906], [430, 956], [660, 1002], [250, 1048], [520, 1092]];
	// 波浪路径（Kotlin while 循环，算术原样保留）
	const wave = (y: number, amp: number): string => {
		let d = `M-20 ${y}`;
		let x = -20;
		while (x < 1020) {
			d += ` Q${x + 45} ${y - amp} ${x + 90} ${y}`;
			x += 90;
		}
		return `${d} L1020 1160 L-20 1160 Z`;
	};
	const splashArc = (cx: number, cy: number): string => {
		const sx = cx + 30 * Math.cos(-Math.PI / 3);
		const sy = cy - 9 + 17 * Math.sin(-Math.PI / 3);
		const ex = cx + 30 * Math.cos(Math.PI / 3);
		const ey = cy - 9 + 17 * Math.sin(Math.PI / 3);
		return `M${sx} ${sy} A30 17 0 0 1 ${ex} ${ey}`;
	};
	return (
		<>
			{vGrad('s8sky', [[0, '#2B1B4E'], [0.5, '#E85D3F'], [1, '#F2C14E']])}
			<rect width={1000} height={1120} fill="url(#s8sky)" />
			{/* 太阳（右上） */}
			<circle cx={820} cy={190} r={70} fill="#FFB347" />
			{/* 海鸥 5 只 */}
			{birds.map(([cx, cy, s], i) => (
				<path key={`bi${i}`} d={`M${cx - 26 * s} ${cy} L${cx} ${cy + 16 * s} L${cx + 26 * s} ${cy}`} fill="none" stroke="#FFFFFF" strokeOpacity={0.85} strokeWidth={7 * s} strokeLinecap="round" strokeLinejoin="round" />
			))}
			{/* 远景海平线 y=820 */}
			<rect x={0} y={820} width={1000} height={100} fill="#1E4D6B" fillOpacity={0.45} />
			<line x1={0} y1={820} x2={1000} y2={820} stroke="#1E4D6B" strokeWidth={10} strokeLinecap="round" />
			{/* 中景悬崖（右下阶梯多边形 + 顶面高光） */}
			<path d="M600 1160 L640 900 L660 760 L800 760 L840 830 L900 830 L900 870 L1000 870 L1000 1160 Z" fill="#4A3B2E" />
			<rect x={660} y={760} width={140} height={14} fill="#6B5A3F" />
			<rect x={840} y={830} width={60} height={12} fill="#6B5A3F" />
			<rect x={900} y={870} width={100} height={12} fill="#6B5A3F" />
			{/* 灯光（两把淡黄扇形，先画在塔后） */}
			<path d="M700 447 L240 368 L240 532 Z" fill="#F9F871" fillOpacity={0.35} />
			<path d="M700 447 L1000 358 L1000 542 Z" fill="#F9F871" fillOpacity={0.35} />
			{/* 灯塔塔身：红白相间横纹（梯形裁剪） */}
			<defs>
				<clipPath id="s8tower">
					<path d="M652 482 L748 482 L762 760 L638 760 Z" />
				</clipPath>
			</defs>
			<g clipPath="url(#s8tower)">
				<rect x={630} y={470} width={140} height={300} fill="#FFFFFF" />
				{[500, 545, 590, 635, 680, 725].map((y, i) => (
					<rect key={`sr${i}`} x={630} y={y} width={140} height={40} fill="#E85D3F" />
				))}
			</g>
			{/* 灯室：廊台 + 梯形室 + 灯窗 + 半圆顶 */}
			<rect x={642} y={468} width={116} height={16} fill="#4A3B2E" />
			<path d="M664 424 L736 424 L748 468 L652 468 Z" fill="#4A3B2E" />
			<rect x={674} y={430} width={52} height={32} fill="#F9F871" />
			<path d="M658 424 A42 22 0 0 1 742 424 Z" fill="#E85D3F" />
			{/* 近景海浪 6 层 */}
			<path d={wave(905, 12)} fill="#4FA3C7" />
			<path d={wave(955, 14)} fill="#7FD1E8" />
			<path d={wave(1000, 12)} fill="#4FA3C7" />
			<path d={wave(1045, 15)} fill="#7FD1E8" />
			<path d={wave(1090, 12)} fill="#FFFFFF" fillOpacity={0.85} />
			<path d={wave(1130, 14)} fill="#4FA3C7" />
			{/* 浪花（圆形 + 喷溅弧） */}
			{splashes.map(([cx, cy], i) => (
				<g key={`sw${i}`}>
					<circle cx={cx} cy={cy} r={13} fill="#FFFFFF" fillOpacity={0.9} />
					<circle cx={cx + 24} cy={cy - 8} r={8} fill="#FFFFFF" fillOpacity={0.7} />
					<path d={splashArc(cx, cy)} fill="none" stroke="#FFFFFF" strokeOpacity={0.7} strokeWidth={6} />
				</g>
			))}
			{titleBars('#2B1B4E', [0.85, 0.6, 0.4], TITLE_STD)}
		</>
	);
}

/* ================= style 9：沙漠绿洲月牙泉 ================= */

function CoverStyle9(): JSX.Element {
	const birds: Array<[number, number, number]> = [[300, 170, 1], [430, 235, 0.8]];
	const dunes: Array<[number, number]> = [[700, 60], [760, -40], [820, 50], [880, -50], [940, 40]];
	const camels: Array<[number, number, number]> = [[715, 1030, 1], [792, 1040, 0.92], [862, 1028, 0.85]];
	const cactus = '#2F6F4A';
	return (
		<>
			{vGrad('s9sky', [[0, '#7FA8C9'], [0.5, '#F2C14E'], [1, '#E8A65D']])}
			<rect width={1000} height={1120} fill="url(#s9sky)" />
			{/* 淡日轮（左上） */}
			<circle cx={170} cy={150} r={52} fill="#F2C14E" fillOpacity={0.35} />
			<circle cx={170} cy={150} r={32} fill="#F2C14E" fillOpacity={0.8} />
			{/* 飞鸟 2 只 */}
			{birds.map(([cx, cy, s], i) => (
				<path key={`bi${i}`} d={`M${cx - 26 * s} ${cy} L${cx} ${cy + 16 * s} L${cx + 26 * s} ${cy}`} fill="none" stroke="#A65E2E" strokeOpacity={0.8} strokeWidth={7 * s} strokeLinecap="round" strokeLinejoin="round" />
			))}
			{/* 远景沙丘 5 层（#D9A066 → #A65E2E 插值） */}
			{dunes.map(([y, bow], i) => (
				<path
					key={`du${i}`}
					d={`M0 ${y + 30} Q250 ${y - 60 + bow} 520 ${y + 10} Q760 ${y + 60 - bow} 1000 ${y - 20} L1000 1160 L0 1160 Z`}
					fill={lerpHex('#D9A066', '#A65E2E', i / 4)}
					fillOpacity={0.8}
				/>
			))}
			{/* 月牙泉（月牙形 + 边缘高光） */}
			<path d="M400 828 Q405 962 530 978 Q655 962 660 828 Q612 918 530 922 Q448 918 400 828 Z" fill="#4FA3C7" />
			<path d="M405 845 Q415 955 530 972 Q645 955 655 845" fill="none" stroke="#BFE3E0" strokeWidth={7} strokeLinecap="round" strokeLinejoin="round" />
			{/* 仙人掌（三圆角矩形 + 两分支） */}
			<rect x={198} y={752} width={40} height={228} rx={19} ry={19} fill={cactus} />
			<rect x={158} y={830} width={48} height={22} rx={11} ry={11} fill={cactus} />
			<rect x={158} y={778} width={22} height={72} rx={11} ry={11} fill={cactus} />
			<rect x={232} y={792} width={44} height={22} rx={11} ry={11} fill={cactus} />
			<rect x={254} y={738} width={22} height={76} rx={11} ry={11} fill={cactus} />
			{/* 近景沙丘（大弧线分割） */}
			<path d="M0 1160 L0 1030 Q260 985 520 1075 L560 1160 Z" fill="#E8A65D" />
			<path d="M440 1160 Q700 1000 1000 1045 L1000 1160 Z" fill="#C97B3A" />
			{/* 骆驼队 3 只 */}
			{camels.map(([cx, cy, s], i) => (
				<g key={`cm${i}`} fill="#6B4◆" stroke="#6B4◆">
					<rect x={cx - 26 * s} y={cy - 28 * s} width={52 * s} height={24 * s} rx={12 * s} ry={12 * s} strokeWidth={0} stroke="none" />
					<circle cx={cx - 4 * s} cy={cy - 32 * s} r={14 * s} strokeWidth={0} stroke="none" />
					<line x1={cx + 20 * s} y1={cy - 20 * s} x2={cx + 30 * s} y2={cy - 44 * s} strokeWidth={10 * s} strokeLinecap="round" fill="none" />
					<rect x={cx + 26 * s} y={cy - 55 * s} width={18 * s} height={13 * s} rx={5 * s} ry={5 * s} strokeWidth={0} stroke="none" />
					<line x1={cx - 16 * s} y1={cy - 6 * s} x2={cx - 16 * s} y2={cy + 22 * s} strokeWidth={9 * s} strokeLinecap="round" fill="none" />
					<line x1={cx + 12 * s} y1={cy - 6 * s} x2={cx + 12 * s} y2={cy + 22 * s} strokeWidth={9 * s} strokeLinecap="round" fill="none" />
				</g>
			))}
			{titleBars('#FFFFFF', [0.9, 0.6, 0.35], TITLE_DESERT)}
		</>
	);
}

/* ================= style 10：雨林瀑布与彩虹 ================= */

function CoverStyle10(): JSX.Element {
	const canopy: Array<[number, number, number, number]> = [
		[90, 240, 130, 90], [260, 180, 150, 100], [430, 230, 120, 85],
		[600, 170, 140, 95], [780, 230, 130, 88], [930, 190, 110, 90],
		[40, 400, 120, 95], [950, 420, 120, 95], [170, 350, 110, 80], [840, 350, 105, 78]
	];
	const rainbow = ['#E85D3F', '#F2C14E', '#F9F871', '#7FD1E8', '#7A5AF8'];
	const leaves: Array<[number, number, number, number, number, string]> = [
		[20, 1160, 330, 930, -60, '#1F6F5A'],
		[10, 1160, 250, 830, -30, '#2F8F6A'],
		[30, 1160, 120, 780, 40, '#7FD1B8'],
		[40, 1160, 420, 1010, -80, '#2F8F6A'],
		[980, 1160, 660, 950, 60, '#1F6F5A'],
		[990, 1160, 750, 850, 30, '#2F8F6A'],
		[970, 1160, 890, 800, -40, '#7FD1B8'],
		[985, 1160, 580, 1030, 80, '#2F8F6A']
	];
	return (
		<>
			{vGrad('s10sky', [[0, '#0B3A2F'], [0.5, '#1F6F5A'], [1, '#7FD1B8']])}
			<rect width={1000} height={1120} fill="url(#s10sky)" />
			{/* 远景雨林树冠 10 团 */}
			{canopy.map(([cx, cy, rx, ry], i) => (
				<ellipse key={`cn${i}`} cx={cx} cy={cy} rx={rx} ry={ry} fill="#0F4A3A" fillOpacity={0.75} />
			))}
			{/* 彩虹（瀑布左侧，5 条同心弧 0.45） */}
			{rainbow.map((c, i) => {
				const r = 235 - i * 23;
				return (
					<path
						key={`rb${i}`}
						d={`M${225 - r} 900 A${r} ${r} 0 0 1 ${225 + r} 900`}
						fill="none"
						stroke={c}
						strokeOpacity={0.45}
						strokeWidth={12}
					/>
				);
			})}
			{/* 瀑布 6 条垂直波浪 */}
			{Array.from({ length: 6 }, (_, k) => {
				const bx = 445 + k * 30;
				const c = k % 2 === 0 ? '#BFE3E0' : '#FFFFFF';
				return (
					<path
						key={`wf${k}`}
						d={`M${bx} 260 Q${bx + 30} 420 ${bx - 22} 590 Q${bx - 40} 760 ${bx + 14} 980`}
						fill="none"
						stroke={c}
						strokeOpacity={0.85}
						strokeWidth={k % 2 === 0 ? 22 : 12}
						strokeLinecap="round"
						strokeLinejoin="round"
					/>
				);
			})}
			{/* 瀑布底部水潭 */}
			<ellipse cx={520} cy={1045} rx={340} ry={60} fill="#4FA3C7" fillOpacity={0.8} />
			{[1030, 1065, 1100].map((y, i) => (
				<line key={`pl${i}`} x1={220} y1={y} x2={820} y2={y} stroke="#4FA3C7" strokeWidth={8} strokeLinecap="round" />
			))}
			{/* 近景热带大叶 8 片 */}
			{leaves.map(([bx, by, tx, ty, bow, c], i) => (
				<path
					key={`lf${i}`}
					d={`M${bx} ${by} Q${bx + (tx - bx) * 0.3 + bow} ${by + (ty - by) * 0.4} ${tx} ${ty} Q${bx + (tx - bx) * 0.55 + bow} ${by + (ty - by) * 0.75} ${bx} ${by} Z`}
					fill={c}
				/>
			))}
			{/* 鹦鹉（右侧树枝，极简几何剪影） */}
			<line x1={1000} y1={640} x2={845} y2={662} stroke="#1F6F5A" strokeWidth={12} strokeLinecap="round" />
			<path d="M878 648 L842 700 L886 660 Z" fill="#E85D3F" />
			<ellipse cx={889} cy={626} rx={17} ry={26} fill="#E85D3F" />
			<circle cx={893} cy={594} r={13} fill="#E85D3F" />
			<path d="M905 590 L918 596 L904 601 Z" fill="#F2C14E" />
			<ellipse cx={888} cy={627} rx={12} ry={15} fill="#F2C14E" />
			{titleBars('#0B3A2F', [0.85, 0.6, 0.4], TITLE_STD)}
		</>
	);
}

/* ================= style 90：自定义文字平铺 ================= */

function CoverStyle90(base: string, text: string, layout: number): JSX.Element {
	const bg = (
		<>
			{vGrad('s90bg', [[0, lerpHex(base, '#FFFFFF', 0.1)], [0.5, base], [1, lerpHex(base, '#000000', 0.1)]])}
			<rect width={1000} height={1120} fill="url(#s90bg)" />
		</>
	);
	const content = text.trim() === '' ? '笔记本' : text;
	// Compose rememberTextMeasurer(fontSize 40) 的离屏画布近似
	const ctx = document.createElement('canvas').getContext('2d')!;
	const measure = (t: string, fontSize: number): number => {
		ctx.font = `bold ${fontSize}px ${FONT}`;
		return ctx.measureText(t).width;
	};
	const m0 = measure(content, 40);
	if (layout === 3) {
		// 居中大字（宽 74%）
		const s2 = (1000 * 0.74) / Math.max(m0, 1);
		const bigSize = 40 * s2;
		return (
			<>
				{bg}
				<text
					x={500}
					y={560}
					fontFamily={FONT}
					fontSize={bigSize}
					fontWeight={700}
					textAnchor="middle"
					dominantBaseline="middle"
					fill="#FFFFFF"
					fillOpacity={0.92}
				>
					{content}
				</text>
			</>
		);
	}
	// 平铺：0 网格 / 1 错行（砖墙）/ 2 斜向（-18°）
	const s1 = (1000 * 0.42) / Math.max(m0, 1);
	const fontSize = 40 * s1;
	const tw = Math.max(measure(content, fontSize), 1);
	const th = fontSize * 1.16;
	const rot = layout === 2 ? 'rotate(-18 500 560)' : undefined;
	const stamps: JSX.Element[] = [];
	let y = -th;
	let row = 0;
	while (y < 1120 + th) {
		const off = layout === 1 && row % 2 === 1 ? tw * 0.55 : 0;
		let x = -tw + off;
		while (x < 1000 + tw) {
			stamps.push(
				<text
					key={`r${row}-${x}`}
					x={x}
					y={y}
					fontFamily={FONT}
					fontSize={fontSize}
					fontWeight={700}
					dominantBaseline="hanging"
					fill="#FFFFFF"
					fillOpacity={0.55}
					transform={rot}
				>
					{content}
				</text>
			);
			x += tw * 1.3;
		}
		y += th * 2.05;
		row++;
	}
	return (
		<>
			{bg}
			{stamps}
		</>
	);
}

/* ================= else（style 0）：素色渐变 ================= */

function CoverStyleSolid(base: string): JSX.Element {
	return (
		<>
			{vGrad('s0bg', [[0, lerpHex(base, '#FFFFFF', 0.1)], [0.5, base], [1, lerpHex(base, '#000000', 0.1)]])}
			<rect width={1000} height={1120} fill="url(#s0bg)" />
		</>
	);
}

/* ================= 组件 ================= */

export default function NotebookCover(props: {
	coverStyle: number;
	coverHue: number;
	coverText?: string;
	coverLayout?: number;
	className?: string;
	style?: CSSProperties;
}): JSX.Element {
	const { coverStyle, coverHue, coverText = '', coverLayout = 0, className, style } = props;
	const base = ART_PALETTE[Math.abs(Math.floor(coverHue)) % 6];
	let art: JSX.Element;
	switch (coverStyle) {
		case 1: art = <CoverStyle1 />; break;
		case 2: art = <CoverStyle2 />; break;
		case 3: art = <CoverStyle3 />; break;
		case 4: art = <CoverStyle4 />; break;
		case 5: art = <CoverStyle5 />; break;
		case 6: art = <CoverStyle6 />; break;
		case 7: art = <CoverStyle7 />; break;
		case 8: art = <CoverStyle8 />; break;
		case 9: art = <CoverStyle9 />; break;
		case 10: art = <CoverStyle10 />; break;
		case 90: art = CoverStyle90(base, coverText, coverLayout); break;
		default: art = CoverStyleSolid(base);
	}
	return (
		<div
			className={className}
			style={{ position: 'relative', overflow: 'hidden', borderRadius: '14px', ...style }}
		>
			<svg viewBox="0 0 1000 1120" preserveAspectRatio="none" width="100%" height="100%" style={{ display: 'block' }}>
				{art}
				{/* —— 统一装订线：窄影 + 细线（1.5 设备像素，不随缩放）—— */}
				<linearGradient id="crease" x1="0" y1="0" x2="1" y2="0">
					<stop offset="0" stopColor="#000000" stopOpacity="0.1" />
					<stop offset="1" stopColor="#000000" stopOpacity="0" />
				</linearGradient>
				<rect x={120} y={0} width={28} height={1120} fill="url(#crease)" />
				<line x1={120} y1={0} x2={120} y2={1120} stroke="#000000" strokeOpacity={0.2} strokeWidth={1.5} vectorEffect="non-scaling-stroke" />
			</svg>
		</div>
	);
}
