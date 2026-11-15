package com.wear.wearbuddy.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wear.wearbuddy.BuildConfig
import com.wear.wearbuddy.data.AppRelease
import com.wear.wearbuddy.data.AppUpdates
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class UpdateState(val visible: Boolean = false, val checking: Boolean = false,
    val release: AppRelease? = null, val downloading: Boolean = false, val bytes: Long = 0,
    val file: File? = null, val error: String? = null) {
    val downloadFraction: Float get() = if (file != null) 1f else
        (bytes.toDouble() / (release?.size ?: 0).coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
}

class UpdateViewModel(app: Application): AndroidViewModel(app) {
    private val mutable = MutableStateFlow(UpdateState())
    internal val state = mutable.asStateFlow()
    private var job: Job? = null
    fun check(manual: Boolean = false) {
        if (job?.isActive == true || (!manual && mutable.value.visible)) return
        job = viewModelScope.launch {
            mutable.value = UpdateState(visible = manual, checking = true)
            try {
                val release = withContext(Dispatchers.IO) { AppUpdates.latest(BuildConfig.VERSION_NAME) }
                mutable.value = UpdateState(visible = manual || release != null, release = release)
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { mutable.value = UpdateState(visible = manual, error = e.message ?: "检测更新失败") }
        }
    }
    internal fun download() {
        val release = mutable.value.release ?: return
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            mutable.update { it.copy(downloading = true, error = null, bytes = 0, file = null) }
            try {
                val file = withContext(Dispatchers.IO) {
                    val context = currentCoroutineContext()
                    AppUpdates.download(release, File(getApplication<Application>().filesDir, "updates/wearbuddy-${release.version}.apk"),
                        { count -> mutable.update { it.copy(bytes = count) } }, { context.ensureActive() })
                }
                mutable.update { it.copy(downloading = false, file = file) }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { mutable.update { it.copy(downloading = false, error = e.message ?: "下载失败") } }
        }
    }
    internal fun dismiss() { job?.cancel(); mutable.update { it.copy(visible = false, downloading = false) } }
    internal fun installError(message: String) { mutable.update { it.copy(error = message) } }
}
