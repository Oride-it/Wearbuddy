package com.wear.wearbuddy.presentation.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.wear.compose.material3.*
import com.wear.wearbuddy.BuildConfig
import com.wear.wearbuddy.vm.UpdateViewModel
import java.io.File

@Composable
fun UpdateDialog(model: UpdateViewModel) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    var permissionReturn by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { permissionReturn++ }
    fun install(file: File) {
        try {
            val manager = context.packageManager
            if (!manager.canRequestPackageInstalls()) {
                model.installError("请先允许安装此来源的应用，再点对钩继续安装")
                permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                return
            }
            @Suppress("DEPRECATION")
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            @Suppress("DEPRECATION")
            val archive = manager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("无法解析安装包")
            @Suppress("DEPRECATION")
            val current = manager.getPackageInfo(context.packageName, flags)
            check(archive.packageName == context.packageName) { "更新包包名不匹配" }
            @Suppress("DEPRECATION")
            val version = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
            check(version > BuildConfig.VERSION_CODE) { "更新包版本不高于当前版本" }
            @Suppress("DEPRECATION")
            val expected = if (Build.VERSION.SDK_INT >= 28) current.signingInfo!!.apkContentsSigners else current.signatures
            @Suppress("DEPRECATION")
            val actual = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo!!.apkContentsSigners else archive.signatures
            check(expected!!.toSet() == actual!!.toSet()) { "更新包签名与当前安装版本不一致" }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            model.installError("已打开系统安装界面，请确认安装")
        } catch (e: Exception) {
            model.installError(e.message ?: "此手表无法打开安装器，安装包已保存在应用内")
        }
    }
    LaunchedEffect(state.file, permissionReturn) {
        if (permissionReturn == 0 || context.packageManager.canRequestPackageInstalls()) state.file?.let { install(it) }
    }
    UpdatePrompt(state, onConfirm = {
        when {
            state.checking || state.downloading -> Unit
            state.file != null -> install(state.file!!)
            state.release != null -> model.download()
            state.error != null -> model.check(true)
            else -> model.dismiss()
        }
    }, onDismiss = model::dismiss)
}

@Composable
internal fun UpdatePrompt(state: com.wear.wearbuddy.vm.UpdateState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(visible = state.visible, onDismissRequest = onDismiss,
        title = { Text(when { state.checking -> "检测更新"; state.release != null -> "新版本 ${state.release!!.version}"; state.error != null -> "检测更新"; else -> "当前版本 ${BuildConfig.VERSION_NAME}" }) },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(onClick = onConfirm)
        },
        dismissButton = { AlertDialogDefaults.DismissButton(onClick = onDismiss) },
        content = {
            item { Text(state.release?.notes ?: if (state.checking) "正在连接更新源…" else if (state.error == null) "已经是最新版本" else "请检查网络后重试") }
            if (state.downloading || state.checking || state.file != null) item {
                Column(Modifier.fillMaxWidth()) {
                    if (state.checking) CircularProgressIndicator()
                    else LinearProgressIndicator(progress = { if (state.file != null) 1f else state.bytes.toFloat() / (state.release?.size ?: 1) }, modifier = Modifier.fillMaxWidth())
                    if (state.downloading) Text("正在下载 ${(100L * state.bytes / (state.release?.size ?: 1)).coerceIn(0, 100)}%")
                }
            }
            if (state.file != null) item { Text("下载完成") }
            state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        })
}
