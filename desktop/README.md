# desktop/ —— Planability **电脑端（PC 端）**

> 这一层是 **Windows 桌面端**（Electron + Vite + React + TypeScript）。
> 手机端在同级目录 **`../app/`**（Kotlin + Compose），两边**没有物理共享代码**，
> 跨端契约靠 `desktop/shared/` 与手机端手工双份保持一致（详见 `docs/UI_CONTRACT_HANDOFF.md`）。

## 怎么认出这是电脑端

- 目录名 `desktop/`、入口 `package.json` 的 `name` 是 PC 端应用；
- 主进程 `src/main/`（Node 侧：AI 编排 / better-sqlite3 / Syncthing / office 桥），
  渲染进程 `src/renderer/`（React 页：Home/Chat/Files/Notes/…）；
- **跑在这里的东西只属于 PC**：`npm run dev`（electron-vite）、
  `scripts/launch-dev.cmd`、`scripts/pc_gate_docx_xlsx.py`（docx/xlsx 渲染闸门）。

## 目录速查

| 路径 | 是什么 |
|---|---|
| `src/main/` | Electron 主进程：`ai/`（agent/工具/会话/Provider）、`store/`（SQLite）、`syncthing.ts`、`datasync.ts` |
| `src/renderer/` | React 界面（七页导航 + ZCode 式工作台） |
| `src/preload/` | 上下文桥（`window.pc.*` API 面） |
| `shared/` | **跨端契约**：`chat.ts` / `ipc.ts` / `types.ts`（改这里 = 改契约，先在 `agents_log` 记 `change`） |
| `native/` | 本地原生模块（`planability-cu`） |
| `scripts/` | PC 侧工具：`launch-dev.cmd`（起开发环境）、`pc_gate_docx_xlsx.py`（docx/xlsx 闸门） |
| `test/` | PC 侧测试（node:test，`*.test.cjs`） |
| `docs/` | PC 侧文档与工作日志（见下） |

## 文档（`docs/`）

| 文件 | 内容 |
|---|---|
| `PC_IDE_PLAN.md` | PC 端 IDE 化改造计划 |
| `PC_IDE_WORKLOG.md` | PC 端 IDE 改造工作日志 |
| `DESKTOP_WORKLOG.md` | 电脑端建设工作日志（跨期连续记录） |
| `AI_WORKBENCH_PLAN.md` | 电脑端 AI 工作台三期改造计划（v1，2026-10-05 定稿） |
| `UI_CONTRACT_HANDOFF.md` | UI 契约交接（PC ↔ 手机 UI 对齐依据） |
| `archive/` | 2026-10-04 仓库搬迁前的**电脑端遗留存档**（本地 zip，不进 git，见下） |

## 常用命令

```bash
npm install          # 首次
npm run dev          # electron-vite 开发模式（或 scripts\launch-dev.cmd）
npm test             # node:test
npm run build        # 产物在 out/（当前版本 0.1.0，尚未产出 dist）
```

## 2026-10-18 归位说明（v19.59）

原先散在**仓库根目录**的 4 个 PC 端文件已并入本目录：

- `DESKTOP_WORKLOG.md` / `PC_IDE_PLAN.md` / `PC_IDE_WORKLOG.md` → `docs/`
- `pc_gate_docx_xlsx.py` → `scripts/`

同时核对了搬迁残留 `AndroidStudioProjects\Planability__moved_to_D_20261004`：
旧 `desktop/` 是本目录的**纯子集**（独有文件 0 个）、旧文档与仓库版一致或更旧，
确认无遗落后已删除，删除前把旧 `desktop/` + 旧文档 + 全量文件清单压成了
`docs/archive/planability-pc-legacy-20261004.zip`（只留在本机，`.gitignore` 已排除，
不进 git 历史）。

> 手机端 AI 工作台方案在**仓库根目录** `AI_WORKBENCH_PLAN.md`（手机端 v3，2026-09-27），
> 不要和 `docs/AI_WORKBENCH_PLAN.md`（电脑端三期 v1）搞混——两份是不同的东西。
