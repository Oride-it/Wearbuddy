package com.wear.wearbuddy.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 没有模型错误的数据源共用的空流。 */
private val NEVER_ERROR: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()

/**
 * 任务数据源。手表脱离手机独立对接 WorkBuddy 云端（[CloudTaskRepository]）。
 * 未登录时使用 [OfflineTaskRepository]：空列表、所有操作提示先登录。
 */
interface TaskRepository {
    val tasks: StateFlow<List<Task>>
    val connected: StateFlow<Boolean>

    /** 云端最近一次失败原因；null 表示正常。 */
    val cloudError: StateFlow<String?>

    /** 云端已判定登录态失效且静默续期也没能恢复。 */
    val sessionExpired: StateFlow<Boolean>

    /** 模型设置失败原因（例如账号没开通所选模型）；null 表示正常。 */
    val modelError: StateFlow<String?> get() = NEVER_ERROR

    fun clearModelError() {}

    suspend fun dispatch(prompt: String, env: ExecEnv, attachments: List<Artifact> = emptyList()): String

    suspend fun reply(id: String, prompt: String, attachments: List<Artifact> = emptyList())

    fun stop(id: String)

    suspend fun retry(id: String)

    suspend fun answerAsk(id: String, ask: AskQuestion, picks: Map<String, AskPick>?)

    suspend fun uploadAttachment(name: String, mimeType: String, bytes: ByteArray): Artifact

    suspend fun downloadArtifact(artifact: Artifact, conversationId: String? = null): ByteArray

    suspend fun loadHistory(id: String): List<ChatMessage> = emptyList()

    /** 列出沙箱工作区某个目录下的文件与子目录；未登录返回空。 */
    suspend fun listWorkspaceDir(id: String, path: String): List<SandboxEntry> = emptyList()

    suspend fun listModels(): List<RemoteModel> = emptyList()

    suspend fun remoteModel(id: String): String? = null

    suspend fun archiveTask(id: String) {}

    suspend fun deleteTask(id: String) {}

    suspend fun listAgents(): List<RemoteAgent> = emptyList()

    suspend fun checkinStatus(): CheckinInfo? = null

    suspend fun claimCheckin(): CheckinInfo? = null

    /** 重新拉取云端会话列表，用于回前台或打开会话相关页面时同步外部新建的任务。 */
    suspend fun refreshSessions() {}

    fun applyModelNow(id: String) {}

    fun markDownloaded(taskId: String, uri: String, localPath: String)

    fun markRead(id: String)

    fun setForeground(foreground: Boolean) {}
}

/** 未登录时的空实现：界面引导先去设置页登录。 */
class OfflineTaskRepository : TaskRepository {
    private val emptyTasks: StateFlow<List<Task>> = MutableStateFlow(emptyList())
    private val connectedFlow: StateFlow<Boolean> = MutableStateFlow(false)
    private val errorText: StateFlow<String?> = MutableStateFlow(null)
    private val expired: StateFlow<Boolean> = MutableStateFlow(false)

    override val tasks: StateFlow<List<Task>> get() = emptyTasks
    override val connected: StateFlow<Boolean> get() = connectedFlow
    override val cloudError: StateFlow<String?> get() = errorText
    override val sessionExpired: StateFlow<Boolean> get() = expired

    override suspend fun dispatch(
        prompt: String,
        env: ExecEnv,
        attachments: List<Artifact>
    ): String = throw IllegalStateException("请先在设置页登录 WorkBuddy")

    override suspend fun reply(id: String, prompt: String, attachments: List<Artifact>) =
        throw IllegalStateException("请先在设置页登录 WorkBuddy")

    override fun stop(id: String) {}

    override suspend fun retry(id: String) {}

    override suspend fun answerAsk(id: String, ask: AskQuestion, picks: Map<String, AskPick>?) {}

    override suspend fun uploadAttachment(
        name: String,
        mimeType: String,
        bytes: ByteArray
    ): Artifact = throw IllegalStateException("请先在设置页登录 WorkBuddy")

    override suspend fun downloadArtifact(artifact: Artifact, conversationId: String?): ByteArray =
        throw IllegalStateException("请先在设置页登录 WorkBuddy")

    override fun markDownloaded(taskId: String, uri: String, localPath: String) {}

    override fun markRead(id: String) {}
}
