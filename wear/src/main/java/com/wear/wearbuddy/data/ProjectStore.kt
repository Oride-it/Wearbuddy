package com.wear.wearbuddy.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 本地项目：名称 + 命令的任务模板。点击项目即把命令作为任务下发到云端。
 * 项目本身只存在本机（云端没有对应接口），持久化在 SharedPreferences。
 */
data class Project(
    val id: String,
    val name: String,
    val command: String,
    val createdAt: Long
)

class ProjectStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("wearbuddy_projects", Context.MODE_PRIVATE)

    fun load(): List<Project> {
        val raw = prefs.getString(KEY_PROJECTS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Project(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    command = o.optString("command"),
                    createdAt = o.optLong("createdAt", 0L)
                )
            }.filter { it.id.isNotBlank() && it.name.isNotBlank() && it.command.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun add(name: String, command: String): Project {
        val project = Project(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            command = command.trim(),
            createdAt = System.currentTimeMillis()
        )
        prefs.edit().putString(KEY_PROJECTS, toJson(load() + project)).apply()
        return project
    }

    fun remove(id: String) {
        prefs.edit().putString(KEY_PROJECTS, toJson(load().filterNot { it.id == id })).apply()
    }

    private fun toJson(projects: List<Project>): String {
        val arr = JSONArray()
        projects.sortedByDescending { it.createdAt }.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("command", p.command)
                    .put("createdAt", p.createdAt)
            )
        }
        return arr.toString()
    }

    private companion object {
        const val KEY_PROJECTS = "projects"
    }
}
