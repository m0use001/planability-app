package io.github.m0use001.planability.ui.edit

import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.data.Attachment
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.ui.files.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 附件区：挂到日程/待办编辑器（ownerKind: schedule / todo）。
 * 图片与任意文件复制进应用私有目录 attachments/，点击走系统打开。
 */
@Composable
fun AttachmentSection(ownerKind: String, ownerId: Long) {
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Attachment>>(emptyList()) }

    fun reload() {
        scope.launch(Dispatchers.IO) {
            val list = Graph.db.attachmentDao().ofOwner(ownerKind, ownerId)
            withContext(Dispatchers.Main) { items = list }
        }
    }

    fun persist(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                var name = "file"
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { name = it }
                }
                val mime = resolver.getType(uri) ?: ""
                val safe = name.replace('/', '_')
                val dir = File(context.filesDir, "attachments").apply { mkdirs() }
                val target = File(dir, "${ownerKind}_${ownerId}_${UUID.randomUUID().toString().take(8)}_$safe")
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                Graph.db.attachmentDao().insert(
                    Attachment(
                        ownerKind = ownerKind, ownerId = ownerId, name = safe,
                        path = target.absolutePath, mime = mime,
                        size = target.length(), createdAt = System.currentTimeMillis()
                    )
                )
            }
            reload()
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) persist(uri)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) persist(uri)
    }

    LaunchedEffect(ownerId, ownerKind) {
        if (ownerId != 0L) reload()
    }

    Column {
        io.github.m0use001.planability.ui.edit.SectionLabel("附件")
        if (ownerId == 0L) {
            Text(
                "保存后即可添加附件",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row {
                TextButton2(Icons.Rounded.AddAPhoto, "添加图片") { imagePicker.launch("image/*") }
                Spacer(Modifier.width(8.dp))
                TextButton2(Icons.Rounded.AttachFile, "添加文件") { filePicker.launch("*/*") }
            }
            items.forEach { a ->
                AttachmentRow(a, onDelete = {
                    haptics.perform(HapticEngine.Kind.Tap)
                    scope.launch(Dispatchers.IO) {
                        Graph.db.attachmentDao().delete(a.id)
                        File(a.path).delete()
                        reload()
                    }
                }, onOpen = {
                    runCatching {
                        val uri = FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", File(a.path)
                        )
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW)
                                .setDataAndType(uri, a.mime.ifBlank { "application/octet-stream" })
                                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        )
                    }
                })
            }
        }
    }
}

@Composable
private fun TextButton2(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // R11：附件入口按钮的行首图标是装饰 → 中性
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun AttachmentRow(item: Attachment, onDelete: () -> Unit, onOpen: () -> Unit) {
    val isImage = item.mime.startsWith("image/") ||
        item.name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            val bmp = if (isImage) remember(item.path) {
                runCatching {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(item.path, opts)
                    var sample = 1
                    while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= 96) sample *= 2
                    BitmapFactory.decodeFile(item.path, BitmapFactory.Options().apply { inSampleSize = sample })
                }.getOrNull()
            } else null
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(), contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                )
            } else {
                Icon(Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    formatSize(item.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, "删除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
