package com.wear.wearbuddy.presentation

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.wear.compose.foundation.lazy.*
import androidx.wear.compose.material3.*
import androidx.wear.compose.material3.lazy.ResponsiveTransformationSpec
import androidx.wear.compose.material3.lazy.TransformationVariableSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.wear.wearbuddy.data.CloudSettings
import com.wear.wearbuddy.presentation.theme.WearbuddyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Browses the watch's primary shared storage even without a system document provider. */
class FilePickerActivity : ComponentActivity() {
    private var revision by mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        revision++ // Also picks up grants made with AppOps while the picker was in the background.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = CloudSettings(this)
        val imagesOnly = intent.type == "image/*"
        setContent {
            WearbuddyTheme(themeColor = settings.themeColor, fontScale = settings.fontScale) {
                AppScaffold {
                    LocalFilePicker(imagesOnly, settings.language == "zh", revision, onCancel = ::finish) { uri ->
                        setResult(Activity.RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        finish()
                    }
                }
            }
        }
    }
}

private fun hasStorageAccess(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 30) {
    // This is Android's effective MANAGE_EXTERNAL_STORAGE AppOp, including shell-granted access.
    Environment.isExternalStorageManager()
} else {
    context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
}

private data class PickerEntry(val file: File, val label: String, val directory: Boolean)
private data class FolderListing(val entries: List<PickerEntry>, val unreadable: Boolean)

private val pickerRowTransformation = ResponsiveTransformationSpec.smallScreen(
    minTransitionAreaHeightFraction = 0.50f,
    maxTransitionAreaHeightFraction = 0.58f,
    containerAlpha = TransformationVariableSpec(0.85f),
    contentAlpha = TransformationVariableSpec(0.9f),
    scale = TransformationVariableSpec(0.56f)
)

@Suppress("DEPRECATION")
private fun listFolder(context: Context, folder: File?, imagesOnly: Boolean, zh: Boolean): FolderListing {
    val shared = Environment.getExternalStorageDirectory()
    val entries = mutableListOf<PickerEntry>()
    if (folder == null) {
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.takeIf { it.isDirectory }?.let {
            entries += PickerEntry(it, if (zh) "应用下载" else "App downloads", true)
        }
    }
    val children = try { (folder ?: shared).listFiles() } catch (_: SecurityException) { null }
    // Some scoped-storage implementations allow access to known public locations but not root listing.
    val files = children?.asList() ?: if (folder == null) {
        listOf(Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DOCUMENTS,
            Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_DCIM)
            .map { File(shared, it) }.filter { it.isDirectory }
    } else emptyList()
    entries += files.mapNotNull { file ->
        val directory = file.isDirectory
        if (directory || !imagesOnly || MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase())?.startsWith("image/") == true) {
            PickerEntry(file, file.name, directory)
        } else null
    }.sortedWith(compareBy<PickerEntry> { !it.directory }.thenBy { it.label.lowercase() }.thenBy { it.label })
    if (folder == null) {
        val artifacts = File(context.filesDir, "artifacts")
        if (artifacts.isDirectory || artifacts.mkdirs()) {
            entries += PickerEntry(artifacts, if (zh) "应用内产物" else "App artifacts", true)
        }
    }
    return FolderListing(entries, unreadable = children == null)
}

@Composable
private fun LocalFilePicker(imagesOnly: Boolean, zh: Boolean, revision: Int, onCancel: () -> Unit, onSelect: (Uri) -> Unit) {
    val context = LocalContext.current
    var path by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val folder = path.lastOrNull()?.let(::File)
    var reload by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var settingsUnavailable by remember(revision, reload) { mutableStateOf(false) }
    val granted = remember(revision, reload) { hasStorageAccess(context) }
    val state = key(folder) { rememberTransformingLazyColumnState() }
    val transformation = rememberTransformationSpec(pickerRowTransformation)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { reload++ }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { reload++ }
    val listing by key(folder, revision, reload) {
        produceState<FolderListing?>(null) {
            value = withContext(Dispatchers.IO) { listFolder(context, folder, imagesOnly, zh) }
        }
    }
    fun goBack() {
        if (path.isEmpty()) onCancel() else path = ArrayList(path.dropLast(1))
        error = null
    }
    fun requestAccess() {
        error = null
        if (hasStorageAccess(context)) { reload++; return }
        if (Build.VERSION.SDK_INT < 30) {
            permission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            return
        }
        val intents = listOf(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", context.packageName, null)), Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        for (intent in intents) {
            try {
                settings.launch(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // Some watches only implement the global all-files screen.
            } catch (_: SecurityException) {
                // The vendor may not export its app-specific screen.
            }
        }
        settingsUnavailable = true
    }
    BackHandler { goBack() }
    ScreenScaffold(scrollState = state, contentPadding = PaddingValues(0.dp)) {
        TransformingLazyColumn(state = state, modifier = Modifier.fillMaxSize()
            .testTag(if (listing == null) "picker-loading" else "picker:${folder?.absolutePath.orEmpty()}"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 26.dp, bottom = 36.dp)) {
            item { ListHeader { Text(if (imagesOnly) { if (zh) "选择图片" else "Choose image" } else { if (zh) "选择文件" else "Choose file" }) } }
            if (folder != null) {
                item { Text(folder.absolutePath, style = MaterialTheme.typography.bodySmall) }
                item {
                    FilledTonalButton(
                        onClick = ::goBack,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformation),
                        transformation = SurfaceTransformation(transformation)
                    ) { Text(if (zh) "上一级" else "Up") }
                }
            }
            if (!granted) {
                item {
                    FilledTonalButton(
                        onClick = ::requestAccess,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformation),
                        transformation = SurfaceTransformation(transformation)
                    ) { Text(if (zh) "授权浏览手表文件" else "Allow storage access") }
                }
                if (settingsUnavailable) item { Text(if (zh) "手表未提供授权页面，仍可浏览可访问的文件夹。" else
                    "This watch has no storage permission screen. Accessible folders can still be opened.") }
            }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            val current = listing
            when {
                current == null -> item { CircularProgressIndicator() }
                current.unreadable -> item { Text(
                    if (zh) "无法列出此目录，可能受系统限制。" else "This directory cannot be listed. Access may be restricted.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                current.entries.isEmpty() -> item { Text(if (zh) "此文件夹没有可选文件" else "No selectable files") }
            }
            items(current?.entries.orEmpty(), key = { it.file.absolutePath }) { entry ->
                Button(onClick = {
                    error = null
                    if (entry.directory) path = ArrayList(path + entry.file.absolutePath)
                    else {
                        val uri = if (entry.file.isFile && entry.file.canRead()) {
                            runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", entry.file) }.getOrNull()
                        } else null
                        if (uri != null) onSelect(uri)
                        else error = if (zh) "文件不可读取" else "File is not readable"
                    }
                }, modifier = Modifier.fillMaxWidth().transformedHeight(this, transformation),
                    transformation = SurfaceTransformation(transformation),
                    icon = { Icon(if (entry.directory) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile, contentDescription = null) },
                    label = { Text(entry.label, maxLines = 2) })
            }
        }
    }
}
