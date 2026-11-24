/**
 * Planability **office-bridge**：手机端 → 电脑端的高保真办公文件渲染服务。
 *
 * ## 为什么它是独立进程，而不是 Electron 主进程里的一个模块
 *
 * 这个服务要做两件事：① 等手机发请求；② 渲染文件。而渲染要引入 pptxgenjs / docx /
 * exceljs 这类重依赖。放进 Electron 主进程有两个坏处：
 * - 主进程崩了用户的整个 PC 客户端（渲染一个坏文件就把 App 带崩，不可接受）；
 * - 打包体积与启动时间被这些库拖住。
 *
 * 所以它是一个**独立的 Node 进程**：`npm run office` 启动，端口默认 4097。
 * 好处是它崩溃只影响"电脑端渲染"这一件事，手机端会自动回退到自带写出器。
 *
 * ## 与手机端的契约（改了要同步改 PcBridge.kt / DocModel.deckToJson）
 *
 * - `GET  /health` → `{"ok":true,"version":"...","caps":["pptx","docx","xlsx"]}`
 * - `POST /render`，三种入参：
 *     · pptx → `{kind:"pptx", deck:{...}}`      结构化演示文稿（见 DocModel）
 *     · docx → `{kind:"docx", doc:{paragraphs:["一行一段", ...]}}`
 *     · xlsx → `{kind:"xlsx", doc:{rows:[["表头"],["单元格"]], sheetNames:[...]}}`
 *   响应体**直接是文件二进制**（Content-Type 按 kind）。
 *
 * 三个刻意的选择：
 * 1. **响应用二进制而不是 base64 JSON**：一份 20 页带图 PPT base64 后涨三分之一。
 * 2. **默认只绑 127.0.0.1**：局域网可达 = 同网段任何人能让这台电脑生成文件。
 *    跨设备请显式 `OFFICE_BRIDGE_HOST=0.0.0.0`，或者用 `adb reverse tcp:4097 tcp:4097`。
 * 3. **没有鉴权**：同上，本机单用户场景下加 token 只会让人以为它安全。
 *    真要跨网，请放在反向代理后面并自行加鉴权。
 *
 * ## docx/xlsx 为什么也走桥
 *
 * 手机端自带的 docx/xlsx 写出器只有"每行一段"和"每行一条制表符记录"这种最朴素的映射，
 * 没有样式、没有合并单元格、没有列宽自适应；电脑端用 docx / exceljs 能给出正常的
 * 排版对象。和 pptx 一样：**电脑在线就用电脑的，不在线就用手机的，并在结果里说明用了哪条路**。
 *
 * ## 排版与手机端的差别要说在前面
 *
 * 两边**不是同一套版式**，只是同一份内容。手机端的翻页快照按手机端那套排版渲染，
 * 它反映的是手机端自己的理解，不代表电脑端输出的效果。真正的效果以电脑上的文件为准。
 */
import http from 'node:http'
import { Buffer } from 'node:buffer'
import fsx from 'node:fs'
import PptxGenJS from 'pptxgenjs'
import { Document, Packer, Paragraph, HeadingLevel, Table, TableRow, TableCell, WidthType } from 'docx'
import ExcelJS from 'exceljs'

const VERSION = 'office-bridge/1.1'
const PORT = Number(process.env.OFFICE_BRIDGE_PORT || 4097)
const HOST = process.env.OFFICE_BRIDGE_HOST || '127.0.0.1'
const CAPS = ['pptx', 'docx', 'xlsx']

const MIME = {
  pptx: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
}

/** pptxgenjs 内置版式名 → 我们定义的母版名。注意：pptxgenjs 的 `LAYOUT_*` 是**页面尺寸预设**，
 *  不是母版名；早先误把 `masterName` 传成 `LAYOUT_TITLE` 会静默失效（布局全变 DEFAULT）。 */
const LAYOUTS = {
  title: 'P_TITLE',
  section: 'P_TITLE',
  bullets: 'P_CONTENT',
  two_column: 'P_CONTENT',
  image: 'P_CONTENT',
  table: 'P_CONTENT',
  quote: 'P_CONTENT',
}

/** 读请求体，限制大小——没有上限的话一个坏客户端能把内存吃光 */
function readBody(req, limit = 32 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = []
    let size = 0
    req.on('data', (c) => {
      size += c.length
      if (size > limit) {
        reject(new Error('请求体过大'))
        req.destroy()
        return
      }
      chunks.push(c)
    })
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
    req.on('error', reject)
  })
}

/** 深色主题用的是自配色，pptxgenjs 的内置母版是浅色的；这里做最小适配。 */
function themeOf(deck) {
  const t = deck.theme || {}
  return {
    dark: !!t.dark,
    accent: (t.accent || '4F6BED').replace('#', '').toUpperCase(),
  }
}

// ============================ pptx ============================

/**
 * deck JSON → pptxgenjs。
 *
 * 只支持手机端 DocModel 里的四种块（para / bullets / image / table）——
 * 手机端本来也只有这四种，不多做、不猜。
 */
async function renderPptx(deck) {
  const pptx = new PptxGenJS()
  const th = themeOf(deck)
  const bg = th.dark ? '11131A' : 'FFFFFF'
  const fg = th.dark ? 'F2F4F8' : '1A1C22'

  // 页面尺寸必须与手机端一致（13.333in × 7.5in = 12192000 × 6858000 EMU）。
  // 用 pptxgenjs 内置的 10 英寸版式会让同样的坐标缩放成另一个样子——
  // "高保真"的前提是两边画布同尺寸，否则只是"另一套版式"，而且不可比。
  pptx.defineLayout({ name: 'PLAN_WIDE', width: 13.333, height: 7.5 })
  pptx.layout = 'PLAN_WIDE'
  pptx.author = deck.author || 'Planability'
  pptx.title = deck.title || ''

  // 真母版：定义了带占位符的 master，页面才真的走"版式继承"，
  // 而不是所有元素都手工摆——后者在 pptxgenjs 里传 masterName 是**静默失效**的
  //（我第一版就踩了：三页布局全是 DEFAULT，还以为母版用上了）
  pptx.defineSlideMaster({
    title: 'P_TITLE',
    background: { color: bg },
    objects: [
      { placeholder: { text: '标题', options: { x: 0.75, y: 0.5, w: 11.83, h: 1.2, fontSize: 40, bold: true, color: fg, align: 'center' } } },
      { placeholder: { text: '副标题', options: { x: 0.75, y: 1.9, w: 11.83, h: 0.7, fontSize: 18, color: th.dark ? '9AA3B2' : '5A6272', align: 'center' } } },
      { slideNumber: { x: 12.3, y: 6.9, color: th.dark ? '9AA3B2' : '5A6272' } },
    ],
  })
  pptx.defineSlideMaster({
    title: 'P_CONTENT',
    background: { color: bg },
    objects: [
      { placeholder: { text: '标题', options: { x: 0.75, y: 0.4, w: 11.83, h: 0.95, fontSize: 28, bold: true, color: fg } } },
      { slideNumber: { x: 12.3, y: 6.9, color: th.dark ? '9AA3B2' : '5A6272' } },
    ],
  })

  for (const s of deck.slides || []) {
    const masterName = LAYOUTS[s.layout] || 'P_CONTENT'
    const isTitle = masterName === 'P_TITLE'
    const slide = pptx.addSlide({ masterName })

    // 占位符**一律显式填**，哪怕内容是空串。
    // 起因是我误判过一次：看到成品里有"演示文稿标题"这句废话，以为是 pptxgenjs
    // 把内置版式的默认文案烤进了 slide xml。抓包后发现那其实是**模型自己传的标题**
    // （scripts/probe-placeholder.mjs 验证过：不填占位符时 slide xml 里一个字都没有）。
    // 但"永远显式填空串"仍然保留：把版式正确性押在某个库版本的未定义行为上，
    // 不如走自己能控制的那条路径——空串时得到空占位符，这是确定的。
    slide.addText(String(s.title || ''), { placeholder: { text: '标题' } })
    if (isTitle) {
      slide.addText(String(s.subtitle || ''), { placeholder: { text: '副标题' } })
    }

    let y = isTitle ? 3.0 : 1.6
    for (const b of s.blocks || []) {
      if (b.type === 'bullets') {
        const h = Math.min(0.45 * (b.items?.length || 1) + 0.3, 7.0 - y)
        slide.addText(
          (b.items || []).map((t) => ({ text: String(t), options: { bullet: true, breakLine: true } })),
          { x: 0.9, y, w: 11.5, h, fontSize: 16, color: fg, valign: 'top' },
        )
        y += h + 0.2
      } else if (b.type === 'para') {
        slide.addText(String(b.text || ''), {
          x: 0.9, y, w: 11.5, h: 0.6, fontSize: 16, bold: !!b.bold, color: fg,
        })
        y += 0.75
      } else if (b.type === 'table' && (b.rows || []).length) {
        // pptxgenjs 的表格是**真表格对象**：能在 PowerPoint 里选中、改行列、套样式，
        // 手机端那种"一堆格子拼出来的表格"做不到这点。这是高保真最实在的一处差别。
        const rows = b.rows.map((r) => (r || []).map((c) => String(c ?? '')))
        slide.addTable(rows, {
          x: 0.75, y, w: 11.83, fontSize: 12,
          border: { pt: 1, color: 'D0D4DC' },
          fill: th.dark ? '1E2230' : 'FFFFFF',
          color: fg,
        })
        y += Math.min(0.42 * rows.length + 0.3, 7.0 - y)
      } else if (b.type === 'image' && b.path) {
        try {
          const h = Math.min(4.2, 7.2 - y)
          slide.addImage({ path: String(b.path), x: 0.75, y, w: 11.83, h })
          y += h + 0.2
        } catch (e) {
          console.warn('[office-bridge] 图片加不进去，跳过：', b.path, e.message)
        }
      }
    }

    if (s.notes) slide.addNotes(String(s.notes))
  }

  return await pptx.write({ outputType: 'nodebuffer' })
}

// ============================ docx ============================

/**
 * 段落数组 → docx。
 *
 * 与手机端保持同一套语义：**一行一段**（手机端 `file_export_document` 就是这么定义的），
 * 但这里给的是真正的 Word 段落对象，可以在 Word 里看到样式、间距、层级，
 * 而不是手机端那种裸 `<w:p><w:r><w:t>`。
 */
async function renderDocx(doc) {
  const paragraphs = Array.isArray(doc.paragraphs) ? doc.paragraphs : []
  const children = paragraphs.map((line) => {
    const raw = String(line ?? '')
    // 空行保持为空段落（不要塞占位空格），首行作标题——和手机端"以 # 开头表示标题"的约定对齐
    if (raw.trim() === '') return new Paragraph({ children: [] })
    if (raw.startsWith('# ')) {
      return new Paragraph({
        text: raw.slice(2).trim(),
        heading: HeadingLevel.HEADING_1,
      })
    }
    if (raw.startsWith('## ')) {
      return new Paragraph({
        text: raw.slice(3).trim(),
        heading: HeadingLevel.HEADING_2,
      })
    }
    return new Paragraph({
      text: raw.replace(/^\*\*|\*\*$/g, ''),
      children: raw.startsWith('**')
        ? [{ text: raw.replace(/\*\*/g, ''), bold: true }]
        : undefined,
    })
  })
  const document = new Document({
    creator: 'Planability',
    title: doc.title || '',
    sections: [{ properties: {}, children: children.length ? children : [new Paragraph({ children: [] })] }],
  })
  return await Packer.toBuffer(document)
}

// ============================ xlsx ============================

/**
 * 看起来像数字就写成数字，否则 Excel 会把它当文本：左对齐、不能求和、排序也乱。
 *
 * 护栏是必须的，因为**朴素转数字会毁数据**。这几条都是常见踩法：
 * - 前导零（`007`、`00123`）多半是编号/邮编/工号，转成数字就丢了
 * - `20260101` 这种 8 位：既可能是数量，也多半是日期/编号。转了用户会发现"日期变成了数字"
 * - `13800138000` 这种 11 位：几乎一定是手机号/工号，不是数量
 * - `1,200`、`45%` 本来就该是文本
 * - 超过 15 位 Excel 本身也存不准，转了反而错
 *
 * 取舍：整数部分**不超过 10 位**才当数字。10 位已经覆盖到百亿级数量，再往上
 * 更多的可能是编号而不是数。这个阈值是经验值，不是真理；写在这里是为了让后来的人
 * 知道它是**有意的选择**，并且知道要放宽时该放宽哪条。
 */
function coerceCell(v) {
  if (typeof v === 'number' || typeof v === 'boolean') return v
  const s = String(v ?? '').trim()
  if (s === '') return ''
  if (!/^[+-]?(\d+(\.\d+)?|\.\d+)$/.test(s)) return s
  if (/^[+-]?0\d/.test(s)) return s                 // 前导零：保持文本

  const digits = s.replace(/^[+-]/, '').replace('.', '')
  if (digits.length > 15) return s                 // 超 Excel 精度
  if (!s.includes('.') && digits.length > 10) return s   // 长整数更可能是编号/手机号

  // 8 位且落在 1900–2099：按日期处理更符合人的意图
  if (!s.includes('.') && digits.length === 8) {
    const y = Number(digits.slice(0, 4))
    const m = Number(digits.slice(4, 6))
    const d = Number(digits.slice(6, 8))
    if (y >= 1900 && y <= 2099 && m >= 1 && m <= 12 && d >= 1 && d <= 31) return s
  }

  const n = Number(s)
  return Number.isFinite(n) ? n : s
}

/** 二维数组 → xlsx，首行作表头并加粗。列宽按内容长度粗估，避免出现 ####。 */
async function renderXlsx(doc) {
  const rows = Array.isArray(doc.rows) ? doc.rows : []
  const wb = new ExcelJS.Workbook()
  wb.creator = 'Planability'
  const ws = wb.addWorksheet(String(doc.sheetName || 'Sheet1').slice(0, 31))
  for (const r of rows) ws.addRow((r || []).map(coerceCell))

  if (rows.length) {
    const header = ws.getRow(1)
    header.font = { bold: true }
    header.alignment = { vertical: 'middle' }
    const cols = Math.max(...rows.map((r) => (r || []).length))
    for (let c = 1; c <= cols; c++) {
      let max = 8
      for (const r of rows) max = Math.max(max, String((r || [])[c - 1] ?? '').length + 2)
      ws.getColumn(c).width = Math.min(max, 46)
    }
  }
  return await wb.xlsx.writeBuffer()
}

// ============================ 真实渲染（LibreOffice） ============================

/**
 * 用 LibreOffice（soffice --headless）把 office 文件转成 PDF 与缩略图。
 *
 * ## 为什么单独用一个进程、还要加超时
 *
 * soffice 是**外部程序**，会崩、会卡、会在有残留实例时静默返回空文件。所以：
 * - 每次转换都用自己的 `-env:UserInstallation` 独立配置目录，避开与用户已有的
 *   LibreOffice 实例抢同一个 profile（这个坑很隐蔽：不抢的话第二次转换会莫名失败）
 * - 硬超时：转不出来就当"这次没转成"，不能让它把整个桥拖住
 * - 先写进临时目录再改名：避免留下半个 PDF 被下游当成成功
 *
 * ## 为什么需要它
 *
 * 之前手机端的"翻页预览"是**我们自己按同一份排版数据画的 PNG**——它反映的是手机端
 * 自己的理解，和电脑上的真实版式不是一回事（尤其当电脑端引擎参与生成时）。
 * 有 LibreOffice 就能出**真·所见即所得**的 PDF 与首页缩略图，让预览与文件真正一致。
 */
/**
 * 找 soffice：环境变量优先，否则探测常见安装位置。
 *
 * 刻意**不只认环境变量**：实测用 `set VAR=... && npm run office` 启动时变量传不进去
 * （cmd 的 && 组合在某些启动方式下会丢），而"必须设环境变量才能用"对用户是个无谓的门槛。
 * 探测列表覆盖了：官方默认安装、便携解包（管理式安装 /a，无需管理员）、Program Files。
 */
const SOFFICE_CANDIDATES = [
  process.env.OFFICE_BRIDGE_SOFFICE,
  'C:\\Program Files\\LibreOffice\\program\\soffice.com',
  'C:\\Program Files (x86)\\LibreOffice\\program\\soffice.com',
  process.env.LOCALAPPDATA && `${process.env.LOCALAPPDATA}\\Programs\\LibreOfficePortable\\program\\soffice.com`,
  process.env.LOCALAPPDATA && `${process.env.LOCALAPPDATA}\\Programs\\LibreOffice\\program\\soffice.com`,
].filter(Boolean)

let sofficePath = null
let sofficeChecked = false

function findSoffice() {
  if (sofficeChecked) return sofficePath
  sofficeChecked = true
  for (const p of SOFFICE_CANDIDATES) {
    try {
      if (fsx.existsSync(p)) { sofficePath = p; return p }
    } catch { /* 无权访问就跳过这个候选 */ }
  }
  sofficePath = ''
  return sofficePath
}

function sofficeAvailable() {
  return !!findSoffice()
}

/** 能力探测：PDF/缩略图取决于本机有没有 LibreOffice，所以 /health 要如实报。 */
async function capabilities() {
  return {
    caps: sofficeAvailable() ? [...CAPS, 'pdf', 'thumb'] : [...CAPS],
    soffice: sofficeAvailable() || false,
  }
}

async function runSoffice(args, timeoutMs = 90000) {
  const { spawn } = await import('node:child_process')
  const exe = findSoffice()
  return new Promise((resolve, reject) => {
    const child = spawn(exe, args, { windowsHide: true })
    let out = ''
    child.stdout.on('data', (d) => { out += d.toString() })
    child.stderr.on('data', (d) => { out += d.toString() })
    const t = setTimeout(() => {
      try { child.kill() } catch { /* 已经退了 */ }
      reject(new Error(`LibreOffice 转换超时（${timeoutMs / 1000}s）`))
    }, timeoutMs)
    child.on('error', (e) => { clearTimeout(t); reject(e) })
    child.on('close', (code) => {
      clearTimeout(t)
      // soffice 转换失败时退出码常常仍是 0，所以不能只看 code——要看有没有产出文件
      resolve({ code, out })
    })
  })
}

/** office 文件 → PDF（字节）。没有 LibreOffice 或转换失败都抛错，调用方据此如实回报。 */
/**
 * 把本地路径转成 LibreOffice 能吃的 `file:///` URI。
 *
 * soffice 的 `-env:UserInstallation` 只接受 **file URI 形式**，直接给 `C:/...` 会被它当成
 * 非法选项（退出码 1 + "Error in option"）。这是 CLI 转换第一步必踩的坑：
 * 报错信息说的是"选项错误"，很容易让人以为是参数名写错，其实是值的格式不对。
 */
function toFileUri(p) {
  const abs = pathReplaceBackslashes(p)
  return `file:///${abs}`
}

function pathReplaceBackslashes(p) {
  return p.replace(/\\/g, '/').replace(/ /g, '%20')
}

async function toPdf(filePath) {
  if (!sofficeAvailable()) throw new Error('本机没有 LibreOffice，无法转 PDF')
  const os = await import('node:os')
  const path = await import('node:path')
  const fs = await import('node:fs')
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'office-bridge-'))
  const profile = toFileUri(path.join(tmpDir, 'profile'))
  const src = toFileUri(filePath)
  const outdir = toFileUri(tmpDir)
  try {
    await runSoffice([
      `-env:UserInstallation=${profile}`,
      '--headless', '--norestore', '--invisible',
      '--convert-to', 'pdf', '--outdir', outdir, src,
    ])
    const base = path.basename(filePath, path.extname(filePath))
    const pdf = path.join(tmpDir, `${base}.pdf`)
    if (!fs.existsSync(pdf)) throw new Error('LibreOffice 没产出 PDF（源文件可能损坏或格式不支持）')
    return fs.readFileSync(pdf)
  } finally {
    try { fs.rmSync(tmpDir, { recursive: true, force: true }) } catch { /* 临时目录留着也无妨 */ }
  }
}

/**
 * PDF 首页 → PNG。
 *
 * 用 **LibreOffice 直接导出 PNG** 而不是 pdf.js：pdf.js 在 Node 里需要一个真实的
 * canvas 实现（@napi-rs/canvas 或 canvas），为了一个缩略图引入原生模块不划算。
 * 代价要说清楚：LibreOffice 的 PNG 导出**只有第一页/第一张**。
 * 多页预览靠 PDF——手机端有系统 PdfRenderer，那才是"所见即所得"的关键路径。
 */
async function toPng(filePath) {
  if (!sofficeAvailable()) throw new Error('本机没有 LibreOffice，无法生成缩略图')
  const os = await import('node:os')
  const path = await import('node:path')
  const fs = await import('node:fs')
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'office-bridge-'))
  const profile = toFileUri(path.join(tmpDir, 'profile'))
  const src = toFileUri(filePath)
  const outdir = toFileUri(tmpDir)
  try {
    await runSoffice([
      `-env:UserInstallation=${profile}`,
      '--headless', '--norestore', '--invisible',
      '--convert-to', 'png:impress_png_Export', '--outdir', outdir, src,
    ])
    const base = path.basename(filePath, path.extname(filePath))
    const png = path.join(tmpDir, `${base}.png`)
    if (!fs.existsSync(png)) throw new Error('LibreOffice 没产出缩略图')
    return fs.readFileSync(png)
  } finally {
    try { fs.rmSync(tmpDir, { recursive: true, force: true }) } catch { /* 留着也无妨 */ }
  }
}

// ============================ 改稿 ============================

/**
 * A1 记法 → {row, col}（均 1 基）。认 `$B$3` / `B3` / `b3`。
 *
 * 为什么支持 A1：模型和用户都是这么念单元格的（B3、C12），
 * 让它去数"第 2 行第 3 列"既啰嗦又容易数错。
 */
function parseA1(ref) {
  const m = /^\$?([A-Za-z]{1,3})\$?(\d{1,7})$/.exec(String(ref || '').trim())
  if (!m) return null
  let col = 0
  for (const ch of m[1].toUpperCase()) col = col * 26 + (ch.charCodeAt(0) - 64)
  return { row: Number(m[2]), col }
}

/**
 * 改 xlsx：读回既有文件、按 ops 改、原样写回。
 *
 * 为什么只有 xlsx：pptxgenjs 与 docx 这两个库**都没有读取既有文件的入口**
 * （我查过：pptxgenjs 原型上没有任何 load/read/parse，docx 只导出内部 Imported* 类型）。
 * 它们只能"从零生成"，而"从零生成"对改稿来说是**倒退**——那正是手机端
 * PptxEditor 刻意避免的：整份重建会抹掉没解析的母版、动画、图表。
 * exceljs 有 `xlsx.load`，能真正读回并保留其余内容，所以只有它值得做改稿。
 *
 * ops 三种，都刻意做窄：
 * - `{op:"set", sheet, cell:"B3", value}`   改单元格
 * - `{op:"addRow", sheet, values:[...]}`    末尾加一行
 * - `{op:"delRow", sheet, row:3}`           删一行
 * 没有"插入中间行""改列宽""改公式"——那些要做就得先想清楚语义，
 * 与其做一半让人踩坑，不如明确只支持这三种。
 */
async function patchXlsx(buffer, ops) {
  const ExcelJS2 = ExcelJS
  const wb = new ExcelJS2.Workbook()
  await wb.xlsx.load(buffer)

  const applied = []
  for (const op of ops || []) {
    const ws = wb.getWorksheet(String(op.sheet || '')) || wb.worksheets[0]
    if (!ws) return { error: `找不到工作表：${op.sheet}` }
    if (op.op === 'set') {
      const rc = parseA1(op.cell) || (op.row && op.col ? { row: op.row, col: op.col } : null)
      if (!rc) return { error: `认不出单元格：${op.cell ?? JSON.stringify(op)}` }
      if (rc.row < 1 || rc.col < 1) return { error: `单元格越界：${op.cell}` }
      ws.getCell(rc.row, rc.col).value = coerceCell(op.value)
      applied.push(`set ${ws.name}!${op.cell ?? rc.row + ',' + rc.col}`)
    } else if (op.op === 'addRow') {
      ws.addRow((op.values || []).map(coerceCell))
      applied.push(`addRow ${ws.name}`)
    } else if (op.op === 'delRow') {
      const n = Number(op.row)
      if (!Number.isInteger(n) || n < 1 || n > ws.rowCount) {
        return { error: `行号不合法：${op.row}（该表共 ${ws.rowCount} 行）` }
      }
      ws.spliceRows(n, 1)
      applied.push(`delRow ${ws.name}!${n}`)
    } else {
      return { error: `不认识的改稿操作：${op.op}（支持 set / addRow / delRow）` }
    }
  }
  const out = await wb.xlsx.writeBuffer()
  return { buffer: Buffer.from(out), applied }
}

// ============================ HTTP ============================

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`)

    if (req.method === 'GET' && url.pathname === '/health') {
      const c = await capabilities()
      res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' })
      // caps 里如实带上 pdf/thumb：没有 LibreOffice 就不报，手机端据此**不**显示
      // "转 PDF"入口，免得用户点了才知道没有
      res.end(JSON.stringify({ ok: true, version: VERSION, caps: c.caps, soffice: c.soffice }))
      return
    }

    if (req.method === 'POST' && url.pathname === '/render') {
      const raw = await readBody(req)
      const text = raw.replace(/^\uFEFF/, '')
      // 落一份"最近一次请求"。两端字段对不上时（比如手机发来的值渲染成了别的文案），
      // 没有它就只能靠猜——我在这上面卡了很久，就是因为看不到手机到底发了什么。
      // 只保留最后一份，不堆积。
      try {
        const fs = await import('node:fs')
        const path = await import('node:path')
        const dir = path.join(process.cwd(), 'tmp', 'office-bridge')
        fs.mkdirSync(dir, { recursive: true })
        fs.writeFileSync(path.join(dir, 'last-request.json'), text, 'utf8')
      } catch (e) {
        console.warn('[office-bridge] 调试文件写不了：', e.message)
      }
      const body = JSON.parse(text)
      const kind = String(body.kind || '')
      // 入参统一包在 payload 里：kind 决定用哪个渲染器，payload 是给它的结构化数据。
      // 分开的好处是加新格式时不用改现有分支，也不会出现"某个字段既可能属于 A 又属于 B"。
      const payload = body.payload || {}

      if (!CAPS.includes(kind)) {
        res.writeHead(400, { 'content-type': 'application/json; charset=utf-8' })
        res.end(JSON.stringify({ ok: false, error: `不支持的格式：${kind}` }))
        return
      }

      let buf, engine
      if (kind === 'pptx') {
        // 入参既可能是 `payload.deck`（手机端 PcBridge.render 发的形状），
        // 也可能是 deck 本体（门禁脚本直连时发的形状）。只认后者会让**所有真机请求**
        // 落成 0 页空 pptx —— 2026-10-11 实测：45627 字节、无 slide、无 sldId，
        // 而手机端"文件存在即非空"就 renameTo 顶掉自己的兜底，还回报"高保真版式"。
        // 两个形状都收；门禁与真机从此走同一条路径。
        buf = await renderPptx(payload.deck || payload)
        engine = 'pptxgenjs'
      } else if (kind === 'docx') {
        buf = await renderDocx(payload)
        engine = 'docx'
      } else {
        buf = await renderXlsx(payload)
        engine = 'exceljs'
      }

      const out = Buffer.isBuffer(buf) ? buf : Buffer.from(buf)

      // `as=pdf` / `as=png`：把刚生成的文件再交给 LibreOffice 转一道。
      // 这样手机端不用先落盘再单独请求一次——少一次往返，也少一次"文件已生成但没转 PDF"的中间态。
      const as = url.searchParams.get('as')
      if (as === 'pdf' || as === 'png') {
        if (!sofficeAvailable()) {
          res.writeHead(501, { 'content-type': 'application/json; charset=utf-8' })
          res.end(JSON.stringify({
            ok: false,
            error: '本机没有 LibreOffice，无法转 PDF/缩略图。原始文件已经生成好了。',
          }))
          return
        }
        const path = await import('node:path')
        const os = await import('node:os')
        const fs = await import('node:fs')
        const ext = kind === 'pptx' ? '.pptx' : kind === 'docx' ? '.docx' : '.xlsx'
        const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'office-bridge-out-'))
        const tmpFile = path.join(tmpDir, `out${ext}`)
        try {
          fs.writeFileSync(tmpFile, out)
          const conv = as === 'pdf' ? await toPdf(tmpFile) : await toPng(tmpFile)
          res.writeHead(200, {
            'content-type': as === 'pdf' ? 'application/pdf' : 'image/png',
            'content-length': String(conv.length),
            'x-office-engine': `${engine}+libreoffice`,
          })
          res.end(conv)
          return
        } finally {
          try { fs.rmSync(tmpDir, { recursive: true, force: true }) } catch { /* 无妨 */ }
        }
      }

      res.writeHead(200, {
        'content-type': MIME[kind],
        'content-length': String(out.length),
        // 告诉手机端是谁渲染的，好让它如实告诉用户
        'x-office-engine': engine,
      })
      res.end(out)
      return
    }

    // 改稿：只有 xlsx。pptx/docx 在这里**明确回 501** 而不是含糊的 400/500，
    // 因为"不支持"和"坏了"是两回事——手机端据此提示用户"这项改稿请用 App 内置改稿"，
    // 而不是以为电脑端坏了再去重试。
    if (req.method === 'POST' && url.pathname === '/patch') {
      const body = JSON.parse((await readBody(req)).replace(/^\uFEFF/, ''))
      const kind = String(body.kind || '')
      if (kind !== 'xlsx') {
        res.writeHead(501, { 'content-type': 'application/json; charset=utf-8' })
        res.end(JSON.stringify({
          ok: false,
          error: `${kind} 的改稿不在电脑端做：pptxgenjs / docx 都没有读取既有文件的入口，` +
            `只能从零重新生成，那会抹掉母版、动画、图表，比手机端的增量改写更差。` +
            `${kind} 请用 App 内置的改稿工具（file_edit_deck / file_edit_document）。`,
        }))
        return
      }
      const src = body.fileBase64 ? Buffer.from(body.fileBase64, 'base64') : null
      if (!src || src.length === 0) {
        res.writeHead(400, { 'content-type': 'application/json; charset=utf-8' })
        res.end(JSON.stringify({ ok: false, error: '缺少 fileBase64（要改的文件）' }))
        return
      }
      const r = await patchXlsx(src, body.ops)
      if (r.error) {
        res.writeHead(400, { 'content-type': 'application/json; charset=utf-8' })
        res.end(JSON.stringify({ ok: false, error: r.error }))
        return
      }
      res.writeHead(200, {
        'content-type': MIME.xlsx,
        'content-length': String(r.buffer.length),
        'x-office-engine': 'exceljs',
        'x-office-applied': encodeURIComponent(r.applied.join('; ')),
      })
      res.end(r.buffer)
      return
    }

    // 转换既有的文件：传 base64 进、要 pdf/png 出。给"改完稿再要份 PDF"这种场景用——
    // 那时候文件已经在手机上了，走 /render 的 as= 已经不适用（那要的是 deck 而非成品文件）。
    if (req.method === 'POST' && url.pathname === '/transform') {
      const to = url.searchParams.get('to') || 'pdf'
      if (to !== 'pdf' && to !== 'png') {
        res.writeHead(400, { 'content-type': 'application/json; charset=utf-8' })
        res.end(JSON.stringify({ ok: false, error: `只支持 to=pdf / to=png，当前 ${to}` }))
        return
      }
      if (!sofficeAvailable()) {
        res.writeHead(501, { 'content-type': 'application/json; charset=utf-8' })
        res.end(JSON.stringify({ ok: false, error: '本机没有 LibreOffice，无法转换' }))
        return
      }
      const body = JSON.parse((await readBody(req)).replace(/^\uFEFF/, ''))
      const bytes = body.fileBase64 ? Buffer.from(body.fileBase64, 'base64') : null
      if (!bytes || !bytes.length) {
        res.writeHead(400, { 'content-type': 'application/json; charset=utf-8' })
        res.end(JSON.stringify({ ok: false, error: '缺少 fileBase64' }))
        return
      }
      const os = await import('node:os')
      const path = await import('node:path')
      const fs = await import('node:fs')
      const ext = body.ext || '.pptx'
      const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'office-bridge-x-'))
      const src = path.join(tmpDir, `src${ext}`)
      try {
        fs.writeFileSync(src, bytes)
        const out = to === 'pdf' ? await toPdf(src) : await toPng(src)
        res.writeHead(200, {
          'content-type': to === 'pdf' ? 'application/pdf' : 'image/png',
          'content-length': String(out.length),
          'x-office-engine': 'libreoffice',
        })
        res.end(out)
        return
      } finally {
        try { fs.rmSync(tmpDir, { recursive: true, force: true }) } catch { /* 无妨 */ }
      }
    }

    res.writeHead(404, { 'content-type': 'application/json; charset=utf-8' })
    res.end(JSON.stringify({ ok: false, error: '没有这个接口' }))  } catch (e) {
    // 这里必须回 500 而不是崩掉：一个坏文件不该让服务永久消失，
    // 否则手机端会一直回退到简版，用户还以为"电脑端不支持"
    console.error('[office-bridge]', e)
    res.writeHead(500, { 'content-type': 'application/json; charset=utf-8' })
    res.end(JSON.stringify({ ok: false, error: String(e.message || e) }))
  }
})

server.listen(PORT, HOST, () => {
  console.log(`[office-bridge] ${VERSION} 已启动：http://${HOST}:${PORT}`)
  console.log(`[office-bridge] 支持：${CAPS.join(', ')}`)
  if (sofficeAvailable()) {
    console.log(`[office-bridge] 找到 LibreOffice（${findSoffice()}）：可转 PDF 与首页缩略图（/render?as=pdf|png）`)
  } else {
    console.log('[office-bridge] 未找到 LibreOffice：PDF 与缩略图不可用（不影响 pptx/docx/xlsx 生成）')
    console.log(`[office-bridge] 装好后可设 OFFICE_BRIDGE_SOFFICE 指定 soffice 路径，或装到上述常见位置`)
  }
  console.log('[office-bridge] 手机端用「导出文件 → 电脑端服务」里填的地址连本机')
  console.log('[office-bridge] 跨设备需 adb reverse tcp:4097 tcp:4097，或显式设 OFFICE_BRIDGE_HOST')
})
