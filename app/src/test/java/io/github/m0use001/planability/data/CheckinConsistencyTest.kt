package io.github.m0use001.planability.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-14：**打卡状态一致性守卫**（用户实测「先打卡再取消，设百分比打卡后卡死——
 * 不显示已打卡、时间轴却暗色、点进去又显示已打卡、没开数据记录却让记录数据」）。
 *
 * 病根与 [TodoProgressTest] 互补：那份钉**判定**（胶囊形态/权重），这份钉**写路径**——
 * 打卡的四个事实（done、完成流水、监督卡、数据/百分比流水）必须由同一条链写入、
 * 同一条链撤销。任何一个入口只写一半，就会出现「这里已打卡、那里没打卡」的状态分裂：
 *
 * 1. **undo 归一**：completeTodo 撤销分支必须清全部四样 + 行上满态百分比归零
 *    （原来挂在传入快照的开关上——开关后来关掉时记录赖着不走，没开数据记录
 *      的待办还能「改记录数据」）。
 * 2. **「撤销某天」唯一入口**：面板/计划页/编辑器三处的 removeCheckin+toggleCheck
 *    已废——toggle 在没卡时会**打出一张卡**（撤销变打卡），且不撤 done 与完成流水。
 *    三处必须走 PlanRepo.undoCheckinDay，toggleCheck 整体退役。
 * 3. **浮层入口跟随开关**：「改记录数据」只允许出现在开了数据记录的待办上。
 * 4. **编辑器满态走正规打卡**：saveTodo 不许手工 copy(done=true)——那条旁路
 *    不写流水/监督卡，打卡面板与 AI 全都看不到。
 * 5. **浮层状态跟随库**：百分比浮层只存 id、渲染时从 bundle 现取，
 *    不许再存打开瞬间的快照对象（打卡后浮层仍显示旧状态）。
 */
class CheckinConsistencyTest {

    private fun repoRoot(): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = java.io.File(dir, "app/src/main/java/io/github/m0use001/planability")
            if (f.isDirectory) return dir!!.absolutePath
            dir = dir.parentFile
        }
        throw AssertionError("找不到仓库根——这条测试要读源码")
    }

    private fun src(rel: String): String =
        java.io.File(repoRoot(), "app/src/main/java/io/github/m0use001/planability/" + rel).readText()

    /**
     * 去掉 // 行注释与 [// 后再判定（照 SingleSourceTest 的先例）。
     * 本次守卫的第一轮假红就是教训：Editors 的修复注释里写了「不再手工 copy(done=true)」，
     * 注释形状骗过了「禁止 copy(done = true)」的断言。守卫只认**代码**。
     */
    private fun code(text: String): String =
        text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .lines()
            .joinToString("\n") { line ->
                val i = line.indexOf("//")
                if (i >= 0) line.substring(0, i) else line
            }

    // ------------------------------------------------------------------ 1. undo 归一

    @Test
    fun `撤销打卡清全部打卡事实且行值归一`() {
        val w = code(src("data/PlanRepo.kt"))
        // 撤销分支的行写回必须带走 undoRollbackPercent（**一律清空**，用户 11-14 定稿：
        // 「撤销打卡后百分比与胶囊都要清空」——留个 80% 的分不清撤销成功还是没生效）
        assertTrue(
            "撤销分支没把行上百分比清空（TodoProgress.undoRollbackPercent）——" +
                "撤销后胶囊还填着、完成率也对不上（用户视角「撤销没生效」）",
            w.contains("percentDone = TodoProgress.undoRollbackPercent(todo.percentDone)")
        )
        // 规则本体：启用百分比的一律归 0（不再有「半途保留」分支）
        val tp = code(src("data/TodoProgress.kt"))
        assertTrue(
            "undoRollbackPercent 又变成「只有满态归零」了——用户要求撤销后百分比都清空",
            tp.contains("if (isEnabled(percentDone)) 0 else percentDone")
        )
        // 流水删除不许再挂在传入快照的开关上（开关后来关掉时残留——
        // 没开数据记录的待办还能「改记录数据」的根源）
        assertFalse(
            "撤销分支的数据记录删除还挂在 dataRecordEnabled 上——开关关掉后记录残留",
            w.contains("if (todo.dataRecordEnabled) checkinDao.deleteOfDay")
        )
        assertFalse(
            "撤销分支的百分比流水删除还挂在快照启用判断上——快照是旧值时残留",
            w.contains("if (TodoProgress.isEnabled(todo.percentDone)) percentDao.deleteOfDay")
        )
    }

    // ------------------------------------------------------------------ 2. 撤销某天唯一入口

    @Test
    fun `撤销某天只许走 undoCheckinDay toggleCheck 退役`() {
        val repo = code(src("data/PlanRepo.kt"))
        assertTrue("PlanRepo.undoCheckinDay 没了——三处「撤销这天」的统一入口", repo.contains("suspend fun undoCheckinDay"))
        // toggleCheck 的洞：toggle 语义让「撤销」在没卡那天打出一张卡
        assertFalse(
            "toggleCheck 又回来了——它是 toggle，撤销没卡的那天会变成打卡（撤销变打卡）",
            repo.contains("suspend fun toggleCheck")
        )
        for (f in listOf(
            "ui/plan/CheckinBoardScreen.kt",
            "ui/plan/PlanScreen.kt",
            "ui/edit/Editors.kt"
        )) {
            val t = src(f)
            assertFalse(
                "$f 还在用 toggleCheck 做撤销——没卡时会加卡、且不撤 done（时间轴仍暗着）",
                t.contains("Graph.plan.toggleCheck")
            )
            assertTrue(
                "$f 的「撤销这天」没走 undoCheckinDay",
                t.contains("Graph.plan.undoCheckinDay")
            )
        }
    }

    // ------------------------------------------------------------------ 3. 浮层入口跟随开关

    @Test
    fun `改记录数据入口必须跟随数据记录开关`() {
        val w = code(src("ui/home/HomeQuickSheets.kt"))
        assertTrue(
            "浮层「改记录数据」没判 dataRecordEnabled——没开数据记录的待办也被引导去录数据" +
                "（用户实测「我没开数据记录按钮，却让我记录数据」）",
            w.contains("if (todo.dataRecordEnabled) onEditData?.let")
        )
    }

    // ------------------------------------------------------------------ 4. 编辑器满态走正规打卡

    @Test
    fun `编辑器满态自动打卡不许绕过 completeTodo`() {
        val w = code(src("ui/edit/Editors.kt"))
        assertFalse(
            "saveTodo 又手工 copy(done=true) 了——旁路不写完成流水/监督卡/百分比流水，" +
                "打卡面板、AI、已归档、滚期全都看不到这次打卡",
            Regex("copy\\(done\\s*=\\s*true").containsMatchIn(w)
        )
        assertTrue(
            "saveTodo 满态没走 completeTodo 正规打卡链",
            w.contains("Graph.plan.completeTodo(t,")
        )
    }

    // ------------------------------------------------------------------ 6. 浮层只许一个动作按钮

    /**
     * 2026-11-14 用户**两次**报障：
     * ①「确定与打卡完成两个按钮重复了」→ 先只改名「保存进度」（仍是两个按钮，被用户再次否掉）；
     * ②「为什么还有个保存进度按钮」→ 定稿：**输入框旁不许有任何按钮**。
     *
     * 手动输入的打完按键盘「完成」或失焦即生效（commitInput），浮层里只有底部一个动作按钮
     * （打卡完成 / 改记录数据 / 撤销打卡）。这样「这个按钮到底会不会把浮层关掉」的歧义
     * 从根上消失——用户不需要猜。
     */
    @Test
    fun `浮层输入框旁不许有第二个按钮`() {
        val w = code(src("ui/home/HomeQuickSheets.kt"))
        assertFalse(
            "又出现了「确定」——与打卡完成重复（用户第一次报障）",
            w.contains("Text(\"确定\"")
        )
        assertFalse(
            "又出现了「保存进度」——仍是两个按钮（用户第二次报障）",
            w.contains("保存进度")
        )
        assertTrue(
            "手动输入应走 commitInput（键盘完成 / 失焦即生效，无需按钮）",
            w.contains("fun commitInput()") && w.contains("onFocusChanged")
        )
        assertTrue("键盘完成键应提交", w.contains("onDone ="))
        // 唯一的动作按钮仍是打卡
        assertTrue("打卡按钮丢了", w.contains("打卡完成"))
    }

    // ------------------------------------------------------------------ 7. 胶囊：勾 ≠ 满格

    /**
     * 2026-11-14 用户三次报障后的定稿：
     * ②「设置为 80% 完成度保存打卡，时间轴上却显示胶囊填满」→ 打卡不许改填充；
     * ③「**100% 才能打勾**」→ 勾只属于满进度，打卡本身不给勾。
     *
     * 于是一个待办的两个事实各归各的：**填充 = 进度（fillFraction）、勾 = 满（showsCheck）**，
     * 「整颗实心」只留给没有比例可画的情况（未启用百分比 / 0%）。
     */
    @Test
    fun `胶囊把打卡勾与百分比填充分开画`() {
        val tp = code(src("data/TodoProgress.kt"))
        // 规则本体 1：启用了百分比时，勾 ⟺ 100%（打卡不给勾）
        assertTrue(
            "showsCheck 的规则被改成「打卡也算勾」了——用户定稿「100% 才能打勾」",
            tp.contains("if (isEnabled(percentDone)) percentDone >= 100 else done")
        )
        // 规则本体 2：实心 = 有勾且没有比例可画
        assertTrue(
            "showsSolidChecked 的规则被改了——它必须是「有勾 && 没有进度轨」",
            tp.contains("showsCheck(done, percentDone) && !showsProgressFill(percentDone)")
        )
        assertFalse(
            "showsDoneStyle 又回来了——它把勾与填充混成一个判定（80% 打卡显示满格的根）",
            tp.contains("fun showsDoneStyle")
        )
        val pill = code(src("ui/common/TodoStatusPill.kt"))
        assertTrue("胶囊没走 showsCheck", pill.contains("TodoProgress.showsCheck("))
        assertTrue(
            "胶囊没走 showsSolidChecked——手写 done→实心的判定会把 80% 打卡画成满格",
            pill.contains("TodoProgress.showsSolidChecked(")
        )
        // 有勾时不许画描边：border 画在内容之上，会把挖出来的勾盖住（用户实测过）
        assertTrue(
            "描边条件又被换回实心态判断——100% 满格的挖孔勾会被描边盖住",
            pill.contains(".then(if (showCheck) Modifier else Modifier.border(")
        )
        assertTrue("挖孔勾没画出来", pill.contains("punchCheckHole()"))
    }

    // ------------------------------------------------------------------ 5. 浮层状态跟随库

    @Test
    fun `百分比浮层只存id渲染时现取`() {
        val w = code(src("ui/home/HomeScreen.kt"))
        assertTrue(
            "浮层没从 bundle 现取——快照对象会让打卡后的浮层仍显示旧状态",
            w.contains("bundle?.todos?.firstOrNull { it.id == pid }")
        )
        assertFalse(
            "浮层又在存整只 Todo 快照（percentTodoId 才是正确形态）",
            w.contains("var percentTodo by remember")
        )
    }
}
