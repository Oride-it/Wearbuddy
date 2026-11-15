package com.wear.wearbuddy.data

import org.json.JSONObject

/** Execution status takes precedence over the sandbox's session/lifecycle status. */
internal object TaskStates {
    fun parse(value: String): TaskStatus? = when (value.uppercase()) {
        "RUNNING", "PENDING", "IN_PROGRESS", "PROCESSING", "WORKING", "BUSY", "GENERATING",
        "ACTIVE", "QUEUED", "STARTED", "PLANNING", "THINKING", "EXECUTING" -> TaskStatus.WORKING
        "COMPLETED", "COMPLETE", "SUCCESS", "DONE", "FINISHED", "IDLE", "ARCHIVED" -> TaskStatus.DONE
        "WAITING_USER_INPUT", "WAITING_INPUT", "REQUIRES_ACTION", "PAUSED", "BLOCKED",
        "WAITING_FOR_USER", "WAITING_FOR_PERMISSION" -> TaskStatus.BLOCKED
        "CANCELLED", "CANCELED", "STOPPED", "ABORTED", "TERMINATED" -> TaskStatus.STOPPED
        "EXPIRED", "FAILED", "ERROR" -> TaskStatus.FAILED
        else -> null
    }

    fun remoteStatus(o: JSONObject): String {
        val session = o.optJSONObject("session")
        return sequenceOf(
            o.optJSONObject("status")?.opt("status"), o.opt("status"),
            session?.opt("status"), session?.opt("state"),
            o.opt("sessionStatus"), session?.opt("sessionStatus"), o.opt("lifecycle")
        ).filterIsInstance<String>().firstOrNull { parse(it) != null }.orEmpty().uppercase()
    }

    fun resolve(remote: TaskStatus?, stream: TaskStatus?, stopped: Boolean = false): TaskStatus? = when {
        stopped -> TaskStatus.STOPPED
        stream in terminal -> stream
        remote in terminal -> remote
        remote == TaskStatus.BLOCKED -> remote
        else -> stream ?: remote
    }

    private val terminal = setOf(TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.STOPPED)
}
