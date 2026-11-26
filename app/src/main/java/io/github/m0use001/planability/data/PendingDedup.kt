package io.github.m0use001.planability.data

/**
 * 待处理草稿判重（2026-11-11 用户「待处理审核中一堆重复的」）。
 *
 * 真机取证（◆，2026-11-11）：40 组重复**全部来自 capture 提纯**——
 * 同一 (群, 日) 被提纯多轮，每轮把同一批事件重插一遍（11-10 单日 879 条、11-09 481 条），
 * 且时间措辞每轮还略变（「2026年10月10日 17:00」/「10月10日 17:00」/「10月10日 17:00前」）。
 * 所以判重不能靠「标题+时间原文」精确相等：
 *  - 标题：去空白后字符 2-gram Jaccard ≥ [SIM]（抓「体质健康测评」vs「学生体质健康测评」0.87、
 *    「参加院运会裁判培训」vs「院运会裁判培训」0.75）；
 *  - 编号：标题里的数字集合两边都有且不同 → 不是同一条（「完成实验作业1」≠「完成实验作业3」）；
 *  - 时间：抽「月/日」集合，两边都有且不相交 → 不同日期的同类事件（如每周组会），不算重复；
 *    一边没有日期 → 同事件的不完整抽取，算重复。
 * 纯函数（JVM 直测）；落库动作在 [InfoRepo.addPendingItems] / [InfoRepo.healPendingDuplicates]。
 */
object PendingDedup {
    /** 标题相似阈值：0.87 的「体质健康测评」对要过；0.25 的「开卷考试/期末考试」对不能过 */
    const val SIM = 0.6f

    /** 时间原文里的日期：中文「11月15日」（capture）/「11/10」（会话工具 fmtDay）/「2026-11-10」 */
    private val MONTH_DAY_CN = Regex("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日")
    private val MONTH_DAY_SLASH = Regex("(?<![\\d/])(\\d{1,2})/(\\d{1,2})(?![\\d/])")
    private val DATE_ISO = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})")
    private val DIGITS = Regex("\\d+")

    fun normalizeTitle(t: String): String = t.lowercase().filterNot { it.isWhitespace() || it == '　' }

    /** 字符 2-gram（去空白后；单字标题退回 1-gram 保证可判） */
    fun bigrams(t: String): Set<String> {
        val s = normalizeTitle(t)
        return if (s.length < 2) setOf(s) else (0 until s.length - 1).mapTo(HashSet()) { s.substring(it, it + 2) }
    }

    fun similarity(a: String, b: String): Float {
        val ga = bigrams(a)
        val gb = bigrams(b)
        if (ga.isEmpty() || gb.isEmpty()) return 0f
        val inter = ga.count { it in gb }
        return inter.toFloat() / (ga.size + gb.size - inter)
    }

    /** 「11月15日 / 2026年10月10日 17:00前 / 11/10 15:00」→ {1115} / {1010} / {1110}；无日期 = 空集 */
    fun monthDaySet(timeText: String): Set<Int> {
        val out = HashSet<Int>()
        MONTH_DAY_CN.findAll(timeText).forEach {
            out += it.groupValues[1].toInt() * 100 + it.groupValues[2].toInt()
        }
        MONTH_DAY_SLASH.findAll(timeText).forEach {
            out += it.groupValues[1].toInt() * 100 + it.groupValues[2].toInt()
        }
        DATE_ISO.findAll(timeText).forEach {
            out += it.groupValues[2].toInt() * 100 + it.groupValues[3].toInt()
        }
        return out
    }

    /** 标题里的数字集合（编号区分用） */
    fun digitSet(title: String): Set<String> = DIGITS.findAll(title).mapTo(HashSet()) { it.value }

    /** 预计算指纹：存量上千行时逐对判重不能每次重建 gram 集合 */
    class Fingerprint(val item: PendingItem) {
        val grams: Set<String> = bigrams(item.title)
        val digits: Set<String> = digitSet(item.title)
        val dates: Set<Int> = monthDaySet(item.timeText)

        fun similarTo(o: Fingerprint): Float {
            val inter = grams.count { it in o.grams }
            return inter.toFloat() / (grams.size + o.grams.size - inter)
        }
    }

    fun fingerprint(item: PendingItem): Fingerprint = Fingerprint(item)

    fun isDuplicate(a: Fingerprint, b: Fingerprint): Boolean {
        if (a.item.kind != b.item.kind) return false
        if (a.digits.isNotEmpty() && b.digits.isNotEmpty() && a.digits != b.digits) return false
        if (a.similarTo(b) < SIM) return false
        val da = a.dates
        val db = b.dates
        return !(da.isNotEmpty() && db.isNotEmpty() && da.intersect(db).isEmpty())
    }

    fun isDuplicate(a: PendingItem, b: PendingItem): Boolean = isDuplicate(fingerprint(a), fingerprint(b))

    /** 入库前判重：同批内自判 + 与现有 pending 判重；返回真正要新插的条目 */
    fun freshOf(existing: List<PendingItem>, incoming: List<PendingItem>): List<PendingItem> {
        val pool = existing.mapTo(ArrayList(existing.size + incoming.size)) { fingerprint(it) }
        val fresh = ArrayList<PendingItem>(incoming.size)
        for (it in incoming) {
            val fp = fingerprint(it)
            if (pool.none { k -> isDuplicate(k, fp) }) {
                pool += fp
                fresh += it
            }
        }
        return fresh
    }

    /** 重复命中不丢信息：旧行缺的 timeText/place/extra 用新抽取补上（提纯重跑往往抽得更全） */
    fun fillBlanks(existing: PendingItem, incoming: PendingItem): PendingItem = existing.copy(
        timeText = existing.timeText.ifBlank { incoming.timeText },
        place = existing.place.ifBlank { incoming.place },
        extra = existing.extra.ifBlank { incoming.extra },
        confidence = if (existing.confidence < 0f) incoming.confidence else existing.confidence
    )

    /** 存量清理计划：同事件聚簇，保留数据最全的一条，其余忽略；保留行补缺字段 */
    data class HealPlan(val updates: List<PendingItem>, val ignoreIds: List<Long>)

    fun planHeal(all: List<PendingItem>): HealPlan {
        val clusters = ArrayList<MutableList<PendingItem>>()
        val reps = ArrayList<Fingerprint>()
        for (item in all.sortedBy { it.id }) {
            val fp = fingerprint(item)
            val idx = reps.indexOfFirst { isDuplicate(it, fp) }
            if (idx < 0) {
                reps += fp
                clusters += mutableListOf(item)
            } else {
                clusters[idx] += item
            }
        }
        val updates = ArrayList<PendingItem>()
        val drops = ArrayList<Long>()
        for (c in clusters) {
            if (c.size < 2) continue
            // 分数：有 timeText 2 分、有 place 1 分、有 extra 1 分；同分取 id 最小（最早）
            val keeper = c.maxWithOrNull(compareBy<PendingItem>({ score(it) }, { -it.id })) ?: c[0]
            var merged = keeper
            for (m in c) if (m.id != keeper.id) merged = fillBlanks(merged, m)
            if (merged != keeper) updates += merged
            c.filter { it.id != keeper.id }.forEach { drops += it.id }
        }
        return HealPlan(updates, drops)
    }

    private fun score(p: PendingItem): Int =
        (if (p.timeText.isNotBlank()) 2 else 0) + (if (p.place.isNotBlank()) 1 else 0) +
            (if (p.extra.isNotBlank()) 1 else 0)
}
