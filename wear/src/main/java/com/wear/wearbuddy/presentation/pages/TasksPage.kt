package com.wear.wearbuddy.presentation.pages

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.*
import com.wear.wearbuddy.data.Task
import com.wear.wearbuddy.data.TaskStatus
import com.wear.wearbuddy.presentation.LocalStrings
import com.wear.wearbuddy.presentation.StatusBadge
import com.wear.wearbuddy.presentation.plus
import com.wear.wearbuddy.presentation.rememberClickHaptic
import com.wear.wearbuddy.vm.AppViewModel
import com.wear.wearbuddy.vm.UiState

@Composable
fun TasksPage(actions: AppViewModel, modifier: Modifier = Modifier) {
    val state by actions.ui.collectAsState()
    ConversationList(state, actions::openNewChat, actions::openTask, modifier)
}

@Composable
internal fun ConversationList(state: UiState, onNew: () -> Unit, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val list = rememberTransformingLazyColumnState()
    val haptic = rememberClickHaptic()
    ScreenScaffold(scrollState = list, modifier = modifier, contentPadding = PaddingValues(0.dp)) { padding ->
        TransformingLazyColumn(state = list, modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = padding + PaddingValues(start = 12.dp, end = 12.dp, top = 7.dp, bottom = 32.dp)) {
            item(key = "header") {
                ListHeader { Text(s.tabTasks, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            }
            item(key = "new") {
                Button(onClick = { haptic(); onNew() }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledVariantButtonColors(),
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    label = { Text(s.newChat) },
                    secondaryLabel = { Text(if (state.loggedIn) state.modelLabel else s.connectFirst, maxLines = 1, overflow = TextOverflow.Ellipsis) })
            }
            if (state.hasDraftChat) item(key = "draft") {
                FilledTonalButton(onClick = onNew, modifier = Modifier.fillMaxWidth(),
                    label = { Text(s.newChat) }, secondaryLabel = { Text(s.startChat) })
            }
            if (!state.hasDraftChat && state.tasks.isEmpty()) item(key = "empty") {
                TitleCard(onClick = { haptic(); onNew() }, modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 17.dp, vertical = 12.dp),
                    title = { Text(s.emptyTasks) }) {
                    Text(s.emptyChatHint, style = MaterialTheme.typography.bodySmall)
                }
            }
            items(state.tasks, key = { "chat:${it.id}" }) { task ->
                TaskCard(task, modifier = Modifier.fillMaxWidth()) { haptic(); onOpen(task.id) }
            }
            if (state.loggedIn && !state.connected) state.cloudError?.let { error ->
                item { Text(error, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** 任务卡片：标题 + 状态徽标；执行中在左侧显示转圈。 */
@Composable
internal fun TaskCard(
    task: Task,
    transformation: SurfaceTransformation? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val copy = com.wear.wearbuddy.presentation.rememberCopyText()
    FilledTonalButton(
        onClick = onClick,
        onLongClick = { copy(task.title) },
        onLongClickLabel = LocalStrings.current.copyTextLabel,
        modifier = modifier.heightIn(min = 48.dp),
        transformation = transformation,
        colors = ButtonDefaults.filledTonalButtonColors(
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
