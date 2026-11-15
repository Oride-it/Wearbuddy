package com.wear.wearbuddy.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

internal enum class CloudTool {
    Messages, Automations, RunHistory, Plugins, PluginMarket, Experts,
    Connectors, Contacts, Spaces, SpaceFiles, Documents, Shares, Agents, AgentAssets, AgentConnectors,
    Skills, Credits, Badges, Buddies, Travel, Streak
}

internal enum class CloudCommand {
    MarkRead, MarkAllRead, Run, Enable, Disable, Delete, Install, Uninstall, SwitchBuddy
}

internal class CloudAccessException : IllegalStateException()

internal data class CloudEntry(
    val id: String,
    val title: String,
    val summary: String = "",
    val description: String = "",
    val commands: List<CloudCommand> = emptyList(),
    val installSource: String = "",
    val childIds: List<String> = emptyList(),
    val isFolder: Boolean = false,
    val website: String = ""
)

internal data class CloudPage(
    val entries: List<CloudEntry>,
    val next: String? = null,
    val unread: Int? = null,
    val streakDays: Int? = null,
    val usesExpertMarket: Boolean = false
)

internal data class CloudDestination(
    val tool: CloudTool,
    val parent: CloudEntry? = null
)

private const val MESSAGES = "/portal/msg-center/message"
private const val SCHEDULES = "/webapi/scheduler/tasks"
private const val PLUGINS = "/console/as/user/plugins"
private const val AGENTS = "/v2/user/cloudagent/agents"
private const val SKILLS = "/v2/user-asset/skill"

internal fun WorkBuddyCloudClient.loadCloudPage(
    destination: CloudDestination,
    cursor: String?,
    query: String
): CloudPage {
    val page = cursor?.toIntOrNull() ?: 1
    return when (destination.tool) {
        CloudTool.Messages -> {
            val suffix = cursor?.let { "&cursor=${segment(it)}" }.orEmpty()
            val data = toolData("GET", "$MESSAGES/list?limit=20$suffix", productCode = true)
            val messages = data.rows("messages").mapNotNull { item ->
                val id = item.text("msg_id")
                val content = item.text("content")
                val title = item.text("title").ifBlank { content.lineSequence().firstOrNull().orEmpty().take(48) }
                if (id.isBlank() || title.isBlank()) return@mapNotNull null
                CloudEntry(
                    id, title, description = content,
                    commands = if (item.optBoolean("is_read")) emptyList() else listOf(CloudCommand.MarkRead)
                )
            }
            val next = data.text("next_cursor").takeIf { data.optBoolean("has_more") && it.isNotBlank() }
            val unread = if (cursor == null) runCatching {
                toolData("GET", "$MESSAGES/summary", productCode = true).optInt("unread_count", -1)
                    .takeIf { it >= 0 }
            }.getOrNull() else null
            CloudPage(messages, next, unread)
        }
        CloudTool.Automations -> {
            val data = toolData("GET", "$SCHEDULES?page=$page&pageSize=20&ownerType=personal&agentType=all")
            data.page("tasks", page) { item ->
                item.entry("id", "name")?.copy(
                    summary = item.text("scheduleDesc", "schedule_desc", "cronExpr", "cron_expr"),
                    description = listOf(
                        item.text("description"),
                        item.optJSONObject("agentConfig")?.text("prompt").orEmpty(),
                        item.text("nextRunAt", "next_run_at")
                    ).filter(String::isNotBlank).joinToString("\n"),
                    commands = listOf(
                        CloudCommand.Run,
                        if (item.optInt("status") == 1) CloudCommand.Disable else CloudCommand.Enable,
                        CloudCommand.Delete
                    )
                )
            }
        }
        CloudTool.RunHistory -> {
            val id = segment(requireNotNull(destination.parent).id)
            toolData("GET", "$SCHEDULES/$id/logs?page=$page&pageSize=20").page("logs", page) { item ->
                item.entry("id", "startTime", "start_time", "status")?.copy(
                    summary = item.text("status"),
                    description = item.text("errorMsg", "error_msg")
                )
            }
        }
        CloudTool.Plugins -> {
            val data = toolData("GET", "$PLUGINS/installed")
            CloudPage(data.rows("plugins").mapNotNull { item ->
                item.entry("id", "plugin_name")?.copy(
                    summary = item.text("version"),
                    description = item.text("description"),
                    commands = listOf(
                        if (item.optBoolean("enabled")) CloudCommand.Disable else CloudCommand.Enable,
                        CloudCommand.Uninstall
                    )
                )
            })
        }
        CloudTool.PluginMarket -> {
            if (query.isBlank()) return CloudPage(emptyList())
            toolData("GET", "/console/as/marketplace/plugins/search?q=${segment(query)}&page=$page&page_size=20")
                .page("plugins", page, missingIsEmpty = true) { item ->
                    val name = item.text("name")
                    val source = item.text("marketplace_name")
                    if (name.isBlank()) return@page null
                    CloudEntry(
                        name, name, source, item.text("description"),
                        commands = if (source.isNotBlank() && !item.optBoolean("installed")) {
                            listOf(CloudCommand.Install)
                        } else emptyList(),
                        installSource = source
                    )
                }
        }
        CloudTool.Experts -> {
            loadExperts(page)
        }
        CloudTool.Connectors -> {
            val data = toolData("GET", "/console/as/connector/user/")
            CloudPage(data.rows("connectors").mapNotNull { item ->
                item.entry("name", "display_name", "name")?.copy(summary = item.text("connect_status"))
            })
        }
        CloudTool.Contacts -> {
            val data = toolData(
                "POST", "/contacts/api/contacts/favorites/list",
                JSONObject().put("page_size", 20).put("page_token", cursor.orEmpty())
                    .put("entity_types", JSONArray().put("user"))
            )
            val next = data.text("page_token").takeIf { data.optBoolean("has_more") && it.isNotBlank() }
            CloudPage(data.rows("items").mapNotNull { item ->
                item.entry("entity_id", "title")?.copy(summary = item.text("subtitle"))
            }, next)
        }
        CloudTool.Spaces -> {
            val spaces = toolData("GET", "/space/api/view/query/spaces").getJSONObject("spaces")
            CloudPage(spaces.keys().asSequence().mapNotNull { key ->
                val space = spaces.optJSONObject(key)?.optJSONObject("value") ?: return@mapNotNull null
                val title = space.optJSONObject("props")?.text("title").orEmpty()
                title.takeIf(String::isNotBlank)?.let {
                    CloudEntry(space.text("id").ifBlank { key }, it, childIds = space.optJSONArray("nodes").strings())
                }
            }.toList())
        }
        CloudTool.SpaceFiles -> loadSpaceFiles(requireNotNull(destination.parent), page)
        CloudTool.Documents -> loadTencentDocuments(destination.parent?.id, cursor?.toIntOrNull() ?: 0, query)
        CloudTool.Shares -> toolData("GET", "/v2/as/netdisk/shares?page=$page&pageSize=20")
            .page("records", page) { item ->
                item.entry("code", "name", "fileName", "title", "code")?.copy(
                    summary = item.text("createdAt", "created_at")
                )
            }
        CloudTool.Agents -> CloudPage(listAgents().map { agent ->
            CloudEntry(
                agent.id, agent.name, description = agent.description.orEmpty(),
                commands = listOf(if (agent.enabled) CloudCommand.Disable else CloudCommand.Enable)
            )
        })
        CloudTool.AgentAssets -> {
            val id = segment(requireNotNull(destination.parent).id)
            val entries = listOf("expert", "skill").flatMap { type ->
                val data = toolData("GET", "$AGENTS/$id/assets?assetType=$type&page=1&pageSize=50")
                data.rows(if (data.has("items")) "items" else "list").mapNotNull { item ->
                    val resource = item.optJSONObject("resource")
                    val title = item.text("displayName", "display_name", "title", "name").ifBlank {
                        resource?.text("displayName", "display_name", "title", "name").orEmpty()
                    }
                    val assetId = item.text("assetId")
                    if (title.isBlank() || assetId.isBlank()) null else CloudEntry(
                        "$type:$assetId", title, type,
                        item.text("description").ifBlank { resource?.text("description").orEmpty() }
                    )
                }
            }
            CloudPage(entries)
        }
        CloudTool.AgentConnectors -> {
            val id = segment(requireNotNull(destination.parent).id)
            val data = toolData("GET", "$AGENTS/$id/connectors?page=1&pageSize=50")
            CloudPage(data.rows("items").mapNotNull { item ->
                item.entry("connectorId", "displayName", "name", "connectorId")
            })
        }
        CloudTool.Skills -> toolData("GET", "$SKILLS/get-list?page_no=$page&page_size=20").page("items", page) { item ->
            item.entry("asset_id", "display_name_zh", "display_name", "display_name_en", "name")?.copy(
                summary = item.text("latest_published_version", "version"),
                description = item.text("description_zh", "description_en"),
                commands = buildList {
                    add(if (item.optInt("enabled", 1) == 0) CloudCommand.Enable else CloudCommand.Disable)
                    if (item.text("source") != "custom") add(CloudCommand.Uninstall)
                }
            )
        }
        CloudTool.Credits -> CloudPage(fetchUsage().packs.mapIndexed { index, pack ->
            CloudEntry(
                index.toString(), pack.name,
                "${pack.left} / ${pack.total}",
                listOfNotNull(pack.status, pack.expireAt).joinToString("\n")
            )
        })
        CloudTool.Badges -> {
            val data = toolData("GET", "/v2/activity/growth/badges")
            CloudPage(data.rows("badges").filter { it.optBoolean("earned") }.mapNotNull { item ->
                item.entry("code", "badge_name", "title")
            })
        }
        CloudTool.Buddies -> {
            val data = toolData("GET", "/activity/growth/buddy/list")
            CloudPage(data.rows("buddies").mapNotNull { item ->
                item.entry("instance_id", "name")?.copy(
                    summary = item.text("rarity"),
                    description = item.text("personality"),
                    commands = if (item.optBoolean("current_buddy")) emptyList() else listOf(CloudCommand.SwitchBuddy)
                )
            })
        }
        CloudTool.Travel -> {
            val data = toolData("GET", "/activity/growth/buddy/travel/records?page=$page&page_size=20")
            data.page("records", page) { item ->
                val location = item.optJSONObject("location")
                val title = location?.text("name", "title").orEmpty().ifBlank { item.text("location_name") }
                val id = item.text("id", "record_id", "depart_at")
                if (id.isBlank() || title.isBlank()) null else CloudEntry(
                    id, title, item.text("reward_credit").takeIf(String::isNotBlank)?.let { "+$it" }.orEmpty(),
                    item.optJSONObject("letter")?.text("text").orEmpty()
                )
            }
        }
        CloudTool.Streak -> loadGrowthStreak()
    }
}

internal fun WorkBuddyCloudClient.runCloudCommand(tool: CloudTool, entry: CloudEntry?, command: CloudCommand) {
    val id = entry?.id?.let(::segment).orEmpty()
    when (tool) {
        CloudTool.Messages -> {
            val body = when (command) {
                CloudCommand.MarkRead -> JSONObject().put("msg_ids", JSONArray().put(requireNotNull(entry).id))
                CloudCommand.MarkAllRead -> JSONObject().put("all", true)
                else -> error("Unsupported message action")
            }
            toolMutation("POST", "$MESSAGES/read", body, productCode = true)
        }
        CloudTool.Automations -> when (command) {
            CloudCommand.Run -> toolMutation("POST", "$SCHEDULES/$id/trigger", JSONObject())
            CloudCommand.Enable, CloudCommand.Disable -> toolMutation(
                "PUT", "$SCHEDULES/$id", JSONObject().put("status", if (command == CloudCommand.Enable) 1 else 0)
            )
            CloudCommand.Delete -> toolMutation("DELETE", "$SCHEDULES/$id")
            else -> error("Unsupported automation action")
        }
        CloudTool.Plugins -> when (command) {
            CloudCommand.Uninstall -> toolMutation("POST", "$PLUGINS/installed/$id/uninstall")
            CloudCommand.Enable, CloudCommand.Disable -> toolMutation(
                "POST", "$PLUGINS/installed/$id/toggle", JSONObject().put("enabled", command == CloudCommand.Enable)
            )
            else -> error("Unsupported plugin action")
        }
        CloudTool.PluginMarket -> {
            val plugin = requireNotNull(entry)
            require(command == CloudCommand.Install)
            val sources = toolData("GET", "/console/as/marketplace/sources").rows("sources")
            val source = sources.first { it.text("name") == plugin.installSource }
            toolMutation(
                "POST", "$PLUGINS/install",
                JSONObject().put("plugin_name", plugin.id).put("marketplace_id", source.get("id"))
                    .put("version", "latest")
            )
        }
        CloudTool.Skills -> {
            val operation = when (command) {
                CloudCommand.Enable -> "enable"
                CloudCommand.Disable -> "disable"
                CloudCommand.Uninstall -> "uninstall"
                else -> error("Unsupported skill action")
            }
            toolMutation("POST", "$SKILLS/$operation", JSONObject().put("asset_ids", JSONArray().put(requireNotNull(entry).id)))
        }
        CloudTool.Agents -> {
            val operation = when (command) {
                CloudCommand.Enable -> "enable"
                CloudCommand.Disable -> "disable"
                else -> error("Unsupported agent action")
            }
            toolMutation("PUT", "$AGENTS/$id/$operation")
        }
        CloudTool.Buddies -> {
            require(command == CloudCommand.SwitchBuddy)
            val instance = requireNotNull(entry).id
            val body = JSONObject().put("instance_id", instance.toLongOrNull() ?: instance)
            toolMutation("POST", "/activity/growth/buddy/switch", body)
        }
        else -> error("This feature has no write operation")
    }
}

internal fun WorkBuddyCloudClient.toolData(
    method: String,
    path: String,
    body: JSONObject? = null,
    productCode: Boolean = false
): JSONObject {
    val response = checkNotNull(featureResponse(method, path, body, productCode))
    return response.optJSONObject("data") ?: response
}

internal fun WorkBuddyCloudClient.toolMutation(
    method: String,
    path: String,
    body: JSONObject? = null,
    productCode: Boolean = false
) {
    checkNotNull(featureResponse(method, path, body, productCode))
}

internal fun segment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

internal fun JSONObject.text(vararg keys: String): String =
    keys.firstNotNullOfOrNull { key ->
        val value = opt(key)
        when (value) {
            is String -> value.trim().takeIf(String::isNotEmpty)
            is Number, is Boolean -> value.toString()
            else -> null
        }
    }.orEmpty()

internal fun JSONObject.rows(key: String): List<JSONObject> {
    val array = getJSONArray(key)
    return (0 until array.length()).mapNotNull(array::optJSONObject)
}

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it) as? String }

private fun JSONObject.page(
    key: String, page: Int, missingIsEmpty: Boolean = false, convert: (JSONObject) -> CloudEntry?
): CloudPage {
    val rows = if (missingIsEmpty && isNull(key)) emptyList() else rows(key)
    val total = optInt("total", optInt("total_count", -1))
    val more = rows.isNotEmpty() && (if (total >= 0) page * 20 < total else rows.size == 20)
    return CloudPage(rows.mapNotNull(convert), (page + 1).toString().takeIf { more })
}

private fun WorkBuddyCloudClient.loadExperts(page: Int): CloudPage {
    if (page == 1) {
        try {
            val data = toolData("GET", "/console/expert/ranking?limit=30")
            val key = if (data.has("items")) "items" else "experts"
            return CloudPage(data.rows(key).mapNotNull(::expertEntry))
        } catch (_: CloudAccessException) {
            // The public expert catalog uses a different gateway from the ranking.
        }
    }
    val data = toolData(
        "POST", "/v2/operation-platform/market/expert/list",
        JSONObject().put("page", page).put("page_size", 20)
            .put("sort_by", "reco_rank").put("sort_order", "desc")
    )
    val key = if (data.has("items")) "items" else "experts"
    return data.page(key, page, convert = ::expertEntry).copy(usesExpertMarket = true)
}

private fun expertEntry(item: JSONObject): CloudEntry? {
    val id = item.text("expert_id", "id")
    val name = item.text("display_name_zh", "display_name", "displayName", "name", "title")
    return if (id.isBlank() || name.isBlank()) null else CloudEntry(
        id, name, description = item.text("description_zh", "description")
    )
}

private fun JSONObject.entry(idKey: String, vararg titleKeys: String): CloudEntry? {
    val id = text(idKey)
    val title = text(*titleKeys)
    return if (id.isNotBlank() && title.isNotBlank()) CloudEntry(id, title) else null
}
