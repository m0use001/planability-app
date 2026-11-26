package io.github.m0use001.planability.data

// M5 KMP 化：Automation / AutomationRun 实体与 AutomationDao / AutomationRunDao 已迁 `:shared`
// （Room 真源），同包跨模块直解。本文件只留 AutomationRepo（Graph 单例 / GeoWatch 重挂 / 系统时钟）。

/** 自动化任务仓库 */
object AutomationRepo {
    fun dao() = Graph.db.automationDao()

    suspend fun all() = dao().all()
    suspend fun save(a: Automation): Long =
        if (a.id == 0L) dao().insert(a.copy(createdAt = System.currentTimeMillis())).also {
            Graph.dataVersion.bump(); resyncGeo()
        }
        else { dao().update(a); Graph.dataVersion.bump(); resyncGeo(); a.id }
    suspend fun delete(id: Long) = dao().delete(id).also { Graph.dataVersion.bump(); resyncGeo() }
    suspend fun setEnabled(id: Long, enabled: Boolean) {
        dao().byId(id)?.let { dao().update(it.copy(enabled = enabled)) }
        Graph.dataVersion.bump()
        resyncGeo()
    }

    /**
     * v24：增删改之后重新对一次「要不要听位置」。
     *
     * 放在 data 层而不是让每个调用点自己调，是因为**漏一处就会留下一个还在耗电的监听**——
     * 而用户提的正是「没用就别扫」。这条链路宁可重复调用也不该有断点。
     */
    private fun resyncGeo() {
        runCatching { io.github.m0use001.planability.notify.GeoWatch.sync(Graph.appContext) }
    }
    suspend fun touchLastRun(id: Long) = dao().touchLastRun(id, System.currentTimeMillis())

    /** v24：带位置触发的自动化（已启用）。给 [io.github.m0use001.planability.notify.GeoWatch] 用。 */
    suspend fun enabledGeo(): List<Automation> = dao().enabledGeo()

    /** v24：把某条自动化的围栏迟滞状态写回（进出围栏时更新）。 */
    suspend fun setGeoInside(id: Long, inside: Boolean) {
        dao().byId(id)?.let { dao().update(it.copy(geoInside = inside)) }
    }

    // ---------- v35：执行日志 ----------

    fun runDao() = Graph.db.automationRunDao()

    /**
     * 落一条执行记录并**自己封顶**。
     *
     * 放在这里而不是让调用点负责 prune，是因为漏一次调用点就等于封顶失效——
     * 而这张表是 15 分钟一轮自动写的，跑一晚上能长几千行。宁可每次多一条
     * `DELETE ... LIMIT` 语句，也不留一个"日志把库撑爆"的隐性风险。
     */
    suspend fun logRun(r: AutomationRun): Long {
        val id = runDao().insert(r.copy(at = r.at.takeIf { it > 0L } ?: System.currentTimeMillis()))
        runDao().prune(RUN_KEEP)
        return id
    }

    /** 某任务的最近执行（日志弹层用）。 */
    suspend fun runsOf(id: Long, limit: Int = 50): List<AutomationRun> = runDao().recent(id, limit)

    /** 全部任务的最近执行（`automation_log` 工具不传 id 时用）。 */
    suspend fun recentRuns(limit: Int = 50): List<AutomationRun> = runDao().recentAll(limit)

    /** 与 AutomationRepo 共用一个封顶常量，避免两处写死不同的数。 */
    const val RUN_KEEP = 500
}
