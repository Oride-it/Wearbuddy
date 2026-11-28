package com.wear.wearbuddy.data

/** 任务在腕上的状态（由云端会话状态派生）。 */
enum class TaskStatus { WORKING, BLOCKED, DONE, FAILED, STOPPED }

/** 执行环境：云端沙箱。 */
enum class ExecEnv { CLOUD }

/** 云端产物文件引用。 */
data class Artifact(
    val name: String,
    val uri: String,
    val mimeType: String? = null,
    val localPath: String? = null,
    val downloadUrl: String? = null
)

data class TaskResult(
    val points: List<String>,
    val files: List<Artifact> = emptyList()
)

data class Task(
    val id: String,
    val title: String,
    val status: TaskStatus,
    val env: ExecEnv,
    val doneSteps: Int,
    val totalSteps: Int,
    val result: TaskResult? = null,
    val error: String? = null,
    val unread: Boolean = false,
    /** 云端等待用户补充信息时的问题；非空即表示该任务被 askuser 阻塞。 */
    val ask: AskQuestion? = null,
    val messages: List<ChatMessage> = emptyList()
)

/** 会话里的一条消息。THOUGHT 为模型的思考过程分片。 */
enum class ChatRole { USER, ASSISTANT, THOUGHT, TOOL }

data class ChatMessage(
    val role: ChatRole,
    val text: String
)

/** 云端可选模型。 */
data class RemoteModel(
    val id: String,
    val name: String,
    val isDefault: Boolean = false,
    val description: String? = null,
    val credits: String? = null
)

/** 账号已获授权的云端 Agent（只读总览）。 */
data class RemoteAgent(
    val id: String,
    val name: String,
    val description: String? = null,
    val enabled: Boolean = true
)

/** 每日签到状态。 */
data class CheckinInfo(
    val active: Boolean = true,
    val todayCheckedIn: Boolean = false,
    val streakDays: Int = 0,
    val dailyCredit: Int = 0,
    val todayCredit: Int = 0
)
