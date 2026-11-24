# tools/perf —— 体积体检的尺子

纯 Python 标准库，无依赖。用法都是 `py -u tools/perf/<脚本> <参数>`。
方案与实测数字见 `设计文档`。

## 为什么需要它们

这个项目踩过一次"没量就动手"的坑：为了修小组件把 R8 关掉，APK 从 83.4MB 涨到 123.1MB（+39.7MB），
而真正的原因只是缺一条 keep 规则。所以体积相关的任何改动，**先量、改完再量**。

## 1. `apkbrk.py <apk>` —— APK 到底把字节花哪了

按 **zip 条目压缩后字节**（`compress_size`）分组求和，这才是 APK 文件里真实的占用。

**别用 .so 的原始大小当体积**：`useLegacyPackaging = true` 意味着 native lib 在包里是 deflate
压缩、装包时解压。`libsyncthingnative.so` 原始 23.8MB，进包只有 10.30MB —— 差 2.3 倍。

```
py -u tools/perf/apkbrk.py app/build/outputs/apk/release/app-release.apk
```

## 2. `zipslack.py <apk>` —— 有没有对齐填充造成的"假体积"

扫 zip 本地文件头，算"文件大小 − 所有条目 csize"，并列出条目之间 >4KB 的空隙。

**已知结论**：`app-debug.apk` 文件 90.6MB，条目合计只有 67.7MB —— **22.92MB 是 64KB 页对齐填充**
（7082 个条目里约 350 个前面垫了 65536 字节）。release 同项只有 0.67MB。

**所以 `debug 90.6MB` 不是体积指标**。要么看 payload（67.7MB），要么看 release（43.2MB）。

## 3. `dexattr.py <map|usage> <文件> [深度] [输出]` —— dex 为什么这么大

用 R8 产物按类名前 N 级包归因：

- `map` + `app/build/outputs/mapping/release/mapping.txt` → **保留**的类（8290 个）
- `usage` + `app/build/outputs/mapping/release/usage.txt` → **被 R8 删掉**的类（23124 个）

```
py -u tools/perf/dexattr.py map app/build/outputs/mapping/release/mapping.txt 3 tmp/kept.txt
```

## 4. `dexstr.py <dex> <字符串...>` —— 直接在 dex 字节里搜（终极证据）

dex 的字符串表就是 UTF-8，直接字节搜索即可。用途：**mapping.txt 与真包可能不一致**时用它定案。
例：release mapping 里 `androidx.compose.material.icons.*` 一个类都没保留（R8 全裁），
但 debug 的 `classes.dex` 里还能搜到 6396 处 `material/icons/filled/`。

```
py -u tools/perf/dexstr.py app/build/intermediates/.../classes.dex "material/icons/rounded/"
```

## 5. `aarls.py [组名前缀...]` —— 查依赖库里到底有什么类（别再照抄老资料）

在 Gradle 缓存里翻 AAR、读出嵌套的 `classes.jar`、列出类名。零成本的事实来源，
用来确认某个 API 在当前版本里**到底存不存在**。

真实案例：`CompilationMode.Default` 在很多教程里都有，但 benchmark 1.5.0 的 AAR 里
只有 `None / Ignore / Interpreted / Partial / Full`，照抄就编译不过。

```
py -u tools/perf/aarls.py androidx.benchmark
py -u tools/perf/aarls.py androidx.test      # 看 runner 里有没有 filters.LargeTest
```

> 注意：这个脚本读的是**本机 Gradle 缓存**（`~/.gradle/caches/modules-2/files-2.1`），
> 所以必须先把依赖下载过一次（跑一次构建即可）。
