package com.wear.wearbuddy.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.wear.wearbuddy.data.GrowthPlan
import com.wear.wearbuddy.data.GrowthTask
import com.wear.wearbuddy.data.CloudSettings
import com.wear.wearbuddy.data.CloudTool
import com.wear.wearbuddy.presentation.EmptyHint
import com.wear.wearbuddy.presentation.LocalStrings
import com.wear.wearbuddy.presentation.SectionCard
import com.wear.wearbuddy.presentation.plus
import com.wear.wearbuddy.presentation.stringsOf
import com.wear.wearbuddy.vm.AppViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val GROWTH_CENTER_URL = "https://www.workbuddy.cn/profile/growth-center"

@Composable
fun GrowthPlanScreen(actions: AppViewModel, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val english = s === stringsOf(CloudSettings.LANG_EN)
    val listState = rememberTransformingLazyColumnState()
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var browserUnavailable by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<GrowthTask?>(null) }
    var updating by remember { mutableStateOf(false) }
    var updateFailed by remember { mutableStateOf(false) }
    fun actionLabel(task: GrowthTask) = if (task.canClaim) {
        if (english) "Claim reward" else "领取奖励"
    } else {
        if (english) "Accept task" else "领取任务"
    }
    val result by produceState<Result<GrowthPlan>?>(null, refresh) {
        value = null
        value = try {
            Result.success(actions.fetchGrowthPlan())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    val plan = result?.getOrNull()
    ScreenScaffold(scrollState = listState, modifier = modifier) { padding ->
        TransformingLazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = padding + PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = 26.dp,
                bottom = 40.dp
            )
        ) {
            item { ListHeader { Text(s.growthPlan) } }
            when {
                result == null -> item { EmptyHint(s.loading) }
                plan == null -> {
                    item { EmptyHint(s.growthLoadFailed) }
                    item {
                        FilledTonalButton(
                            onClick = { refresh++ },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(s.retry) }
                    }
                }
                else -> item {
                    val level = plan.levelName.ifBlank { "${s.growthLevel} ${plan.level}" }
                    SectionCard(title = level, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                        Text("${s.growthEnergy} ${plan.energy}", style = MaterialTheme.typography.bodySmall)
                        Text("${s.growthProgress} ${plan.completed}/${plan.total}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                FilledTonalButton(
                    onClick = {
                        browserUnavailable = try {
                            uriHandler.openUri(GROWTH_CENTER_URL)
                            false
                        } catch (_: IllegalArgumentException) {
                            true
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(s.growthOpenWebsite) }
            }
            if (browserUnavailable) item { EmptyHint(s.growthBrowserUnavailable) }
            if (updateFailed) item { EmptyHint(if (english) "The action failed" else "操作失败") }
            items(listOf(CloudTool.Badges, CloudTool.Buddies, CloudTool.Travel, CloudTool.Streak), key = { it.name }) { tool ->
                FilledTonalButton(
                    onClick = { actions.openCloudTool(tool) },
                    enabled = !updating,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(tool.title(english)) }
            }

            if (plan != null) {
                if (plan.tasks.isEmpty()) item { EmptyHint(s.growthNoTasks) }
                items(plan.tasks) { task ->
                    val status = when {
                        task.locked -> s.growthLocked
                        task.status == "not_accepted" -> s.growthUnstarted
                        task.status == "accepted" || task.status == "in_progress" -> s.growthInProgress
                        task.status == "completed" -> s.growthCompleted
                        task.status == "claimed" -> s.growthClaimed
                        else -> ""
                    }
                    SectionCard(title = task.title, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
                        if (task.target > 0) {
                            Text("${task.current}/${task.target}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (task.energy > 0) {
                            Text("${s.growthEnergy} +${task.energy}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (task.canAccept || task.canClaim) {
                            FilledTonalButton(
                                onClick = { pending = task },
                                enabled = !updating,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(actionLabel(task)) }
                        }
                    }
                }
            }
        }
    }
    pending?.let { task ->
        AlertDialog(
            visible = true,
            onDismissRequest = { pending = null },
            title = { Text(actionLabel(task)) },
            text = { Text(task.title) },
            confirmButton = {
                AlertDialogDefaults.ConfirmButton(onClick = {
                    pending = null
                    if (updating) return@ConfirmButton
                    updating = true
                    updateFailed = false
                    scope.launch {
                        try {
                            actions.updateGrowthTask(task)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            updateFailed = true
                        } finally {
                            refresh++
                            updating = false
                        }
                    }
                })
            }
        )
    }
}
