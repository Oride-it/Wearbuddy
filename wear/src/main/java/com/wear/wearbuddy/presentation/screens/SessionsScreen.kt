package com.wear.wearbuddy.presentation.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.wear.wearbuddy.data.CloudSettings
import com.wear.wearbuddy.presentation.EmptyHint
import com.wear.wearbuddy.presentation.LocalStrings
import com.wear.wearbuddy.presentation.StatusBadge
import com.wear.wearbuddy.presentation.plus
import com.wear.wearbuddy.presentation.rememberClickHaptic
import com.wear.wearbuddy.presentation.stringsOf
import com.wear.wearbuddy.vm.AppViewModel
import kotlinx.coroutines.CancellationException

/** 会话管理（覆盖页）：列出云端会话，可归档或删除。 */
@Composable
fun SessionsScreen(actions: AppViewModel, modifier: Modifier = Modifier) {
    val state by actions.ui.collectAsState()
    val s = LocalStrings.current
    val listState = rememberTransformingLazyColumnState()
    val haptic = rememberClickHaptic()
    val english = s === stringsOf(CloudSettings.LANG_EN)
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchIds by remember { mutableStateOf<List<String>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchFailed by remember { mutableStateOf(false) }
    var inputFailed by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(actions.pinnedSessions()) }
    val task = state.tasks.firstOrNull { it.id == selected }
    val searchLabel = if (english) "Search sessions" else "搜索会话"
    val renameLabel = if (english) "Rename" else "重命名"
    val onInputUnavailable = { inputFailed = true }
    val searchInput = rememberWearTextInput(searchLabel, onInputUnavailable) {
        inputFailed = false
        query = it
    }
    val renameInput = rememberWearTextInput(renameLabel, onInputUnavailable) { title ->
        inputFailed = false
        task?.let { actions.renameSession(it.id, title) }
    }
    LaunchedEffect(query) {
        searchIds = null
        searchFailed = false
        if (query.isNotBlank()) {
            searching = true
            try {
                searchIds = actions.searchSessions(query)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                searchFailed = true
            } finally {
                searching = false
            }
        }
    }
    val visible = if (query.isBlank()) state.tasks else {
        val byId = state.tasks.associateBy { it.id }
        searchIds.orEmpty().mapNotNull(byId::get)
    }
    val sorted = visible.sortedByDescending { it.id in pinned }

    // 归档/删除成功后仓库会移除该会话，自动回到列表。
    LaunchedEffect(state.tasks, selected) {
        if (selected != null && task == null) selected = null
    }
    BackHandler(enabled = task != null) {
        confirmDelete = false
        selected = null
    }

    ScreenScaffold(scrollState = listState, modifier = modifier) { padding ->
        TransformingLazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = padding + PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = 26.dp,
                bottom = 40.dp
            )
        ) {
            item {
                ListHeader(
                    modifier = Modifier.fillMaxWidth(),
                    transformation = null
                ) {
                    Text(
                        task?.title ?: s.sessionsTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (task == null) {
                item {
                    FilledTonalButton(onClick = searchInput, modifier = Modifier.fillMaxWidth()) {
                        Text(query.ifBlank { searchLabel }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (query.isNotBlank()) item {
                    FilledTonalButton(onClick = { query = "" }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (english) "Clear search" else "清除搜索")
                    }
                }
                if (searching || searchFailed) {
                    item { EmptyHint(if (searching) s.loading else if (english) "Search failed" else "搜索失败") }
                } else if (sorted.isEmpty()) {
                    item {
                        EmptyHint(s.sessionsEmpty, modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    item {
                        Text(
                            s.sessionsHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    items(sorted, key = { it.id }) { item ->
                        Card(
                            onClick = {
                                haptic()
                                selected = item.id
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (item.id in pinned) "📌 ${item.title}" else item.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.size(8.dp))
                                StatusBadge(item.status)
                            }
                        }
                    }
                }
            } else {
                item {
                    FilledTonalButton(
                        onClick = { actions.openTask(task.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (english) "Open session" else "打开会话") }
                }
                item {
                    FilledTonalButton(onClick = renameInput, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(renameLabel)
                    }
                }
                item {
                    FilledTonalButton(
                        onClick = { pinned = actions.toggleSessionPin(task.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (task.id in pinned) {
                            if (english) "Unpin on watch" else "取消腕上置顶"
                        } else if (english) "Pin on watch" else "在手表置顶")
                    }
                }
                item {
                    Text(
                        s.sessionsHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                item {
                    FilledTonalButton(
                        onClick = {
                            haptic()
                            actions.archiveTask(task.id)
                        },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                        transformation = null
                    ) {
                        Icon(
                            Icons.Rounded.Archive,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(s.archiveSession, maxLines = 1)
                    }
                }
                item {
                    FilledTonalButton(
                        onClick = {
                            haptic()
                            confirmDelete = true
                        },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                        transformation = null,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(s.deleteSession, maxLines = 1)
                    }
                }
                item {
                    FilledTonalButton(
                        onClick = {
                            haptic()
                            selected = null
                        },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                        transformation = null
                    ) {
                        Text(s.cancel, maxLines = 1)
                    }
                }
            }
            if (inputFailed) item { EmptyHint(if (english) "Text input is unavailable" else "此设备无法打开文字输入") }
        }
    }

    if (confirmDelete && task != null) {
        AlertDialog(
            visible = true,
            onDismissRequest = { confirmDelete = false },
            confirmButton = {
                AlertDialogDefaults.ConfirmButton(onClick = {
                    confirmDelete = false
                    actions.deleteTask(task.id)
                })
            },
            title = { Text(s.confirmDeleteSession) }
        )
    }
}
