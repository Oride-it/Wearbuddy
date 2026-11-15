package com.wear.wearbuddy.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

private const val DOCUMENT_GATEWAY = "/console/agent-gateway/tdocs-app/api/v6/open/agent/manage"

internal fun WorkBuddyCloudClient.loadSpaceFiles(space: CloudEntry, page: Int): CloudPage {
    val batch = space.childIds.drop((page - 1) * 20).take(20)
    if (batch.isEmpty()) return CloudPage(emptyList())
    val requests = JSONArray()
    for (id in batch) {
        requests.put(JSONObject().put("pointer", JSONObject().put("id", id).put("table", "block")).put("version", -1))
    }
    val result = toolData(
        "POST", "/space/api/view/query/records",
        JSONObject().put("spaceId", space.id).put("requests", requests)
    )
    val blocks = result.getJSONObject("records").optJSONObject("block") ?: JSONObject()
    val entries = batch.mapNotNull { id ->
        val record = blocks.optJSONObject(id) ?: return@mapNotNull null
        val value = record.optJSONObject("value") ?: record
        val title = value.optJSONObject("props")?.text("title").orEmpty()
        if (title.isBlank()) null else CloudEntry(id, title, value.text("type"))
    }
    return CloudPage(entries, (page + 1).toString().takeIf { page * 20 < space.childIds.size })
}

internal fun WorkBuddyCloudClient.loadTencentDocuments(folder: String?, offset: Int, query: String): CloudPage {
    val searching = query.isNotBlank()
    val result = if (searching) {
        val body = JSONObject().put("pattern", query)
            .put("tag_info", JSONObject().put("tag_type", 1).put("from", offset).put("size", 20).put("limit", -1))
            .put("sort_rules", JSONObject().put("sort_by", 1).put("asc", false))
            .put("folder_filter", JSONObject().put("is_folder", false))
        if (folder != null) body.put("directory_file_id", folder)
        documentResult("$DOCUMENT_GATEWAY/search_file", body)
    } else {
        val body = JSONObject().put("list_type", 1).put("count", 20).put("offset", offset)
        if (folder != null) body.put("parent_id", folder)
        documentResult("$DOCUMENT_GATEWAY/list_file", body)
    }
    val files = if (searching) {
        result.rows("result").flatMap { group -> group.rows("file_data_list") }
    } else result.rows("files")
    val entries = files.mapNotNull { file ->
        val id = file.text("file_id")
        val title = file.text("name", "file_name")
        if (id.isBlank() || title.isBlank()) null else CloudEntry(
            id = id,
            title = title,
            summary = file.text("ext", "extension"),
            description = file.text("owner_nick", "creator_name"),
            isFolder = file.optBoolean("is_folder"),
            website = documentWebsite(file.text("doc_url", "tencent_doc_url"))
        )
    }
    val next = if (searching) {
        result.optInt("next").takeIf { !result.optBoolean("is_over") && it > offset }
    } else (offset + files.size).takeIf { files.isNotEmpty() && !result.optBoolean("finish", true) }
    return CloudPage(entries, next?.toString())
}

private fun WorkBuddyCloudClient.documentResult(path: String, body: JSONObject): JSONObject {
    val data = toolData("POST", path, body)
    check(data.optInt("retcode", -1) == 0)
    return data.getJSONObject("result")
}

private fun documentWebsite(url: String): String {
    val uri = runCatching { URI(if (url.startsWith("//")) "https:$url" else url) }.getOrNull() ?: return ""
    return if (uri.scheme == "https" && uri.userInfo == null && uri.host in setOf("docs.qq.com", "doc.weixin.qq.com")) {
        uri.toString()
    } else ""
}

internal fun WorkBuddyCloudClient.loadGrowthStreak(): CloudPage {
    val data = toolData("GET", "/activity/growth/streak")
    val streak = data.getJSONObject("streak")
    val days = streak.getInt("days")
    val tiers = data.optJSONObject("redemption_status")?.optJSONArray("tiers")
    val rewards = if (tiers == null) emptyList() else (0 until tiers.length()).mapNotNull { index ->
        val tier = tiers.optJSONObject(index) ?: return@mapNotNull null
        val name = tier.text("tier")
        if (name.isBlank()) null else CloudEntry(name, name, tier.text("status"))
    }
    return CloudPage(rewards, streakDays = days)
}
