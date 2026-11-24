# 打 Windows 安装包（exe / 快捷方式 / 开始菜单）

```powershell
cd desktop
npm run dist
```

产物在 `desktop/dist/`：

| 文件 | 用途 |
|---|---|
| `Planability-0.1.0-setup.exe` | NSIS 安装包：装到 `%LOCALAPPDATA%\Programs\Planability`，创建**桌面快捷方式**和**开始菜单快捷方式**，写入「应用和功能」卸载项（当前用户级，不需要管理员） |
| `Planability-0.1.0-portable.exe` | 单文件绿色版，不装、不建快捷方式 |
| `win-unpacked\` | 未打包目录，排查「装完打不开」时先跑 `dist\win-unpacked\Planability.exe` |

`setup.exe` 支持 `/S` 静默安装（本次验证就是这么做的）。

## 开始菜单在哪

`%APPDATA%\Microsoft\Windows\Start Menu\Programs\Planability.lnk`

`menuCategory: false` 是有意的：不建子文件夹，快捷方式直接落在「所有应用」顶层，开始菜单里打字搜 "Planability" 立刻命中。若改成子文件夹（比如 `"menuCategory": "Planability"`），它会藏在「所有应用 → Planability → Planability」两层里。

## 图标

`resources/icon.ico`（16/32/48/64/128/256 六档）由 `npm run icon` 生成：

```
scripts/icon-source.html  --(Chromium rasterize)-->  resources/icon.png  -->  resources/icon.ico
```

源图形是**手机启动器那套**（`app/src/main/res/drawable/ic_launcher_{fg,bg_day}.xml` 的竖轴 + 三条递减横线 + 主题推导蓝 `#5464C7`）。之所以绕一圈用 HTML/SVG 而不是直接放个 ico：Electron 的 `nativeImage` 既不认 SVG 也不认 WebP，浏览器窗口认。留着 `icon-source.html` 是为了手机那边改图标时能重新算出来，而不是变成一个没人知道怎么重建的二进制。

## 打包时容易踩的两个坑（都已修在 package.json 里）

1. **`files: ["out/**/*"]` 会漏掉 out/ 之外的运行期文件。** 计算注入的 napi 模块在 `native/planability-cu/planability-cu.node`，`cu-native.ts` 从 `process.resourcesPath/native/...` 找它。所以用 `extraResources` 显式带进去 —— 漏了的话电脑操作类工具在安装版里全部失效，而开发时（从仓库跑）完全正常，很难联想到是打包配置的问题。
2. **没有 `win.icon` 就是 Electron 的默认原子图。** 安装后快捷方式和开始菜单显示的是那个图，用户第一眼看到的就是它。

## 已验证的事实（2026-10-18）

- `npm run dist` exit=0，产出 setup.exe / portable.exe 各 ~95 MB。
- `win-unpacked\resources\native\planability-cu\planability-cu.node` 存在；`app.asar.unpacked` 里有 better-sqlite3 的 `.node`。
- 静默安装后：桌面与开始菜单两个 `.lnk` 都存在、都指向 `...\Programs\Planability\Planability.exe`，`IconLocation` 指向 exe；从开始菜单快捷方式启动，主窗口标题为 `Planability`。
- 从安装版 exe 抽出的图标与 `resources/icon.png` 同一张（不是默认原子图）。
- 打包后 `npm test` 仍 exit=0（53 个日志 2637 PASS），`typecheck` exit=0 —— 说明给 Electron ABI 重建 better-sqlite3 没有把开发态测试带坏。
