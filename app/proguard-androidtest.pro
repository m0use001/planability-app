-dontwarn com.google.errorprone.annotations.MustBeClosed
# 测试 APK 被 R8 压掉了 kotlin stdlib（androidx.test 用到 kotlin.LazyKt），测试包不心疼体积，整包保留
-keep class kotlin.** { *; }

# 2026-11-04 instrumentation 全家桶缺口（主包 R8 裁掉的内部类被测试框架引用）：
# kotlinx DelayWithTimeoutDiagnostics、compose InfiniteAnimationPolicy$DefaultImpls…
# 打地鼠没完——测试 APK 直接整包 keep（体积无关紧要，只影响测试包）。
-keep class androidx.compose.** { *; }
-keep class kotlinx.coroutines.** { *; }

# POI 引用 java.awt.geom（桌面 AWT），Android 上没有；测试包只做引用检查
-dontwarn java.awt.**
