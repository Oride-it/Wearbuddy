package com.wear.wearbuddy.presentation.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.wear.wearbuddy.presentation.copyOnLongPress
import com.wear.wearbuddy.presentation.isHtml
import com.wear.wearbuddy.presentation.openHtmlPreview
import com.wear.wearbuddy.data.Artifact
import com.wear.wearbuddy.presentation.EmptyHint
import com.wear.wearbuddy.presentation.LocalStrings
import com.wear.wearbuddy.presentation.plus
import com.wear.wearbuddy.presentation.rememberClickHaptic
import com.wear.wearbuddy.vm.AppViewModel
import com.wear.wearbuddy.vm.ArtifactPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 产物中心（覆盖页）：汇总所有任务产出的文件，可直接预览或重新下载。 */
@Composable
fun ArtifactsScreen(actions: AppViewModel, modifier: Modifier = Modifier) {
    val state by actions.ui.collectAsState()
    val s = LocalStrings.current
    val listState = rememberTransformingLazyColumnState()

    val files = remember(state.tasks) {
        val seen = HashSet<Pair<String, String>>()
        state.tasks.flatMap { task ->
            task.result?.files.orEmpty().mapNotNull { file ->
                if (seen.add(task.id to file.uri)) task.id to file else null
            }
        }
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
                    Text(s.artifactsTitle)
                }
            }
            if (files.isEmpty()) {
                item {
                    EmptyHint(s.artifactsEmpty, modifier = Modifier.fillMaxWidth())
                }
            } else {
                items(files, key = { "${it.first}|${it.second.uri}" }) { (taskId, file) ->
                    ArtifactRow(
                        actions = actions,
                        taskId = taskId,
                        file = file,
                        preview = state.artifactPreviews[com.wear.wearbuddy.vm.artifactPreviewKey(taskId, file.uri)],
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
internal fun ArtifactRow(
    actions: AppViewModel,
    taskId: String,
    file: Artifact,
    preview: ArtifactPreview?,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    val haptic = rememberClickHaptic()
    var expanded by remember(taskId, file.uri) { mutableStateOf(false) }
    val context = LocalContext.current
    val copy = com.wear.wearbuddy.presentation.rememberCopyText()
    var launchHtml by remember(taskId, file.uri) { mutableStateOf(false) }
    LaunchedEffect(launchHtml, preview?.localPath) {
        if (launchHtml && preview?.localPath != null) {
            launchHtml = false
            openHtmlPreview(context, file, preview.localPath)
        }
    }

    val openArtifact = {
        haptic()
        if (file.isHtml()) {
            launchHtml = true
            actions.loadArtifactPreview(file, taskId)
        } else if (preview?.error != null) {
            actions.loadArtifactPreview(file, taskId)
        } else {
            expanded = !expanded
            if (expanded) actions.loadArtifactPreview(file, taskId)
        }
    }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openArtifact()
        else android.widget.Toast.makeText(context,
            if (com.wear.wearbuddy.data.CloudSettings(context).language == "zh") "需要存储权限才能保存到公共 Download 文件夹" else
                "Storage permission is needed to save to Download", android.widget.Toast.LENGTH_LONG).show()
    }
    Card(
        onClick = {
            if (android.os.Build.VERSION.SDK_INT <= 28 && context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) permissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            else openArtifact()
        },
        onLongClick = { copy(file.name) },
        onLongClickLabel = s.copyTextLabel,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        preview?.loading == true -> s.loading
                        preview?.error != null -> "${preview.error} · ${s.artifactTapToRetry}"
                        preview?.localPath != null -> s.artifactSaved
                        else -> if (file.isHtml()) s.previewHtml else s.artifactTapToView
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (preview?.error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (expanded && preview?.text != null) {
            Spacer(Modifier.size(6.dp))
            Text(preview.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.copyOnLongPress(preview.text))
        }
        if (expanded && preview?.localPath != null && preview.text == null) {
            ArtifactImage(preview.localPath, file.name)
        }
    }
}

@Composable
private fun ArtifactImage(path: String, name: String) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                val options = BitmapFactory.Options()
                while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize.coerceAtLeast(1) > 640) {
                    options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
                }
                BitmapFactory.decodeFile(path, options)
            } else null
        }
    }
    bitmap?.let {
        Spacer(Modifier.size(6.dp))
        Image(it.asImageBitmap(), name, Modifier.fillMaxWidth())
    }
}
