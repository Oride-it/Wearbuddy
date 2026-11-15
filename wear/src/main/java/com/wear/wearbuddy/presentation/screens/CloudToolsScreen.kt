package com.wear.wearbuddy.presentation.screens

import android.app.Activity
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import com.wear.wearbuddy.data.CloudCommand
import com.wear.wearbuddy.data.CloudAccessException
import com.wear.wearbuddy.data.CloudDestination
import com.wear.wearbuddy.data.CloudEntry
import com.wear.wearbuddy.data.CloudSettings
import com.wear.wearbuddy.data.CloudTool
import com.wear.wearbuddy.presentation.EmptyHint
import com.wear.wearbuddy.presentation.LocalStrings
import com.wear.wearbuddy.presentation.plus
import com.wear.wearbuddy.presentation.stringsOf
import com.wear.wearbuddy.vm.AppViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun CloudTool.title(english: Boolean): String {
    val labels = when (this) {
        CloudTool.Messages -> "消息中心" to "Messages"
        CloudTool.Automations -> "定时任务" to "Scheduled tasks"
        CloudTool.RunHistory -> "执行记录" to "Run history"
        CloudTool.Plugins -> "已安装插件" to "Installed plugins"
        CloudTool.PluginMarket -> "插件市场" to "Plugin market"
        CloudTool.Experts -> "专家榜" to "Expert ranking"
        CloudTool.Connectors -> "连接器" to "Connectors"
        CloudTool.Contacts -> "收藏联系人" to "Favorite contacts"
        CloudTool.Spaces -> "空间" to "Spaces"
        CloudTool.SpaceFiles -> "空间文件" to "Space files"
        CloudTool.Documents -> "腾讯文档" to "Tencent Docs"
        CloudTool.Shares -> "我的文件分享" to "Shared files"
        CloudTool.Agents -> "云端 Agent" to "Cloud agents"
        CloudTool.AgentAssets -> "专家和技能" to "Experts and skills"
        CloudTool.AgentConnectors -> "Agent 连接器" to "Agent connectors"
        CloudTool.Skills -> "已安装技能" to "Installed skills"
        CloudTool.Credits -> "积分包明细" to "Credit packages"
        CloudTool.Badges -> "已获得徽章" to "Earned badges"
        CloudTool.Buddies -> "我的 Buddy" to "My Buddies"
        CloudTool.Travel -> "旅行记录" to "Travel history"
        CloudTool.Streak -> "连续活跃" to "Activity streak"
    }
    return if (english) labels.second else labels.first
}

private fun CloudCommand.label(english: Boolean): String {
    val labels = when (this) {
        CloudCommand.MarkRead -> "标为已读" to "Mark as read"
        CloudCommand.MarkAllRead -> "全部标为已读" to "Mark all as read"
        CloudCommand.Run -> "立即执行" to "Run now"
        CloudCommand.Enable -> "启用" to "Enable"
        CloudCommand.Disable -> "停用" to "Disable"
        CloudCommand.Delete -> "删除" to "Delete"
        CloudCommand.Install -> "安装" to "Install"
        CloudCommand.Uninstall -> "卸载" to "Uninstall"
        CloudCommand.SwitchBuddy -> "切换 Buddy" to "Switch Buddy"
    }
    return if (english) labels.second else labels.first
}

private val menu = listOf(
    CloudTool.Messages, CloudTool.Automations, CloudTool.Experts,
    CloudTool.Skills, CloudTool.Plugins, CloudTool.PluginMarket, CloudTool.Connectors,
    CloudTool.Contacts, CloudTool.Spaces, CloudTool.Documents, CloudTool.Shares, CloudTool.Agents, CloudTool.Credits
)

@Composable
internal fun CloudToolsScreen(actions: AppViewModel, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val english = s === stringsOf(CloudSettings.LANG_EN)
    CloudList(s.cloudTools, modifier) {
        items(menu, key = { it.name }) { tool ->
            FilledTonalButton(onClick = { actions.openCloudTool(tool) }, modifier = Modifier.fillMaxWidth()) {
                Text(tool.title(english))
            }
        }
    }
}

@Composable
internal fun CloudToolScreen(
    destination: CloudDestination,
    actions: AppViewModel,
    modifier: Modifier = Modifier,
    selected: CloudEntry? = null
) {
    val s = LocalStrings.current
    val english = s === stringsOf(CloudSettings.LANG_EN)
    val tool = destination.tool
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var entries by remember { mutableStateOf(emptyList<CloudEntry>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var cursor by remember { mutableStateOf<String?>(null) }
    var next by remember { mutableStateOf<String?>(null) }
    var unread by remember { mutableStateOf<Int?>(null) }
    var streakDays by remember { mutableStateOf<Int?>(null) }
    var usesExpertMarket by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<CloudCommand?>(null) }
    val revisions by actions.cloudRevisions.collectAsState()
    val revision = revisions[tool]
    var appliedRevision by remember { mutableStateOf(revision) }
    val searchLabel = if (tool == CloudTool.Documents) {
        if (english) "Search documents" else "搜索文档"
    } else if (english) "Search plugins" else "搜索插件"

    val textInput = rememberWearTextInput(searchLabel, onUnavailable = {
        actionMessage = if (english) "Text input is unavailable" else "此设备无法打开文字输入"
    }) { text ->
        query = text
        entries = emptyList()
        cursor = null
        version++
    }

    LaunchedEffect(destination, cursor, version, revision) {
        if (selected != null) {
            loading = false
            return@LaunchedEffect
        }
        if (revision != appliedRevision && cursor != null) {
            cursor = null
            return@LaunchedEffect
        }
        appliedRevision = revision
        loading = true
        loadError = null
        try {
            val page = actions.loadCloudTool(destination, cursor, query)
            val previous = if (cursor == null) emptyList() else entries
            entries = (previous + page.entries).associateBy { it.id }.values.toList()
            next = page.next?.takeUnless { it == cursor }
            if (cursor == null) {
                unread = page.unread
                streakDays = page.streakDays
                usesExpertMarket = page.usesExpertMarket
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: CloudAccessException) {
            loadError = if (english) "This account or session cannot access the feature"
            else "当前账号或登录会话无法访问此功能"
        } catch (_: Exception) {
            loadError = if (english) "Could not load this feature" else "云端数据加载失败"
        } finally {
            loading = false
        }
    }

    CloudList(selected?.title ?: tool.title(english), modifier) {
        if (selected == null) {
            if (tool == CloudTool.PluginMarket || tool == CloudTool.Documents) item {
                Button(onClick = textInput, enabled = !loading && !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(query.ifBlank { searchLabel }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            streakDays?.let { days ->
                item { EmptyHint(if (english) "Active for $days days" else "已连续活跃 $days 天") }
            }
            if (usesExpertMarket) {
                item { EmptyHint(if (english) "Ranking unavailable. Showing the expert catalog." else "专家榜暂不可用，以下为专家市场") }
            }
            if (tool == CloudTool.Messages && (unread ?: 0) > 0) {
                item { EmptyHint(if (english) "Unread ${unread}" else "未读 ${unread}") }
            }
            if (tool == CloudTool.Messages && entries.any { CloudCommand.MarkRead in it.commands }) item {
                FilledTonalButton(
                    onClick = { pending = CloudCommand.MarkAllRead },
                    enabled = !loading && !busy, modifier = Modifier.fillMaxWidth()
                ) { Text(CloudCommand.MarkAllRead.label(english)) }
            }
            items(entries, key = { it.id }) { row ->
                Button(
                    onClick = { actions.openCloudEntry(destination, row) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(row.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = row.summary.takeIf(String::isNotBlank)?.let { summary ->
                        { Text(summary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                )
            }
            if (!loading && loadError == null && entries.isEmpty() && (tool != CloudTool.PluginMarket || query.isNotBlank())) {
                item { EmptyHint(if (english) "No entries yet" else "暂无记录") }
            }
            if (!loading && loadError == null && next != null) item {
                FilledTonalButton(onClick = { cursor = next }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (english) "Load more" else "加载更多")
                }
            }
        } else {
            val details = listOf(selected.summary, selected.description).filter(String::isNotBlank)
            items(details) { text ->
                Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 10.dp))
            }
            items(selected.commands, key = { it.name }) { command ->
                FilledTonalButton(
                    onClick = { pending = command },
                    enabled = !loading && !busy && loadError == null, modifier = Modifier.fillMaxWidth()
                ) { Text(command.label(english)) }
            }
            val children = when (tool) {
                CloudTool.Automations -> listOf(CloudTool.RunHistory)
                CloudTool.Agents -> listOf(CloudTool.AgentAssets, CloudTool.AgentConnectors)
                CloudTool.Spaces -> listOf(CloudTool.SpaceFiles)
                CloudTool.Documents -> if (selected.isFolder) listOf(CloudTool.Documents) else emptyList()
                else -> emptyList()
            }
            items(children, key = { it.name }) { child ->
                FilledTonalButton(
                    onClick = { actions.openCloudTool(child, selected) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(child.title(english)) }
            }
            if (selected.website.isNotBlank()) item {
                FilledTonalButton(onClick = {
                    try {
                        uriHandler.openUri(selected.website)
                    } catch (_: IllegalArgumentException) {
                        actionMessage = if (english) "Open this file in Tencent Docs" else "请在腾讯文档打开此文件"
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (english) "Open document" else "打开文档") }
            }
        }

        if (loading || busy) item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
        }
        loadError?.let { message ->
            item { EmptyHint(message) }
            item {
                FilledTonalButton(onClick = { version++ }, modifier = Modifier.fillMaxWidth()) { Text(s.retry) }
            }
        }
        actionMessage?.let { message -> item { EmptyHint(message) } }
    }

    pending?.let { command ->
        AlertDialog(
            visible = true,
            onDismissRequest = { pending = null },
            title = { Text(command.label(english)) },
            text = selected?.let { row -> { Text(row.title) } },
            confirmButton = {
                AlertDialogDefaults.ConfirmButton(onClick = {
                    pending = null
                    if (busy) return@ConfirmButton
                    busy = true
                    actionMessage = null
                    scope.launch {
                        try {
                            actions.runCloudAction(tool, selected, command)
                            if (selected != null) actions.closeOverlay()
                            else actionMessage = if (english) "Done" else "操作完成"
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            actionMessage = if (english) "The action failed" else "操作失败"
                        } finally {
                            busy = false
                        }
                    }
                })
            }
        )
    }
}

@Composable
internal fun CloudList(
    title: String,
    modifier: Modifier,
    content: TransformingLazyColumnScope.() -> Unit
) {
    val listState = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = listState, modifier = modifier) { padding ->
        TransformingLazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = padding + PaddingValues(start = 14.dp, end = 14.dp, top = 26.dp, bottom = 40.dp)
        ) {
            item { ListHeader { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
            content()
        }
    }
}

@Composable
internal fun rememberWearTextInput(
    title: String,
    onUnavailable: () -> Unit,
    onText: (String) -> Unit
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let(RemoteInput::getResultsFromIntent)?.getCharSequence("text")
                ?.toString()?.trim()?.let(onText)
        }
    }
    return {
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putTitleExtra(intent, title)
        RemoteInputIntentHelper.putRemoteInputsExtra(
            intent, listOf(RemoteInput.Builder("text").setLabel(title).build())
        )
        try {
            launcher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            onUnavailable()
        }
    }
}
