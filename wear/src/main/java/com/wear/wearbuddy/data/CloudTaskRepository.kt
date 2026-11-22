package com.wear.wearbuddy.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 云端任务数据源：手表脱离手机，直接调用 WorkBuddy「云上模式」。
 * 对话内容通过 ACP 实时更新；REST 轮询用于同步列表和恢复连接后的状态。
 */
class CloudTaskRepository(private val settings: CloudSettings) : TaskRepository {

    private val client = WorkBuddyCloudClient(settings)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pollers = ConcurrentHashMap<String, Job>()
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var lastRefreshAt = 0L

    private val foregroundFlow = MutableStateFlow(true)

    private val sessions = ConcurrentHashMap<String, WorkBuddyCloudClient.Session>()

    private val serverModels = ConcurrentHashMap<String, String>()

    private val stoppedIds = ConcurrentHashMap.newKeySet<String>().apply { addAll(settings.stoppedSessions) }

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    override val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _cloudError = MutableStateFlow<String?>(null)
    override val cloudError: StateFlow<String?> = _cloudError.asStateFlow()

    private val _sessionExpired = MutableStateFlow(false)
    override val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    private val _modelError = MutableStateFlow<String?>(null)
    override val modelError: StateFlow<String?> = _modelError.asStateFlow()

    override fun clearModelError() {
        _modelError.value = null
    }

    init {
        client.onStreamChanged = { id, snapshot ->
            updateTask(id) { task ->
                val status = TaskStates.resolve(null, snapshot.status, id in stoppedIds) ?: task.status
                task.copy(
                    messages = snapshot.messages,
                    status = status,
                    error = snapshot.error ?: task.error?.takeIf { status == TaskStatus.FAILED },
                    unread = task.unread || (status != task.status && status == TaskStatus.DONE),
                    result = mergeResult(task.result, snapshot.files)
                )
            }
        }
        scope.launch { refreshSessions() }
        scope.launch { healthLoop() }
    }

    override suspend fun dispatch(prompt: String, env: ExecEnv, attachments: List<Artifact>): String =
        withContext(Dispatchers.IO) {
            val model = currentModel()
            val conversation = client.createConversation(prompt, model)
            val session = conversation.session ?: client.fetchSession(conversation.id)
            sessions[conversation.id] = session
            val task = Task(
                id = conversation.id,
                title = conversation.title ?: prompt,
                status = TaskStatus.WORKING,
                env = env,
                doneSteps = 0,
                totalSteps = 0
            )
            _tasks.update { listOf(task) + it }
            startPolling(conversation.id)
            // create 里的 prompt 只用来生成会话标题，首轮指令必须通过 ACP session/prompt 下发。
            deliver(conversation.id) {
                applyModel(conversation.id, session)
                client.sendPrompt(session, prompt, attachments)
            }
            conversation.id
        }

    override suspend fun reply(id: String, prompt: String, attachments: List<Artifact>) = withContext(Dispatchers.IO) {
        setStopped(id, false)
        updateTask(id) { it.copy(status = TaskStatus.WORKING, error = null, ask = null) }
        startPolling(id)
        deliver(id) {
            val session = sessionOf(id)
            applyModel(id, session)
            client.sendPrompt(session, prompt, attachments)
        }
    }

    override suspend fun answerAsk(id: String, answer: String) = withContext(Dispatchers.IO) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return@withContext
        val ask = task.ask
        updateTask(id) { it.copy(status = TaskStatus.WORKING, ask = null, unread = false, error = null) }
        startPolling(id)
        deliver(id) {
            val session = sessionOf(id)
            applyModel(id, session)
            if (ask?.requestId != null) {
                client.resolveInterruption(session, ask, answer)
            } else {
                client.sendPrompt(session, answer)
            }
        }
    }

    override suspend fun uploadAttachment(name: String, mimeType: String, bytes: ByteArray): Artifact =
        withContext(Dispatchers.IO) { client.uploadFile(name, mimeType, bytes) }

    override suspend fun downloadArtifact(artifact: Artifact, conversationId: String?): ByteArray =
        withContext(Dispatchers.IO) { client.downloadArtifact(artifact, conversationId) }

    override suspend fun loadHistory(id: String): List<ChatMessage> =
        withContext(Dispatchers.IO) { client.loadHistory(id) }

    override suspend fun listModels(): List<RemoteModel> =
        withContext(Dispatchers.IO) { client.listModels(settings.enterpriseId) }

    override suspend fun remoteModel(id: String): String? =
        withContext(Dispatchers.IO) { runCatching { client.getConversation(id)?.model }.getOrNull() }

    suspend fun searchSessions(title: String): List<String> = withContext(Dispatchers.IO) {
        val matches = client.listConversations(size = 50, title = title)
        _tasks.update { current ->
            val known = current.mapTo(mutableSetOf()) { it.id }
            current + matches.filter { known.add(it.id) }.map { it.toTask(ExecEnv.CLOUD) }
        }
        matches.map { it.id }
    }

    suspend fun renameSession(id: String, title: String) = withContext(Dispatchers.IO) {
        client.renameConversation(id, title)
        updateTask(id) { it.copy(title = title) }
    }

    override suspend fun archiveTask(id: String) = withContext(Dispatchers.IO) {
        client.archiveConversation(id)
        forget(id)
    }

    override suspend fun deleteTask(id: String) = withContext(Dispatchers.IO) {
        client.deleteConversation(id)
        forget(id)
    }

    override suspend fun listAgents(): List<RemoteAgent> =
        withContext(Dispatchers.IO) { client.listAgents() }

    override suspend fun checkinStatus(): CheckinInfo =
        withContext(Dispatchers.IO) { client.checkinStatus() }

    override suspend fun claimCheckin(): CheckinInfo =
        withContext(Dispatchers.IO) { client.claimCheckin() }

    private fun forget(id: String) {
        pollers.remove(id)?.cancel()
        sessions.remove(id)
        setStopped(id, false)
        _tasks.update { list -> list.filterNot { it.id == id } }
    }

    override fun applyModelNow(id: String) {
        scope.launch { runCatching { applyModel(id, sessionOf(id)) } }
    }

    override fun stop(id: String) {
        pollers.remove(id)?.cancel()
        setStopped(id, true)
        updateTask(id) { it.copy(status = TaskStatus.STOPPED, unread = false, ask = null) }
        scope.launch {
            repeat(2) { attempt ->
                if (runCatching { client.cancelTurn(sessionOf(id)) }.isSuccess) return@launch
                if (attempt == 0) {
                    sessions.remove(id)
                    delay(500)
                }
            }
        }
    }

    override suspend fun retry(id: String) = withContext(Dispatchers.IO) {
        val prompt = _tasks.value.firstOrNull { it.id == id }?.title.orEmpty()
        setStopped(id, false)
        updateTask(id) { it.copy(status = TaskStatus.WORKING, error = null) }
        startPolling(id)
        deliver(id) {
            val session = sessionOf(id)
            applyModel(id, session)
            client.sendPrompt(session, prompt)
        }
    }

    override fun markRead(id: String) {
        updateTask(id) { it.copy(unread = false) }
    }

    override fun markDownloaded(taskId: String, uri: String, localPath: String) {
        updateTask(taskId) { t ->
            val files = t.result?.files.orEmpty().map { if (it.uri == uri) it.copy(localPath = localPath) else it }
            t.copy(result = t.result?.copy(files = files))
        }
    }

    fun close() {
        client.closeAllLinks()
        scope.cancel()
    }

    override fun setForeground(foreground: Boolean) {
        foregroundFlow.value = foreground
    }

    override suspend fun refreshSessions() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - lastRefreshAt < REFRESH_MIN_INTERVAL) return@withContext
        if (!refreshing.compareAndSet(false, true)) return@withContext
        lastRefreshAt = now
        try {
            refreshList()
        } finally {
            refreshing.set(false)
        }
    }

    // ---------- 内部 ----------

    private fun sessionOf(id: String): WorkBuddyCloudClient.Session =
        sessions[id] ?: client.fetchSession(id).also { sessions[id] = it }

    private fun currentModel(): String =
        settings.selectedModel.ifBlank { WorkBuddyCloudClient.DEFAULT_MODEL }

    private fun applyModel(id: String, session: WorkBuddyCloudClient.Session) {
        if (!client.hasLink(id)) serverModels.remove(id)
        val model = currentModel()
        if (serverModels[id] == model) return
        runCatching { client.setModel(session, model) }
            .onSuccess {
                serverModels[id] = model
                _modelError.value = null
            }
            .onFailure { e ->
                val msg = e.message.orEmpty()
                _modelError.value = if (msg.contains("service info not found") || msg.contains("400")) {
                    "云端不支持「$model」，请重新选择模型"
                } else {
                    "模型设置失败：" + msg.take(60)
                }
            }
    }

    private fun deliver(id: String, call: () -> Unit) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { call() }
            } catch (e: java.io.IOException) {
                // A lost response does not mean the accepted task stopped on the server.
                updateTask(id) { it.copy(error = "连接中断，正在重新同步云端进度") }
            } catch (e: Exception) {
                updateTask(id) {
                    it.copy(status = TaskStatus.FAILED,
                        error = "指令下发失败：" + (e.message ?: e.javaClass.simpleName).take(60))
                }
            }
        }
    }

    private suspend fun refreshList() {
        runCatching { client.listConversations() }
            .onSuccess { remote ->
                markHealthy()
                val mapped = remote.map { it.toTask(ExecEnv.CLOUD) }
                if (mapped.isNotEmpty()) {
                    _tasks.update { local -> merge(local, mapped) }
                }
                _tasks.value
                    .filter { it.status == TaskStatus.WORKING && !pollers.containsKey(it.id) }
                    .forEach { startPolling(it.id) }
            }
            .onFailure { markUnhealthy(it) }
    }

    private suspend fun healthLoop() {
        while (scope.isActive) {
            if (!_connected.value) {
                foregroundFlow.first { it }
                runCatching { client.listConversations(size = 1) }
                    .onSuccess { markHealthy() }
                    .onFailure { markUnhealthy(it) }
            }
            delay(HEALTH_INTERVAL)
            client.trimLinks(LINK_IDLE_TTL)
        }
    }

    private fun markHealthy() {
        _connected.value = true
        _cloudError.value = null
    }

    private fun markUnhealthy(error: Throwable) {
        _connected.value = false
        _cloudError.value = when (error) {
            is SessionExpiredException -> {
                _sessionExpired.value = true
                "登录已过期，请重新登录"
            }
            is java.net.UnknownHostException, is java.net.ConnectException -> "网络不可用，请检查手表网络"
            is java.net.SocketTimeoutException -> "请求超时，请稍后重试"
            is javax.net.ssl.SSLException -> "网络连接不安全，请检查网络"
            else -> (error.message ?: error.javaClass.simpleName).take(80)
        }
    }

    private fun startPolling(id: String) {
        pollers.remove(id)?.cancel()
        pollers[id] = scope.launch {
            var backoff = INITIAL_POLL_INTERVAL
            while (isActive) {
                if (settings.extremeSaver) {
                    foregroundFlow.first { it }
                    val wentBackground = withTimeoutOrNull(backoff) { foregroundFlow.first { !it } }
                    if (wentBackground != null) continue
                } else {
                    delay(backoff)
                }

                val remote = runCatching { client.getConversation(id) }.getOrNull()
                if (remote == null) {
                    backoff = (backoff * 2).coerceAtMost(maxPollInterval())
                    continue
                }
                val stream = client.streamSnapshot(id)?.takeIf { client.hasLink(id) }
                val polledStatus = remote.status.toTaskStatusOrNull()
                val remoteStatus = TaskStates.resolve(polledStatus, stream?.status, id in stoppedIds)
                if (remoteStatus == TaskStatus.WORKING) remote.session?.let { sessions[id] = it }
                updateTask(id) { t ->
                    val status = remoteStatus?.takeUnless {
                        t.status == TaskStatus.STOPPED && it == TaskStatus.WORKING
                    } ?: t.status
                    t.copy(
                        status = status,
                        title = remote.title ?: t.title,
                        error = if (status == TaskStatus.FAILED) (t.error ?: "云端执行失败") else t.error,
                        unread = status == TaskStatus.DONE || status == TaskStatus.FAILED ||
                            (status == TaskStatus.BLOCKED && t.ask == null),
                        ask = if (status == TaskStatus.BLOCKED) (remote.ask ?: t.ask) else null,
                        result = if (status == TaskStatus.DONE) cloudResult(remote, t.result)
                            else mergeResult(t.result, remote.artifacts)
                    )
                }
                val now = _tasks.value.firstOrNull { it.id == id }?.status
                if (now == null || now.isTerminal()) return@launch
                backoff = (backoff * 2).coerceAtMost(maxPollInterval())
            }
        }
    }

    private fun cloudResult(
        remote: WorkBuddyCloudClient.RemoteConversation,
        prev: TaskResult?
    ): TaskResult {
        val downloaded = prev?.files.orEmpty()
            .mapNotNull { f -> f.localPath?.let { f.uri to it } }
            .toMap()
        val files = (prev?.files.orEmpty() + remote.artifacts).distinctBy { it.uri }.map { a ->
            downloaded[a.uri]?.let { a.copy(localPath = it) } ?: a
        }
        val points = buildList {
            add("任务已在云端完成")
            if (files.isNotEmpty()) add("已产出 ${files.size} 个文件")
        }
        return TaskResult(points = points, files = files)
    }

    private fun mergeResult(prev: TaskResult?, incoming: List<Artifact>): TaskResult? {
        if (incoming.isEmpty()) return prev
        val filesByUri = prev?.files.orEmpty().associateByTo(linkedMapOf()) { it.uri }
        incoming.forEach { file ->
            filesByUri[file.uri] = file.copy(localPath = filesByUri[file.uri]?.localPath ?: file.localPath)
        }
        val files = filesByUri.values.toList()
        return TaskResult(prev?.points.orEmpty(), files)
    }

    private fun TaskStatus.isTerminal() =
        this == TaskStatus.DONE || this == TaskStatus.FAILED || this == TaskStatus.STOPPED

    private fun maxPollInterval(): Long =
        if (settings.extremeSaver) SAVER_MAX_POLL_INTERVAL else MAX_POLL_INTERVAL

    private fun updateTask(id: String, transform: (Task) -> Task) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private fun merge(local: List<Task>, remote: List<Task>): List<Task> {
        val remoteIds = remote.map { it.id }.toSet()
        val localById = local.associateBy { it.id }
        val merged = remote.map { r ->
            val l = localById[r.id] ?: return@map r
            val keepStopped = (l.status == TaskStatus.STOPPED || stoppedIds.contains(r.id)) &&
                r.status == TaskStatus.WORKING
            r.copy(
                status = TaskStates.resolve(r.status, client.streamSnapshot(r.id)?.status, keepStopped) ?: r.status,
                result = r.result ?: l.result,
                messages = l.messages,
                unread = if (keepStopped) false else r.unread
            )
        }
        return merged + local.filter { it.id !in remoteIds }
    }

    private fun WorkBuddyCloudClient.RemoteConversation.toTask(env: ExecEnv): Task {
        val status = TaskStates.resolve(this.status.toTaskStatusOrNull() ?: TaskStatus.DONE, null, id in stoppedIds)!!
        return Task(
            id = id,
            title = title ?: "云端任务",
            status = status,
            env = env,
            doneSteps = 0,
            totalSteps = 0,
            error = if (status == TaskStatus.FAILED) "云端执行失败" else null,
            ask = if (status == TaskStatus.BLOCKED) ask else null,
            result = if (status == TaskStatus.DONE) cloudResult(this, null) else null
        )
    }

    private fun String.toTaskStatusOrNull(): TaskStatus? = TaskStates.parse(this)

    @Synchronized private fun setStopped(id: String, stopped: Boolean) {
        if (stopped) stoppedIds.add(id) else stoppedIds.remove(id)
        settings.stoppedSessions = stoppedIds.toSet()
    }

    private companion object {
        const val INITIAL_POLL_INTERVAL = 2_000L
        const val MAX_POLL_INTERVAL = 8_000L
        const val SAVER_MAX_POLL_INTERVAL = 30_000L
        const val HEALTH_INTERVAL = 60_000L
        const val LINK_IDLE_TTL = 15 * 60_000L
        const val REFRESH_MIN_INTERVAL = 15_000L
    }
}
