package io.github.m0use001.planability.ai.office

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「AI 生成的文件按对话存放」这条约定：**扫描侧必须递归到子目录**。
 *
 * ## 为什么需要这条测试
 *
 * 2026-10-11 我实现了 `dirFor`，落点规则是：
 * - 项目内 + 有文件夹 → `projects/<项目名__id>/<文件夹名>/`
 * - 项目内 + 无文件夹 → `projects/<项目名__id>/`
 * - 未分类 + 无文件夹 → `projects/_global/<标题__id>/`
 *
 * 但同一版里我写的 `allFiles` 只 `listFiles { isFile }` **扫一层**。
 * 前两种落点在深度 2，第三种也是深度 2 —— 于是**刚生成的文件在导出列表里一个都不显示**，
 * 只有 v14.3 之前的旧扁平 `exports/` 能看见。编译、单测、提交全绿，功能是坏的。
 *
 * 之所以漏：设备上 `_global` 当时是空的，**没有真实数据能暴露它**；
 * 而 `allFiles` 依赖 `Context`/`Graph`，纯 JVM 单测跑不到，
 * 所以只能靠读代码发现。写这条测试就是为了让它下次自己变红。
 *
 * ## 为什么用源码级断言而不是行为断言
 *
 * `ExportStore` 是 `object`，`allFiles(context, limit)` 要 `Context` 和数据库，
 * JVM 单测里没有。要真跑行为断言就得把它重构成可注入的纯函数——
 * 那是为一个 bug 做一次架构改动，不划算。约定本身是**"扫几层"**，
 * 钉住"必须有递归、且有深度上限"就够了：真出问题时这条会指着她怀疑的代码。
 */
class ExportStoreScanConventionTest {

    private fun repoRoot(): File {
        // 工作目录通常是模块目录（app/），也可能被构建配置挪动，向上找总没错
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "app/src/main/java/io/github/m0use001/planability").isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError(
            "找不到仓库根（从 ${System.getProperty("user.dir")} 一路向上都没见到 " +
                "app/src/main/java/io/github/m0use001/planability）——这条测试要读源码，" +
                "找不到就说明工作目录不对，别把它当成'约定没被破坏'"
        )
    }

    private fun exportStoreSource(): String {
        val f = File(
            repoRoot(),
            "app/src/main/java/io/github/m0use001/planability/ai/office/ExportStore.kt"
        )
        assertTrue("ExportStore.kt 不见了（改名/挪包了？）", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    @Test
    fun `扫描侧必须递归进子目录而不是只看一层`() {
        val src = exportStoreSource()
        assertTrue(
            "ExportStore 里找不到 collect(...) 的递归调用 —— " +
                "落点在深度 2（<项目名__id>/<文件夹名>/ 与 _global/<标题__id>/），" +
                "只扫一层的话**刚生成的文件在导出列表里一个都不显示**，而编译和单测都是绿的。" +
                "\n注意设备上目录为空时看不出问题，别拿'测试过了'当证据。",
            src.contains("collect(")
        )
        assertTrue(
            "collect 里没有对子目录递归（collect(f, out, depth + 1)）—— " +
                "只收当前层的文件等于没修",
            src.contains("collect(f, out, depth + 1)")
        )
    }

    @Test
    fun `递归必须有深度上限`() {
        val src = exportStoreSource()
        assertTrue(
            "MAX_SCAN_DEPTH 不见了 —— 这是 Syncthing 同步目录，可能有环（软链/回指），" +
                "无界递归会直接栈溢出。业务上只有两层，上限不需要很大，但不能没有。",
            src.contains("MAX_SCAN_DEPTH")
        )
        assertTrue(
            "collect 里没有按 MAX_SCAN_DEPTH 提前返回 —— 定义了常量却没用等于没限深",
            src.contains("depth > MAX_SCAN_DEPTH")
        )
    }

    @Test
    fun `旧扁平目录仍然要一并扫描`() {
        val src = exportStoreSource()
        assertTrue(
            "legacyFiles 不再扫 legacyDir 了？—— v14.3 之前生成的文件还在旧位置，" +
                "「导出文件」整页删掉后它们改由文件库底部的「旧导出目录」分组收着；" +
                "这一组没了就等于告诉用户文件被删了",
            src.contains("collect(legacyDir(context)")
        )
    }

    /**
     * 落点深度的对照表：这几条是 [ExportStore.dirFor] 与扫描侧**共同的前提**。
     * 哪一侧改了目录层级而另一侧没跟上，表现都是"文件生成了但列表里没有"。
     *
     * 2026-10-29：落点从"项目根 / 项目内文件夹 / _global 三档"统一收进
     * `<项目数据>/[当前会话|已归档会话]/<标题__id>/`（见 `ProjectDirs`）。
     * 所以这里钉的是新约定的入口：会话目录只许经 `ProjectDirs` 解析
     * （手拼路径 = 迟早和扫描侧分叉），旧扁平导出目录仍要能找。
     */
    @Test
    fun `目录层级约定写清楚了`() {
        val src = exportStoreSource()
        for (marker in listOf("ProjectDirs.ensureSessionDir", "ProjectDirs.findSessionDirs", "legacyDir")) {
            assertTrue(
                "ExportStore 里找不到 `$marker` —— 会话目录必须经 ProjectDirs 解析" +
                    "（当前会话/已归档会话 两处、含标题改名自愈），手拼路径会让写入与扫描分叉",
                src.contains(marker)
            )
        }
    }
}