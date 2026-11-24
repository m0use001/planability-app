package io.github.m0use001.planability.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v28 改动形状的源码级断言。
 *
 * 这三处各自都能"编译过、单测绿"，但漏一行就是**运行期才炸**或**静默失效**：
 * 1. 实体加了字段却没在 `MIGRATION_27_28` 里 ALTER → 老用户升级即崩（Room 校验 schema 不匹配）；
 * 2. 迁移写了却没挂进 `addMigrations(...)` → 迁移根本不会执行，同样崩；
 * 3. 设置快照加了字段却没在 `persistDiff` 里写 KV → **开关点了没反应、重启即丢**，全绿无人知。
 *
 * 运行时断言跨不过去（一个在 Room、一个在 DataStore 语义、一个在 UI），所以钉在源码层。
 */
class MigrationShapeTest {

    private fun source(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val root = generateSequence(dir) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java/io/github/m0use001/planability").isDirectory }
        assertTrue("找不到仓库根（从 ${System.getProperty("user.dir")} 向上）", root != null)
        val f = File(root, rel)
        assertTrue("源码不在了：$rel", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    @Test
    fun `迁移给两张表各加两列且建了标签颜色表`() {
        val sql = source("app/src/main/java/io/github/m0use001/planability/data/AppDatabase.kt")
        val block = sql.substringAfter("MIGRATION_27_28", missingDelimiterValue = "")
        assertTrue("MIGRATION_27_28 定义不见了", block.isNotEmpty())
        for (table in listOf("schedules", "todos")) {
            for (col in listOf("urgency", "importance")) {
                assertTrue(
                    "迁移里缺 ALTER TABLE `$table` ADD COLUMN `$col` —— 老用户升级会 schema 不匹配直接崩",
                    block.contains("ALTER TABLE `$table` ADD COLUMN `$col` INTEGER NOT NULL DEFAULT 2")
                )
            }
        }
        assertTrue(
            "label_colors 表没建 —— 长按改色一写就崩",
            block.contains("CREATE TABLE IF NOT EXISTS `label_colors`")
        )
        // 存量行必须按旧 priority 回填重要性，否则老的「高/低」在手机上显示成「中」，
        // 而 PC 读 priority 还是「高/低」（真机 8 行里 3 行实测踩中，见 2026-10-11 日志）。
        // 注意迁移里用的是 Kotlin 循环变量 `$t`，所以断言打在循环形状上而不是字面表名上
        assertTrue(
            "MIGRATION_27_28 没给 todos/schedules 回填 importance —— 存量高/低会被抹成中",
            block.contains("listOf(\"todos\", \"schedules\")") &&
                block.contains("UPDATE `\$t` SET `importance` = CASE `priority`")
        )
        assertTrue(
            "MIGRATION_27_28 没挂进 addMigrations —— 写了不注册等于没迁移",
            sql.contains("MIGRATION_26_27, MIGRATION_27_28")
        )
        // 28→29：修 27→28 第一版漏回填的存量（已在 28 的机器靠它自愈）
        assertTrue(
            "MIGRATION_28_29 修复迁移缺失 —— 已升到 28 的机器拿不到回填修正",
            sql.contains("private val MIGRATION_28_29")
        )
        assertTrue(
            "MIGRATION_28_29 没挂进 addMigrations",
            // 注册串后面还会继续追加新迁移（MIGRATION_29_30），所以只断言这一段前缀
            sql.contains("MIGRATION_27_28, MIGRATION_28_29")
        )
        // 29→30：封面编号体系（素色 0 → -1，新风格从 0 起）
        assertTrue(
            "MIGRATION_29_30 缺失 —— 旧机升上来后素色会变成新风格 0",
            sql.contains("private val MIGRATION_29_30")
        )
        // 只断言到迁移名、**不带结尾的 `)`**：注册串每次加新迁移都会变长，
        // 带 `)` 就等于把「它是最后一挂」也断言了，加一个新迁移这条就假红（本文件真踩过）。
        assertTrue(
            "MIGRATION_29_30 没挂进 addMigrations",
            sql.contains("MIGRATION_28_29, MIGRATION_29_30")
        )
        // 31→32：自动化执行日志（新表 automations_run）
        assertTrue(
            "MIGRATION_31_32 缺失 —— 没有它，老库不会建 automations_run 表，一读就崩",
            sql.contains("private val MIGRATION_31_32")
        )
        val createRun = sql.substringAfter(
            "CREATE TABLE IF NOT EXISTS `automations_run`",
            missingDelimiterValue = ""
            // 终止符用自己后面那句 CREATE INDEX。用 ");" 是不行的——
            // 建表是 `"a" + "b" + ... NOT NULL)"` 这种字符串拼接，源码里根本没有 `);`，
            // substringBefore 找不到就会**返回整个文件**，把后面 MIGRATION_30_31 的
            // `DEFAULT 0` 也吞进来，断言于是报一个根本不存在的问题（本文件实测踩过）。
        ).substringBefore("CREATE INDEX")
        assertTrue("迁移里没有 automations_run 的建表语句", createRun.isNotEmpty())
        assertTrue(
            "实体 AutomationRun 已进 @Database entities，但迁移没建对应表 —— Room 校验 schema 不符，升级当场崩",
            sql.contains("AutomationRun::class")
        )
        assertTrue(
            "automations_run 的索引没建 —— 实体写了 indices，漏建同样判 schema 不符",
            sql.contains("index_automations_run_automationId_at")
        )
        // 实体里是 Kotlin 默认值（val ok: Boolean = false），它**不会**生成 SQL DEFAULT。
        // 迁移里若手写了 DEFAULT，TableInfo 会多出一个 defaultValue → schema 对不上。
        assertTrue(
            "automations_run 的 CREATE 里出现了 DEFAULT —— 实体没有 @ColumnInfo(defaultValue)，" +
                "两边 defaultValue 对不上会让 Room 判 schema 不符",
            !createRun.contains("DEFAULT")
        )
        // 版本检查改为**由版本号推导最后一挂迁移**：版本会一直涨，
        // 写死数字每加一个迁移就要改一次（上一版就是这么假红的）。
        val ver = Regex("""version = (\d+)""").find(sql)?.groupValues?.get(1)?.toInt() ?: -1
        assertTrue("AppDatabase 的 version 没解析到", ver >= 32)
        assertTrue(
            "version=$ver 但 MIGRATION_${ver - 1}_$ver 没定义 —— 版本涨了没写迁移，老库升级即崩",
            sql.contains("private val MIGRATION_${ver - 1}_$ver")
        )
        assertTrue(
            "MIGRATION_${ver - 1}_$ver 没挂进 addMigrations —— 定义了不注册等于没迁移",
            sql.contains("MIGRATION_${ver - 1}_$ver)")
        )
    }

    @Test
    fun `实体的两列默认值必须是 -1（未设置）`() {
        val entities = source("app/src/main/java/io/github/m0use001/planability/data/PlanEntities.kt")
        // 2026-10-20 需求：重要/紧急改为**可选、默认不设置**。
        // 迁移里的 DEFAULT 2 只作用于 v28 之前的老行（它们本来就是"中"），
        // 新建行由实体默认值 -1 表达"未设置"，两者语义不同、不能强行对齐。
        val defaults = Regex("""val (urgency|importance): Int = (-?\d+)""").findAll(entities)
            .associate { it.groupValues[1] to it.groupValues[2] }
        assertTrue("实体里没找到 urgency/importance 两列", defaults.size >= 2)
        assertTrue("urgency 默认应为 -1（未设置），实际 ${defaults["urgency"]}", defaults["urgency"] == "-1")
        assertTrue("importance 默认应为 -1（未设置），实际 ${defaults["importance"]}", defaults["importance"] == "-1")
    }

    @Test
    fun `着色开关的 KV 键在读和写两处都在`() {
        val repo = source("app/src/main/java/io/github/m0use001/planability/data/SettingsRepo.kt")
        val key = "plan.colorByUrgency"
        val hits = repo.split(key).size - 1
        assertTrue(
            "`$key` 只出现了 $hits 次 —— load 和 persistDiff 必须各一次，" +
                "漏了 persistDiff 就是「开关点了没反应、重启即丢」这种全绿也测不出来的失效",
            hits >= 2
        )
        assertTrue(
            "SettingsSnapshot 里没有对应字段 —— UI 没东西可读",
            repo.contains("planColorByUrgencyImportance")
        )
    }

    /**
     * v58（2026-11-24 开源整理）：位图图标素材剔除，coverStyle/coverIcon/coverIcons 三处换算。
     *
     * 跑不过 Room 运行时（JVM 单测没有真实的旧库），所以钉在迁移体形状上：
     * 漏任何一条 UPDATE，旧库升级后就是「封面静默变素色 / 图标错位」，
     * 而这种错不会崩、也不会有任何报错。
     */
    @Test
    fun `v58 图标下标迁移三处齐全`() {
        val db = source("app/src/main/java/io/github/m0use001/planability/data/AppDatabase.kt")
        assertTrue("版本没升到 58", db.contains("version = 58,"))
        assertTrue("MIGRATION_57_58 没定义", db.contains("private val MIGRATION_57_58"))
        assertTrue(
            "MIGRATION_57_58 没挂进 addMigrations —— 写了不注册等于没迁移",
            db.contains("MIGRATION_56_57, MIGRATION_57_58)")
        )
        val block = db.substringAfter("private val MIGRATION_57_58")
            .substringBefore("private val MIGRATION_42_43")
        // ① 款式 15 → 16（编码 = 款式 + 配色×100，只改低位）
        assertTrue(
            "没把款式 15 并入 16 —— 存量贴图图标款的封面会静默变素色",
            block.contains("(`coverStyle` % 100) = 15")
        )
        // ② coverIcon：>= 14360 减 14360，< 14360 归 0
        assertTrue(
            "coverIcon 没减 14360 —— 矢量图标下标全部错位 14360",
            block.contains("WHEN `coverIcon` >= 14360 THEN `coverIcon` - 14360")
        )
        assertTrue(
            "coverIcon 越界值没归 0 —— 贴图下标会落成任意一张矢量图标",
            block.contains("ELSE 0 END")
        )
        // ③ coverIcons CSV 逐项：迁移体里有逐行读写的循环
        assertTrue(
            "coverIcons CSV 没逐行迁移 —— 多图标排列里 >= 14360 的下标全部错位",
            block.contains("SELECT `id`, `coverIcons` FROM `notebooks`") &&
                block.contains("- 14360")
        )
    }
}
