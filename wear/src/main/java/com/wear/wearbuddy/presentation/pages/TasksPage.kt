package com.wear.wearbuddy.presentation.pages

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import com.wear.wearbuddy.data.Task
import com.wear.wearbuddy.data.TaskStatus
import com.wear.wearbuddy.presentation.LocalStrings
import com.wear.wearbuddy.presentation.InputBar
import com.wear.wearbuddy.presentation.EmptyHint
import com.wear.wearbuddy.presentation.StatusBadge
import com.wear.wearbuddy.presentation.rememberClickHaptic
import com.wear.wearbuddy.vm.AppViewModel

/**
 * 第 1 页：任务。上半部分是任务列表（卡片 + 状态徽标），
 * 底部是输入条（圆圈选模型｜输入｜发送），发送后自动进入「思考中」详情页。
 */
@Composable
fun TasksPage(
    actions: AppViewModel,
    modifier: Modifier = Modifier
) {
    val state by actions.ui.collectAsState()
    val s = LocalStrings.current
    val listState: TransformingLazyColumnState = rememberTransformingLazyColumnState()
    val haptic = rememberClickHaptic()

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            if (spoken.isNotBlank()) actions.sendTask(spoken)
        }
    }

    Box(modifier.fillMaxSize()) {
        TransformingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 26.dp,
                bottom = 84.dp
            )
        ) {
            item {
                ListHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        ,
                    transformation = null
                ) {
                    Text(s.tabTasks)
                }
            }

            if (!state.loggedIn) {
                item {
                    EmptyHint(
                        text = s.notLoggedInHint,
                        modifier = Modifier
                            .fillMaxWidth()
                            
                    )
                }
            } else if (state.tasks.isEmpty()) {
                item {
                    EmptyHint(
                        text = if (state.connected) s.emptyTasks else s.networkOffline,
                        modifier = Modifier
                            .fillMaxWidth()
                            
                    )
                }
            } else {
                items(state.tasks, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        transformation = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            ,
                        onClick = {
                            haptic()
                            actions.openTask(task.id)
                        }
                    )
                }
            }
        }

        // 底部输入条：选模型｜语音｜打开「发消息」输入页
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            if (state.loggedIn && !state.connected) {
                val errorText = state.cloudError
                if (errorText != null) Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                ) {
                    Icon(
                        Icons.Rounded.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        errorText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
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
                onOpenInput = { actions.openCompose(null) },
                enabled = state.loggedIn
            )
        }
    }
}

/** 任务卡片：标题 + 状态徽标；执行中在左侧显示转圈。 */
@Composable
private fun TaskCard(
    task: Task,
    transformation: SurfaceTransformation?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        transformation = transformation,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (task.status == TaskStatus.WORKING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.size(8.dp))
            }
            Text(
                task.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.size(8.dp))
            StatusBadge(task.status)
        }
    }
}
