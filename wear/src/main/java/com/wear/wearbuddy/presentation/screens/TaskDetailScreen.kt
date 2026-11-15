package com.wear.wearbuddy.presentation.screens

import android.app.Activity
import android.content.Intent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import com.wear.wearbuddy.data.TaskStatus
import com.wear.wearbuddy.presentation.InputBar
import com.wear.wearbuddy.presentation.LocalStrings
import com.wear.wearbuddy.presentation.MessageBubble
import com.wear.wearbuddy.presentation.rememberClickHaptic
import com.wear.wearbuddy.presentation.statusLabelOf
import com.wear.wearbuddy.vm.AppViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * 任务详情（覆盖页）：状态头（思考中转圈）、完整对话消息（含「思考」过程）、
 * askuser 应答、产物清单、停止/重试、底部追问输入条。
 */
@Composable
fun TaskDetailScreen(
    actions: AppViewModel,
    modifier: Modifier = Modifier
) {
    val state by actions.ui.collectAsState()
    val s = LocalStrings.current
    val task = state.activeTask ?: return
    val listState = rememberTransformingLazyColumnState()
    val haptic = rememberClickHaptic()
    val copy = com.wear.wearbuddy.presentation.rememberCopyText()

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            if (spoken.isNotBlank()) actions.sendReply(spoken)
        }
    }

    // 新消息到达时滚到底部
    LaunchedEffect(state.history.size) {
        if (state.history.isNotEmpty()) {
            runCatching { listState.requestScrollToItem(state.history.size + 2) }
        }
    }

    ScreenScaffold(
        scrollState = listState,
        modifier = modifier
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(Modifier.weight(1f)) {
                TransformingLazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 26.dp,
                        bottom = 16.dp
                    )
                ) {
                    // 状态头
                    item {
                        TitleCard(
                            onClick = {},
                            onLongClick = { copy(task.title) },
                            onLongClickLabel = s.copyTextLabel,
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (task.status == TaskStatus.WORKING) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text(
                                        when (task.status) {
                                            TaskStatus.WORKING -> s.statusThinking
                                            else -> statusLabelOf(task.status)
                                        },
                                        maxLines = 1
                                    )
                                }
                            },
                            subtitle = {
                                Text(
                                    task.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                ,
                            transformation = null
                        )
                    }

                    task.error?.let { err ->
                        item {
                            Card(
                                onClick = {},
                                modifier = Modifier
                                    .fillMaxWidth()
                                    ,
                                transformation = null,
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                )
                            ) {
                                Text(err, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    // askuser：云端的问题 + 选项
                    if (task.status == TaskStatus.BLOCKED && task.ask != null) {
                        item {
                            ListHeader {
                                Text(s.askPrompt)
                            }
                        }
                        item {
                            Card(
                                onClick = {},
                                modifier = Modifier
                                    .fillMaxWidth()
                                    ,
                                transformation = null
                            ) {
                                Text(
                                    task.ask.question,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        val options = task.ask.options.ifEmpty { emptyList() }
                        items(options, key = { it.label }) { option ->
                            Button(
                                onClick = {
                                    haptic()
                                    actions.answerAsk(option.label)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    ,
                                transformation = null
                            ) {
                                Text(option.label, maxLines = 2)
                            }
                        }
                    }

                    // 对话消息
                    if (state.historyLoading && state.history.isEmpty()) {
                        item {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 18.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp))
                            }
                        }
                    } else if (state.history.isEmpty() && task.status == TaskStatus.WORKING) {
                        item {
                            Text(
                                s.statusThinking + "…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    } else {
                        items(state.history) { message ->
                            MessageBubble(
                                message = message,
                                modifier = Modifier,
                                transformation = null
                            )
                        }
                        // 追问等待云端回复
                        if (state.historyBusy) {
                            item {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 6.dp),
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        s.statusThinking,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // 产物文件
                    task.result?.files?.takeIf { it.isNotEmpty() }?.let { files ->
                        item {
                            ListHeader { Text(s.artifacts) }
                        }
                        items(files, key = { it.uri }) { file ->
                            ArtifactRow(actions, task.id, file,
                                state.artifactPreviews[com.wear.wearbuddy.vm.artifactPreviewKey(task.id, file.uri)],
                                Modifier.fillMaxWidth())
                        }
                    }

                    // 停止 / 重试
                    item {
                        when (task.status) {
                            TaskStatus.WORKING, TaskStatus.BLOCKED -> {
                                FilledTonalButton(
                                    onClick = {
                                        haptic()
                                        actions.stopActiveTask()
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        ,
                                    transformation = null,
                                    colors = androidx.wear.compose.material3.ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                ) {
                                    Icon(
                                        Icons.Rounded.Stop,
                                        contentDescription = null,
                                        modifier = Modifier.size(ButtonDefaults.IconSize)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(s.stop, maxLines = 1)
                                }
                            }

                            TaskStatus.FAILED -> {
                                Button(
                                    onClick = {
                                        haptic()
                                        actions.retryActiveTask()
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        ,
                                    transformation = null
                                ) {
                                    Text(s.retry, maxLines = 1)
                                }
                            }

                            else -> Unit
                        }
                    }
                }
            }

            // 底部追问输入条：选模型｜语音｜打开「发消息」输入页
            if (task.status != TaskStatus.WORKING || state.history.isNotEmpty()) {
                InputBar(
                    onModelClick = actions::openModelPicker,
                    onVoiceClick = {
                        runCatching {
                            voiceLauncher.launch(
                                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(
                                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                    )
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, s.inputHint)
                                }
                            )
                        }
                    },
                    onOpenInput = { actions.openCompose(task.id) },
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                )
            }
        }
    }
}
