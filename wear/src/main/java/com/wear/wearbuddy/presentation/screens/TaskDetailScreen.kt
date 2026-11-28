package com.wear.wearbuddy.presentation.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.FocusRequester
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.rotary.onPreRotaryScrollEvent
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import androidx.wear.compose.material3.TimeText
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

private val ChatControlsBottomInset = 22.dp
private val ChatTopInset = 26.dp
private val ChatBottomInset = ChatControlsBottomInset + 58.dp
private const val LATEST_MESSAGE = "latest-message"
private const val ASK_CARD = "ask"

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
    TaskDetailContent(state, actions, modifier)
}

@Composable
internal fun TaskDetailContent(state: com.wear.wearbuddy.vm.UiState, actions: AppViewModel, modifier: Modifier = Modifier) {
    val task = state.activeTask ?: return
    key(task.id) { TaskConversation(state, task, actions, modifier) }
}

@Composable
private fun TaskConversation(
    state: com.wear.wearbuddy.vm.UiState,
    task: com.wear.wearbuddy.data.Task,
    actions: AppViewModel,
    modifier: Modifier
) {
    val s = LocalStrings.current
    val listState = rememberLazyListState()
    val haptic = rememberClickHaptic()
    val copy = com.wear.wearbuddy.presentation.rememberCopyText()

    val scope = rememberCoroutineScope()
    val rotaryFocus = remember { FocusRequester() }
    val ask = task.ask?.takeIf { task.status == TaskStatus.BLOCKED }
    val loadingEmptyHistory = state.historyLoading && state.history.isEmpty()
    val waitingForFirstMessage = state.history.isEmpty() && task.status == TaskStatus.WORKING
    val showPendingReply = state.historyBusy && !loadingEmptyHistory && !waitingForFirstMessage
    val conversationEndIndex = 1 + (if (state.historySyncFailed) 1 else 0) +
        (if (task.error != null) 1 else 0) +
        (if (loadingEmptyHistory || waitingForFirstMessage) 1 else state.history.size) +
        (if (showPendingReply) 1 else 0)
    val latestKey = if (ask == null) LATEST_MESSAGE else ASK_CARD
    val reserve = with(LocalDensity.current) { (ChatTopInset + ChatBottomInset).roundToPx() }
    val tolerance = with(LocalDensity.current) { 18.dp.roundToPx() }
    val atLatest by remember(listState, latestKey, reserve, tolerance) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val row = layout.visibleItemsInfo.firstOrNull { it.key == latestKey }
            val target = (layout.viewportSize.height - reserve).coerceAtLeast(0)
            row != null && when {
                latestKey == ASK_CARD -> row.offset in -tolerance..tolerance
                !listState.canScrollBackward -> row.offset <= target + tolerance
                else -> row.offset in (target - tolerance)..(target + tolerance)
            }
        }
    }
    val latestVisible by rememberUpdatedState(atLatest)
    var followLatest by remember { mutableStateOf(true) }
    var rotaryScrolling by remember { mutableStateOf(false) }
    var artifactsExpanded by rememberSaveable { mutableStateOf(false) }
    val scrollTracker = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) followLatest = false
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && consumed.y != 0f) followLatest = latestVisible
                return Offset.Zero
            }
        }
    }
    suspend fun scrollToLatest() {
        val height = snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
        val offset = if (ask != null) 0 else -(height - reserve).coerceAtLeast(0)
        listState.scrollToItem(conversationEndIndex, offset)
    }
    val hasConversation = state.history.isNotEmpty() || ask != null
    LaunchedEffect(Unit) { rotaryFocus.requestFocus() }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && rotaryScrolling) {
                rotaryScrolling = false
                followLatest = latestVisible
            }
        }
    }
    LaunchedEffect(conversationEndIndex, state.history.lastOrNull(), ask, reserve) {
        if (hasConversation && followLatest) scrollToLatest()
    }

    ScreenScaffold(
        scrollState = listState,
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
        timeText = { TimeText(backgroundColor = Color.Transparent) }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(scrollTracker)
                .onPreRotaryScrollEvent {
                    rotaryScrolling = true
                    followLatest = false
                    false
                }
                .rotaryScrollable(RotaryScrollableDefaults.behavior(listState), rotaryFocus),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            // 给首尾消息留出空间；滚动时列表仍然铺满屏幕。
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = ChatTopInset,
                bottom = ChatBottomInset
            )
        ) {
            // 状态头
            item(key = "header") {
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
                    modifier = Modifier.fillMaxWidth(),
                    transformation = null
                )
            }

            if (state.historySyncFailed) item(key = "history-sync") {
                FilledTonalButton(onClick = actions::retryHistory, modifier = Modifier.fillMaxWidth(),
                    label = { Text(s.historyUnavailable) }, secondaryLabel = { Text(s.retry) })
            }

            task.error?.let { err ->
                item {
                    Card(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(),
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

            // 对话消息
            if (loadingEmptyHistory) {
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
            } else if (waitingForFirstMessage) {
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
                itemsIndexed(state.history, key = { index, _ -> "message:$index" }) { _, message ->
                    MessageBubble(message)
                }
                // 追问等待云端回复
                if (showPendingReply) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            horizontalArrangement = Arrangement.Center
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
            if (ask != null) {
                item(key = ASK_CARD) {
                    AskCard(ask, state.busy) { picks -> actions.answerAsk(ask, picks) }
                }
            }
            item(key = LATEST_MESSAGE) { Spacer(Modifier.height(1.dp)) }

            // 停止 / 重试
            item(key = "actions") {
                when (task.status) {
                    TaskStatus.WORKING, TaskStatus.BLOCKED -> {
                        FilledTonalButton(
                            onClick = {
                                haptic()
                                actions.stopActiveTask()
                            },
                            modifier = Modifier.fillMaxWidth(),
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
                            modifier = Modifier.fillMaxWidth(),
                            transformation = null
                        ) {
                            Text(s.retry, maxLines = 1)
                        }
                    }

                    else -> Unit
                }
            }

            val artifactFiles = task.result?.files.orEmpty()
            if (artifactFiles.isNotEmpty()) {
                item(key = "artifacts-header") {
                    FilledTonalButton(
                        onClick = {
                            haptic()
                            artifactsExpanded = !artifactsExpanded
                            if (artifactsExpanded) followLatest = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        transformation = null
                    ) {
                        Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(s.cloudArtifacts + " · " + artifactFiles.size, modifier = Modifier.weight(1f))
                        Icon(if (artifactsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
                if (artifactsExpanded) {
                    items(artifactFiles, key = { "artifact:" + it.uri }) { file ->
                        ArtifactRow(actions, task.id, file,
                            state.artifactPreviews[com.wear.wearbuddy.vm.artifactPreviewKey(task.id, file.uri)],
                            Modifier.fillMaxWidth())
                    }
                }
            }
            item(key = "browse-workspace") {
                FilledTonalButton(
                    onClick = {
                        haptic()
                        actions.openWorkspaceBrowser()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    transformation = null
                ) {
                    Icon(
                        Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(s.browseWorkspace, maxLines = 1)
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(1.dp)) }
        }
        if (hasConversation && !atLatest) {
            FilledTonalIconButton(onClick = {
                followLatest = true
                scope.launch { scrollToLatest() }
            }, modifier = Modifier.align(Alignment.BottomEnd)
                .padding(end = 15.dp, bottom = ChatControlsBottomInset + 54.dp)
                .size(32.dp)) {
                Icon(Icons.Rounded.ArrowDownward, contentDescription = s.jumpToLatest, modifier = Modifier.size(18.dp))
            }
        }
        InputBar(
            onModelClick = actions::openModelPicker,
            onOpenInput = { actions.openCompose(task.id) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = ChatControlsBottomInset),
            enabled = !state.busy && (task.status != TaskStatus.WORKING || state.history.isNotEmpty())
        )
    }
}

@Composable
internal fun NewChatScreen(actions: AppViewModel, modifier: Modifier = Modifier) {
    val state by actions.ui.collectAsState()
    val s = LocalStrings.current
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ListHeader { Text(s.newChat) }
                Text(s.startChat, style = MaterialTheme.typography.bodySmall)
            }
        }
        InputBar(
            onModelClick = actions::openModelPicker,
            onOpenInput = { actions.openCompose(null) },
            modifier = Modifier.padding(bottom = ChatControlsBottomInset),
            enabled = !state.busy
        )
    }
}
