package com.wear.wearbuddy.vm

import android.app.Application
import android.content.Context
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wear.wearbuddy.data.ChatMessage
import com.wear.wearbuddy.data.ChatRole
import com.wear.wearbuddy.data.Artifact
import com.wear.wearbuddy.data.CheckinInfo
import com.wear.wearbuddy.data.CloudSettings
import com.wear.wearbuddy.data.CloudCommand
import com.wear.wearbuddy.data.CloudDestination
import com.wear.wearbuddy.data.CloudEntry
import com.wear.wearbuddy.data.CloudTool
import com.wear.wearbuddy.data.GrowthTask
import com.wear.wearbuddy.data.loadCloudPage
import com.wear.wearbuddy.data.runCloudCommand
import com.wear.wearbuddy.data.CloudTaskRepository
import com.wear.wearbuddy.data.ExecEnv
import com.wear.wearbuddy.data.NativeLogin
import com.wear.wearbuddy.data.OfflineTaskRepository
import com.wear.wearbuddy.data.Project
import com.wear.wearbuddy.data.ProjectStore
import com.wear.wearbuddy.data.RemoteAgent
import com.wear.wearbuddy.data.RemoteModel
import com.wear.wearbuddy.data.SessionExpiredException
import com.wear.wearbuddy.data.Task
import com.wear.wearbuddy.data.TaskRepository
import com.wear.wearbuddy.data.TaskStatus
import com.wear.wearbuddy.data.UsageSnapshot
import com.wear.wearbuddy.data.WorkBuddyCloudClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.Locale
import java.io.File
import java.security.MessageDigest

fun artifactPreviewKey(taskId: String?, uri: String) = "${taskId.orEmpty()}|$uri"

data class ArtifactPreview(val loading: Boolean = true, val text: String? = null,
    val localPath: String? = null, val error: String? = null, val publicUri: String? = null)

/** 输入页里待发送的附件（图片 / 文件），发送时才读取字节并上传云端。 */
data class PendingAttachment(val name: String, val uri: String, val mimeType: String)

/** 覆盖在三分页之上的子页面（进入新页面，不参与左右翻页）。 */
sealed interface Overlay {
    data object NewChat : Overlay
    data class TaskDetail(val id: String) : Overlay
    data class Compose(val taskId: String?) : Overlay
    data object AddProject : Overlay
    data object ModelPicker : Overlay
    data object Login : Overlay
    data object Accounts : Overlay
    data object About : Overlay
    data object Storage : Overlay
    data object Artifacts : Overlay
    data object Sessions : Overlay
    data object Agents : Overlay
    data object Checkin : Overlay
    data object GrowthPlan : Overlay
    data object CloudTools : Overlay
}

internal data class CloudModule(
    val destination: CloudDestination,
    val returnTo: Overlay,
    val entry: CloudEntry? = null
) : Overlay

/** 原生登录（手机号 + 短信验证码）表单状态。 */
data class LoginForm(
    val phone: String = "",
    val code: String = "",
    val sending: Boolean = false,
    val countdown: Int = 0,
    val submitting: Boolean = false,
    val error: String? = null
)

data class UiState(
    val overlay: Overlay? = null,
    val tasks: List<Task> = emptyList(),
    val hasDraftChat: Boolean = false,
    val connected: Boolean = false,
    val cloudError: String? = null,
    val loggedIn: Boolean = false,
    val nickname: String = "",
    val avatarUrl: String = "",
    val phone: String = "",
    /** 任务页输入框草稿。 */
    val draft: String = "",
    /** 任务详情页追问草稿。 */
    val replyDraft: String = "",
    val busy: Boolean = false,
    val toast: String? = null,
    // 积分
    val usage: UsageSnapshot? = null,
    val usageLoading: Boolean = false,
    // 模型
    val models: List<RemoteModel> = emptyList(),
    val modelsLoading: Boolean = false,
    val modelsError: String? = null,
    val selectedModel: String = "",
    val cloudModel: String? = null,
    /** 模型选择页的来处：任务 ID 表示从任务详情打开，选择后回到详情。 */
    val modelPickerReturnId: String? = null,
    // 会话详情
    val history: List<ChatMessage> = emptyList(),
    val historyLoading: Boolean = false,
    val historySyncFailed: Boolean = false,
    val historyBusy: Boolean = false,
    val artifactPreviews: Map<String, ArtifactPreview> = emptyMap(),
    // 项目
    val projects: List<Project> = emptyList(),
    // Agent 总览 / 每日签到
    val agents: List<RemoteAgent> = emptyList(),
    val agentsLoading: Boolean = false,
    val agentsError: String? = null,
    val checkin: CheckinInfo? = null,
    val checkinLoading: Boolean = false,
    val checkinError: String? = null,
    // 偏好设置
    val themeColor: String = CloudSettings.THEME_BLUE,
    val language: String = CloudSettings.LANG_ZH,
    val fontScale: String = CloudSettings.FONT_STANDARD,
    val ttsEnabled: Boolean = false,
    val hapticsEnabled: Boolean = true,
    // 登录表单
    val login: LoginForm = LoginForm()
) {
    val activeTaskId: String? get() = (overlay as? Overlay.TaskDetail)?.id
    val activeTask: Task? get() = tasks.firstOrNull { it.id == activeTaskId }
    val modelLabel: String
        get() = selectedModel.takeIf { it.isNotBlank() }
            ?.let { id -> models.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() } ?: id }
            ?: WorkBuddyCloudClient.DEFAULT_MODEL
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private var settings = CloudSettings(app)
    private val accountStore = com.wear.wearbuddy.data.AccountStore(app)
    private var loginSettings: CloudSettings? = null
    private val projectStore = ProjectStore(app)

    private val _ui = MutableStateFlow(
        UiState(
            loggedIn = settings.isLoggedIn,
            nickname = settings.nickname,
            phone = settings.accountHint,
            selectedModel = settings.selectedModel,
            themeColor = settings.themeColor,
            language = settings.language,
            fontScale = settings.fontScale,
            ttsEnabled = settings.ttsEnabled,
            hapticsEnabled = settings.hapticsEnabled,
            projects = projectStore.load()
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var repo: TaskRepository = buildRepo()

    private var foregroundNow = true
    private var prevStatus: Map<String, TaskStatus> = emptyMap()
    private var toastJob: Job? = null
    private var countdownJob: Job? = null
    private var historyJob: Job? = null
    private var usageJob: Job? = null
    private var profileJob: Job? = null
    private var repoJobs: MutableList<Job> = mutableListOf()

    /** 本次登录流程（发码 → 提交）必须共用同一个 Keycloak 会话。 */
    private var loginFlow: NativeLogin? = null

    private val speech = SpeechManager(app)

    init {
        bindRepo()
        // 已登录时启动即刷新积分（版本 / 积分卡片）
        if (settings.isLoggedIn) loadUsage()
    }

    override fun onCleared() {
        speech.shutdown()
        (repo as? CloudTaskRepository)?.close()
        super.onCleared()
    }

    private fun buildRepo(): TaskRepository =
        if (settings.isLoggedIn) CloudTaskRepository(settings) else OfflineTaskRepository()

    private fun bindRepo() {
        profileJob?.cancel()
        repoJobs.forEach { it.cancel() }
        repoJobs = mutableListOf()
        _ui.update {
            it.copy(
                loggedIn = settings.isLoggedIn,
                nickname = settings.nickname,
                phone = settings.accountHint,
                avatarUrl = "",
                connected = repo.connected.value,
                cloudError = repo.cloudError.value,
                hasDraftChat = settings.isLoggedIn && settings.hasDraftChat,
                draft = "",
                replyDraft = "",
                tasks = repo.tasks.value
            )
        }
        if (settings.isLoggedIn) {
            val account = settings.accountHint
            profileJob = viewModelScope.launch {
                val profile = withContext(Dispatchers.IO) {
                    runCatching { WorkBuddyCloudClient(settings).fetchAccountProfile() }.getOrNull()
                }
                if (profile != null && settings.isLoggedIn && settings.accountHint == account) {
                    if (profile.nickname.isNotBlank()) settings.nickname = profile.nickname
                    _ui.update { it.copy(nickname = settings.nickname, avatarUrl = profile.avatarUrl) }
                }
            }
        }
        repoJobs += viewModelScope.launch { repo.connected.collect { c -> _ui.update { it.copy(connected = c) } } }
        repoJobs += viewModelScope.launch { repo.cloudError.collect { e -> _ui.update { it.copy(cloudError = e) } } }
        repoJobs += viewModelScope.launch {
            repo.sessionExpired.collect { expired -> if (expired) onSessionExpired() }
        }
        repoJobs += viewModelScope.launch {
            repo.modelError.collect { msg ->
                if (!msg.isNullOrBlank()) {
                    showToast(msg)
                    repo.clearModelError()
                }
            }
        }
        repoJobs += viewModelScope.launch { repo.tasks.collect { list -> handleTasks(list) } }
    }

    private fun handleTasks(list: List<Task>) {
        val prev = prevStatus
        list.forEach { t ->
            val before = prev[t.id]
            if (before != null && before != t.status && t.id == _ui.value.activeTaskId) {
                if (t.status == TaskStatus.DONE && _ui.value.ttsEnabled) {
                    speakTaskResult(t)
                }
            }
        }
        _ui.update { state ->
            val active = list.firstOrNull { it.id == state.activeTaskId }
            val messages = active?.messages.orEmpty()
            state.copy(tasks = list,
                history = messages.takeIf { it.isNotEmpty() } ?: state.history,
                historyLoading = state.historyLoading && messages.isEmpty(),
                historyBusy = state.historyBusy && active?.status == TaskStatus.WORKING)
        }
        prevStatus = list.associate { it.id to it.status }
    }

    // ---------- 任务 ----------

    fun openNewChat() {
        if (!settings.isLoggedIn) { openLogin(); return }
        settings.hasDraftChat = true
        _ui.update { it.copy(overlay = Overlay.NewChat, hasDraftChat = true) }
    }

    fun setDraft(text: String) = _ui.update { it.copy(draft = text) }

    fun setReplyDraft(text: String) = _ui.update { it.copy(replyDraft = text) }

    /** 任务页发送：创建会话并进入详情页（自动进入「思考中」状态）。 */
    fun sendTask(text: String = _ui.value.draft) {
        val prompt = text.trim()
        if (prompt.isBlank() || _ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        val fromDraft = _ui.value.overlay == Overlay.NewChat
        viewModelScope.launch {
            runCatching { repo.dispatch(prompt, ExecEnv.CLOUD) }
                .onSuccess { id ->
                    if (fromDraft) settings.hasDraftChat = false
                    _ui.update {
                        it.copy(
                            busy = false,
                            draft = "",
                            hasDraftChat = settings.hasDraftChat,
                            historySyncFailed = false,
                            history = listOf(ChatMessage(ChatRole.USER, prompt)),
                            historyLoading = false,
                            historyBusy = true,
                            overlay = Overlay.TaskDetail(id)
                        )
                    }
                    startHistoryWatcher(id)
                }
                .onFailure { e ->
                    _ui.update { it.copy(busy = false, toast = e.message ?: "下发失败") }
                    clearToastLater()
                }
        }
    }

    fun openTask(id: String) {
        _ui.update {
            it.copy(
                overlay = Overlay.TaskDetail(id),
                history = it.tasks.firstOrNull { task -> task.id == id }?.messages.orEmpty(),
                historyLoading = true,
                historySyncFailed = false,
                historyBusy = false,
                replyDraft = "",
                cloudModel = null
            )
        }
        repo.markRead(id)
        startHistoryWatcher(id)
        refreshCloudModel(id)
    }

    /** 详情页追问。 */
    fun sendReply(text: String = _ui.value.replyDraft) {
        val id = _ui.value.activeTaskId ?: return
        val prompt = text.trim()
        if (prompt.isBlank()) return
        _ui.update {
            it.copy(
                replyDraft = "",
                history = it.history + ChatMessage(ChatRole.USER, prompt),
                historyBusy = true
            )
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { repo.reply(id, prompt) } }
        }
    }

    fun stopActiveTask() {
        val id = _ui.value.activeTaskId ?: return
        repo.stop(id)
        _ui.update { it.copy(toast = "已中断任务") }
        clearToastLater()
    }

    /** 打开「发消息」输入页：taskId=null 表示新建任务，否则是详情页追问。 */
    fun openCompose(taskId: String? = null) {
        _ui.update { it.copy(overlay = Overlay.Compose(taskId)) }
    }

    /** 输入页发送：先上传附件，再新建 / 追问，文字与附件一并送出。 */
    fun submitCompose(taskId: String?, text: String, attachments: List<PendingAttachment>) {
        val prompt = text.trim()
        if ((prompt.isBlank() && attachments.isEmpty()) || _ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val uploaded = runCatching {
                val resolver = getApplication<Application>().contentResolver
                attachments.map { att ->
                    val bytes = withContext(Dispatchers.IO) {
                        resolver.openInputStream(Uri.parse(att.uri))?.use { it.readBytes() }
                            ?: throw IllegalStateException("无法读取 ${att.name}")
                    }
                    repo.uploadAttachment(att.name, att.mimeType, bytes)
                }
            }
            uploaded
                .onSuccess { artifacts ->
                    val userText = prompt.ifBlank { "[附件]" }
                    if (taskId == null) {
                        val dispatchPrompt = prompt.ifBlank { artifacts.firstOrNull()?.name ?: "新任务" }
                        runCatching { repo.dispatch(dispatchPrompt, ExecEnv.CLOUD, artifacts) }
                            .onSuccess { id ->
                                settings.hasDraftChat = false
                                _ui.update {
                                    it.copy(
                                        busy = false,
                                        draft = "",
                                        hasDraftChat = false,
                                        historySyncFailed = false,
                                        history = listOf(ChatMessage(ChatRole.USER, userText)),
                                        historyLoading = false,
                                        historyBusy = true,
                                        overlay = Overlay.TaskDetail(id)
                                    )
                                }
                                startHistoryWatcher(id)
                            }
                            .onFailure { e ->
                                _ui.update { it.copy(busy = false, toast = e.message ?: "下发失败") }
                                clearToastLater()
                            }
                    } else {
                        _ui.update {
                            it.copy(
                                busy = false,
                                overlay = Overlay.TaskDetail(taskId),
                                replyDraft = "",
                                history = it.history + ChatMessage(ChatRole.USER, userText),
                                historyBusy = true
                            )
                        }
                        startHistoryWatcher(taskId)
                        viewModelScope.launch {
                            withContext(Dispatchers.IO) { runCatching { repo.reply(taskId, prompt, artifacts) } }
                        }
                    }
                }
                .onFailure { e ->
                    _ui.update { it.copy(busy = false, toast = e.message ?: "附件上传失败") }
                    clearToastLater()
                }
        }
    }

    fun retryActiveTask() {
        val id = _ui.value.activeTaskId ?: return
        viewModelScope.launch { runCatching { repo.retry(id) } }
    }

    fun loadArtifactPreview(artifact: Artifact, taskId: String? = _ui.value.activeTaskId) {
        val key = artifactPreviewKey(taskId, artifact.uri)
        val sourceRepo = repo
        val profileId = settings.profileId
        val previous = _ui.value.artifactPreviews[key]
        if (previous?.loading == true) return
        _ui.update { it.copy(artifactPreviews = it.artifactPreviews + (key to ArtifactPreview())) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val context = getApplication<Application>()
                // The public download may have been deleted while the in-memory preview stayed open.
                if (previous?.localPath != null && java.io.File(previous.localPath).isFile && previous.publicUri != null &&
                    runCatching { context.contentResolver.openFileDescriptor(Uri.parse(previous.publicUri), "r")
                        ?.use { true } == true }.getOrDefault(false)) return@runCatching previous
                val bytes = com.wear.wearbuddy.data.ArtifactDownloads.cachedBytes(context, profileId, key, artifact.name)
                    ?: sourceRepo.downloadArtifact(artifact, taskId)
                val saved = com.wear.wearbuddy.data.ArtifactDownloads.save(context, profileId, key, artifact.name, artifact.mimeType, bytes)
                val file = saved.previewFile
                val name = artifact.name
                if (taskId != null) sourceRepo.markDownloaded(taskId, artifact.uri, file.absolutePath)
                val extension = name.substringAfterLast('.', "").lowercase()
                val text = if (artifact.mimeType?.startsWith("text/") == true || extension in setOf(
                    "txt", "md", "csv", "tsv", "json", "xml", "html", "htm", "log", "yaml", "yml", "kt", "py", "js", "css"
                )) bytes.toString(Charsets.UTF_8) else null
                ArtifactPreview(loading = false, text = text, localPath = file.absolutePath, publicUri = saved.publicUri.toString())
            } }
            val preview = result.getOrElse { ArtifactPreview(loading = false, error = it.message ?: "文件加载失败") }
            if (settings.profileId != profileId) return@launch
            _ui.update { it.copy(artifactPreviews = it.artifactPreviews + (key to preview)) }
        }
    }

    /** 回答云端抛出的 askuser 问题。 */
    fun answerAsk(answer: String) {
        val id = _ui.value.activeTaskId ?: return
        if (answer.isBlank()) return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { repo.answerAsk(id, answer) }
                .onSuccess { _ui.update { it.copy(busy = false) } }
                .onFailure { e ->
                    _ui.update { it.copy(busy = false, toast = e.message ?: "应答失败") }
                    clearToastLater()
                }
        }
    }

    /** 详情页复用实时连接，定期检查并恢复已断开的连接。 */
    private fun startHistoryWatcher(id: String) {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            var retryDelay = HISTORY_REFRESH_INTERVAL
            while (isActive && _ui.value.activeTaskId == id) {
                if (foregroundNow) {
                    val synced = refreshHistoryOnce(id)
                    retryDelay = if (synced) HISTORY_REFRESH_INTERVAL else (retryDelay * 2).coerceAtMost(30_000L)
                }
                delay(retryDelay)
            }
        }
    }

    fun retryHistory() {
        _ui.value.activeTaskId?.let { startHistoryWatcher(it) }
    }

    private suspend fun refreshHistoryOnce(id: String): Boolean {
        if (!settings.isLoggedIn) return false
        val source = repo
        val result = withContext(Dispatchers.IO) { runCatching { source.loadHistory(id) } }
        _ui.update {
            if (source !== repo || it.activeTaskId != id) return@update it
            val current = it.activeTask
            it.copy(
                history = current?.messages?.takeIf { rows -> rows.isNotEmpty() }
                    ?: result.getOrNull()?.takeIf { rows -> rows.isNotEmpty() } ?: it.history,
                historyLoading = false,
                historySyncFailed = result.isFailure,
                historyBusy = it.historyBusy && current?.status == TaskStatus.WORKING
            )
        }
        return result.isSuccess
    }

    private fun refreshCloudModel(id: String) {
        viewModelScope.launch {
            val model = withContext(Dispatchers.IO) {
                runCatching { repo.remoteModel(id) }.getOrNull()
            }
            _ui.update { it.copy(cloudModel = model) }
        }
    }

    // ---------- 模型 ----------

    /** 打开模型选择：记住来处，选择后回到原来的页面（任务页或任务详情）。 */
    fun openModelPicker() {
        val fromDetail = _ui.value.activeTaskId
        _ui.update {
            it.copy(
                overlay = Overlay.ModelPicker,
                modelsLoading = true,
                modelsError = null,
                modelPickerReturnId = fromDetail
            )
        }
        loadModels()
    }

    fun loadModels() {
        if (!settings.isLoggedIn) {
            _ui.update { it.copy(modelsLoading = false, modelsError = "请先登录") }
            return
        }
        _ui.update { it.copy(modelsLoading = true, modelsError = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repo.listModels() } }
            result
                .onSuccess { list ->
                    if (list.isNotEmpty() && list.none { it.id == settings.selectedModel }) {
                        settings.selectedModel = list
                            .firstOrNull { it.id == WorkBuddyCloudClient.DEFAULT_MODEL }?.id
                            ?: list.first().id
                    }
                    _ui.update {
                        it.copy(
                            models = list,
                            modelsLoading = false,
                            selectedModel = settings.selectedModel,
                            modelsError = if (list.isEmpty()) "云端没有返回可选模型" else null
                        )
                    }
                }
                .onFailure { e ->
                    _ui.update { it.copy(modelsLoading = false, modelsError = e.message ?: "模型列表获取失败") }
                }
        }
    }

    fun chooseModel(id: String) {
        settings.selectedModel = id
        val returnId = _ui.value.modelPickerReturnId
        _ui.update {
            it.copy(
                selectedModel = id,
                overlay = returnId?.let { overlayId -> Overlay.TaskDetail(overlayId) } ?: Overlay.NewChat,
                modelPickerReturnId = null
            )
        }
        if (returnId != null) {
            repo.applyModelNow(returnId)
            refreshCloudModel(returnId)
            startHistoryWatcher(returnId)
        }
        showToast("已切换模型")
    }

    // ---------- 项目 ----------

    fun openAddProject() = _ui.update { it.copy(overlay = Overlay.AddProject) }

    fun addProject(name: String, command: String) {
        if (name.isBlank() || command.isBlank()) return
        val project = projectStore.add(name, command)
        _ui.update { it.copy(projects = projectStore.load(), overlay = null, toast = "已创建「${project.name}」") }
        clearToastLater()
    }

    fun deleteProject(id: String) {
        projectStore.remove(id)
        _ui.update { it.copy(projects = projectStore.load(), toast = "已删除项目") }
        clearToastLater()
    }

    fun runProject(id: String) {
        val project = _ui.value.projects.firstOrNull { it.id == id } ?: return
        sendTask(project.command)
    }

    // ---------- 积分 ----------

    fun loadUsage() {
        if (!settings.isLoggedIn) return
        if (_ui.value.usageLoading) return
        _ui.update { it.copy(usageLoading = true) }
        usageJob?.cancel()
        usageJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { WorkBuddyCloudClient(settings).fetchUsage() }
            }
            result
                .onSuccess { snap -> _ui.update { it.copy(usage = snap, usageLoading = false) } }
                .onFailure { e ->
                    if (e is SessionExpiredException) onSessionExpired()
                    _ui.update { it.copy(usageLoading = false, toast = e.message?.take(60) ?: "积分加载失败") }
                    clearToastLater()
                }
        }
    }

    // ---------- 登录 ----------

    fun savedAccounts() = accountStore.accounts()

    fun openAccounts() { _ui.update { it.copy(overlay = Overlay.Accounts) } }

    fun addAccount() {
        openLogin()
        loginSettings = accountStore.create()
        _ui.update { it.copy(login = LoginForm()) }
    }

    fun switchAccount(id: String) {
        val next = accountStore.accounts().firstOrNull { it.profileId == id } ?: return
        if (next.profileId == settings.profileId) {
            if (!settings.isLoggedIn) openLogin()
            return
        }
        viewModelScope.coroutineContext.cancelChildren()
        (repo as? CloudTaskRepository)?.close()
        historyJob?.cancel()
        usageJob?.cancel()
        profileJob?.cancel()
        countdownJob?.cancel()
        loginFlow = null
        loginSettings = null
        settings = next
        accountStore.activate(next)
        prevStatus = emptyMap()
        _ui.update { UiState(overlay = Overlay.Accounts, projects = projectStore.load(),
            selectedModel = settings.selectedModel, themeColor = settings.themeColor,
            language = settings.language, fontScale = settings.fontScale,
            ttsEnabled = settings.ttsEnabled, hapticsEnabled = settings.hapticsEnabled) }
        repo = buildRepo()
        bindRepo()
        if (settings.isLoggedIn) loadUsage() else openLogin()
    }

    fun openLogin() {
        loginSettings = null
        loginFlow = null
        countdownJob?.cancel()
        _ui.update {
            it.copy(
                overlay = Overlay.Login,
                login = LoginForm(phone = if (it.loggedIn) it.phone else "")
            )
        }
    }

    fun setLoginPhone(value: String) =
        _ui.update { it.copy(login = it.login.copy(phone = value.filter { c -> c.isDigit() }.take(11), error = null)) }

    fun setLoginCode(value: String) =
        _ui.update { it.copy(login = it.login.copy(code = value.filter { c -> c.isDigit() }.take(6), error = null)) }

    fun cancelLogin() {
        countdownJob?.cancel()
        loginFlow = null
        _ui.update { it.copy(overlay = null, login = LoginForm()) }
    }

    /** 第一步：请求短信验证码，成功后开始倒计时。 */
    fun sendLoginCode() {
        val form = _ui.value.login
        if (form.sending || form.countdown > 0) return
        if (form.phone.length != 11) {
            _ui.update { it.copy(login = form.copy(error = "请输入 11 位手机号")) }
            return
        }
        val flow = NativeLogin(loginSettings ?: settings).also { loginFlow = it }
        _ui.update { it.copy(login = form.copy(sending = true, error = null)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { flow.sendCode(form.phone) } }
            android.util.Log.w(
                "WB-LOGIN",
                "sendCode phone=***" + form.phone.takeLast(4) +
                    " ok=" + result.isSuccess + " err=" + (result.exceptionOrNull()?.let { it.javaClass.simpleName + ": " + it.message } ?: "-")
            )
            result
                .onSuccess { seconds ->
                    if (seconds == NativeLogin.ALREADY_SIGNED_IN) {
                        countdownJob?.cancel()
                        loginFlow = null
                        _ui.update { it.copy(login = LoginForm()) }
                        applySignedIn(form.phone, "已用现有会话登录成功")
                    } else {
                        _ui.update { it.copy(login = it.login.copy(sending = false, countdown = seconds.coerceIn(1, 120))) }
                        startCountdown()
                    }
                }
                .onFailure { e ->
                    _ui.update {
                        it.copy(login = it.login.copy(sending = false, countdown = 60, error = e.message ?: "验证码发送失败"))
                    }
                    startCountdown()
                }
        }
    }

    /** 第二步：提交验证码换取会话。 */
    fun submitLogin() {
        val form = _ui.value.login
        if (form.submitting) return
        if (form.code.length < 4) {
            _ui.update { it.copy(login = form.copy(error = "请输入短信验证码")) }
            return
        }
        val flow = loginFlow
        if (flow == null) {
            _ui.update { it.copy(login = form.copy(error = "请先获取验证码")) }
            return
        }
        _ui.update { it.copy(login = form.copy(submitting = true, error = null)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { flow.submit(form.phone, form.code) } }
            result
                .onSuccess { account ->
                    countdownJob?.cancel()
                    loginFlow = null
                    _ui.update { it.copy(login = LoginForm()) }
                    applySignedIn(account)
                }
                .onFailure { e ->
                    _ui.update { it.copy(login = it.login.copy(submitting = false, error = e.message ?: "登录失败，请重试")) }
                }
        }
    }

    fun logout() {
        viewModelScope.coroutineContext.cancelChildren()
        loginSettings = null
        loginFlow = null
        countdownJob?.cancel()
        settings.signOut()
        (repo as? CloudTaskRepository)?.close()
        repo = buildRepo()
        bindRepo()
        _ui.update {
            it.copy(
                overlay = null,
                login = LoginForm(),
                usage = null,
                projects = projectStore.load(),
                artifactPreviews = emptyMap(),
                history = emptyList(),
                toast = "已退出登录"
            )
        }
        clearToastLater()
    }

    private fun onSessionExpired() {
        settings.markSessionExpired()
        (repo as? CloudTaskRepository)?.close()
        repo = buildRepo()
        bindRepo()
        _ui.update { it.copy(overlay = null, login = LoginForm()) }
        showToast("登录已过期，请重新登录")
    }

    private suspend fun applySignedIn(account: String, toastText: String? = null) {
        loginSettings?.let { candidate ->
            val loginJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            viewModelScope.coroutineContext[Job]?.children?.filter { it != loginJob }?.forEach { it.cancel() }
            historyJob?.cancel()
            usageJob?.cancel()
            profileJob?.cancel()
            _ui.update { UiState(themeColor = settings.themeColor, language = settings.language,
                fontScale = settings.fontScale, ttsEnabled = settings.ttsEnabled,
                hapticsEnabled = settings.hapticsEnabled, projects = projectStore.load()) }
            candidate.themeColor = _ui.value.themeColor
            candidate.language = _ui.value.language
            candidate.fontScale = _ui.value.fontScale
            settings = candidate
            loginSettings = null
        }
        accountStore.activate(settings)
        (repo as? CloudTaskRepository)?.close()
        repo = buildRepo()
        bindRepo()
        _ui.update {
            it.copy(
                overlay = null,
                login = LoginForm(),
                toast = toastText ?: "登录成功，已直连 WorkBuddy 云端"
            )
        }
        clearToastLater()
        loadUsage()
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (_ui.value.login.countdown > 0) {
                delay(1000)
                _ui.update { it.copy(login = it.login.copy(countdown = (it.login.countdown - 1).coerceAtLeast(0))) }
            }
        }
    }

    // ---------- 偏好设置 ----------

    fun setThemeColor(color: String) {
        settings.themeColor = color
        _ui.update { it.copy(themeColor = color) }
    }

    fun setLanguage(language: String) {
        settings.language = language
        _ui.update { it.copy(language = language) }
    }

    fun setFontScale(scale: String) {
        settings.fontScale = scale
        _ui.update { it.copy(fontScale = scale) }
    }

    fun setTtsEnabled(enabled: Boolean) {
        settings.ttsEnabled = enabled
        _ui.update { it.copy(ttsEnabled = enabled) }
        if (enabled) speech.warmUp()
    }

    fun setHapticsEnabled(enabled: Boolean) {
        settings.hapticsEnabled = enabled
        _ui.update { it.copy(hapticsEnabled = enabled) }
    }

    // ---------- 导航 / 生命周期 ----------

    fun openAbout() = _ui.update { it.copy(overlay = Overlay.About) }

    fun openStorage() = _ui.update { it.copy(overlay = Overlay.Storage) }

    fun openArtifacts() {
        _ui.update { it.copy(overlay = Overlay.Artifacts) }
        viewModelScope.launch { repo.refreshSessions() }
    }

    fun openSessions() {
        _ui.update { it.copy(overlay = Overlay.Sessions) }
        viewModelScope.launch { repo.refreshSessions() }
    }

    internal suspend fun searchSessions(query: String): List<String> =
        (repo as? CloudTaskRepository)?.searchSessions(query) ?: emptyList()

    internal fun renameSession(id: String, title: String) {
        val cloud = repo as? CloudTaskRepository ?: return
        val normalized = title.trim().take(100)
        if (normalized.isBlank()) return
        mutateConversation("会话已重命名") { cloud.renameSession(id, normalized) }
    }

    internal fun pinnedSessions(): Set<String> = settings.pinnedSessions

    internal fun toggleSessionPin(id: String): Set<String> {
        val pinned = settings.pinnedSessions
        return (if (id in pinned) pinned - id else pinned + id).also { settings.pinnedSessions = it }
    }

    fun openAgents() {
        _ui.update { it.copy(overlay = Overlay.Agents) }
        loadAgents()
    }

    fun openCheckin() {
        _ui.update { it.copy(overlay = Overlay.Checkin) }
        loadCheckin()
    }

    fun openGrowthPlan() = _ui.update { it.copy(overlay = Overlay.GrowthPlan) }

    fun openCloudTools() = _ui.update { it.copy(overlay = Overlay.CloudTools) }

    private val _cloudRevisions = MutableStateFlow<Map<CloudTool, Int>>(emptyMap())
    internal val cloudRevisions = _cloudRevisions.asStateFlow()
    private val cloudWrite = Mutex()

    internal fun openCloudTool(tool: CloudTool, parent: CloudEntry? = null) = _ui.update {
        it.copy(overlay = CloudModule(CloudDestination(tool, parent), it.overlay ?: Overlay.CloudTools))
    }

    internal fun openCloudEntry(destination: CloudDestination, entry: CloudEntry) = _ui.update {
        val parent = it.overlay as? CloudModule ?: return@update it
        it.copy(overlay = CloudModule(destination, parent, entry))
    }

    internal suspend fun loadCloudTool(destination: CloudDestination, cursor: String?, query: String) =
        withContext(Dispatchers.IO) { WorkBuddyCloudClient(settings).loadCloudPage(destination, cursor, query) }

    internal suspend fun runCloudAction(tool: CloudTool, entry: CloudEntry?, command: CloudCommand) {
        viewModelScope.async {
            check(cloudWrite.tryLock())
            try {
                withContext(Dispatchers.IO) {
                    WorkBuddyCloudClient(settings).runCloudCommand(tool, entry, command)
                }
            } finally {
                // Refresh after a failure too: a timed-out response may follow a successful write.
                _cloudRevisions.update { it + (tool to ((it[tool] ?: 0) + 1)) }
                cloudWrite.unlock()
            }
        }.await()
    }

    suspend fun fetchGrowthPlan() = withContext(Dispatchers.IO) {
        WorkBuddyCloudClient(settings).fetchGrowthPlan()
    }

    internal suspend fun updateGrowthTask(task: GrowthTask) = withContext(Dispatchers.IO) {
        WorkBuddyCloudClient(settings).updateGrowthTask(task)
    }

    fun archiveTask(id: String) = mutateConversation("会话已归档") { repo.archiveTask(id) }

    fun deleteTask(id: String) = mutateConversation("会话已删除") { repo.deleteTask(id) }

    private fun mutateConversation(done: String, call: suspend () -> Unit) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { call() }
                .onSuccess {
                    _ui.update { it.copy(busy = false, toast = done) }
                    clearToastLater()
                }
                .onFailure { e ->
                    _ui.update { it.copy(busy = false, toast = e.message ?: "操作失败") }
                    clearToastLater()
                }
        }
    }

    fun loadAgents() {
        if (_ui.value.agentsLoading) return
        _ui.update { it.copy(agentsLoading = true, agentsError = null) }
        viewModelScope.launch {
            runCatching { repo.listAgents() }
                .onSuccess { list -> _ui.update { it.copy(agentsLoading = false, agents = list) } }
                .onFailure { e ->
                    _ui.update { it.copy(agentsLoading = false, agentsError = e.message ?: "加载失败") }
                }
        }
    }

    fun loadCheckin() {
        if (_ui.value.checkinLoading || !_ui.value.loggedIn) return
        _ui.update { it.copy(checkinLoading = true, checkinError = null) }
        viewModelScope.launch {
            runCatching { repo.checkinStatus() }
                .onSuccess { info -> _ui.update { it.copy(checkinLoading = false, checkin = info) } }
                .onFailure { e ->
                    _ui.update { it.copy(checkinLoading = false, checkinError = e.message ?: "签到状态加载失败") }
                }
        }
    }

    /** 设置页展示签到状态用：已加载过就不重复请求。 */
    fun loadCheckinIfNeeded() {
        if (_ui.value.checkin != null) return
        loadCheckin()
    }

    fun claimCheckin() {
        if (_ui.value.checkinLoading || !_ui.value.loggedIn) return
        _ui.update { it.copy(checkinLoading = true, checkinError = null) }
        viewModelScope.launch {
            runCatching { repo.claimCheckin() }
                .onSuccess { info ->
                    _ui.update { it.copy(checkinLoading = false, checkin = info, toast = "签到成功") }
                    clearToastLater()
                    loadUsage()
                }
                .onFailure { e ->
                    _ui.update { it.copy(checkinLoading = false, checkinError = e.message ?: "签到失败") }
                }
        }
    }

    fun closeOverlay() {
        if (_ui.value.overlay == Overlay.Login && (_ui.value.login.sending || _ui.value.login.submitting)) return
        loginSettings = null
        historyJob?.cancel()
        val state = _ui.value
        if (state.overlay is CloudModule) {
            _ui.update { it.copy(overlay = state.overlay.returnTo) }
            return
        }
        val parent = when (val child = state.overlay) {
            Overlay.ModelPicker -> state.modelPickerReturnId?.let(Overlay::TaskDetail) ?: Overlay.NewChat
            is Overlay.Compose -> child.taskId?.let(Overlay::TaskDetail) ?: Overlay.NewChat
            else -> null
        }
        if (parent != null) {
            _ui.update { it.copy(overlay = parent, modelPickerReturnId = null) }
            if (parent is Overlay.TaskDetail) startHistoryWatcher(parent.id)
        } else {
            _ui.update {
                it.copy(overlay = null, history = emptyList(), replyDraft = "", modelPickerReturnId = null)
            }
        }
    }

    fun clearToast() = _ui.update { it.copy(toast = null) }

    fun setForeground(foreground: Boolean) {
        foregroundNow = foreground
        repo.setForeground(foreground)
        if (foreground) viewModelScope.launch { repo.refreshSessions() }
    }

    // ---------- 内部 ----------

    private fun speakTaskResult(task: Task) {
        val last = _ui.value.history.lastOrNull { it.role == ChatRole.ASSISTANT }?.text
        val spoken = last?.take(200) ?: "任务「${task.title}」已完成"
        speech.speak(spoken)
    }

    private fun showToast(text: String) {
        _ui.update { it.copy(toast = text) }
        clearToastLater()
    }

    private fun clearToastLater() {
        toastJob?.cancel()
        toastJob = viewModelScope.launch {
            delay(4000)
            _ui.update { it.copy(toast = null) }
        }
    }

    companion object {
        /** 详情页打开期间回拉历史的间隔。 */
        private const val HISTORY_REFRESH_INTERVAL = 3_000L
    }
}

/**
 * 系统级 TTS 封装：朗读任务完成结果。
 * 手表上没有引擎时静默失败，不影响任何流程。
 */
class SpeechManager(context: Context) {

    private var tts: TextToSpeech? = null
    @Volatile
    private var ready = false

    init {
        runCatching {
            tts = TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS && runCatching {
                    tts?.language = Locale.CHINA
                }.isSuccess
            }
        }
    }

    fun warmUp() { /* 初始化已在 constructor 里完成 */ }

    fun speak(text: String) {
        if (!ready) return
        runCatching {
            tts?.setSpeechRate(1.0f)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wb-result")
        }
    }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }
}
