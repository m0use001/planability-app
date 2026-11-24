# ---- R8 keep 规则 ----

# JNA 反射入口（vosk-android 经 JNA 加载 libvosk.so；R8 会破坏 fromNative 反射查找）
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { *; }
-dontwarn java.awt.**
-dontwarn java.beans.**

# Vosk JNI/JNA 桥
-keep class org.vosk.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# GenieX 端侧推理 SDK（com.qualcomm.qti:geniex-android）：
# libnpu_jni.so 按**类名/方法名/字段名**反射访问 bean（如 HubSource.getValue()I、
# ModelPullInput.model_name），这些成员在 Kotlin 侧没有静态调用点，R8 会当死代码剥掉 →
# 运行时 NoSuchMethodError（JNI 检测到 pending exception 直接 abort，进程 SIGABRT）。
# 2026-10-22 真机实测：不 keep 时 pull 必崩（0.3.1 与 0.8.0 同样表现）。
-keep class com.geniex.sdk.** { *; }

# ---- Material 图标（封面图标选择器靠反射枚举） ----
# material-icons-extended 的图标是**顶层扩展属性**，编译成
# androidx/compose/material/icons/filled/XxxKt.getXxx(Icons.Filled)。
# 代码里只按类名字符串反射，R8 判定不可达，会把整个 filled 包删干净——
# 真机表现为 Material 系列一个图标都没有（日志 MAT=0），而 rounded 因为
# 有直接引用反而活着。所以这一包必须整包 keep。
-keep class androidx.compose.material.icons.filled.** { *; }

# ---- androidx.work（桌面小组件的命根子） ----
# 本项目 **debug 也开了 R8**（见 app/build.gradle.kts），而 WorkManager 完全靠反射造对象：
#   1. `InputMerger`：`Class.newInstance()`（androidx.work.InputMerger.fromClassName）
#   2. `Worker`：`Class.forName(...).newInstance(Context, WorkerParameters)`
# 这两处都不存在「直接调用」，R8 会判定不可达并**裁掉无参构造/构造签名**。
#
# 后果极其隐蔽（2026-10-17 实测）：真机日志只有一行
#   E WM-InputMerger: java.lang.InstantiationException:
#       java.lang.Class<androidx.work.OverwritingInputMerger> has no zero argument constructor
#   E WM-WorkerWrapper: Could not create Input Merger androidx.work.OverwritingInputMerger
# 然后那个 worker 就不跑了。而 **Glance 的小组件 session 正是跑在 WorkManager 的
# `SessionWorker` 里**，于是 `provideGlance` 永远不被调用 → 桌面组件永远停在
# initialLayout 的「加载中…」→ 没有任何属于我们代码的异常、Glance 也不打错误日志。
# 排查时几乎不可能往「R8 裁了构造器」上想，所以这条注释刻意写长。
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.InputMerger { <init>(); }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-dontwarn androidx.work.**

# ---- Glance 的 ActionCallback（同一个 R8 坑，别漏） ----
# `actionRunCallback<MyAction>()` 只把**类名**塞进 Intent，真正执行时由
# `ActionCallbackBroadcastReceiver` 按类名反射还原并实例化。和上面 WorkManager 一模一样：
# 没有直接调用 → R8 判定不可达 → 类被裁或构造器被裁 → 点小组件上的勾选框/按钮**毫无反应**，
# 而且不会有任何崩溃日志（异常在广播接收器里被吞掉）。
-keep class androidx.glance.appwidget.action.** { *; }
-keep class * implements androidx.glance.appwidget.action.ActionCallback { *; }

# ---- ML Kit OCR（同一个 R8 反射坑，第三个实例）----
# 2026-10-22 真机实证：地图瓦片 OCR 调 `InputImage.fromBitmap()` 抛
# `NullPointerException: getClass() on null`，栈在 vision-common 内部的
# LazyInstanceMap/zzmr.create —— 那是**按类型反射取实现**的注册表，R8 把
# "只被反射引用"的类裁掉后 key 变 null。项目里 file_ocr / 截图采集也用同一路径，
# 但调用处包了 runCatching，所以一直静默失败没人发现。
# 保留整包（小，OCR 运行时就两三个 MB，不能省）。
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.gms.internal.vision.** { *; }
-dontwarn com.google.mlkit.**

# androidx.test 的 Tracer 引用了 errorprone 注解（仅编译期），R8 缺类告警按官方 missing_rules 加 dontwarn（2026-10-30 phone-b，androidTest 跑位图渲染验收用）
-dontwarn com.google.errorprone.annotations.MustBeClosed

# androidx.test 的 OutputDirCalculator 引用 kotlin.LazyKt；R8 会把 app 包里没人用的它裁掉，
# instrumentation 启动即 NoClassDefFoundError（androidTest 跑不了）。补 keep（2026-10-30 phone-b）。
-keep class kotlin.LazyKt { *; }
-keep class kotlin.collections.** { *; }
-keep class kotlin.ranges.** { *; }
-keep class kotlin.sequences.** { *; }

# ---- kotlin stdlib 整包 keep（2026-10-31，把逐个补门面改成一次管够）----
# 背景：测试 APK 不带 kotlin stdlib（R8 把它当 library，运行时靠主包提供），
# 而 R8 会把 stdlib 的「门面类」（IntrinsicsKt 这类 @JvmName 门面）横移合并进
# Impl 类——主包内部引用会被同步改写（App 照常跑），但**测试 APK 的引用改不到**，
# 结果是仪器化测试随机哪个门面缺了就全量 ClassNotFoundException。
# phone-b 2026-10-30 踩过 LazyKt，今天又轮到 kotlin.coroutines.intrinsics.IntrinsicsKt
# ——补丁式追不完，整包 keep 保住原始类名（体积 +~2MB，相对 200MB 包可忽略）。
-keep class kotlin.** { *; }

# ---- jlatexmath 整包 keep（2026-11-02，LaTeX 宏全灭根因）----
# jlatexmath 的**宏表按方法名反射查** PredefMacros 的静态方法（TeXFormula 静态初始化时
# getMethod(name, TeXParser, String[])）；debug 包也开 R8，改名后查不到 → 宏注册成
# MacroInfo(null) → 运行时 `MacroInfo.invoke` NPE。症状：`\begin{pmatrix}` / `\operatorname`
# 等宏命令整段回退裸文本，而 `\lambda`（符号表，非反射）一切正常——线性代数会话整表公式
# 裸显即此因（真机栈：MacroInfo.invoke → TeXParser.firstpass）。
-keep class org.scilab.forge.jlatexmath.** { *; }
-keep class ru.noties.jlatexmath.** { *; }


# ---- onnxruntime 整包 keep（v51，2026-11-05）----
# ORT 的 JNI 用 NewObject + 方法签名直接构造 ai.onnxruntime.TensorInfo / OnnxTensor，
# R8 改名后 jmethodID 查不到，**在 native 层 SIGSEGV**（栈：GetMethodShorty →
# convertToTensorInfo → OrtSession.run）。症状极具误导性：建会话正常、一 run 就崩，
# 看起来像模型坏了。debug 包也开 R8，所以模拟器/真机都会中。
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ---- POI / PDFBox 文档解析（2026-11-09 第一刀）----
# POI 的 OOXML 靠 xmlbeans 的 SchemaTypeSystem 反射建模型；R8 改名/裁类后
# 运行时 NoClassDefFoundError / XmlException。这些包整包 keep（体积可忽略）。
-keep class org.apache.poi.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-keep class org.openxmlformats.** { *; }
-keep class schemaorg_apache_xmlbeans.** { *; }
-keep class com.tom_roush.** { *; }
-keep class com.tom_roush.pdfbox.** { *; }
# 可选依赖：POI/xmlbeans 引用了 Saxon/StAX/OSGi/log4j 的符号，但运行时不需要。
# 不 dontwarn 的话 R8 直接 Missing class 失败（debug 包同样开 R8）。
-dontwarn javax.xml.stream.**
-dontwarn net.sf.saxon.**
-dontwarn org.osgi.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.**
-dontwarn javax.imageio.**

# poi-ooxml-lite 只带部分 OOXML schema；图表(chart)相关类在 full schema 包里，
# 我们用不到（只抽文字），声明 dontwarn 让 R8 不再因引用不存在而失败。
-dontwarn org.openxmlformats.**

# ---- log4j（POI 的日志依赖，2026-11-09 设备实测）----
# POI 5.x 依赖 log4j-api；AbstractLogger 静态初始化时按**类名字符串**反射
# newInstance DefaultFlowMessageFactory。R8 改名后字符串与真实类名对不上 →
# InstantiationException → ExceptionInInitializerError，XWPFDocument() 直接建不出来。
# 只在真机暴露（JVM 单测不混淆，永远绿），所以整包 keep。
-keep class org.apache.logging.log4j.** { *; }

# ---- commons-compress（POI 读写 zip 的依赖，2026-11-09 设备实测）----
# ExtraFieldUtils 静态块用反射注册所有 ZipExtraField 实现（getConstructor().newInstance()），
# R8 改名/删构造后 InstantiationException。整包 keep（约 1MB，相对总包可忽略）。
# 只 keep zip 包（ExtraFieldUtils 反射实例化的都在这里）；keep 全库会把
# commons-compress 对 zstd/brotli/asm/xz 的**可选**依赖变成活引用 → R8 Missing class。
-keep class org.apache.commons.compress.archivers.zip.** { *; }
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn org.tukaani.xz.**

# 2026-11-12 模拟器 compose ui-test 首跑：主包 R8 裁掉了 ui-test 运行期要引用的
# kotlinx DelayWithTimeoutDiagnostics（主代码不引用、测试包引用）→ NoClassDefFoundError。
# 与 app/proguard-androidtest.pro 2026-11-04 的注释同族（打地鼠），按类点名不整包。
-keep class kotlinx.coroutines.DelayWithTimeoutDiagnostics { *; }
# 2026-11-12 同族第二只：ui-test 的 TestRule 初始化引用 InfiniteAnimationPolicy$DefaultImpls，
# 主代码不引用 → 主包 R8 裁掉 → 测试包 ClassNotFound（androidtest.pro 2026-11-04 注释预告过）
-keep class androidx.compose.ui.platform.InfiniteAnimationPolicy$DefaultImpls { *; }
