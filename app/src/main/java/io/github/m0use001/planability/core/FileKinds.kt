package io.github.m0use001.planability.core

/**
 * 文件类型的**唯一真相**：哪些扩展名算纯文本、哪些算二进制。
 *
 * 为什么集中一份（2026-10-20）：
 * - 工具层要判断"能不能用 file_export 直接写"；
 * - 预览层要判断"能不能应用内打开"（`isPreviewable`）；
 * - 分享层要判断 MIME。
 *
 * 这三处以前各写各的：`file_export` 什么扩展名都收（能给 .docx 塞纯文本），
 * 预览层的 `TEXT_EXTS` 又漏了 `.htm`（用户生成的网页打不开）。
 * 于是同一个文件在不同入口表现不一致。现在统一从这里取。
 */
object FileKinds {

    /** 能当纯文本生成/查看/编辑的扩展名（含网页、数据、代码与配置） */
    val TEXT_EXTS: Set<String> = setOf(
        // 文档/笔记
        "txt", "md", "markdown", "mdx", "rst", "org", "adoc", "tex", "bib",
        // 网页
        "htm", "html", "xhtml", "css", "scss", "sass", "less", "js", "mjs", "cjs", "jsx", "ts", "tsx", "vue", "svelte",
        // 数据/配置
        "json", "jsonl", "ndjson", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg", "properties", "env",
        "csv", "tsv", "ics", "vcf", "sql", "proto", "graphql", "gql",
        // 代码
        "kt", "kts", "java", "gradle", "groovy", "py", "rb", "php", "go", "rs", "swift", "lua", "dart",
        "c", "h", "cc", "cpp", "hpp", "cs", "m", "r", "pl", "vb", "asm", "s", "sh", "bash", "zsh",
        "bat", "cmd", "ps1",
        // 其它纯文本
        "log", "diff", "patch", "svg", "gitignore", "dockerignore", "editorconfig"
    )

    /**
     * 二进制/容器格式：**不能**按文本生成或读取。
     * 它们要么有专门的工具（docx/xlsx/pptx/pdf），要么根本不该由 AI 生成。
     */
    val BINARY_EXTS: Set<String> = setOf(
        "docx", "doc", "xlsx", "xls", "pptx", "ppt", "pdf",
        "zip", "rar", "7z", "gz", "tar", "jar", "apk", "aab", "ipa",
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "ico",
        "mp3", "wav", "amr", "m4a", "mp4", "mov", "avi", "mkv",
        "db", "sqlite", "so", "dex", "class", "bin", "ttf", "otf", "woff", "woff2"
    )

    fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isText(name: String): Boolean = extOf(name) in TEXT_EXTS

    fun isBinary(name: String): Boolean = extOf(name) in BINARY_EXTS
}
