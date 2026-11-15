package com.wear.wearbuddy.presentation

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.*
import androidx.wear.compose.material3.*
import com.wear.wearbuddy.data.CloudSettings
import com.wear.wearbuddy.presentation.theme.WearbuddyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Browses readable local folders without requiring a system document provider. */
class FilePickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = CloudSettings(this)
        val imagesOnly = intent.type == "image/*"
        setContent {
            WearbuddyTheme(themeColor = settings.themeColor, fontScale = settings.fontScale) {
                AppScaffold {
                    LocalFilePicker(imagesOnly, settings.language == "zh", onCancel = { finish() }) { file ->
                        setResult(Activity.RESULT_OK, Intent().setData(androidx.core.content.FileProvider.getUriForFile(
                            this@FilePickerActivity, "$packageName.files", file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        finish()
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalFilePicker(imagesOnly: Boolean, zh: Boolean, onCancel: () -> Unit, onSelect: (File) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var folder by remember { mutableStateOf<File?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val state = rememberTransformingLazyColumnState()
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }
    val storageSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { revision++ }
    val roots = remember {
        listOfNotNull(File(context.filesDir, "artifacts").apply { mkdirs() },
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.apply { mkdirs() },
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStorageDirectory()).distinctBy { it.absolutePath }
    }
    val entries by produceState<List<File>?>(null, folder, revision) {
        value = null
        value = withContext(Dispatchers.IO) {
            val files = folder?.listFiles()?.toList()
            if (folder != null && files == null) error = if (zh) "无法读取此文件夹，请授权后重试" else "Grant access to read this folder"
            (files ?: if (folder == null) roots else emptyList()).filter {
                !imagesOnly || it.isDirectory || it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
            }.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }
    fun goBack() {
        val current = folder
        folder = if (current == null || current in roots) null else current.parentFile
        error = null
    }
    BackHandler { if (folder == null) onCancel() else goBack() }
    LaunchedEffect(folder) { state.scrollToItem(0) }
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(horizontal = 12.dp, vertical = 28.dp)) {
            item { ListHeader { Text(if (imagesOnly) { if (zh) "选择图片" else "Choose image" } else { if (zh) "选择文件" else "Choose file" }) } }
            folder?.let { dir ->
                item { Text(dir.absolutePath, style = MaterialTheme.typography.bodySmall) }
                item { FilledTonalButton(onClick = { goBack() }, modifier = Modifier.fillMaxWidth()) { Text(if (zh) "上一级" else "Up") } }
            }
            item {
                FilledTonalButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 30) {
                        runCatching {
                            storageSettings.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}")))
                        }.onFailure { error = if (zh) "此手表没有存储授权设置，可浏览应用文件夹" else "Storage settings unavailable; app folders remain accessible" }
                    } else permissions.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }, modifier = Modifier.fillMaxWidth()) { Text(if (zh) "授权浏览手表文件" else "Allow storage access") }
            }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (entries == null) item { CircularProgressIndicator() }
            else if (entries!!.isEmpty()) item { Text(if (zh) "此文件夹没有可选文件" else "No selectable files") }
            items(entries.orEmpty(), key = { it.absolutePath }) { file ->
                Button(onClick = {
                    error = null
                    if (file.isDirectory) folder = file
                    else if (file.canRead()) onSelect(file)
                    else error = if (zh) "文件不可读取" else "File is not readable"
                }, modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(if (file.isDirectory) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile, contentDescription = null) },
                    label = { Text(file.name.ifBlank { file.absolutePath }, maxLines = 2) })
            }
        }
    }
}

