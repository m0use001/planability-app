package io.github.m0use001.planability.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 专注会话记录 */
@Entity(tableName = "focus_sessions", indices = [Index("startDay")])
data class FocusSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 开始 dayKey（统计归档） */
    val startDay: Long,
    val start: Long,
    val end: Long,
    val plannedMin: Int,
    val completed: Boolean
)

/**
 * 素色默认款的封面编号。
 *
 * v19.9：用户要求**新封面从 0 开始编号**，而原来素色款占着 0（连带被下线的 1..10 图案款也是这段区间）。
 * 于是素色让位到 -1：它是"没有风格"的默认态，不参与风格编号；0 起留给真正的新风格。
 * 存量行由 MIGRATION_29_30 与备份导入的版本判断统一改写成 -1。
 */
const val COVER_SOLID = -1

/** 笔记本（含封面参数与标签；标签与全软日程/待办共用一套体系，存 CSV） */
@Entity(tableName = "notebooks")
data class NotebookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /**
     * 封面样式：COVER_SOLID(-1) 素色 / 0 起为新风格 / 90 自定义文字平铺。
     * v19.9 下线的 1..10 图案款与旧版把素色记作 0 的历史数据，导入时都归一到 COVER_SOLID。
     */
    val coverStyle: Int = COVER_SOLID,
    /** 封面色相（美术内容色，不随主题） */
    val coverHue: Float = 210f,
    val createdAt: Long = 0,
    /** v17：标签 CSV（与 LABEL_PRESETS 体系共享） */
    val labels: String = "",
    /** v18：自定义封面文字（style=90 时生效） */
    val coverText: String = "",
    /** v18：自定义封面排列方式 0 网格 / 1 错行 / 2 斜向 / 3 居中大字；MC 款复用它存摆放方式 */
    val coverLayout: Int = 0,
    /**
     * v19.36：封面图标下标（`ui/notes/MaterialIconSet.NAMES` 的下标）。
     *
     * 落库之前选中的图标只活在内存 state 里，一关弹窗就丢——所以这一列是必须的。
     * v58 之前的位图素材池已剔除，存量值由 `MIGRATION_57_58` 归一。
     */
    val coverIcon: Int = 0,
    /**
     * v19.37：多图标排列里**每个位置**各自的图标下标，CSV（逗号分隔，按该排列的槽位顺序）。
     *
     * 只有 `coverIcon` 一个值时，多图标排列（四角/满铺/错位网格…）的陪衬位只能靠
     * `nearby()` 自动挑邻近图标，用户没法指定。这一列让每个槽位都能单独选。
     *
     * **空串 = 未指定**：渲染时退回 nearby()，这样老笔记本和没改过的排列不受影响，
     * 也避免「换一次摆放方式就要补一串下标」。
     */
    val coverIcons: String = ""
)

/** 笔记：kind 三态 md / pdf / freeform */
@Entity(tableName = "notes", indices = [Index("notebookId"), Index("trashed"), Index("updatedAt")])
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val notebookId: Long,
    val title: String,
    /** md / pdf / freeform */
    val kind: String = "md",
    /** Markdown 源码 */
    val content: String = "",
    /** 无边记笔迹 JSON（画布绝对坐标） */
    val inkJson: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val trashed: Boolean = false,
    val trashedAt: Long = 0,
    /** v15：置顶（本笔记本内排序置前） */
    val pinned: Boolean = false,
    /** 所属项目（0 = 未分类，v5 项目大体系） */
    val projectId: Long = 0,
    /**
     * v49 知识库：笔记级标签 CSV（与笔记本 tags 同一套写法，不预设枚举）。
     * 知识库检索/问答按标签过滤；逗号分隔、前后空白由写入端规范化。
     */
    val tags: String = ""
)

/**
 * 2026-11-07 P0-2 列表行投影：不含 content/inkJson 正文。
 *
 * 列表行只渲染 标题/类型/预览/时间/字数/标签——预览带正文前 240 字（`NotePreview.flatten`
 * 实际只用前 80 字输出）；「N 字」用 LENGTH(content)（原 wordCount 是 CJK 字数+西文词数，
 * SQL 投影算不了，显示差 ±10% 量级不可感）；打开笔记时编辑器再按 id 取全文。
 * 手写笔记（freeform）的 inkJson 可能很大，不进列表行是主要收益之一。
 */
data class NoteRow(
    val id: Long,
    val notebookId: Long,
    val title: String,
    val kind: String,
    val createdAt: Long,
    val updatedAt: Long,
    val trashed: Boolean,
    val trashedAt: Long,
    val pinned: Boolean,
    val projectId: Long,
    val tags: String,
    /** 正文前 240 字（列表预览用，可能被截断） */
    val preview: String,
    /** 正文总字符数（列表「N 字」用） */
    val size: Int
)

/** 笔记本网格「N 篇 · 最近更新」聚合行（2026-11-07 P0-2：替代 allNotes() 全量加载） */
data class NotebookNoteStat(val notebookId: Long, val cnt: Int, val latest: Long)

/** 笔记录音（MediaRecorder 录制 / MediaPlayer 播放） */
@Entity(tableName = "note_audio", indices = [Index("noteId")])
data class NoteAudio(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    /** 应用私有目录内的绝对路径 */
    val path: String,
    val durMs: Int = 0,
    val createdAt: Long = 0
)

/** 附件：挂到日程/待办（ownerKind: schedule / todo） */
@Entity(tableName = "attachments", indices = [Index("ownerKind", "ownerId")])
data class Attachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerKind: String,
    val ownerId: Long,
    val name: String,
    /** 应用私有目录内的绝对路径 */
    val path: String,
    val mime: String = "",
    val size: Long = 0,
    val createdAt: Long = 0
)
