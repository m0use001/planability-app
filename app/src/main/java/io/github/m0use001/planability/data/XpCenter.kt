package io.github.m0use001.planability.data

/**
 * XP/等级：XP 总量存 settings KV（xp.total）。
 * 升级曲线：升到 L+1 级需要 100 × L（等级 L 累计需 50·L·(L-1)）。
 */
object XpCenter {
    private const val KEY = "xp.total"

    /** 当前等级（从 1 级起） */
    fun levelOf(xp: Long): Int {
        var lvl = 1
        var need = 100L
        var rest = xp
        while (rest >= need) {
            rest -= need
            lvl += 1
            need = 100L * lvl
        }
        return lvl
    }

    /** （当前级内已得，本级所需） */
    fun progressOf(xp: Long): Pair<Long, Long> {
        var lvl = 1
        var need = 100L
        var rest = xp
        while (rest >= need) {
            rest -= need
            lvl += 1
            need = 100L * lvl
        }
        return rest to need
    }

    suspend fun total(): Long = Graph.db.settingsDao().get(KEY)?.toLongOrNull() ?: 0L

    /** 加减 XP 并 bump 数据版本（供等级卡刷新） */
    suspend fun add(amount: Long) {
        if (amount == 0L) return
        val dao = Graph.db.settingsDao()
        val cur = dao.get(KEY)?.toLongOrNull() ?: 0L
        dao.put(SettingKV(KEY, (cur + amount).coerceAtLeast(0L).toString()))
        Graph.dataVersion.bump()
    }
}
