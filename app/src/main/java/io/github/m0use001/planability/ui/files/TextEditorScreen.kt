package io.github.m0use001.planability.ui.files

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.m0use001.planability.app.LocalOverlayClose
import io.github.m0use001.planability.app.PredictiveBackOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset

/** 文本编辑器可处理的扩展名（唯一真相在 core.FileKinds，这里只是别名） */
val TEXT_EXTS = io.github.m0use001.planability.core.FileKinds.TEXT_EXTS
val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
val OFFICE_EXTS = setOf("docx", "xlsx", "pptx")

/** 单击文件时是否进入应用内预览/编辑（否则走系统打开兜底） */
fun isPreviewable(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in TEXT_EXTS || ext == "pdf" || ext in IMAGE_EXTS || ext in OFFICE_EXTS
}

/** 纯文本编辑器：等宽字体 + 保存（UTF-8 读，含乱码回退 GBK 显示；保存统一 UTF-8） */
@Composable
fun TextEditorScreen(file: File, onClose: () -> Unit, embedded: Boolean = false) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(file) {
        val content = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = file.readBytes()
                var s = String(bytes, Charsets.UTF_8)
                if (s.contains('\uFFFD')) {
                    s = runCatching { String(bytes, Charset.forName("GBK")) }.getOrDefault(s)
                }
                s
            }.getOrDefault("")
        }
        text = content
        loaded = true
    }

    fun save() {
        if (saving) return
        saving = true
        scope.launch(Dispatchers.IO) {
            runCatching { file.writeText(text) }
            withContext(Dispatchers.Main) { dirty = false; saving = false }
        }
    }

    // v57 平板双栏：本体抽成 editorBody——手机自包 PredictiveBackOverlay（原路径零变化），
    // 平板由 FilesScreen 宽屏分支的右栏直接承载（embedded=true）。
    val editorBody: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
            ) {
                IconButton(onClick = LocalOverlayClose.current) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                }
                Text(
                    file.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { save() }) {
                    Text(if (saving) "保存中…" else if (dirty) "保存" else "已保存")
                }
            }
            if (loaded) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; dirty = true },
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        lineHeight = 22.sp
                    ),
                    placeholder = { Text("文件为空，输入内容后点「保存」") }
                )
            }
        }
    }
    if (embedded) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalOverlayClose provides { if (dirty) save(); onClose() }
        ) { editorBody() }
    } else {
        PredictiveBackOverlay(onBack = { if (dirty) save(); onClose() }) { editorBody() }
    }
}
