package io.github.m0use001.planability.data

import io.github.m0use001.planability.ai.rag.NoteBlocks

/**
 * v53.7 第 2 期：笔记**块级编辑**入口（给 AI 工具用的语义层）。
 *
 * 为什么放在扩展函数而不是 [NotesRepo] 内部：`DomainRepos.kt` 同一时刻有同伴在改
 * （助理中枢 v53 迁移），同文件并发是本项目唯一不可接受的事故；扩展函数放在独立文件里
 * 语义完全一样，且不碰他正在写的那些行。
 *
 * 语义（对应思源的事务形状，但收敛到「一篇笔记 + 一组块」）：
 * - 读：先 [blockOutline] 拿到 `#index kind sha 「摘要」` 清单；
 * - 写：`editBlocks(noteId, [Edit(index, expectSha, newText)])`；
 * - **指纹不符 = 人已改过这块** → 该项记冲突、不覆盖，其余照常应用；
 * - 真正落库只在有改动时发生，且走 [NotesRepo.saveNote]（顺带重建链接图与语义索引）。
 */
suspend fun NotesRepo.blockOutline(noteId: Long): String? =
    noteById(noteId)?.let { NoteBlocks.outline(it.content) }

suspend fun NotesRepo.editBlocks(noteId: Long, edits: List<NoteBlocks.Edit>): NoteBlocks.Result {
    val note = noteById(noteId)
        ?: return NoteBlocks.Result("", 0, edits.map { it.index })
    val res = NoteBlocks.apply(note.content, edits)
    if (res.applied > 0) {
        // 标题口径与编辑器一致（首个非空行去掉 # 与空格、截 30 字）：
        // 不改的话，AI 重写首行标题后笔记列表里会一直挂着旧名字。
        val title = res.content.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trimStart()?.removePrefix("#")?.trimStart()?.take(30)
            ?: note.title
        saveNote(note.copy(content = res.content, title = title.ifBlank { note.title }))
    }
    return res
}
