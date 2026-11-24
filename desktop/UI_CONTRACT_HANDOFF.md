# 交给 pc-a 的 UI 契约清单（pc-b，2026-10-06）

**这份文件的存在理由**：主进程侧我已完成 **M21 / 悬浮窗 / M19 / M20** 共十余刀的 IPC 与契约，
而这些**几乎全部的界面都在你的线上**。继续各写各的只会让契约漂移，所以先把「调用什么、
返回什么、哪些是硬要求」写死成一份清单。

**每条都标注了「硬要求」** —— 那些不是风格偏好，而是我在主进程侧刻意设计、
**违反了会产生静默错误**的点。

---

## 0. 前置：已就绪的通道总览

全部走 preload 桥接（`window.planability.*`），通道常量在 `shared/chat.ts` / `shared/float.ts`。

| 命名空间 | 状态 |
|---|---|
| `window.planability.chat` | M17/M19/M20 新增已完成，见下 |
| `window.planability.float` | 悬浮窗专用，见下 |
| `window.planability.onWorkflow` | 工作流步骤进度推送 |

---

## 1. 悬浮窗（`window.planability.float`）

**你的文件**：`src/renderer/float.html`（现在是**占位页**，只写了「待 pc-a 构建」）+
新建 `src/renderer/src/float/`。构建入口我已配好（`electron.vite.config.ts` 多入口）。

### 硬要求三条

1. **草稿必须经 `draftGet`/`draftSet` 读写主进程**，不许把输入框的 state 当真相源。
   原因：输入框要能在两个 BrowserWindow 之间搬，搬完是新 renderer 空白 state。
2. **`draftSet` 会返回 `{ok:false, reason:'stale-revision', current}`**（两个窗口同时持有焦点时）。
   收到就**用 `current` 重新 `adopt`，不要自己合并** —— 两段分叉文本无法安全合并。
3. **不许自己判断该不该进悬浮模式**。调 `enter()`，它会因 streaming 拒绝并**给出可直接展示的
   `reason`**，自己判断会漏掉那条语义。

### 契约要点

- 二级菜单 = **3 个 mode 单选（全屏/矩形/窗口）+ 1 个快门**，**不是四个并列捕获按钮**。
  `mode` 是持久状态、截图是一次性动作（用户明确纠正过的模型）。
- `shutter()` 在窗口模式下**可能返回 `{ok:false, needTarget:true}`** —— 此时要先 `listTargets()`
  弹选择器，再按 id 捕获。
- 图片附件：**Q4=A，位图不进模型上下文**。附件只有 `{path, width, height, bytes}`，
  chip 用 `path` 加载本地 PNG 显示缩略图即可。
- `setLinked(role|'all', boolean)` 就是 Q3=A 的显式链条开关，**可逆**。

---

## 2. M19 上下文面板（`window.planability.chat.context*`）

### 硬要求

**面板绝不能自己算 token。** 请求还带着系统提示和全部工具定义，渲染层按可见文本数出来的数字
**和供应商账单不是一个数**。`contextManifest()` 是唯一真相源。

### 契约

```
contextManifest()      → ContextManifest | null   // 上一次请求的逐项分解
contextTogglesGet()    → Partial<Record<ContextKey, boolean>>
contextTogglesSet(t)   → 同上
contextHistory(limit?) → ContextManifest[]        // 每请求一条的台账
```

`ContextManifest.rows[]` 已按 tokens 降序排好，每行带 `{key, label, tokens, share, enabled, compacted}`。
**`tier` 必须显示出来**（`none`/`micro`/`fold`/`summary`）—— 上下文被降级时用户必须知道，
否则模型三轮后违反指令都没人看得出原因。

`ContextKey` 六项：`system / tools / memory / todo / persona / history`。

---

## 3. 记忆检视页（`memory_history` 工具 + `memories` 集合）

### 硬要求

**展示 `forget` 时要说清「审计轨迹仍在，可恢复」**，不要显示成"已永久删除"。
这是 append-only 日志的核心价值，UI 若把它藏起来，用户就不敢删东西。

- `memory_history` 工具返回逐条事件（`#seq / 时间 / kind / source / scope / reason / body`）——
  这就是「可检视」的入口。
- `memories` 集合是**投影**，不是真相。
- 作用域：`user` 跨项目可见，`workspace` 只在对应 `workspaceRoot` 可见。**别自己过滤**。

---

## 4. Skills 管理页（`skillsDir`，默认 `<userData>/skills`）

```
skills.list()               → SkillListResult   // { skills: SkillInfo[], problems[], dir }
skills.read(key)            → SkillFile         // { key, text, exists, origin, editable, preview, problems }
skills.write(key, text)     → SkillWriteResult  // { ok, error?, key?, file?, replaced?, problems? }
```

### 硬要求

**读写回环的单位是「文件原文」，不是结构化字段。** 页面是文件上的**文本框**，不是表单。
如果 `write` 收 `{name, description, body...}` 再序列化回去，**每次保存都会静默丢掉 UI 不认识的
frontmatter 键、并重排文件** —— 而这个模块存在的理由就是「技能是人拿自己的编辑器写的」。
`read` 因此回 `{text, preview, problems}`：`text` 给编辑器，`preview` 给「这条会被加载成什么样」，
`problems` 让用户在**保存之前**就看到缺 frontmatter。

**`editable: false` 的项不要给编辑框。** 内置技能在应用包里，写进去会被下次更新冲掉；
对它们请提供「创建我的副本」—— `write` 传内置的 key 会落到用户目录形成覆盖，
返回里 `replaced: false`，据此把文案从「已保存」改成「已创建你的副本，内置版保留」。
之后 `list()` 里那条会带 `overridden: true`。

**`write` 返回 `ok: true` 不代表写对了，`problems` 才是结论。** 加载侧的设计是
「坏文件跳过、不致命、一个错别字不该让人关掉整个技能系统」，所以写侧**不拒绝**半成品
（用户在自己的编辑器里存草稿是正常状态）—— 如实回报加载器的判断，让 UI 说话。

**`ok: false` 必须显示 `error`，绝不静默。** 被拒的只有三种：key 清洗后为空、
文件超过 80000 字、写入失败（目录不可写等）。

**`read` 对不存在的 key 返回 `exists: false` + 空 text，不是错误** —— 页面据此开空白编辑器新建。

**`read` 两种拼写都认**：frontmatter 里的 `name`，或文件名主干。内置的「周计划」住在
`weekly-plan.md` 里，用户不该需要知道这件事。

**用户副本覆盖内建时必须显示出来**（`skill.overridden` 标记）。静默覆盖会让用户无法判断
自己改的那版有没有生效 —— 这是我第一版的 bug。

- frontmatter：`name / description / trigger / priority / enabled / keywords: [a, b]`
- 坏文件**不会让整份技能列表失效**，但主进程会 `console.warn`，`loadSkills` 的 `problems` 里有理由。
  建议在设置页给一个「有 N 个技能文件有问题」的入口。
- 技能正文进系统提示时被**框架化为「参考资料，非指令」**，所以模型不会把它当操作指令执行。
  但**它确实占 token**：未命中的技能不会注入（按 keywords 选）。
- **没有删除通道**，是刻意的：删除没有 undo，而覆盖内建已经能满足「我不想用内建那版」。
  真要做，同 key 的旧文件建议先 `.bak`。

---

## 5. M20 工作流编排页（`window.planability.chat.workflow*`）

```
workflowList()               → WorkflowInfo[]
workflowValidate(w)          → { ok, errors[] }     // 不跑，只校验
workflowRun(workflowId, task)→ { ok, runId?, error? }
workflowReplay(runId)        → { ok, text? }
workflowRuns(limit?)         → WorkflowRunInfo[]
onWorkflow(cb)               → () => void
```

### 硬要求

1. **`workflowReplay` 不调任何 agent、不写任何东西** —— 这正是它的用途（回放一个睡醒后跑歪的
   定时任务）。**别在 UI 上"顺手"加个重跑按钮**。
2. **失败要显示 `failedAt` 和逐步状态**。工作流**失败即 halt**（不是跳过继续），所以
   `steps.length` 会小于工作流总步数 —— 别当成数据错误。
3. 角色是**按 name 解析**（工作流写 `researcher`，agent 改 key 也不失效）。未命中会报
   「没有名为 X 的 agent」，要展示出来而不是静默。

---

## 6. 自动化（`Automation` 新增四个可选字段）

`kind: 'ask'|'agent'|'workflow'` / `workflowId` / `taskText` / `timeoutMinutes`。

### 硬要求

- **全部可选且读取时回落**，所以 `automationList()` 的形状对旧客户端**完全兼容**，
  别改老字段。
- **`kind` 未知一律当 `ask`**（不派发 agent）—— 绝不会因为读到一个没见过的值就把用户的
  每日简报变成派 agent 的任务。UI 也别把未知值当 agent 显示。
- 新建 agent 任务时**没有 task 文案会被拒绝**（避免把字面量占位符当任务发出去）。

---

## 7. 一个跨所有页面的提醒

**`shared/*.ts` 不能 import `src/main/**`** —— `tsconfig.web.json` 只含
`src/renderer/**` 与 `shared/**`，反向 import 会让**所有碰该契约的渲染层文件 typecheck 失败**。
所以两边各自声明形状，由 `src/main/ai/float/conformance.ts` 用编译期断言证明一致。
**你若看到类型对不上，改 `shared/` 侧，并同步扩 `conformance.ts` 的断言，不要改成 import。**

---

## 8. 我这边剩下的

- 验证者自动修复环（Laya COSMOS 先例，M20 第 1 项尾巴）
- `team-assemble` / `handoff` 的 IPC 通道（模块已就绪，未接）
- 运行态 + AX 验收**全部归你**：悬浮窗拖拽、截屏三模式真跑、托盘高 DPI、
  技能注入是否真的被执行、M21 注入是否点得中

我只有单测与构建证据，**不冒认运行态**。
