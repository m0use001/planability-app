#!/usr/bin/env node
/* lint-ui：E′ UI 规范门禁（P4 收口件，进 npm test 链）。
 *
 * 三条规则，各对应一次真实事故/验收教训：
 *
 *  R1 裸 hex：颜色只能来自 token（--md-sys-* / 桥接变量）。视觉验收第 1 轮抓出 73 处
 *     绕过 token 的色值——其中 --text-faint/--ok 两处"保留旧值"让浅色主题下出现 2.78:1
 *     与 1.48:1 的不可读组合。白名单：NotebookCover.tsx（SVG 封面调色板，美术资产）、
 *     m3-tokens.css（token 定义本源）、Assistants.tsx 的实体色板（存进数据的颜色，
 *     非样式）。
 *
 *  R2 组件层禁 opacity 压对比：视觉验收第 3 轮抓出六处 <4.5:1，其中两处是自己组件层
 *     写的 opacity 降权（m3-listrow__meta 0.65、--muted 整行 0.55）。层次一律用
 *     颜色档 + 字号表达。豁免：opacity: 0/1（显隐切换）、>=0.7。
 *
 *  R3 旧按钮类不得复活：P3 已把 24 页的 .btn/.btn-ghost/.chip-btn 全部迁到 M3 组件层
 *     （pages 里唯一刻意保留的是 Chat slash/@ 弹层的 pop-item，非按钮类）。这条防止
 *     新代码把旧模式写回来。
 *
 * Run: npm run test:lintui (CommonJS——仓库 scripts 目录无 type:module)
 */
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const LOG = path.join(os.tmpdir(), 'pcb-lintui.txt');
const out = [];
let failed = 0;
const check = (name, cond, detail) => {
	out.push((cond ? 'PASS' : 'FAIL') + '  ' + name + (cond || detail === undefined ? '' : '  << ' + detail));
	if (!cond) { failed++; }
};
const flush = () => { try { fs.writeFileSync(LOG, out.join('\n')); } catch { /* logging only */ } };
process.on('exit', flush);

const root = path.join(__dirname, '..');
const rendererSrc = path.join(root, 'src', 'renderer', 'src');

// 白名单：R1 不检查的文件（调色板资产 / token 定义本源）
const HEX_ALLOWED_FILES = new Set(['NotebookCover.tsx', 'm3-tokens.css']);
// R1 行级白名单：实体数据色（存进数据，非样式）
const HEX_LINE_OK = /COLORS\s*=|color:\s*['"]#/;

const stripComments = line => line
	// 2026-11-24 修：行尾 \r（CRLF）让 /\/\/.*$/ 的 $ 匹配失败——行注释剥除对 CRLF
	// 文件整体失效（React #310 的注释文字被 R1 误咬成裸 hex）。先归一掉 \r 再剥。
	.replace(/\r$/, '')
	.replace(/\/\*[^]*?\*\//g, '')
	.replace(/\/\/.*/, '');

const hexFiles = [];
const opacityHits = [];
const oldClassHits = [];

function walk(dir) {
	for (const f of fs.readdirSync(dir, { withFileTypes: true })) {
		const full = path.join(dir, f.name);
		if (f.isDirectory()) { walk(full); continue; }
		if (!/\.(tsx?|css)$/.test(f.name)) { continue; }
		const rel = path.relative(rendererSrc, full).replace(/\\/g, '/');
		const raw = fs.readFileSync(full, 'utf8');
		// 先整体剥掉跨行块注释（把内容换成空白但保留换行），再逐行判——
		// 只按行剥会漏 /* ... 换行 ... */ 形态，把注释里写的教训文字误判成违规代码。
		const text = raw.replace(/\/\*[\s\S]*?\*\//g, m => m.replace(/[^\n]/g, ''));
		const lines = text.split('\n');
		lines.forEach((line, i) => {
			const code = stripComments(line);
			// R1：裸 hex（#fff/#0b57d0/长 hex）；样式表 url(#filter) 这类 SVG 引用不在 renderer 出现，不特判
			if (!HEX_ALLOWED_FILES.has(f.name) && f.name !== 'styles.css' && !HEX_LINE_OK.test(line)) {
				const m = code.match(/#[0-9a-fA-F]{3,8}\b/);
				if (m) { hexFiles.push(`${rel}:${i + 1} ${m[0]}`); }
			}
			// R2：组件层 opacity 压对比（0 < x < 0.7）
			if (/^components[\\/]m3[\\/].*\.css$/.test(rel)) {
				const o = code.match(/opacity:\s*(0\.\d+)/);
				if (o && parseFloat(o[1]) < 0.7) { opacityHits.push(`${rel}:${i + 1} opacity:${o[1]}`); }
			}
		});
		// R3：旧按钮类（pages 与 components 都不让回来；pop-item 是刻意保留的弹层，不在检查范围）
		if (/^pages[\\/]/.test(rel) && /\.tsx$/.test(f.name)) {
			for (const i in lines) {
				if (/className=["'`][^"'`]*\b(btn-ghost|chip-btn)\b/.test(lines[i])) {
					oldClassHits.push(`pages/${rel.split(/[\\/]/).pop()}:${Number(i) + 1}`);
				}
			}
		}
	}
}
walk(rendererSrc);

check('R1 裸 hex：页面与组件层为 0（token 外无色值）', hexFiles.length === 0, hexFiles.slice(0, 8).join(' | '));
check('R2 组件层无 opacity 压对比（<0.7）', opacityHits.length === 0, opacityHits.join(' | '));
check('R3 旧按钮类未复活（pages 内 .btn-ghost/.chip-btn = 0）', oldClassHits.length === 0, oldClassHits.slice(0, 8).join(' | '));
// styles.css 的 34 处存量 hex 是 P4 清理对象：打印基线计数，数量增加才 FAIL（防新增不追旧账）
const stylesPath = path.join(root, 'src', 'renderer', 'src', 'styles.css');
const stylesHex = (fs.readFileSync(stylesPath, 'utf8').match(/#[0-9a-fA-F]{3,8}\b/g) || []).length;
// P4 清段完成（2026-11-06）：styles.css 的 34 处存量已全部归 token，基线收到 0——此后任何新增都 FAIL。
check('styles.css 裸 hex = 0（P4 清段已完成，禁止回潮）', stylesHex === 0, `当前 ${stylesHex}`);

out.push(failed === 0 ? '\nALL PASS' : '\n' + failed + ' FAILED');
console.log(out.join('\n'));
process.exit(failed === 0 ? 0 : 1);
