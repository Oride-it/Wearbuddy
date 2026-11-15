package com.wear.wearbuddy.presentation.pages

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import com.wear.wearbuddy.data.Project
import com.wear.wearbuddy.presentation.EmptyHint
import com.wear.wearbuddy.presentation.LocalStrings
import com.wear.wearbuddy.presentation.rememberClickHaptic
import com.wear.wearbuddy.vm.AppViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * 第 2 页：项目。顶部「添加项目」按钮，点开新建页（名称 + 命令）；
 * 项目卡片点按即把命令作为任务下发，长按删除。
 */
@Composable
fun ProjectsPage(
    actions: AppViewModel,
    modifier: Modifier = Modifier
) {
    val state by actions.ui.collectAsState()
    val s = LocalStrings.current
    val listState = rememberTransformingLazyColumnState()
    val haptic = rememberClickHaptic()
    var deleting by remember { mutableStateOf<Project?>(null) }

    TransformingLazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 26.dp, bottom = 40.dp)
    ) {
        item {
            ListHeader(
                modifier = Modifier
                    .fillMaxWidth()
                    ,
                transformation = null
            ) {
                Text(s.tabProjects)
            }
        }
        item {
            // 最顶部的「添加」按钮（M3e 药丸按钮）
            Button(
                onClick = {
                    haptic()
                    actions.openAddProject()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    ,
                transformation = null
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Spacer(Modifier.width(6.dp))
                Text(s.addProjectButton, maxLines = 1)
            }
        }
        if (state.projects.isEmpty()) {
            item {
                EmptyHint(
                    text = s.emptyProjects,
                    modifier = Modifier
                        .fillMaxWidth()
                        
                )
            }
        } else {
            items(state.projects, key = { it.id }) { project ->
                ProjectCard(
                    project = project,
                    transformation = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        ,
                    onRun = {
                        haptic()
                        actions.runProject(project.id)
                    },
                    onDelete = { deleting = project }
                )
            }
        }
    }

    deleting?.let { project ->
        AlertDialog(
            visible = true,
            onDismissRequest = { deleting = null },
            confirmButton = {
                AlertDialogDefaults.ConfirmButton(
                    onClick = {
                        actions.deleteProject(project.id)
                        deleting = null
                    }
                )
            },
            title = { Text(s.confirmDeleteProject) },
            text = { Text(project.name) }
        )
    }
}

/** 项目卡片：名称 + 命令预览；点按运行，长按删除。 */
@Composable
private fun ProjectCard(
    project: Project,
    transformation: SurfaceTransformation?,
    modifier: Modifier = Modifier,
    onRun: () -> Unit,
    onDelete: () -> Unit
) {
    val s = LocalStrings.current
    TitleCard(
        onClick = onRun,
        onLongClick = onDelete,
        onLongClickLabel = s.deleteProject,
        title = {
            Text(
                project.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        modifier = modifier,
        transformation = transformation,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        content = {
            Text(
                project.command,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.runProject,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    )
}
