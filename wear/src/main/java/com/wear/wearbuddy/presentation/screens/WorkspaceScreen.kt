package com.wear.wearbuddy.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.wear.wearbuddy.data.Artifact
import com.wear.wearbuddy.presentation.EmptyHint
import com.wear.wearbuddy.presentation.LocalStrings
import com.wear.wearbuddy.presentation.rememberClickHaptic
import com.wear.wearbuddy.vm.AppViewModel
import com.wear.wearbuddy.vm.artifactPreviewKey

/**
 * 工作区目录浏览（覆盖页）：列出云端沙箱当前目录的文件与子目录。
 * 目录可逐层进入 / 返回；文件行复用产物预览（点击展开内容或下载）。
 */
@Composable
fun WorkspaceScreen(actions: AppViewModel, modifier: Modifier = Modifier) {
    val state by actions.ui.collectAsState()
    val ws = state.workspace ?: return
    val s = LocalStrings.current
    val haptic = rememberClickHaptic()
    val listState = rememberLazyListState()
    val rotaryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { rotaryFocus.requestFocus() }

    ScreenScaffold(scrollState = listState, modifier = modifier, contentPadding = PaddingValues(0.dp)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().rotaryScrollable(
                RotaryScrollableDefaults.behavior(listState), rotaryFocus
            ),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 26.dp,
                bottom = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item(key = "header") {
                ListHeader {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(s.workspaceTitle)
                        }
                        Text(
                            ws.path,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (ws.stack.isNotEmpty()) {
                item(key = "parent") {
                    FilledTonalButton(
                        onClick = {
                            haptic()
                            actions.openWorkspaceParent()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        transformation = null
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(s.workspaceParent, maxLines = 1)
                    }
                }
            }

            if (ws.loading) {
                item(key = "loading") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp))
                    }
                }
            } else if (ws.error != null) {
                item(key = "error") {
                    Card(
                        onClick = {
                            haptic()
                            actions.retryWorkspaceDir()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        transformation = null,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Text(
                            s.workspaceLoadFailed + "\n" + ws.error,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            } else if (ws.entries.isEmpty()) {
                item(key = "empty") {
                    EmptyHint(s.workspaceEmpty, modifier = Modifier.fillMaxWidth())
                }
            } else {
                val dirs = ws.entries.filter { it.isDir }
                val files = ws.entries.filterNot { it.isDir }
                items(dirs, key = { "dir:" + it.path }) { entry ->
                    DirectoryRow(entry.name, onClick = {
                        haptic()
                        actions.openWorkspaceChild(entry)
                    })
                }
                items(files, key = { "file:" + it.path }) { entry ->
                    val artifact = remember(entry.path) {
                        Artifact(name = entry.name, uri = "file://" + entry.path)
                    }
                    ArtifactRow(
                        actions = actions,
                        taskId = ws.taskId,
                        file = artifact,
                        preview = state.artifactPreviews[artifactPreviewKey(ws.taskId, artifact.uri)],
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/** 目录行：文件夹图标 + 名称，整行可点击进入。 */
@Composable
private fun DirectoryRow(name: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        transformation = null,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Folder,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
