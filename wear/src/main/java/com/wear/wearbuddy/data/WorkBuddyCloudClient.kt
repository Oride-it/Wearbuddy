package com.wear.wearbuddy.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * WorkBuddy「云上模式」客户端。端点与信封取自 web 端（workbuddy.cn/app）前端 bundle，
 * 统一信封：`{code, msg, data}`，`code == 0`（或缺省）为成功。
 *
 * 链路：
 *  1. 下达任务   POST {base}/console/as/conversations/ body `{prompt, model, plugins[]}`
 *  2. 取接入点   GET  {base}/console/as/conversations/{id}/session
 *  3. 看进度     GET  {base}/console/as/conversations/{id}（状态兜底轮询）
 *  4. 下发/追问  ACP：GET `link`（Accept: text/event-stream）取响应头 `Acp-Connection-Id`，
 *                再 POST JSON-RPC 2.0 `session/prompt`；中断用 `session/cancel`。
 */
class WorkBuddyCloudClient(private val settings: CloudSettings) {

    /** 刚上传文件的原始字节，供随后的 prompt 内联图片用（只留最近几个）。 */
    private val uploadCache = object : LinkedHashMap<String, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > MAX_CACHED_UPLOADS
    }

    data class Session(
        val conversationId: String,
        val link: String,
        val token: String,
        val cwd: String?,
        val sandboxId: String? = null,
        /** 沙箱数据网关，附件要 POST 到这里的 `/files` 才能被 agent 读到。 */
        val dataEndpoint: String? = null,
        val sessionId: String = conversationId
    )

    data class RemoteConversation(
        val id: String,
        val title: String?,
        val status: String,
        val session: Session? = null,
        val model: String? = null,
        val artifacts: List<Artifact> = emptyList(),
        val ask: AskQuestion? = null
    )

    // ---------- 登录态 ----------

    fun probe(): Boolean = runCatching {
        callEnvelope("GET", "/console/as/conversations/?type=cloud&page=1&size=1", null)
    }.getOrNull() != null

    data class AccountProfile(val nickname: String, val avatarUrl: String)

    /** Same account endpoint and avatar fields used by the official web client. */
    fun fetchAccountProfile(): AccountProfile? {
        val accounts = callEnvelope("GET", "/console/accounts", null)?.optJSONArray("accounts") ?: return null
        val entries = (0 until accounts.length()).mapNotNull { accounts.optJSONObject(it) }
        val account = entries.firstOrNull { it.optString("type") == "personal" }
            ?: entries.firstOrNull() ?: return null
        fun field(name: String) = account.optString(name).trim().takeUnless { it == "null" }.orEmpty()
        val avatar = field("avatarUrl").ifBlank { field("avatar_url") }
        val url = avatar.takeIf { it.isNotBlank() }?.let { URL(URL(base() + "/"), it) }
        return AccountProfile(field("nickname"), url?.takeIf { it.protocol == "https" }?.toString().orEmpty())
    }

    /** Growth Center reads the account profile, energy balance and task list separately. */
    fun fetchGrowthPlan(): GrowthPlan {
        val profile = checkNotNull(callEnvelope("GET", "/v2/activity/growth/profile", null))
        val energy = checkNotNull(callEnvelope("GET", "/activity/growth/energy", null))
        val hasBuddy = runCatching {
            toolData("GET", "/activity/growth/buddy/list").optJSONArray("buddies")?.length()?.let { it > 0 }
        }.getOrNull() == true
        val tasks = checkNotNull(
            callEnvelope("GET", "/v2/activity/growth/tasks", null)?.optJSONArray("tasks")
        )
        return GrowthPlan(
            level = profile.getInt("level"),
            levelName = profile.optString("level_name").takeUnless { it == "null" }.orEmpty(),
            completed = profile.getInt("completed"),
            total = profile.getInt("total"),
            energy = energy.getInt("balance"),
            tasks = (0 until tasks.length()).mapNotNull { index ->
                val task = tasks.optJSONObject(index) ?: return@mapNotNull null
                val title = task.optString("title").takeIf { it.isNotBlank() && it != "null" }
                    ?: return@mapNotNull null
                val progress = task.optJSONObject("progress")
                GrowthTask(
                    title = title,
                    status = task.optString("accept_status"),
                    locked = task.optBoolean("locked") ||
                        (!hasBuddy && task.optString("task_code") !in setOf("first_buddy", "RichMeow_Chat")),
                    current = progress?.optInt("current") ?: 0,
                    target = progress?.optInt("target") ?: 0,
                    energy = task.optInt("reward_energy"),
                    code = task.optString("task_code"),
                    type = task.optString("task_type"),
                    hasReward = task.optBoolean("has_reward", true)
                )
            }
        )
    }

    internal fun updateGrowthTask(task: GrowthTask) {
        when {
            task.canAccept -> toolMutation(
                "POST", "/activity/growth/tasks/accept",
                JSONObject().put("task_codes", JSONArray().put(task.code))
            )
            task.canClaim -> toolMutation("POST", "/activity/growth/tasks/${segment(task.code)}/claim")
            else -> error("This growth task is not actionable")
        }
    }

    // ---------- 会话 ----------

    /** 下达任务：创建会话并可直接下发首轮 prompt。 */
    fun createConversation(prompt: String, model: String = DEFAULT_MODEL): RemoteConversation {
        val plugins = JSONArray().put(
            JSONObject().put("name", "weixinpay").put("marketplace", "codebuddy-builtin")
        )
        val body = JSONObject()
            .put("prompt", prompt.take(TITLE_LIMIT))
            .put("model", model)
            .put("plugins", plugins)
        val data = callEnvelope("POST", "/console/as/conversations/", body)
            ?: throw IllegalStateException("下达任务失败：响应缺少 data")
        val id = data.optString("id").ifBlank { data.optString("conversationId") }
        if (id.isBlank()) throw IllegalStateException("下达任务失败：响应缺少会话 ID")
        return RemoteConversation(
            id = id,
            title = prompt.take(TITLE_LIMIT),
            status = "RUNNING",
            session = parseSession(id, data.optJSONObject("session"))
        )
    }

    /** 取会话接入点（长连接地址与用途令牌）。 */
    fun fetchSession(id: String): Session {
        val data = callEnvelope("GET", "/console/as/conversations/${enc(id)}/session", null)
            ?: throw IllegalStateException("获取会话接入点失败")
        return parseSession(id, data) ?: throw IllegalStateException("会话接入点缺少 link/token")
    }

    /** 查会话详情（轮询用）。 */
    fun getConversation(id: String): RemoteConversation? {
        val data = callEnvelope("GET", "/console/as/conversations/${enc(id)}", null) ?: return null
        return parseConversation(data, data.toString())
    }

    /** 会话列表（云上模式）。 */
    fun listConversations(size: Int = 30, title: String = ""): List<RemoteConversation> {
        val sort = try {
            URLEncoder.encode("""{"orderBy":"updatedAt","order":"desc"}""", "UTF-8")
        } catch (e: Exception) {
            "%7B%22orderBy%22%3A%22updatedAt%22%2C%22order%22%3A%22desc%22%7D"
        }
        val origins = URLEncoder.encode(CONVERSATION_ORIGINS, "UTF-8")
        val titleFilter = if (title.isBlank()) "" else "&title=${enc(title)}"
        val path = "/console/as/conversations/?type=cloud&page=1&size=$size" +
            "&conversationOrigin=$origins&sort=$sort$titleFilter"
        val data = callEnvelope("GET", path, null) ?: return emptyList()
        val array = data.optJSONArray("conversations") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i -> parseConversation(array.optJSONObject(i)) }
    }

    fun renameConversation(id: String, title: String) {
        toolMutation("POST", "/console/as/conversations/${segment(id)}", JSONObject().put("title", title))
    }

    fun archiveConversation(id: String) {
        callEnvelope("POST", "/console/as/conversations/${enc(id)}/archive", null)
    }

    fun deleteConversation(id: String) {
        callEnvelope(
            "POST",
            "/console/as/conversations/${enc(id)}/delete",
            JSONObject().put("id", id)
        )
    }

    /** 已授权 Agent（对应 Web 端专家列表的 granted 接口）。 */
    fun listAgents(): List<RemoteAgent> {
        val data = callEnvelope(
            "GET",
            "/v2/user/cloudagent/agents/granted?page=1&pageSize=50&source=usable",
            null
        ) ?: return emptyList()
        val array = data.optJSONArray("items") ?: data.optJSONArray("agents") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val role = o.optString("agentRole")
            if (role.isNotBlank() && role != "specialist") return@mapNotNull null
            val id = o.optString("agentId").ifBlank { o.optString("id") }
            if (id.isBlank() || id.startsWith("wecom_")) return@mapNotNull null
            val name = sequenceOf(
                o.optString("agentName"),
                o.optString("name"),
                o.optString("displayName"),
                id
            ).firstOrNull { it.isNotBlank() } ?: id
            RemoteAgent(
                id = id,
                name = name,
                description = sequenceOf(
                    o.optString("descriptionZh"),
                    o.optString("description"),
                    o.optString("summary")
                ).firstOrNull { it.isNotBlank() && it != "null" },
                enabled = !o.has("enabled") || o.optBoolean("enabled", true)
            )
        }
    }

    /** 每日签到状态；Web 端用 checkin-activity-status，旧接口作为兜底。 */
    fun checkinStatus(): CheckinInfo {
        val primary = runCatching {
            callEnvelope("POST", "/billing/meter/checkin-activity-status", JSONObject())
        }.getOrElse { if (it is SessionExpiredException) throw it else null }
        val data = primary
            ?: callEnvelope("POST", "/billing/meter/checkin-status", JSONObject())
            ?: return CheckinInfo()
        return data.toCheckinInfo()
    }

    /** 领取每日签到积分。 */
    fun claimCheckin(): CheckinInfo {
        val data = callEnvelope("POST", "/billing/meter/daily-checkin", JSONObject())
            ?: return CheckinInfo(todayCheckedIn = true)
        return data.toCheckinInfo().copy(todayCheckedIn = true)
    }

    private fun JSONObject.toCheckinInfo(): CheckinInfo {
        fun int(vararg keys: String): Int = keys.firstNotNullOfOrNull { key ->
            if (has(key)) optInt(key, -1).takeIf { it >= 0 } else null
        } ?: 0
        return CheckinInfo(
            active = optBoolean("active", true),
            todayCheckedIn = optBoolean("today_checked_in") || optBoolean("todayCheckedIn"),
            streakDays = int("streak_days", "streakDays"),
            dailyCredit = int("daily_credit", "dailyCredit"),
            todayCredit = int("today_credit", "todayCredit", "credit")
        )
    }

    /**
     * 可选模型列表：`GET /console/enterprises/{enterpriseId|personal}/models`。
     * `agents[name=cli].models` 这一组才是账号已开通、真正能用的。
     */
    fun listModels(enterpriseId: String? = null): List<RemoteModel> {
        val ent = enterpriseId?.trim()?.takeIf { it.isNotBlank() } ?: CloudSettings.ENTERPRISE_PERSONAL
        val data = callEnvelope("GET", "/console/enterprises/${enc(ent)}/models", null) ?: return emptyList()
        val array = data.optJSONArray("models") ?: return emptyList()
        val enabled = cliModelIds(data)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            if (o.optBoolean("disabled")) return@mapNotNull null
            val modelId = o.optString("id").ifBlank { o.optString("model") }
            if (modelId.isBlank()) return@mapNotNull null
            if (enabled.isNotEmpty() && modelId !in enabled) return@mapNotNull null
            RemoteModel(
                id = modelId,
                name = o.optString("name").ifBlank { o.optString("displayName") }
                    .takeIf { it.isNotBlank() } ?: modelId,
                isDefault = o.optBoolean("isDefault"),
                credits = o.optString("credits").takeIf { it.isNotBlank() && it != "null" },
                description = o.optString("descriptionZh")
                    .takeIf { it.isNotBlank() } ?: o.optString("description").takeIf { it.isNotBlank() }
            )
        }
    }

    private fun cliModelIds(data: JSONObject): Set<String> {
        val agents = data.optJSONArray("agents") ?: return emptySet()
        val agent = (0 until agents.length())
            .mapNotNull { agents.optJSONObject(it) }
            .firstOrNull { it.optString("name") == "cli" }
            ?: return emptySet()
        val models = agent.optJSONArray("models") ?: return emptySet()
        return (0 until models.length())
            .map { models.optString(it) }
            .filter { it.isNotBlank() }
            .toSet()
    }

    /** History replay shares the prompt connection, so live updates cannot be stolen by a second reader. */
    fun loadHistory(session: Session): List<ChatMessage> = linkOf(session).snapshot().messages

    internal var onStreamChanged: ((String, AcpSnapshot) -> Unit)? = null

    internal fun streamSnapshot(id: String): AcpSnapshot? = links[id]?.snapshot()

    /** Send on the loaded connection and consume both live updates and the final RPC result. */
    fun sendPrompt(session: Session, text: String, attachments: List<Artifact> = emptyList()) {
        val model = settings.selectedModel.ifBlank { DEFAULT_MODEL }
        val blocks = JSONArray()
        val sourceBlocks = JSONArray()
        val sandboxPaths = mutableListOf<Pair<String, String>>()

        attachments.forEach { a ->
            val bytes = inlineBytes(a)
            val sandboxPath = bytes?.let {
                runCatching { uploadToSandbox(session, a.name, it) }.getOrNull()
            }
            val block = if (sandboxPath != null && bytes != null) {
                sandboxPaths += a.name to sandboxPath
                resourceLinkBlock(a, bytes.size, sandboxPath, model)
            } else {
                legacyContentBlock(a)
            }
            blocks.put(block)
            sourceBlocks.put(block)
        }

        if (text.isNotBlank()) {
            blocks.put(textBlock(text, model))
            sourceBlocks.put(JSONObject().put("type", "text").put("text", text))
        }
        if (sandboxPaths.isNotEmpty()) {
            blocks.put(sandboxPathBlock(sandboxPaths, model))
        }

        val meta = JSONObject()
            .put("conversationId", session.conversationId)
            .put("model", model)
            .put("extra", JSONObject().put("sourceContentBlocks", sourceBlocks))
        val params = JSONObject()
            .put("sessionId", session.sessionId)
            .put("cwd", session.cwd.orEmpty())
            .put("mcpServers", JSONArray())
            .put("prompt", blocks)
            .put("_meta", JSONObject().put("codebuddy.ai", meta))
        val link = linkOf(session)
        link.beginTurn(text)
        link.rpc(ACP_PROMPT, params, PROMPT_READ_TIMEOUT)
    }

    private fun textBlock(text: String, model: String): JSONObject = JSONObject()
        .put("type", "text")
        .put("text", text)
        .put("_meta", JSONObject().put("codebuddy.ai", JSONObject().put("model", model)))

    private fun resourceLinkBlock(
        a: Artifact,
        size: Int,
        sandboxPath: String,
        model: String
    ): JSONObject {
        val meta = JSONObject()
            .put("displayAsContext", false)
            .put("displayAsPhrase", true)
            .put("icon", "file")
            .put("blockStatus", "completed")
            .put("timestamp", System.currentTimeMillis())
            .put("filename", a.name)
            .put("fileName", a.name)
            .put("originalFilename", a.name)
            .put("size", size)
            .put("displayText", a.name)
            .put("title", a.name)
            .put("attachmentTransport", "cloud")
            .put("codebuddy.ai", JSONObject().put("sandboxPath", sandboxPath).put("model", model))
        return JSONObject()
            .put("type", "resource_link")
            .put("name", a.name)
            .put("title", a.name)
            .put("uri", presignedDownloadUrl(a) ?: a.uri)
            .put("mimeType", a.mimeType ?: "application/octet-stream")
            .put("_meta", meta)
    }

    private fun sandboxPathBlock(paths: List<Pair<String, String>>, model: String): JSONObject {
        val lines = paths.joinToString("\n") { (name, path) -> "- $name: $path" }
        val text = "<attachment_sandbox_paths>\n" +
            "以下附件已存放在沙箱本地路径，请直接读写这些路径。\n" +
            "$lines\n" +
            "</attachment_sandbox_paths>"
        return JSONObject()
            .put("type", "text")
            .put("text", text)
            .put(
                "_meta",
                JSONObject()
                    .put("codebuddy.ai/hiddenPromptContext", "attachment-sandbox-paths")
                    .put("codebuddy.ai", JSONObject().put("model", model))
            )
    }

    private fun legacyContentBlock(a: Artifact): JSONObject {
        val mime = a.mimeType.orEmpty()
        val bytes = inlineBytes(a)
        if (bytes != null && bytes.isNotEmpty()) {
            if (mime.startsWith("image/") && bytes.size <= MAX_INLINE_IMAGE) {
                return JSONObject()
                    .put("type", "image")
                    .put("mimeType", mime)
                    .put("data", Base64.getEncoder().encodeToString(bytes))
            }
            if (bytes.size <= MAX_INLINE_IMAGE) {
                val res = JSONObject().put("uri", a.uri).put("mimeType", mime)
                if (isTextLike(mime, a.name)) {
                    res.put("text", String(bytes, Charsets.UTF_8))
                } else {
                    res.put("blob", Base64.getEncoder().encodeToString(bytes))
                }
                return JSONObject().put("type", "resource").put("resource", res)
            }
        }
        return JSONObject()
            .put("type", "resource_link")
            .put("name", a.name)
            .put("title", a.name)
            .put("uri", presignedDownloadUrl(a) ?: a.uri)
            .apply { a.mimeType?.let { put("mimeType", it) } }
    }

    private fun isTextLike(mime: String, name: String): Boolean {
        if (mime.startsWith("text/") || mime.contains("json") || mime.contains("xml")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf(
            "txt", "md", "csv", "tsv", "log", "yaml", "yml", "json", "xml", "html", "css",
            "js", "ts", "kt", "java", "py", "go", "rs", "c", "h", "cpp", "sh", "sql", "ini", "conf"
        )
    }

    private fun inlineBytes(artifact: Artifact): ByteArray? =
        uploadCache[artifact.uri] ?: runCatching { downloadArtifact(artifact) }.getOrNull()

    /** 设定本会话实际使用的大模型（只对同一条连接上跑的 agent 生效）。 */
    fun setModel(session: Session, modelId: String) {
        linkOf(session).rpc(
            ACP_SET_MODEL,
            JSONObject().put("sessionId", session.sessionId).put("modelId", modelId)
        )
    }

    /** 中断当前执行。 */
    fun cancelTurn(session: Session) {
        linkOf(session).rpc(
            ACP_CANCEL,
            JSONObject().put("sessionId", session.sessionId),
            timeoutMs = CANCEL_TIMEOUT
        )
    }

    /** 回答 askuser 问题：走 ACP 扩展方法。 */
    fun resolveInterruption(session: Session, question: AskQuestion, answer: String) {
        val requestId = question.requestId ?: return
        val params = JSONObject()
            .put("sessionId", session.sessionId)
            .put("requestId", requestId)
            .put("toolCallId", requestId)
            .put("optionId", answer)
            .put("answer", answer)
        linkOf(session).rpc(ACP_RESOLVE_INTERRUPTION, params)
    }

    // ---------- 积分用量 ----------

    /** 拉取账号积分：资源包明细 + 今日请求次数。 */
    fun fetchUsage(): UsageSnapshot {
        val summary = runCatching {
            callEnvelope("POST", "/billing/meter/get-user-resource-summary", JSONObject())
        }.getOrNull()
        val fromSummary = summary?.let { packsOfSummary(it) }.orEmpty()
        if (fromSummary.isNotEmpty()) {
            return UsageSnapshot(packs = fromSummary, todayRequests = runCatching { todayRequests() }.getOrNull())
        }

        val accounts = mutableListOf<JSONObject>()
        runCatching {
            val data = callEnvelope(
                "POST", "/billing/meter/get-user-resource",
                JSONObject().put("PageNumber", 1).put("PageSize", USAGE_PACK_LIMIT)
            )
            accounts += accountsOf(data)
        }
        if (accounts.isEmpty()) {
            runCatching {
                val data = callEnvelope(
                    "POST", "/billing/meter/get-user-resource-paid-packages",
                    JSONObject()
                        .put("PageNumber", 1)
                        .put("PageSize", USAGE_PACK_LIMIT)
                        .put("PackageCodes", JSONArray(PAID_PACKAGE_CODES))
                        .put("Status", JSONArray(listOf(0, 3)))
                )
                accounts += accountsOf(data)
            }
        }
        if (accounts.isEmpty()) throw IllegalStateException("积分接口未返回资源包")

        val packs = accounts.distinctBy { it.toString() }.mapNotNull { o ->
            val total = numberOf(o, "Total", "total", "TotalCapacity", "Capacity", "capacity")
            val left = numberOf(o, "left", "Left", "RemainCapacity", "Remain", "Remaining", "remain", "Balance")
            if (total == null && left == null) return@mapNotNull null
            UsagePack(
                name = o.optString("PackageName").ifBlank { packageLabel(o.optString("PackageCode")) },
                total = total ?: 0.0,
                left = left ?: 0.0,
                status = o.optString("Status").takeIf { it.isNotBlank() },
                expireAt = o.optString("ExpiredTime").ifBlank { o.optString("DeductionEndTime") }
                    .takeIf { it.isNotBlank() }
            )
        }
        if (packs.isEmpty()) throw IllegalStateException("积分接口字段无法识别")

        return UsageSnapshot(packs = packs, todayRequests = runCatching { todayRequests() }.getOrNull())
    }

    private fun packsOfSummary(data: JSONObject): List<UsagePack> {
        val arr = data.optJSONArray("Packages") ?: data.optJSONArray("packages") ?: return emptyList()
        val subscription = data.optString("SubscriptionPackageName")
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val total = numberOf(o, "CycleTotalCapacity", "TotalCapacity")
            val left = numberOf(o, "CycleRemainCapacity", "RemainCapacity")
            val used = numberOf(o, "CycleUsedCapacity", "UsedCapacity")
            if (total == null && left == null) return@mapNotNull null
            val code = o.optString("PackageCode")
            UsagePack(
                name = subscription.ifBlank { packageLabel(code) },
                total = total ?: ((left ?: 0.0) + (used ?: 0.0)),
                left = left ?: ((total ?: 0.0) - (used ?: 0.0))
            )
        }
    }

    private fun packageLabel(code: String): String {
        val number = Regex("""code_(\d{3})""").find(code)?.groupValues?.get(1)
        return when (number) {
            "001" -> "免费额度"
            "002", "003", "004" -> "专业版"
            "006" -> "赠送积分"
            "039", "040" -> "试用额度"
            else -> if (number != null) "积分包 $number" else "积分包"
        }
    }

    private fun todayRequests(): Int? {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val end = fmt.format(Date())
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
        val body = JSONObject()
            .put("startTime", fmt.format(calendar.time))
            .put("endTime", end)
            .put("pageNum", 1)
            .put("pageSize", 1)
        val data = callEnvelope("POST", "/billing/meter/get-user-daily-usage", body) ?: return null
        (data.opt("total") as? Number)?.let { return it.toInt() }
        return data.optJSONArray("data")?.length() ?: data.optJSONArray("list")?.length()
    }

    private fun accountsOf(node: Any?): List<JSONObject> = when (node) {
        is JSONObject -> {
            val direct = node.optJSONArray("Accounts") ?: node.optJSONArray("accounts")
            if (direct != null) {
                (0 until direct.length()).mapNotNull { direct.optJSONObject(it) }
            } else {
                node.keys().asSequence().mapNotNull { accountsOf(node.opt(it)) }.firstOrNull().orEmpty()
            }
        }

        is JSONArray -> (0 until node.length()).asSequence()
            .mapNotNull { accountsOf(node.opt(it)) }.firstOrNull().orEmpty()

        else -> emptyList()
    }

    private fun numberOf(o: JSONObject, vararg keys: String): Double? {
        keys.forEach { key ->
            val d = when (val v = o.opt(key)) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull()
                else -> null
            }
            if (d != null && d.isFinite()) return d
        }
        return null
    }

    // ---------- 产物上传 / 下载 ----------

    fun uploadFile(name: String, mimeType: String, bytes: ByteArray): Artifact {
        val objectKey = "uploads/${System.currentTimeMillis()}-${randomTag()}-${safeFileName(name)}"
        val item = presign(listOf(objectKey)).firstOrNull()
            ?: throw IllegalStateException("上传失败：未取得预签名地址")
        val uploadUrl = item.optString("upload_url").ifBlank { item.optString("url") }
        if (uploadUrl.isBlank()) throw IllegalStateException("上传失败：响应缺少 upload_url")
        val conn = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = TRANSFER_TIMEOUT
            doOutput = true
            setRequestProperty("Content-Type", "application/octet-stream")
        }
        try {
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("上传失败：HTTP $code")
        } finally {
            conn.disconnect()
        }
        val artifact = Artifact(name = name, uri = "agent://files/$objectKey", mimeType = mimeType)
        if (bytes.size <= MAX_INLINE_IMAGE) uploadCache[artifact.uri] = bytes
        return artifact
    }

    private fun uploadToSandbox(session: Session, name: String, bytes: ByteArray): String? {
        val endpoint = session.dataEndpoint?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        val safe = safeFileName(name)
        if (safe.isBlank()) return null
        val path = "/root/uploads/wb-watch-${System.currentTimeMillis()}/$safe"
        val boundary = "----WbWatchBoundary${System.nanoTime()}"
        val head = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"$safe\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val conn = (URL("$endpoint/files?path=${enc(path)}&username=root").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = TRANSFER_TIMEOUT
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("x-access-token", session.token)
            setRequestProperty("Origin", base())
            setRequestProperty("Accept", "*/*")
        }
        return try {
            conn.outputStream.use { out ->
                out.write(head)
                out.write(bytes)
                out.write(tail)
            }
            if (conn.responseCode !in 200..299) return null
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONArray(text).optJSONObject(0)?.optString("path")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun safeFileName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\r\\n\"]"), "_")
            .ifBlank { "attachment" }

    private fun randomTag(): String =
        java.util.UUID.randomUUID().toString().replace("-", "").take(7)

    fun downloadArtifact(artifact: Artifact, conversationId: String? = null): ByteArray {
        val path = ArtifactKeys.sandboxPath(artifact.uri)
        if (path != null) {
            require(!conversationId.isNullOrBlank()) { "读取沙箱产物需要所属会话" }
            repeat(2) { attempt ->
                val session = fetchSession(conversationId)
                val endpoint = session.dataEndpoint?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("会话缺少沙箱文件服务地址")
                try {
                    return SandboxFiles.read(endpoint, session.token, path)
                } catch (e: SandboxFiles.ReadFailure) {
                    if (attempt == 1 || e.status !in setOf(401, 403, 404)) throw e
                }
            }
            error("沙箱文件读取失败")
        }
        val urls = LinkedHashSet<String>()
        artifact.uri.takeIf { it.startsWith("https://") }?.let(urls::add)
        artifact.downloadUrl?.takeIf { it.startsWith("https://") }?.let(urls::add)
        if (urls.isEmpty()) {
            presignedDownloadUrls(artifact).forEach(urls::add)
            if (urls.isEmpty()) throw IllegalStateException("下载失败：未取得预签名地址")
        }

        var lastError = "未知错误"
        // 产物链接有时效：download_url 过期就重新预签名一次再试，不再直接报 404。
        repeat(2) { attempt ->
            for (url in urls.toList()) {
                val (code, bytes) = fetchArtifact(url)
                if (bytes != null) return bytes
                lastError = "HTTP $code"
            }
            if (attempt == 0) {
                presignedDownloadUrls(artifact).forEach { if (it !in urls) urls.add(it) }
            }
        }
        throw IllegalStateException("下载失败：$lastError")
    }

    private fun fetchArtifact(url: String): Pair<Int, ByteArray?> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = TRANSFER_TIMEOUT
        }
        return try {
            val code = conn.responseCode
            if (code in 200..299) code to conn.inputStream.use { it.readBytes() }
            else code to null
        } finally {
            conn.disconnect()
        }
    }

    private fun presignedDownloadUrl(artifact: Artifact): String? =
        presignedDownloadUrls(artifact).firstOrNull()

    /**
     * 对象键在 Web 端存在“原样 / 解码 / 重新编码”几种写法，一次把变体都签名，
     * 下载时逐个尝试，避免中文或空格文件名因为键不一致而 404。
     */
    private fun presignedDownloadUrls(artifact: Artifact): List<String> {
        val keys = ArtifactKeys.variantsOf(artifact.uri)
        if (keys.isEmpty()) return emptyList()
        return runCatching {
            presign(keys).mapNotNull { item ->
                item.optString("download_url").takeIf { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    private fun presign(paths: List<String>): List<JSONObject> {
        val body = JSONObject().put("object_keys", JSONArray(paths))
        val data = callEnvelope("POST", "/console/as/support/presigned_url", body)
            ?: throw IllegalStateException("预签名请求失败：响应缺少 data")
        val items = data.optJSONArray("items") ?: data.optJSONObject("data")?.optJSONArray("items")
        return items?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }.orEmpty()
    }

    // ---------- 内部：HTTP ----------

    private fun callEnvelope(method: String, path: String, body: JSONObject?): JSONObject? {
        val root = featureResponse(method, path, body, renewOnUnauthorized = true) ?: return null
        return root.optJSONObject("data") ?: root
    }

    internal fun featureResponse(
        method: String,
        path: String,
        body: JSONObject? = null,
        productCode: Boolean = false,
        renewOnUnauthorized: Boolean = false
    ): JSONObject? {
        val raw = request(method, base() + path, body?.toString(), json = true,
            productCode = productCode, renewOnUnauthorized = renewOnUnauthorized)
        val root = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val code = root.opt("code")
        if ((code is Number && code.toLong() != 0L) || root.opt("success") == false) {
            val schedulerError = root.optJSONObject("error")?.optString("message").orEmpty()
            throw IllegalStateException(root.optString("msg").ifBlank {
                schedulerError.ifBlank { "云端返回 code=$code" }
            })
        }
        return root
    }

    private fun request(
        method: String, url: String, body: String?, json: Boolean, productCode: Boolean = false,
        renewOnUnauthorized: Boolean = true
    ): String? {
        var result = execute(method, url, body, json, productCode, renewOnUnauthorized)
        var attempt = 0
        // 登录态失效有两种表现：302 被重定向回登录页，或直接 401。
        while (renewOnUnauthorized && (result.code == HTTP_UNAUTHORIZED || result.code in 300..399) &&
            attempt < MAX_AUTH_ATTEMPTS && renewSession()
        ) {
            result = execute(method, url, body, json, productCode, renewOnUnauthorized)
            attempt++
        }
        if (result.code !in 200..299) {
            val msg = result.text?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.optString("msg")?.takeIf { it.isNotBlank() }
            if (!renewOnUnauthorized && (result.code in 300..399 || result.code == 401 || result.code == 403)) {
                throw CloudAccessException()
            }
            if (result.code == HTTP_UNAUTHORIZED || result.code in 300..399) {
                throw SessionExpiredException(msg ?: "登录已过期")
            }
            throw IllegalStateException(msg ?: "HTTP ${result.code}")
        }
        return if (result.code == 204) "{}" else result.text
    }

    private fun execute(
        method: String, url: String, body: String?, json: Boolean, productCode: Boolean,
        absorbErrorCookies: Boolean
    ): HttpResult {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            settings.cookies.header(url)?.let { setRequestProperty("Cookie", it) }
            setRequestProperty("User-Agent", settings.userAgent)
            setRequestProperty("Referer", base() + "/app")
            setRequestProperty("Accept", if (json) "application/json, text/plain, */*" else "*/*")
            setRequestProperty("Accept-Language", if (settings.language == CloudSettings.LANG_EN) "en-US" else "zh-CN")
            setRequestProperty(REQUEST_ID_HEADER, nextRequestId())
            setRequestProperty("X-Submitted-From", SUBMITTED_FROM)
            setRequestProperty("X-Client-Platform", CLIENT_PLATFORM)
            if (productCode) setRequestProperty("X-Product-Code", "workbuddy")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (absorbErrorCookies || code in 200..299) {
                settings.cookies.absorb(conn.headerFields, url)
            }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            return HttpResult(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun renewSession(): Boolean {
        val now = System.currentTimeMillis()
        synchronized(renewLock) {
            if (now - lastRenewAt < RENEW_THROTTLE_MS) return false
            if (now - lastRenewFailedAt < RENEW_BACKOFF_MS) return false
            lastRenewAt = now
        }
        val ok = runCatching { NativeLogin(settings).renew() }.getOrDefault(false)
        if (!ok) synchronized(renewLock) { lastRenewFailedAt = System.currentTimeMillis() }
        return ok
    }

    private class HttpResult(val code: Int, val text: String?)

    private var lastRenewAt = 0L
    private var lastRenewFailedAt = 0L

    // ---------- 内部：常驻 ACP 连接 ----------

    inner class AcpLink internal constructor(
        val sessionId: String,
        private val acpSessionId: String,
        private val url: String,
        private val bearer: String,
        private val connectionId: String,
        private val sessionToken: String,
        private val raw: HttpURLConnection
    ) {
        private val events = AcpEvents(acpSessionId, sessionId)
        private val pending = java.util.concurrent.ConcurrentHashMap<Int, PendingRpc>()
        @Volatile private var replaying = true

        internal fun snapshot(): AcpSnapshot = events.snapshot()

        internal fun beginTurn(text: String) {
            events.beginTurn(text)
            publish()
        }

        private fun publish() {
            if (!replaying) onStreamChanged?.invoke(sessionId, events.snapshot())
        }

        internal fun receive(data: String) {
            val root = runCatching { JSONObject(data) }.getOrNull() ?: return
            val id = root.optInt("id", -1)
            if (!root.has("method")) {
                val request = pending[id] ?: return
                request.response = root
                if (request.method == ACP_PROMPT) events.finishTurn(root.optJSONObject("result"), root.optJSONObject("error"))
                if (request.method == ACP_LOAD) replaying = false
                publish()
                request.done.countDown()
            } else if (events.accept(root, replaying)) publish()
            lastUsedAt = System.currentTimeMillis()
        }

        internal fun initialize(session: Session) {
            rpc("initialize", JSONObject().put("protocolVersion", 1).put("clientCapabilities", JSONObject()
                .put("fs", JSONObject().put("readTextFile", false).put("writeTextFile", false))))
            rpc(ACP_LOAD, JSONObject().put("sessionId", session.sessionId)
                .put("cwd", session.cwd ?: "/workspace").put("mcpServers", JSONArray()))
        }

        @Volatile
        private var closed = false

        @Volatile
        var lastUsedAt: Long = System.currentTimeMillis()
            private set

        val alive: Boolean get() = !closed

        fun rpc(method: String, params: JSONObject, timeoutMs: Int = READ_TIMEOUT) {
            check(alive) { "ACP 连接已关闭" }
            if (method == ACP_CANCEL) {
                postRpc(url, bearer, connectionId, sessionToken, method, params, timeoutMs, null, ::receive)
                return
            }
            val id = requestSeq.incrementAndGet()
            val request = PendingRpc(method)
            pending[id] = request
            try {
                postRpc(url, bearer, connectionId, sessionToken, method, params, timeoutMs, id, ::receive)
                if (!request.done.await(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    throw java.net.SocketTimeoutException("ACP $method 等待响应超时")
                }
                request.response?.optJSONObject("error")?.let {
                    throw IllegalStateException(it.optString("message").ifBlank { "ACP $method 失败" })
                }
                check(request.response != null) { "ACP 连接已断开，请重新打开任务同步" }
                lastUsedAt = System.currentTimeMillis()
            } finally {
                pending.remove(id)
            }
        }

        fun close() {
            closed = true
            pending.values.forEach { it.done.countDown() }
            runCatching { raw.disconnect() }
        }
    }

    private class PendingRpc(val method: String) {
        val done = java.util.concurrent.CountDownLatch(1)
        @Volatile var response: JSONObject? = null
    }

    private val links = java.util.concurrent.ConcurrentHashMap<String, AcpLink>()

    @Synchronized private fun linkOf(session: Session): AcpLink {
        links[session.conversationId]?.takeIf { it.alive }?.let { return it }
        // A waking sandbox may not serve ACP yet; refresh expired credentials between safe handshake attempts.
        var current = session
        var ready: AcpLink? = null
        var failure: Exception? = null
        for (attempt in 0..2) {
            try {
                if (attempt > 0) {
                    Thread.sleep(500L * attempt)
                    current = fetchSession(session.conversationId)
                }
                ready = openLink(current)
                break
            } catch (e: Exception) { failure = e }
        }
        val link = ready ?: throw checkNotNull(failure)
        links[session.conversationId] = link
        try {
            link.initialize(current)
            return link
        } catch (t: Throwable) {
            links.remove(session.conversationId, link)
            link.close()
            throw t
        }
    }

    fun closeLink(id: String) {
        links.remove(id)?.close()
    }

    fun closeAllLinks() {
        links.values.forEach { it.close() }
        links.clear()
    }

    fun trimLinks(maxIdleMs: Long) {
        val now = System.currentTimeMillis()
        links.values
            .filter { !it.alive || now - it.lastUsedAt > maxIdleMs }
            .forEach { link -> if (links.remove(link.sessionId, link)) link.close() }
    }

    private fun openLink(session: Session): AcpLink {
        val url = normalizeLink(session.link)
        val raw = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = LINK_READ_TIMEOUT
            setRequestProperty("Authorization", "Bearer ${session.token}")
            setRequestProperty("User-Agent", settings.userAgent)
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Origin", base())
            setRequestProperty("Cache-Control", "no-cache")
            useCaches = false
        }
        val connectionId: String
        val sessionToken: String
        try {
            val status = raw.responseCode
            check(status in 200..299) { "ACP 连接失败：HTTP $status，请重试或重新打开会话" }
            check(raw.contentType.orEmpty().contains("text/event-stream", ignoreCase = true)) {
                "ACP 接入点未返回事件流，请重新打开会话"
            }
            connectionId = raw.getHeaderField(ACP_CONNECTION_HEADER)?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("ACP 服务未返回连接标识，请稍后重试")
            sessionToken = raw.getHeaderField(ACP_SESSION_TOKEN_HEADER)?.trim().orEmpty()
        } catch (t: Throwable) {
            runCatching { raw.disconnect() }
            throw t
        }
        val link = AcpLink(session.conversationId, session.sessionId, url, session.token, connectionId, sessionToken, raw)
        // Decode every event on the same stream used by session/load and session/prompt.
        Thread({
            runCatching {
                BufferedReader(InputStreamReader(raw.inputStream, Charsets.UTF_8)).use { reader ->
                    readSse(reader, link::receive)
                }
            }
            link.close()
        }, "acp-link-${session.conversationId}").apply { isDaemon = true }.start()
        return link
    }

    fun hasLink(id: String): Boolean = links[id]?.alive == true

    private fun postRpc(
        url: String,
        token: String,
        connectionId: String,
        acpSessionToken: String,
        method: String,
        params: JSONObject,
        readTimeout: Int = READ_TIMEOUT,
        requestId: Int?,
        receive: (String) -> Unit
    ) {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
        requestId?.let { payload.put("id", it) }

        val post = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT
            this.readTimeout = readTimeout
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", settings.userAgent)
            if (acpSessionToken.isNotBlank()) setRequestProperty(ACP_SESSION_TOKEN_HEADER, acpSessionToken)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json, text/event-stream")
            setRequestProperty(ACP_CONNECTION_HEADER, connectionId)
        }
        try {
            post.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = post.responseCode
            if (code !in 200..299) throw IllegalStateException("ACP $method 失败：HTTP $code")
            post.inputStream?.let { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                    if (post.contentType.orEmpty().contains("text/event-stream", ignoreCase = true)) {
                        readSse(reader, receive)
                    } else {
                        val body = reader.readText()
                        if (body.isNotBlank()) receive(body)
                    }
                }
            }
        } finally {
            post.disconnect()
        }
    }

    // ---------- 内部：解析 ----------

    private fun parseSession(id: String, o: JSONObject?): Session? {
        if (o == null) return null
        val link = o.optString("link").ifBlank { o.optString("endpoint") }.ifBlank { o.optString("url") }
        val token = o.optString("token")
        if (link.isBlank() || token.isBlank()) return null
        return Session(
            conversationId = id,
            link = link,
            token = token,
            cwd = o.optString("cwd").takeIf { it.isNotBlank() },
            sandboxId = o.optString("sandboxId").takeIf { it.isNotBlank() },
            dataEndpoint = SandboxFiles.dataEndpoint(o),
            sessionId = o.optString("sessionId").ifBlank { o.optString("session_id") }.ifBlank { id }
        )
    }

    private fun parseConversation(o: JSONObject?, raw: String = o?.toString().orEmpty()): RemoteConversation? {
        if (o == null) return null
        val id = o.optString("id").ifBlank { o.optString("conversationId") }
        if (id.isBlank()) return null
        val session = o.optJSONObject("session")
        val status = sequenceOf(
            (o.opt("sessionStatus") as? String).orEmpty(),
            session?.optString("sessionStatus").orEmpty(),
            session?.optString("status").orEmpty(),
            session?.optString("state").orEmpty(),
            o.optJSONObject("status")?.optString("status").orEmpty(),
            (o.opt("status") as? String).orEmpty(),
            (o.opt("lifecycle") as? String).orEmpty()
        ).firstOrNull { it.isNotBlank() }.orEmpty().uppercase()
        return RemoteConversation(
            id = id,
            title = o.optString("name").takeIf { it.isNotBlank() }
                ?: o.optString("title").takeIf { it.isNotBlank() },
            status = status,
            model = sequenceOf(
                o.optString("model"),
                o.optJSONObject("status")?.optString("model").orEmpty(),
                session?.optString("model").orEmpty()
            ).firstOrNull { it.isNotBlank() },
            session = parseSession(id, session),
            artifacts = artifactsOf(raw),
            ask = askOf(o, status)
        )
    }

    private fun artifactsOf(raw: String): List<Artifact> =
        ARTIFACT_URI.findAll(raw)
            .map { ArtifactKeys.trimTail(it.value) }
            .filter { it.length > "agent://files/".length }
            .distinct()
            .map { uri ->
                val name = ArtifactKeys.decodeSegment(uri.substringAfterLast('/')).ifBlank { "产物文件" }
                Artifact(name = name, uri = uri)
            }
            .toList()

    private fun askOf(o: JSONObject, status: String): AskQuestion? {
        val first = findQuestions(o)?.optJSONObject(0)
        if (first != null) {
            val question = first.optString("question").ifBlank { first.optString("title") }
            if (question.isNotBlank()) {
                val options = first.optJSONArray("options")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val opt = arr.optJSONObject(i) ?: return@mapNotNull null
                        val label = opt.optString("label").ifBlank { opt.optString("value") }
                        if (label.isBlank()) null
                        else AskOption(label, opt.optString("description").takeIf { it.isNotBlank() })
                    }
                }.orEmpty()
                return AskQuestion(
                    question = question,
                    header = first.optString("header").takeIf { it.isNotBlank() },
                    options = options,
                    requestId = findInterruptionId(o)
                )
            }
        }
        return if (status in WAITING_STATES) AskQuestion("云端需要你补充信息后继续") else null
    }

    private fun findQuestions(node: Any?): JSONArray? = when (node) {
        is JSONObject -> node.optJSONArray("questions")?.takeIf { it.length() > 0 }
            ?: node.keys().asSequence().mapNotNull { findQuestions(node.opt(it)) }.firstOrNull()
        is JSONArray -> (0 until node.length()).asSequence().mapNotNull { findQuestions(node.opt(it)) }.firstOrNull()
        else -> null
    }

    private fun findInterruptionId(node: Any?): String? = when (node) {
        is JSONObject -> {
            val direct = node.optString("interruptionId").ifBlank { node.optString("requestId") }
            if (direct.isNotBlank()) direct
            else node.keys().asSequence().mapNotNull { findInterruptionId(node.opt(it)) }.firstOrNull()
        }
        is JSONArray -> (0 until node.length()).asSequence().mapNotNull { findInterruptionId(node.opt(it)) }.firstOrNull()
        else -> null
    }

    private fun base() = settings.baseUrl.trimEnd('/').ifBlank { CloudSettings.DEFAULT_BASE_URL }

    private fun normalizeLink(link: String) = link.replace(Regex("^http://"), "https://")

    private fun enc(raw: String) = URLEncoder.encode(raw, "UTF-8")

    private fun nextRequestId() = "wb-watch-${requestSeq.incrementAndGet()}"

    companion object {
        /** 兜底模型：账号没有显式选择时使用（官方端默认）。 */
        const val DEFAULT_MODEL = "hy4-preview"

        private val PAID_PACKAGE_CODES = listOf(
            "TCACA_code_001_PqouKr6QWV",
            "TCACA_code_002_AkiJS3ZHF5",
            "TCACA_code_006_DbXS0lrypC",
            "TCACA_code_029_6wCGEWquYy",
            "TCACA_code_030_BjSt89qTvr",
            "TCACA_code_035_ArVxJcGDsm",
            "TCACA_code_036_lupO5WgNdG",
            "TCACA_code_037_WxOD3MpI2o",
            "TCACA_code_038_OhvqZtiPKr",
            "TCACA_code_039_KRcQj7wUat",
            "TCACA_code_040_mi9rCYg46x"
        )

        private const val TITLE_LIMIT = 100
        private const val CONNECT_TIMEOUT = 8_000
        private const val READ_TIMEOUT = 15_000
        private const val PROMPT_READ_TIMEOUT = 10 * 60_000
        private const val TRANSFER_TIMEOUT = 60_000
        private const val LINK_READ_TIMEOUT = 10 * 60_000
        private const val CANCEL_TIMEOUT = 8_000

        private const val MAX_INLINE_IMAGE = 8 * 1024 * 1024
        private const val MAX_CACHED_UPLOADS = 4

        private const val HTTP_UNAUTHORIZED = 401
        private const val MAX_AUTH_ATTEMPTS = 2
        private const val RENEW_THROTTLE_MS = 5_000L
        private const val RENEW_BACKOFF_MS = 60_000L
        private val renewLock = Any()

        private const val ACP_CONNECTION_HEADER = "Acp-Connection-Id"
        private const val ACP_SESSION_TOKEN_HEADER = "acp-session-token"
        private const val ACP_PROMPT = "session/prompt"
        private const val ACP_CANCEL = "session/cancel"
        private const val ACP_SET_MODEL = "session/set_model"
        private const val ACP_LOAD = "session/load"
        private const val ACP_RESOLVE_INTERRUPTION = "_codebuddy.ai/resolveInterruption"
        private const val REQUEST_ID_HEADER = "X-Request-ID"

        private val ARTIFACT_URI = Regex("""(?:agent://(?:files/)?|file:///(?:workspace|root)/)[^\s"'\\()\[\]{}<>，。；：！？、]+""")

        private val WAITING_STATES = setOf(
            "WAITING_USER_INPUT", "WAITING_INPUT", "REQUIRES_ACTION", "PAUSED", "BLOCKED"
        )
        private const val SUBMITTED_FROM = "web"
        private const val CLIENT_PLATFORM = "web"
        private const val USAGE_PACK_LIMIT = 100

        private const val CONVERSATION_ORIGINS =
            "agents,workbuddy-mp,workbuddy-app,openapi,workbuddy-deploy"


        private val requestSeq = AtomicInteger(0)
    }
}

/** 网关已不认当前登录态且静默续期失败，界面据此提示用户重新登录。 */
class SessionExpiredException(message: String) : RuntimeException(message)
