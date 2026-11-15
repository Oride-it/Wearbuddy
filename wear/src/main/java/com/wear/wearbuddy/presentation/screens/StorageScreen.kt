package com.wear.wearbuddy.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.wear.wearbuddy.presentation.LocalStrings
import com.wear.wearbuddy.presentation.SectionCard
import com.wear.wearbuddy.presentation.plus
import com.wear.wearbuddy.presentation.rememberClickHaptic
import java.io.File
import java.util.Locale

/** 存储空间（覆盖页）：缓存/数据占用 + 清除缓存。 */
@Composable
fun StorageScreen(modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val listState = rememberTransformingLazyColumnState()
    val haptic = rememberClickHaptic()
    var cacheBytes by remember { mutableStateOf(dirSize(context.cacheDir)) }
    var dataBytes by remember { mutableStateOf(dirSize(context.filesDir)) }
    var confirming by remember { mutableStateOf(false) }

    ScreenScaffold(scrollState = listState, modifier = modifier) { padding ->
        TransformingLazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = padding + PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = 26.dp,
                bottom = 40.dp
            )
        ) {
            item {
                ListHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        ,
                    transformation = null
                ) {
                    Text(s.storageTitle)
                }
            }
            item {
                SectionCard(
                    title = s.storageTitle,
                    shape = RoundedCornerShape(16.dp),
                    transformation = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        
                ) {
                    Text(
                        "${s.storageCache}: ${formatSize(cacheBytes)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${s.storageData}: ${formatSize(dataBytes)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            item {
                FilledTonalButton(
                    onClick = {
                        haptic()
                        confirming = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        ,
                    transformation = null
                ) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(s.clearCache, maxLines = 1)
                }
            }
            item {
                Button(
                    onClick = {
                        haptic()
                        cacheBytes = dirSize(context.cacheDir)
                        dataBytes = dirSize(context.filesDir)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        ,
                    transformation = null
                ) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("刷新", maxLines = 1)
                }
            }
        }
    }

    if (confirming) {
        AlertDialog(
            visible = true,
            onDismissRequest = { confirming = false },
            confirmButton = {
                AlertDialogDefaults.ConfirmButton(
                    onClick = {
                        context.cacheDir?.deleteRecursively()
                        cacheBytes = dirSize(context.cacheDir)
                        confirming = false
                    }
                )
            },
            title = { Text(s.clearCache) }
        )
    }
}

private fun dirSize(dir: File?): Long {
    if (dir == null || !dir.exists()) return 0L
    return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.1f MB", mb)
}
