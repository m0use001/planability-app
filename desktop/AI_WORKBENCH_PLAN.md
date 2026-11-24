# Planability 桌面端 AI 工作台改造计划（三期 v1）

> 2026-10-05 定稿（pc-a）。继承根目录 `AI_WORKBENCH_PLAN.md`（手机端 v3）的核心命题与原则：
> **让 Agent 像在 IDE 里调用工具一样，调用 Planability 的全部功能与数据**；本地优先 / 隐私优先 / 依赖克制 / 复用现有资产。
> 本期主题 = **完整 AI 工作台 + 预置多 agent 协同**（多主代理 + 子代理 + 团队模板），UI 以 ZCode 为标杆。
> 调研输入：D:\IDE 下 7 个 AI IDE 源码探查（ZCode-main / COD / opencode / guIDE / SideX / prokop / xcore6 / VibeIDE）+ 用户补充的产品卖点清单。

---

## 0. 决策记录（用户拍板 2026-10-05）

| # | 决策 | 内容 |
|---|---|---|
| D1 | 多 agent 形态 | **子代理派生 + 预置团队 + 多主代理协作**：用户可对每个主代理分别下达命令；主代理之间互相通信协作；主代理可派生带独立上下文的子代理 |
| D2 | 工具边界 | **规划域 + 文件域 + 网页域 + shell**（shell 带 opencode 式三层安全网：硬禁表 / 只读白名单免审批 / 其余审批） |
| D3 | 存储 | **SQLite（better-sqlite3）**存会话树/消息/事件流/审计/用量；规划六集合**保持 JSON store**（dump 跨端互通不动） |
| D4 | UI 幅度 | **信息页升级为 ZCode 式工作台**（会话侧栏 + transcript + 运行徽章）；全局七页导航与 M3E 壳保持 |
| D5 | Computer Use（2026-10-05 追加） | **立项为 M21**，执行顺序排在 M18 三域之后、M19/M20 之前；每次键鼠注入必须审批，截图默认不出网 |

默认采纳（未否决即生效）：
- 预置团队：①「规划团队」总管/规划师/研究员/记录员；②「学习团队」导师/出题/批改；③ Neural Inverse 三角色（只读研究员=Explorer、执行者=Editor、审查者=Verifier）作为子代理标配预设。
- 手机端契约**不动**（本期为桌面端专属改造，不回移手机端）。
- 模型暂用现有 GLM 双槽；本地模型自动发现（`/v1/models` 探测，opencode `local.go` 模式）列 M18 可选。
- 并发上限默认 **4**（Lya COSMOS 先例），可配。

## 1. 调研结论速查（抄什么，出处）

### ZCode（UI 标杆 + harness，`D:\IDE\ZCode-main`）
- 行式会话协议：`packages/shared/src/zcode-protocol-v4/rows.ts`（rowId 单调、整行 upsert + delta）→ M16 会话协议 v2
- per-tool 渲染器注册表：`packages/ui/src/ToolCallBlocks/resolveRenderer.ts` + `renderers/` → M16 transcript
- 交互卡：`renderers/ask-question.tsx`（问答卡）、`ai-elements/plan.tsx` + `PlanDetailSidePane`（plan 审批）、`confirmation.tsx`（确认卡基元）→ M16
- 会话侧栏：`WorkspaceSidebar.tsx` + `TaskList.tsx`（置顶/归档分组）；子代理轨迹侧栏 `SubagentSessionSidePane.tsx` → M16/M17
- 设计系统：`DESIGN.md` + `packages/ui/src/styles.css`（`text-ui-*` 字阶、语义色、confirmation 色）→ M16 token 对齐
- turn 状态机 + system-reminder 回灌：`apps/zcode-cli/packages/core/src/runtime/methods/turn-loop.ts` → M17/M19
- SubagentPort + 任务注册表：`contracts/src/interfaces/subagent.port.ts`、`core/src/runtime-task/registry.ts`（前台/后台、steering、终态通知、防迟到完成）→ M17
- AgentProfile frontmatter：`core/src/subagent/profile.ts`（tools 白名单/permissionMode/maxTurns/memory 域）→ M17
- 两层压缩：`core/src/compact/{microcompact,policy,prompt}.ts` → M19
- 工具元数据权限：`core/src/tool/types.ts` + `permission/{service,broker,rule-matching}.ts`（allow/deny/ask 规则）→ M18

### COD（VSCode fork 已验证可抄，`D:\IDE\COD\COD-main`）
- plan review 卡 + autopilot 档：`src/vs/platform/agentHost/common/agentHostPlanReview.ts` → M16
- 本地记忆事件层（append-only JSONL、可检视可擦除）：`src/vs/workbench/contrib/localMemory/common/localMemory.ts` L12-73 → M19
- Skills = frontmatter md + 用户副本覆盖：`src/vs/sessions/skills/*/SKILL.md` → M19
- Agents Window（多会话网格/置顶/未读态）：多主代理面板的形态参考 → M17
- AI 定制项六类统一管理 + harness 抽象：`AI_CUSTOMIZATIONS.md` → M19 管理页

### opencode（Go 归档版，`D:\IDE\opencode-main`；注意=旧版，无 server/SSE/配置化 agent）
- 阻塞式权限服务（本次/本会话记住/自动三态）：`internal/permission/permission.go` → M18
- 审批带 diff：`internal/llm/tools/edit.go` → M18
- 会话树：`internal/session/session.go`（parent_session_id、列表只显根、子会话 cost 汇总父）→ M17
- 消息 typed-parts：`internal/message/content.go` → M17（SQLite schema 直接参考）
- 原地摘要压缩（SummaryMessageID 指针 + 95% 自动触发）：`internal/llm/agent/agent.go`、`internal/tui/tui.go` → M19
- shell 三层安全网：`internal/llm/tools/bash.go` → M18
- 本地模型发现：`internal/llm/models/local.go` → M18 可选

### guIDE（同技术栈 Electron+Vite+React，`D:\IDE\guIDE-1.8.1`）
- 循环健壮性三件套：`main/agenticChat.js`（stuck-cycle 检测/半截工具调用抢救/失败分类）→ M17
- ContextBudget：`main/contextManager.js` → M19
- Electron 最小多 agent 底座：`main/ipc/agentHandlers.js`（agent-spawn IPC + Map + status 事件）→ M17 骨架

### VibeIDE（Code-OSS fork；⚠ `D:\IDE\VibeIDE-1.22.0` 的 src/ 缺失，源码在 `C:\Users\test\Downloads\VibeIDE-1.22.0.zip`）
- agent 循环防护栈：`src/vs/workbench/contrib/vibeide/browser/chatThreadService.ts` L191-199 + ~5805（迭代硬帽+软检查点、tool-call 签名环反死循环、连续错误熔断、abort 超时）→ M17
- 角色预设注册表（只读角色锁工具白名单）：`.../vibeSubagentRegistryService.ts` → M17 预置团队
- 子代理预算（步数/时间/token）+ head+tail 截断 + 花费/运行台账 → M17
- ProviderId 动态合并（内置表+providers.json，safeStorage 加密）→ M18
- 固定四段交接格式：`common/agentHandoff.ts` → M20
- 快照恢复 + checkpoint 串行协调器：`common/rollbackSnapshotService.ts` → M18 文件域 undo
- **抄前要修的坑**：secret detection 只掩码终端结果——须在上下文组装前、MCP 调用前也调用；其 `contextGatheringService` 是补全用 LSP 收集器，不是 chat 上下文管理器，勿误抄。

### 其余
- **prokop**（`D:\IDE\prokop-server-v1.17.1`）：持久 agent=晋升的配置 + home 目录 + USER.md/MEMORY.md（`domains/agents/{promotion,home}.ts`）→ M17 团队持久化；跨项目记忆作用域（user/workspace 两级）→ M19
- **DevWit**（打包版，产品形态）：透明上下文（每请求完整上下文+逐项 token 成本）→ M19 token manifest（手机端 v3 §四同款设计）；严格授权（写/shell 全审批）→ M18 默认 ask；任务→自动组队 → M20
- **Lya xcore6**（`runtime/xcore6/src/`）：内核即 MCP server（远期可选）；4 并行上限先例
- **Zed**（安装版无源码，产品形态）：并行 agent 侧栏调度、**ACP（Agent Client Protocol）接入第三方 agent**——与 VibeIDE `common/acp/acpProtocol.ts`（JSON-RPC，已实测对接 Claude Code）互证 → M18 可选外部主代理
- **SideX**：按域分层的类型化命令面纪律（架构参考）
- **用户补充卖点已并入**：OpenCode 模型无关→M18 provider 动态合并；Neural Inverse Explorer/Editor/Verifier→M17 预设；VibeIDE Telegram 远程派活→backlog 不排期

### 与手机端 v3 方案的衔接（不重复造轮子）
- PTL 工具语义照手机 v3 §2（工具定义/风险三级/50KB 截断/审计/undo），桌面端已有 10 工具在此基础上扩域
- 记忆打分检索沿用无嵌入公式（v3 §3.3），memories 集合跨端互通
- token manifest、PromptGuard、DeadMansSwitch、LoopDetector 护栏清单照 v3 §3.5/§四 落桌面版

## 2. 目标架构（五层）

```
┌ UI 层    工作台（信息页）：会话/团队侧栏 + transcript + 交互卡 + 用量面板
├ 编排层  AgentManager：多主代理 + 子代理（spawn/stop/sendMessage/wait）
│         消息总线（任意 agent 互发：steer/queued/resume）+ 团队模板 + 任务板
├ 能力层  工具注册表（规划域+文件域+网页域+shell+agent 域+MCP）
│         元数据权限（readOnly/destructive/risk × 四档 × allow/deny/ask 规则）
├ 上下文  ContextBudget + 微压缩 + 95% 自动摘要（worker 槽）+ token manifest
│         记忆（memories 集合 + append-only 事件流，user/workspace 作用域）
├ 模型层  双槽路由（GLM 主槽=对话/工具；worker 槽=摘要/抽取/命名）+ 并发上限 4
└ 数据层  SQLite（会话树/消息 parts/事件/审计/用量）+ JSON store（规划六集合，dump 不动）
```

关键数据模型：
- `agents(id, role, kind: primary|sub, parent_session_id, profile_json, status, …)`——主代理与会话一一对应，子代理挂父会话，列表只显根
- `messages(id, session_id, role, parts_json, finished_at, …)`——typed parts（text/reasoning/tool_call/tool_result/finish）
- `events(id, ts, kind, payload_json)`——append-only 记忆事件层（计划/决策/工具事件/交接）
- `usage(agent_id, session_id, tokens_in, tokens_out, cost, at)`——逐 agent/会话台账
- 团队模板 = 内置 AgentProfile 集合（name/color/systemPrompt/tools/permMode/maxTurns/memory 域），用户可复制覆盖（COD skills 先例）

## 3. 分期里程碑（每期独立验收 + 路径式提交）

### M16 工作台外壳（UI 参考 ZCode）
1. `shared/chat.ts` 会话协议 v2：行式模型（rowId 单调、upsert+delta）+ per-tool 渲染注册表契约
2. 信息页 → 工作台三栏：左侧会话/团队侧栏（置顶/归档、运行中徽章）、中间 transcript、工具行收编
3. transcript 重构：工具调用块注册表（现 tool_start/end 行 → 按工具注册渲染器）、思考折叠增强、plan 审批卡（COD plan review：批准/改参/autopilot）、AskUserQuestion 交互卡、确认卡基元统一
4. `styles.css` 引入 `text-ui-*` 字阶与语义色 token（与 M3E 融合，仍单文件）
- 验收：流式渲染一致性（断流/重生成/多会话切换）；四卡回归全过；AX 观察+盘上数据双证

### M17 多 agent 内核
1. `ai/agents/AgentManager.ts`：多主代理（各自会话、用户分别下令）+ 子代理派生（task 工具）+ SendMessage 总线（主↔主、主↔子；steer/queued/终态通知）+ guIDE 骨架（spawn IPC+status 事件）
2. AgentProfile：Assistant 扩展（tools 白名单/permMode/maxTurns/记忆域/color），预置团队三套（规划/学习/三角色）
3. 会话树进 SQLite（M17 起引 better-sqlite3 + 迁移框架；消息 typed-parts 落库；JSON store 不动）
4. 循环防护栈：guIDE 三件套 + VibeIDE 签名环/软检查点/熔断；子代理预算（步数/时间/token）+ head+tail 截断
5. 用量台账：逐 agent/会话 token 记账（usage 表 + 统计页区块）
- 验收：①用户对两个主代理分别下令→两 agent 互发消息协作→产出合并结果；②主代理派子代理跑只读任务→轨迹侧栏可查→结果汇回；③danger 操作冒泡审批；④并发上限 4 生效

### M18 工具与权限扩张
1. 文件域：file_list/read/write/search（限用户选定工作区根，写入前快照，审批卡带 diff——opencode 模式）+ VibeIDE 快照恢复模式（checkpoint 串行协调）
2. 网页域：web_fetch/web_search（danger 级默认 ask）
3. shell：opencode 三层安全网（硬禁表/只读白名单/审批），工作目录=工作区根，输出头尾截断
4. 权限升级：工具元数据化（readOnly/destructive/needsApproval/allowedInPlan）+ allow/deny/ask 规则（会话级记住已有 alwaysAllow 并入）+ 子代理默认继承收窄
5. provider 动态合并（VibeIDE 模式）+ 本地模型 `/v1/models` 发现（可选）；secret detection 在上下文组装前+工具结果回填前+MCP 调用前三处生效（修正 VibeIDE 缺口）
6. 可选：ACPHost 外部主代理（接 Claude Code 等，VibeIDE acpProtocol 参考实现）
- 验收：shell 只读命令免审批直跑、写命令审批卡带将执行命令；文件写入→改→快照回滚；越权子代理调用被白名单拦截并回纠正

### M19 上下文与记忆
1. ContextBudget（guIDE）+ 微压缩（清旧工具结果）+ 95% 自动摘要（worker 槽、SummaryMessageID 指针原地压缩）
2. token manifest：每请求逐项（系统提示/工具/注入/历史）token 分解落盘 + 上下文面板逐项开关（DevWit 透明上下文 / 手机 v3 §四）
3. 记忆升级：memories 集合 + append-only 事件流（COD localMemory 模式，可检视可擦除页）；user/workspace 作用域（prokop 跨项目记忆）；agent 自动沉淀（轮次完成后台走 worker 槽，mem0 增量抽取照 v3 §3.2）
4. Skills：frontmatter md 目录（内建：周计划/复盘/优先级排序；用户可写；COD 用户副本覆盖模式）
5. system-reminder 回灌（todo/团队状态）
- 验收：长会话 95% 触发压缩后任务连续性保持；manifest 面板逐项开关后 tokens 实变；记忆条目可查可删；skill 加载注入生效

### M20 编排与自动化
1. 多 agent 工作流 v1：声明式步骤编排（步骤→角色→汇合，ZCode dynamic workflow 的简化声明版）+ journal 可回放；验证者自动修复环（Laya COSMOS 先例）
2. 自动化引擎接入 agent 任务（定时跑团队任务，替代现 callNonStream 单发）
3. 任务→自动组队（DevWit：模糊需求→选模板装配团队）
4. 交接格式：固定四段 handoff md（VibeIDE agentHandoff）写同步文件夹，跨端/跨会话可读
5. backlog（不排期）：Telegram 等远程通道派活、agent 内核即 MCP server（xcore6 模式）
   - **2026-10-06 经用户确认：远程通道派活（跨公网派 agent 任务）不做，从 backlog 移除。**
     未曾实现过任何远程控制代码。理由：审批模型不跨边界（应用内写操作要用户点确认，
     而远程无人能点，`confirm` 档在远程场景直接失效），且远程场景下撤销能力为零
     （journal 只记输入输出、不记副作用，是 M20-1 的刻意设计）。
     若将来重启此项，先答：谁能派 / 能带什么附件 / 工具权限档 / 结果怎么回 / 审计落哪 / 失败怎么回滚。
   - agent 内核即 MCP server **已做**（`a08f870`，`src/main/ai/mcp-kernel.ts` + `mcp-server.ts`）。
     注意它**不是远程控制**：本地进程、stdio、**不监听端口、不联网**，写工具默认关闭。
     已明确记为独立于上述远程通道的一项。
- 验收：一个三步工作流（研究员收集→规划师排程→记录员写笔记）跑通并可回放；自动化定时触发团队任务落日志

### M21 Computer Use（D5 立项，执行顺序：M18 → M21 → M19/M20）

**目标**：agent 能看屏幕并操作 GUI，默认最小权限、逐次审批。

**工具面（`cu_` 域，M18 三域落地后接入）**：
- `cu_screenshot`（risk=auto 只读）：Electron `desktopCapturer` 主进程截屏，**落盘 + 行内元数据**（路径/尺寸/缩放比），不直接把位图塞进上下文（双槽模型 vision 支持待探测，先按不支持设计）；渲染层 per-tool 渲染器出缩略图行（ZCode `cua.tsx` 模式）
- `cu_screen_text`（auto）：取屏幕文本供模型定位（Windows OCR / AX 文本抽取，选型见下）
- `cu_click / cu_scroll / cu_key`（risk=confirm，**逐次审批**）；`cu_type`（confirm）默认走**剪贴板粘贴路径**（复制→Ctrl+V）减少键鼠注入面
- `cu_position`（auto）：光标/目标窗口 bounds，附坐标系换算元数据（截图缩放 vs 物理像素——工具输出统一带 scale，模型一律给物理屏坐标）

**安全**：键鼠一律 confirm（永不 auto）；硬禁表（系统级快捷键如 Win+L、关机类、格式化类）；截图仅本地落盘，**出网需另设显式开关**（VibeIDE imageQA 闸门先例）；输入注入要求目标前台（ZCode 观察层 frontmost-mismatch 经验进工具错误提示）。

**选型已拍板（2026-10-05，采纳 pc-b d0208e5 意见）**：键鼠注入 = **自写 napi-rs SendInput 模块**（避开 GPLv3 与解释器依赖，沿 COD native/napi-rs 先例；ABI 重建走 better-sqlite3 同款 electron-rebuild 路径）；执行上**截图先行**（M21 第一刀 = 只读截屏工具+渲染，键鼠第二刀）。屏幕文本 = Windows OCR 系统 API 免依赖起步（AX 抽取作补充）。

**借鉴出处**：ZCode 工具语义/审批/CUA 渲染器（`apps/zcode-cli/packages/core/src/tool/handlers/`、`packages/ui/src/ToolCallBlocks/renderers/cua.tsx`）、VibeIDE 出网闸门与 allowlist（`common/imageQA/securityGuardrails.ts`、`electron-main/mcpChannel.ts`）、harness 观察层失联/stale 判定协议（进工具重试语义）。

**验收**：①截屏行+缩略渲染可见且元数据 scale 正确 ②点击目标窗口输入文字（全链 confirm 审批、拒绝时零注入）③`cu_type` 经剪贴板粘贴落地 ④硬禁表命中被拒 ⑤审批卡拒绝后光标/剪贴板无变化（盘上/日志双证）。

**声明的边界**：不做后台窗口注入、不做游戏级低延迟、不做模型端 vision 依赖（v2 再议）。

## 4. 风险与纪律

- **GLM 并发配额**：多主代理+子代理并发共享双槽；并发上限默认 4，worker 槽分担摘要/抽取；usage 台账先行，超限排队不报错
- **同文件并发**：desktop 内多人协作时按 agents_log 纪律（开工读对方尾部 20 条、路径式提交、动共享契约先记 change）
- **安全边界**：contextIsolation 不放宽；preload 白名单按域扩张；shell/文件域一律过审批内核（阻塞式、与 UI 解耦）；禁明文凭据（沿用 safeStorage 思路，Windows=DPAPI）
- **Mimosa 判定**：spawn 字面量命令名+PATH 前置；标识符不含 ApiKey/Token 字面量
- **SQLite 引入**：better-sqlite3 需 electron-rebuild；迁移框架照 ZCode `migration-runner` 模式；规划数据不迁、dump 合同不动
- **全链禁 PowerShell**；构建 `npm run dev/dist`；typecheck 双配置保持 0 错
