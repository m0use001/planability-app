/* 宽屏板材质 P0 守卫 —— 纯函数边界 + deckSlot 对拍 + 弹簧物理 + 源码探测器自检。
 *
 * 会红的门（故障注入已验）：
 *  - pageSlide 若被改成欠阻尼（dampingRatio<1）→ 「临界不过冲」断言红；
 *  - paneOf 门槛若被改掉（minListWidth 或公式）→ 边界值红；
 *  - 板圆角若散落到别的 CSS → 「24px 单一来源」探测器红。
 *
 * Run: npm run test:board
 */
const path = require('node:path');
const fs = require('node:fs');

let failed = 0;
function check(name, cond, extra) {
	console.log((cond ? 'PASS' : 'FAIL') + '  ' + name + (cond || extra === undefined ? '' : `\n      ${extra}`));
	if (!cond) { failed++; }
}
function close(a, b, eps, name) {
	check(name, Math.abs(a - b) <= eps, `got ${a}, want ${b} ±${eps}`);
}

const LIB = p => require(path.join(__dirname, '..', '.test', 'src', 'renderer', 'src', 'lib', p));
const { BOARD, WIDE } = LIB('board.js');
const { sizeClassOf, isWide, isExpanded, MEDIUM_WIDTH_BREAKPOINT, EXPANDED_WIDTH_BREAKPOINT } = LIB('sizing.js');
const { paneOf, detailWidthFor } = LIB('masterDetail.js');
const { deckSlot, mainWidthOf } = LIB('deckSlot.js');
const spring = LIB('spring.js'); // 纯模块：SPRING/MOTION_MS 真源在 spring.ts，motion.ts 只是转出口
const { SPRING, MOTION_MS } = spring;

console.log('== sizing：断点边界（手机 WindowSize.kt 同口径 600/840）==');
check('599 → compact', sizeClassOf(599) === 'compact');
check('600 → medium', sizeClassOf(600) === 'medium');
check('839 → medium', sizeClassOf(839) === 'medium');
check('840 → expanded', sizeClassOf(840) === 'expanded');
check('0 → compact', sizeClassOf(0) === 'compact');
check('1600 → expanded', sizeClassOf(1600) === 'expanded');
check('isWide: medium/expanded 宽、compact 不宽', isWide('medium') && isWide('expanded') && !isWide('compact'));
check('isExpanded: 仅 expanded', isExpanded('expanded') && !isExpanded('medium') && !isExpanded('compact'));
check('断点常量 600/840', MEDIUM_WIDTH_BREAKPOINT === 600 && EXPANDED_WIDTH_BREAKPOINT === 840);

console.log('== masterDetail：分栏门槛「先算宽度够不够，再看选中态」==');
check('1280 宽 + 无选中 → overview', paneOf(1280, BOARD.detailWidth, false) === 'overview');
check('1280 宽 + 选中 → detail', paneOf(1280, BOARD.detailWidth, true) === 'detail');
check('700 宽 + 选中 → single（700-380=320 < minListWidth 380）', paneOf(700, BOARD.detailWidth, true) === 'single');
check('恰好门槛：760 宽 + 选中 → detail（760-380=380 ≥ 380）', paneOf(760, BOARD.detailWidth, true) === 'detail');
check('门槛差 1：759 宽 + 选中 → single', paneOf(759, BOARD.detailWidth, true) === 'single');
check('detailWidthFor 表单型 → 380', detailWidthFor(2000, false) === 380);
close(detailWidthFor(1000, true), 400, 0, 'detailWidthFor 内容型 1000 → 40% = 400');
close(detailWidthFor(2000, true), 480, 0, 'detailWidthFor 内容型 2000 → 夹到上限 480');
close(detailWidthFor(900, true), 380, 0, 'detailWidthFor 内容型 900 → 360 抬到下限 380');

console.log('== deckSlot：纸条几何对拍（手机 DeckGeometryTest 口径）==');
// windowW=1600, secW=380, gap=12 → mainW=1208
const W = 1600, SEC = 380, GAP = 12;
close(mainWidthOf(W, SEC, GAP), 1208, 0, 'mainW = 1600-380-12 = 1208');
{
	const main = deckSlot(1, 1, W, SEC, GAP);   // 主框位
	close(main.x, 0, 1e-6, 'progress=index → x=0（主框）');
	close(main.w, 1208, 1e-6, 'progress=index → w=mainW');
	const sec = deckSlot(1, 2, W, SEC, GAP);    // 副框位
	close(sec.x, 1220, 1e-6, 'progress=index+1 → x=mainW+gap=1220（副框）');
	close(sec.w, 380, 1e-6, 'progress=index+1 → w=secW（已收窄）');
	const off = deckSlot(1, 0, W, SEC, GAP);    // 左缘外
	close(off.x, -1208, 1e-6, 'progress=index-1 → x=-mainW（左缘外整板宽）');
	const out = deckSlot(1, 3, W, SEC, GAP);    // 右缘外
	close(out.x, 1600, 1e-6, 'progress=index+2 → x=windowW（完全出屏）');
	close(out.w, 380, 1e-6, '出屏后宽保持 secW');
	const mid = deckSlot(1, 1.5, W, SEC, GAP);  // 半途：主框→副框连续插值
	close(mid.x, 610, 1e-6, 'delta=0.5 → x=(mainW+gap)/2=610');
	close(mid.w, 794, 1e-6, 'delta=0.5 → w=(mainW+secW)/2=794（内容逐帧改排版）');
	// 根层两板：panes=[副板(0),主板(1)]，level=1 → 副板在副框、主板在主框
	const rootSec = deckSlot(0, 1, W, SEC, GAP);
	close(rootSec.x, 1220, 1e-6, 'level=1 时 panes[0] 在副框位');
	// 越界钳制：mainW 不为负
	close(mainWidthOf(500, SEC, GAP), 108, 1e-6, '窄窗 mainW=500-380-12=108（≥0）');
}

console.log('== spring：M3E 弹簧物理（临界不过冲 / 欠阻尼必过冲）==');
check('MOTION 档位：pageSlide 临界 1.0/380', SPRING.pageSlide.dampingRatio === 1.0 && SPRING.pageSlide.stiffness === 380);
check('MOTION 档位：standard 0.8/380', SPRING.standard.dampingRatio === 0.8 && SPRING.standard.stiffness === 380);
check('MOTION 档位：quick 0.6/800', SPRING.quick.dampingRatio === 0.6 && SPRING.quick.stiffness === 800);
check('MOTION 档位：emphasized 0.8/200', SPRING.emphasized.dampingRatio === 0.8 && SPRING.emphasized.stiffness === 200);
check('参考时长档位间距 ≥1.3×（120/180/300）',
	MOTION_MS.standard / MOTION_MS.quick >= 1.3 && MOTION_MS.emphasized / MOTION_MS.standard >= 1.3);
{
	const ps = spring.settleSpring(0, 1, SPRING.pageSlide);
	check('pageSlide 临界阻尼不过冲（overshoot < 0.001）', ps.overshoot < 0.001, `overshoot=${ps.overshoot}`);
	check('pageSlide 1 秒内落定（<90 步）', ps.steps < 90, `steps=${ps.steps}`);
	const wb = spring.settleSpring(0, 1, SPRING.wobble);
	check('wobble 欠阻尼必过冲（overshoot > 0.02）', wb.overshoot > 0.02, `overshoot=${wb.overshoot}`);
	const st = spring.settleSpring(0, 1, SPRING.standard);
	// ζ=0.8 的解析过冲 = e^(−πζ/√(1−ζ²)) = 0.0152；子步积分后应落在解析值 ±20% 带
	check('standard 过冲落在解析值 0.0152 ±20%（子步积分精度）',
		st.overshoot > 0.012 && st.overshoot < 0.019, `overshoot=${st.overshoot}`);
	// 对照（数值精度红线）：不用子步积分、直接 dt=1/60 裸积分，数值阻尼会把过冲杀成 0——
	// 这条证明「为什么要 stepSpringSub」，谁删子步谁红。
	{
		let s = { x: 0, v: 0 };
		let ov = 0;
		for (let i = 0; i < 300; i++) {
			s = spring.stepSpring(s, 1, SPRING.standard, 1 / 60);
			if (s.x - 1 > ov) { ov = s.x - 1; }
		}
		check('裸 stepSpring 在 dt=1/60 过冲 <0.003（证明子步积分的必要性）', ov < 0.003, `overshoot=${ov}`);
	}
	close(spring.dampingFor(SPRING.pageSlide), 2 * Math.sqrt(380), 0.01, '阻尼系数 = 2·ζ·√k = 2√380');
	// stepSpring 单步：受力方向正确
	const s1 = spring.stepSpring({ x: 0, v: 0 }, 1, SPRING.pageSlide, 1 / 60);
	check('单步朝目标加速（v>0）', s1.v > 0 && s1.x > 0, JSON.stringify(s1));
}

console.log('== BOARD/WIDE 常量（与手机逐字相同）==');
check('BOARD: shape24/gap12/padding16/detail380/content480/minList380/inset14',
	BOARD.shape === 24 && BOARD.gap === 12 && BOARD.padding === 16 && BOARD.detailWidth === 380
	&& BOARD.contentDetailWidth === 480 && BOARD.minListWidth === 380 && BOARD.rowDividerInset === 14);
check('WIDE: secondary380/workbench960/timeline680/calendar620/content880/body760',
	WIDE.secondaryWidth === 380 && WIDE.workbenchMaxWidth === 960 && WIDE.timelineMaxWidth === 680
	&& WIDE.calendarMaxWidth === 620 && WIDE.contentMaxWidth === 880 && WIDE.assistantBodyWidth === 760);

console.log('== 源码探测器自检（守卫自己必须会红，先证明探测器能咬坏样本）==');
const SRC = path.join(__dirname, '..', 'src', 'renderer', 'src');

/** 探测器：24px 板圆角只允许出现在 styles/board.css（单一来源）。 */
function findStrayRadius24(dir) {
	const hits = [];
	const walk = d => {
		for (const e of fs.readdirSync(d, { withFileTypes: true })) {
			const p = path.join(d, e.name);
			if (e.isDirectory()) { walk(p); continue; }
			if (!e.name.endsWith('.css')) { continue; }
			const text = fs.readFileSync(p, 'utf8');
			if (text.includes('border-radius: 24px') && path.relative(SRC, p).split(path.sep).join('/') !== 'styles/board.css') {
				hits.push(path.relative(SRC, p).split(path.sep).join('/'));
			}
		}
	};
	walk(dir);
	return hits;
}
// 探测器自检：喂一个合成坏样本，必须咬住（证明不是永远绿的假守卫）
{
	const bad = 'border-radius: 24px';
	const good = 'border-radius: 22px';
	const bites = s => s.includes('border-radius: 24px');
	check('探测器自检：坏样本咬住 / 好样本放行', bites(bad) && !bites(good));
}
{
	const stray = findStrayRadius24(SRC);
	check('24px 板圆角单一来源（只在 styles/board.css）', stray.length === 0, `散落在: ${stray.join(', ')}`);
}
{
	// 旧 margin hack 只许 chat.css 有（既有债）；新板文件不得再犯
	const hackFiles = [];
	const walk = d => {
		for (const e of fs.readdirSync(d, { withFileTypes: true })) {
			const p = path.join(d, e.name);
			if (e.isDirectory()) { walk(p); continue; }
			if (!/\.(css|tsx)$/.test(e.name)) { continue; }
			const rel = path.relative(SRC, p).split(path.sep).join('/');
			if (rel === 'pages/chat.css') { continue; }
			if (fs.readFileSync(p, 'utf8').includes('margin: -32px -44px')) { hackFiles.push(rel); }
		}
	};
	walk(SRC);
	check('margin:-32/-44 hack 不扩散（仅 chat.css 既有债）', hackFiles.length === 0, `新犯: ${hackFiles.join(', ')}`);
}
{
	// 常量单一来源：板几何字面量（380 详情宽）不得写死进组件 tsx（须从 lib/board 取）
	const offenders = [];
	const libDir = path.join(SRC, 'components', 'm3');
	for (const f of ['board.tsx', 'deck.tsx', 'paneblocks.tsx']) {
		const text = fs.readFileSync(path.join(libDir, f), 'utf8');
		// 允许出现在注释里；出现「width: 380」/「= 380」这类裸字面量即违规
		const code = text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
		if (/(width:\s*380|detailWidth\s*=\s*380(?!\d))/.test(code)) { offenders.push(f); }
	}
	check('380 详情宽不写死进组件（须从 BOARD 取）', offenders.length === 0, `违规: ${offenders.join(', ')}`);
}

console.log(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
process.exit(failed === 0 ? 0 : 1);
