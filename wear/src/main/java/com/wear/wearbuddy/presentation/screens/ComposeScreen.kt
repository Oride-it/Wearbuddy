package com.wear.wearbuddy.presentation.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.wear.wearbuddy.presentation.plus
import com.wear.wearbuddy.presentation.LocalStrings
import com.wear.wearbuddy.presentation.rememberClickHaptic
import com.wear.wearbuddy.vm.AppViewModel
import com.wear.wearbuddy.vm.PendingAttachment

/**
 * 「发消息」输入页：文本 + 附件（图片 / 文件）一并发送。
 * 选项一排为「语音输入｜图片」（不含表情），另有「选择文件」「粘贴」「换行」，
 * 底部贴合屏幕的发送按钮把文字与已选附件一起送出。taskId=null 新建任务，否则追问。
 */
@Composable
fun ComposeScreen(
    actions: AppViewModel,
    taskId: String?,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    val state by actions.ui.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptic = rememberClickHaptic()
    val listState = rememberTransformingLazyColumnState()

    val text = if (taskId == null) state.draft else state.replyDraft
    val setText = if (taskId == null) actions::setDraft else actions::setReplyDraft
    var attachments by remember { mutableStateOf(listOf<PendingAttachment>()) }

    fun addAttachment(uri: Uri, fallbackMime: String) {
        val resolver = context.contentResolver
        val localFile = if (uri.scheme == "file") uri.path?.let { java.io.File(it) } else null
        val mime = if (localFile != null) android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(localFile.extension.lowercase()) ?: "application/octet-stream"
            else resolver.getType(uri) ?: fallbackMime
        val name = localFile?.name ?: resolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        } ?: uri.lastPathSegment ?: "file"
        attachments = attachments.filterNot { it.uri == uri.toString() } + PendingAttachment(name, uri.toString(), mime)
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            if (spoken.isNotBlank()) {
                setText(if (text.isBlank()) spoken else "$text $spoken")
            }
        }
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let { addAttachment(it, "image/*") }
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let { addAttachment(it, "application/octet-stream") }
    }

    fun startVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, s.voiceInput)
        }
        runCatching { voiceLauncher.launch(intent) }
    }

    ScreenScaffold(
        scrollState = listState,
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
        edgeButton = {
            EdgeButton(
                onClick = {
                    haptic()
                    actions.submitCompose(taskId, text, attachments)
                },
                enabled = !state.busy && (text.isNotBlank() || attachments.isNotEmpty())
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(s.send, maxLines = 1)
            }
        }
    ) { padding ->
        TransformingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 26.dp,
                bottom = 0.dp
            )
        ) {
            item {
                ListHeader { Text(s.composeTitle) }
            }

            // 文本输入
            item {
                Card(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                    transformation = null
                ) {
                    Text(
                        s.messageLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth()) {
                        if (text.isEmpty()) {
                            Text(
                                s.messageHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        BasicTextField(
                            value = text,
                            onValueChange = setText,
                            minLines = 2,
                            maxLines = 5,
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 已选附件：仅显示文件名，可点击移除
            items(attachments, key = { it.uri }) { att ->
                Card(
                    onClick = {
                        haptic()
                        attachments = attachments.filterNot { it.uri == att.uri }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    transformation = null
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (att.mimeType.startsWith("image/")) Icons.Rounded.Image
                            else Icons.Rounded.InsertDriveFile,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            att.name,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 语音输入｜图片（去掉表情）
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            haptic()
                            startVoice()
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        transformation = null
                    ) {
                        Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(s.voiceInput, maxLines = 1, style = MaterialTheme.typography.labelSmall)
                    }
                    FilledTonalButton(
                        onClick = {
                            haptic()
                            runCatching { imageLauncher.launch(Intent(context, com.wear.wearbuddy.presentation.FilePickerActivity::class.java).setType("image/*")) }
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        transformation = null
                    ) {
                        Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(s.imageLabel, maxLines = 1)
                    }
                }
            }

            // 选择文件
            item {
                FilledTonalButton(
                    onClick = {
                        haptic()
                        runCatching { fileLauncher.launch(Intent(context, com.wear.wearbuddy.presentation.FilePickerActivity::class.java).setType("*/*")) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    transformation = null
                ) {
                    Icon(Icons.Rounded.InsertDriveFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s.chooseFile, maxLines = 1)
                }
            }

            // 粘贴｜换行
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            haptic()
                            val clip = clipboard.getText()?.toString().orEmpty()
                            if (clip.isNotEmpty()) setText(text + clip)
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        transformation = null
                    ) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(s.paste, maxLines = 1)
                    }
                    FilledTonalButton(
                        onClick = {
                            haptic()
                            setText(text + "\n")
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        transformation = null
                    ) {
                        Icon(Icons.Rounded.KeyboardReturn, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(s.newline, maxLines = 1)
                    }
                }
            }
        }
    }
}
