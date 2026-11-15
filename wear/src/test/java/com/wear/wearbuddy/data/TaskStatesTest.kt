package com.wear.wearbuddy.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskStatesTest {
    @Test fun `finished execution is not shown as running just because its sandbox is alive`() {
        val response = JSONObject().put("sessionStatus", "RUNNING")
            .put("status", JSONObject().put("status", "idle"))
        assertEquals("IDLE", TaskStates.remoteStatus(response))
        assertEquals(TaskStatus.DONE, TaskStates.parse(TaskStates.remoteStatus(response)))
    }

    @Test fun `cancelled execution overrides a running session and null fields are skipped`() {
        assertEquals("CANCELLED", TaskStates.remoteStatus(JSONObject().put("sessionStatus", "RUNNING")
            .put("status", "cancelled")))
        assertEquals("COMPLETED", TaskStates.remoteStatus(JSONObject().put("status", JSONObject.NULL)
            .put("session", JSONObject().put("status", "completed"))))
    }

    @Test fun `REST completion repairs a stream that missed its completion event`() {
        assertEquals(TaskStatus.DONE, TaskStates.resolve(TaskStatus.DONE, TaskStatus.WORKING))
        assertEquals(TaskStatus.STOPPED, TaskStates.resolve(TaskStatus.STOPPED, TaskStatus.WORKING))
    }

    @Test fun `finished stream is not restarted by an older running list response`() {
        assertEquals(TaskStatus.DONE, TaskStates.resolve(TaskStatus.WORKING, TaskStatus.DONE))
        assertEquals(TaskStatus.FAILED, TaskStates.resolve(TaskStatus.WORKING, TaskStatus.FAILED))
        assertEquals(TaskStatus.WORKING, TaskStates.resolve(TaskStatus.WORKING, TaskStatus.WORKING))
    }

    @Test fun `explicit stop survives running or blocked remote state`() {
        assertEquals(TaskStatus.STOPPED, TaskStates.resolve(TaskStatus.WORKING, TaskStatus.WORKING, true))
        assertEquals(TaskStatus.STOPPED, TaskStates.resolve(TaskStatus.BLOCKED, null, true))
    }
}
