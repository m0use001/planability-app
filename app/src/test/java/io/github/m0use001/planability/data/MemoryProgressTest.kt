package io.github.m0use001.planability.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v36「记忆整理进度表」的形状断言（B 项第一步）。
 *
 * 这些地方每一处都能"编译过、单测绿"，但漏一行就是运行期才炸：
 * 1. 实体注册进 @Database 又改了 version，却没写迁移 → 老用户升级即崩（Room 校验 schema）；
 * 2. 迁移写了却没挂进 addMigrations(...) → 迁移根本不执行，同样崩；
 * 3. 进度表被谁加上 ForeignKey → 会话删除/重写的行为悄悄变掉，且本库全库都没有外键，
 *    一旦破例后面所有清理逻辑都得重新想；
 * 4. 粗筛 SQL 不用 LEFT JOIN + COALESCE → "有新轮次的会话" 一条都查不出来（NULL 比较恒为假），
 *    表现是整理器永远空转，且没有任何报错。
 */
class MemoryProgressTest {

    private fun source(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val root = generateSequence(dir) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java/io/github/m0use001/planability").isDirectory }
        assertTrue("找不到仓库根（从 ${System.getProperty("user.dir")} 向上）", root != null)
        val f = File(root, rel)
        assertTrue("源码不在了：$rel", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    private val dbSrc by lazy { source("app/src/main/java/io/github/m0use001/planability/data/AppDatabase.kt") }
    private val entitySrc by lazy {
        source("app/src/main/java/io/github/m0use001/planability/data/MemoryProgress.kt")
    }

    @Test
    fun `进度表已注册且版本至少走到 36`() {
        assertTrue(
            "MemoryProgressEntity 没注册进 @Database —— Room 根本不建这张表，一写就崩",
            dbSrc.contains("MemoryProgressEntity::class")
        )
        // 只要求"至少 36"：37 已经被上下文压缩那条线占号（phone-c 的 MIGRATION_36_37），
        // 这条线往后推版本号很正常，钉死 36 反而会每次都被别人的升级碰红。
        val v = Regex("version = (\\d+)").find(dbSrc)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        assertTrue("读不到 @Database 的 version", v != null)
        assertTrue(
            "version=$v，不到 36 —— memory_progress 是 35→36 进来的，不到 36 说明迁移链断了",
            v >= 36
        )
        // 我这一段（35→36）必须还在，不能被后来者重构掉
        assertTrue(
            "MIGRATION_35_36 不见了 —— memory_progress 表就是它建的，删了老用户升级会缺表",
            dbSrc.contains("MIGRATION_35_36")
        )
    }

    @Test
    fun `MIGRATION_35_36 建表并挂进 addMigrations`() {
        val block = dbSrc.substringAfter("MIGRATION_35_36", missingDelimiterValue = "")
        assertTrue("MIGRATION_35_36 定义不见了", block.isNotEmpty())
        assertTrue(
            "迁移里没建 memory_progress 表 —— 老用户升级后一写进度就崩",
            block.contains("CREATE TABLE IF NOT EXISTS `memory_progress`")
        )
        // 两个列都必须有：缺 consolidatedAt 就永远判不出"有更新"，缺 sessionId 就没有主键
        assertTrue("迁移里缺 sessionId 列", block.contains("`sessionId` INTEGER NOT NULL"))
        assertTrue(
            "迁移里缺 consolidatedAt 列或缺 NOT NULL DEFAULT 0 —— 老行会是 NULL，COALESCE 才救得回来",
            block.contains("`consolidatedAt` INTEGER NOT NULL DEFAULT 0")
        )
        assertTrue(
            "迁移没挂进 addMigrations —— 写了不注册等于没迁移，用户带着 35 的库上来直接崩",
            dbSrc.contains("MIGRATION_34_35, MIGRATION_35_36")
        )
    }

    @Test
    fun `进度表刻意不加外键`() {
        // 钉的是"真的用了外键"：import androidx.room.ForeignKey 或 @ForeignKey(...)。
        // 不能直接钉单词 ForeignKey —— 那段解释"为什么不加"的注释里本来就带这个词。
        assertTrue(
            "MemoryProgress.kt import 了 androidx.room.ForeignKey —— 本库全库无外键" +
                "（会话行会被整行重写），破例之后所有清理逻辑都得重想；孤儿行用 deleteOrphans 兜",
            !entitySrc.contains("import androidx.room.ForeignKey")
        )
        assertTrue(
            "MemoryProgress.kt 里出现 @ForeignKey(...) —— 同样是不该破例的外键",
            !entitySrc.contains("ForeignKey(")
        )
    }

    @Test
    fun `粗筛 SQL 用 LEFT JOIN 加 COALESCE`() {
        assertTrue(
            "pendingSessionIds 少了 LEFT JOIN —— 没整理过的会话（memory_progress 里没有行）" +
                "会被整条丢出候选，整理器永远只处理老会话",
            entitySrc.contains("LEFT JOIN memory_progress")
        )
        assertTrue(
            "pendingSessionIds 少了 COALESCE —— consolidatedAt 为 NULL 时比较恒为假，" +
                "第一次整理的会话一条都选不出来（表现：整理器空转且不报错）",
            entitySrc.contains("COALESCE(m.consolidatedAt, 0)")
        )
        assertTrue(
            "没有 findPending —— 第二步调度器启动补跑要靠它列出全部候选",
            entitySrc.contains("suspend fun findPending")
        )
    }
}
