package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-22 三连报障根修守卫（用户宽屏实测 + vdisplay 取证）：
 *
 * 1. 「发送消息后屏幕飞到之前的消息」——autoFollow 的关闭判据旧版是
 *    「滚动停稳且不在底就关」，流式钳底那记 scrollBy 收尾时内容又长高、
 *    ProgrammaticScroll 标记已撤，程序滚动被当成用户离开 → 跟随关死 →
 *    视口钉在旧消息上、回复在屏幕外疯长（「最新」胶囊常驻）。根修 =
 *    关跟随必须 `sawDrag`（用户的手真的拖过列表）。
 * 2. 「阅读位置锚点存错/恢复跳错」——切条后 LazyColumn 条目序号 ≠ 节点序号，
 *    保存锚点必须经 nodeStartItem 换算。
 * 3. 「AI 会话界面框不完整」——embedded 时 chatBody 的 Scaffold 默认
 *    containerColor=background 把壳层 SplitBoards(transparent) 画的左板底色
 *    整个盖掉（右板工作台树没被盖，实拍左右不对称）。
 *
 * 断言读源码、与编译无关；CRLF 一律归一化后再匹配（仓库既有约定）。
 */
class ChatFollowGuardTest {

    private val src: String by lazy {
        val f = File("src/main/java/io/github/m0use001/planability/ui/info/ChatScreen.kt")
        assertTrue("源码不在了（cwd 应为 app 模块目录）", f.isFile)
        f.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    @Test
    fun `关跟随必须要求用户真的拖过列表（sawDrag）`() {
        // 关跟随的分支必须带 sawDrag 门槛（sawDrag 与 autoFollow=false 之间允许夹
        // DEBUG 日志行——2026-11-22 排查蹦顶时加的 Log.d 不该让守卫误红）
        assertTrue(
            "autoFollow=false 的分支没有 sawDrag 门槛——钳底差一步又会被当成用户离开（发送后飞回旧消息）",
            Regex("ProgrammaticScroll\\.active\\(\\) &&\\s*\\n\\s*sawDrag\\s*\\n\\s*\\) \\{[\\s\\S]{0,400}?autoFollow = false")
                .containsMatchIn(src)
        )
        // snapshotFlow 必须把 listDragged 绑进同一流（sawDrag 的置位来源）
        assertTrue(
            "滚动停稳探测器没有收集 listDragged（sawDrag 无从置位）",
            src.contains("Triple(listState.isScrollInProgress, atBottom, listDragged)")
        )
    }

    @Test
    fun `阅读锚点保存必须经条目序号换算`() {
        assertTrue(
            "锚点保存没有经 nodeStartItem 换算条目序号——切条后直接索引节点数组会存错消息",
            src.contains("nodesNow.indices.lastOrNull { nodeStartItemNow[it] <= fi }")
        )
    }

    @Test
    fun `embedded 会话板不得用背景色盖掉壳层板框`() {
        assertTrue(
            "chatBody Scaffold 缺 embedded 透明 containerColor——左板相框被 background 盖掉（框不完整）",
            src.contains("containerColor = if (embedded) androidx.compose.ui.graphics.Color.Transparent")
        )
    }

    @Test
    fun `发送必须接管跟随（恢复过阅读位置的会话发送后要看得到回复）`() {
        assertTrue(
            "MessageList 没有 followTick 参数/接管 effect——发送后视口留在旧阅读位置",
            src.contains("followTick: Int = 0") &&
                Regex("LaunchedEffect\\(followTick\\)").containsMatchIn(src)
        )
        val sendCount = Regex("chatFollowTick\\.intValue\\+\\+").findAll(src).count()
        assertTrue("sendCard/sendCurrent 两条发送路径都要 ++（实际 $sendCount 处）", sendCount >= 2)
        assertTrue(
            "MessageList 调用点没接 followTick",
            src.contains("followTick = chatFollowTick.intValue")
        )
    }

    @Test
    fun `整条与首片必须同key（定稿切条锚点不丢）`() {
        // 流式（整条 Whole）→ 定稿（多片 Part，key=id#0..k）翻转时，若整条 key 是裸 id，
        // key 集合全换 = LazyColumn 丢锚点按序号回退 = 视口瞬移（「排版剧烈变化」）
        assertTrue(
            "Whole 条目没有用与首片相同的 id#0 key——定稿切条瞬间丢锚点",
            src.contains("""is ChatNode.Single -> "${'$'}{node.msg.id}#0"""")
        )
    }

    @Test
    fun `收尾贴底必须两轮（切条收尾瞬间条目数在变）`() {
        val seg = src.substringAfter("val snapToBottom").substringBefore("var lastSavedAnchor")
        assertTrue("snapToBottom 没走统一贴底原语 settleToLatest（2026-11-23 按索引贴底）", seg.contains("settleToLatest("))
        // 反向：不许回到单轮直落
        assertFalse(
            "snapToBottom 出现了 repeat 之外的旧单轮形态",
            Regex("repeat\\(1\\)").containsMatchIn(seg)
        )
    }
}
