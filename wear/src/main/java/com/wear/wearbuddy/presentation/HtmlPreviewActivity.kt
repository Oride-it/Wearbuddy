package com.wear.wearbuddy.presentation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.wear.compose.material3.*
import com.wear.wearbuddy.data.Artifact
import com.wear.wearbuddy.data.CloudSettings
import com.wear.wearbuddy.presentation.theme.WearbuddyTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun Artifact.isHtml(): Boolean = mimeType?.substringBefore(';') == "text/html" ||
    name.substringAfterLast('.', "").lowercase() in setOf("html", "htm")

internal fun openHtmlPreview(context: Context, artifact: Artifact, path: String) {
    val remote = sequenceOf(artifact.downloadUrl, artifact.uri).filterNotNull()
        .firstOrNull { it.startsWith("https://") }
    if (runCatching { WebView.getCurrentWebViewPackage() }.getOrNull() == null) {
        openHtmlInBrowser(context, path, remote)
    } else {
        context.startActivity(Intent(context, HtmlPreviewActivity::class.java)
            .addFlags(if (context is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("path", path).putExtra("title", artifact.name).putExtra("remote", remote))
    }
}

internal fun openHtmlInBrowser(context: Context, path: String, remote: String?) {
    val uri = remote?.let(Uri::parse) ?: FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))
    val intent = Intent(Intent.ACTION_VIEW).apply {
        if (remote == null) setDataAndType(uri, "text/html") else data = uri
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try { context.startActivity(intent) }
    catch (_: android.content.ActivityNotFoundException) {
        val zh = CloudSettings(context).language == "zh"
        Toast.makeText(context, if (zh) "未找到可打开 HTML 的浏览器，文件已保留" else
            "No browser can open this HTML file. The file has been saved.", Toast.LENGTH_LONG).show()
    }
}

/** Uses an installed WebView provider; Wear devices without one use their browser. */
class HtmlPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path") ?: return finish()
        val file = File(path).canonicalFile
        if (!file.toPath().startsWith(File(filesDir, "artifacts").canonicalFile.toPath()) || !file.isFile) return finish()
        val settings = CloudSettings(this)
        val webView = runCatching {
            WebView(this).apply {
                @Suppress("SetJavaScriptEnabled")
                this.settings.javaScriptEnabled = true
                this.settings.allowFileAccess = false
                this.settings.allowContentAccess = false
                this.settings.domStorageEnabled = true
                this.settings.setSupportZoom(true)
                this.settings.builtInZoomControls = true
                this.settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.scheme !in setOf("https", "http", "about")
                }
            }
        }.getOrElse {
            openHtmlInBrowser(this, path, intent.getStringExtra("remote"))
            finish()
            return
        }
        setContent {
            WearbuddyTheme(themeColor = settings.themeColor, fontScale = settings.fontScale) {
                var html by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(path) {
                    html = withContext(Dispatchers.IO) { file.readText() }
                    webView.loadDataWithBaseURL("https://preview.wearbuddy.invalid/", html!!, "text/html", "UTF-8", null)
                }
                DisposableEffect(webView) { onDispose { webView.destroy() } }
                Column(Modifier.fillMaxSize().padding(top = 22.dp, bottom = 12.dp)) {
                    Text(intent.getStringExtra("title").orEmpty(), maxLines = 1,
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 32.dp))
                    if (html == null) CircularProgressIndicator()
                    AndroidView(factory = { webView }, modifier = Modifier.weight(1f).fillMaxWidth())
                    TextButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (settings.language == "zh") "返回" else "Back")
                    }
                }
            }
        }
    }
}
