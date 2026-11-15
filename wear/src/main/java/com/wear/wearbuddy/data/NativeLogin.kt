package com.wear.wearbuddy.data

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * WorkBuddy 原生登录（手机号 + 短信验证码），不依赖 WebView、不依赖浏览器。
 *
 * 官方手机端登录页使用 auth-v2 JSON 接口：
 *
 * ```
 * createSession: /auth-v2/realms/copilot/signin/phone/login  GET  ?platform=web  -> {session_state}
 * sendSms:       …/phone/sms    POST {session_state, phone}  -> {status, retry_after, expires_in}
 * verify:        …/phone/verify POST {session_state, phone, sms_code, skip_enterprise}
 *                                                              -> {status, ticket, nickname}
 * finalize:      …/finalize     GET  ?ticket=…&response_type=code&session_state=… -> {status, code}
 * shortSession:  …/session      POST {code, issuer, redirect_uri}  -> 换取会话 cookie
 * ```
 *
 * 登录完成后按官方页面的做法「由后续拉账号列表补种登录态」：用同一套 Keycloak SSO
 * 静默走一遍 OIDC，让网关把 `session` 种下来（[bootstrapConsoleSession]）。
 * 整条链路共用 [CloudSettings.cookies] 这一个浏览器式 cookie 罐。
 */
class NativeLogin(private val settings: CloudSettings) {

    /** 本次登录会话的 `session_state`。 */
    private var sessionState: String? = null

    /**
     * 第 1~2 步：建登录会话并发短信验证码。
     *
     * @return 重发倒计时秒数；若 Keycloak SSO 仍有效、无需短信即可换到新会话，
     *   直接完成登录并返回 [ALREADY_SIGNED_IN]。
     */
    fun sendCode(phone: String): Int {
        sessionState = null

        // SSO 仍可能让我们完全不用短信：先试一次静默登录。
        if (bootstrapConsoleSession() && verify()) {
            settings.saveSession(account = phone)
            return ALREADY_SIGNED_IN
        }

        val state = createAuthSession()
        android.util.Log.w("WB-LOGIN", "session_state=" + state.take(12) + "…")
        sessionState = state

        val payload = JSONObject()
            .put("session_state", state)
            .put("phone", phoneNumber(phone))
            .toString()
        val res = call("POST", authV2(SMS_PATH), payload, follow = true, jsonLike = true)
        android.util.Log.w(
            "WB-LOGIN",
            "sms HTTP=" + res.status + " body=" + res.body.take(200)
        )

        val json = runCatching { JSONObject(res.body) }.getOrNull()
            ?: throw IllegalStateException("验证码发送失败，请稍后重试")

        when (json.optString("status")) {
            "ok" -> return json.optInt("retry_after", DEFAULT_COOLDOWN)
                .takeIf { it > 0 } ?: DEFAULT_COOLDOWN

            "cooldown" -> throw IllegalStateException(
                "发送过于频繁，请 " + json.optInt("retry_after", DEFAULT_COOLDOWN) + " 秒后再试"
            )

            "captcha_required" -> throw IllegalStateException(
                "服务端要求人机安全验证，请稍后再试或更换网络"
            )
        }
        throw IllegalStateException(smsErrorText(json))
    }

    /**
     * 第 3~5 步：提交验证码 → 签发 code → 换会话 → 校验并保存。
     * 必须在 [sendCode] 之后调用（依赖同一次登录会话）。
     */
    fun submit(phone: String, code: String): String {
        val state = sessionState ?: throw IllegalStateException("请先获取验证码")

        val payload = JSONObject()
            .put("session_state", state)
            .put("phone", phoneNumber(phone))
            .put("sms_code", code.trim())
            .put("skip_enterprise", true)
            .toString()
        val res = call("POST", authV2(PHONE_VERIFY_PATH), payload, follow = true, jsonLike = true)
        val json = runCatching { JSONObject(res.body) }.getOrNull()

        // 官方前端的成功状态是 ready_to_login（带 ticket，还带 nickname）。
        val status = json?.optString("status").orEmpty()
        when (status) {
            "ready_to_login" -> Unit
            "oneid_required" -> throw IllegalStateException("该账号需要企业身份验证，请改用官方页面登录")
            "confirm_bind" -> throw IllegalStateException("该手机号需要确认绑定，请改用官方页面登录")
            else -> throw IllegalStateException(verifyErrorText(json, res.status))
        }
        val ticket = json?.optString("ticket").orEmpty()
        if (ticket.isBlank()) throw IllegalStateException("登录票据缺失，请重新获取验证码")
        // 顺手把云端返回的昵称记下来，设置页资料卡展示用。
        val nickname = json?.optString("nickname").orEmpty()

        // finalize 直接签发 OIDC authorization code
        val finalizeUrl = authV2(FINALIZE_PATH) + "?ticket=" + enc(ticket) +
            "&response_type=code&session_state=" + enc(state)
        val finalized = runCatching {
            JSONObject(call("GET", finalizeUrl, null, follow = true, jsonLike = true).body)
        }.getOrNull()
        val authCode = finalized?.optString("code").orEmpty()

        if (authCode.isNotBlank()) runCatching { shortSession(authCode) }

        // 官方页面在非企微场景下不调 short-session，而是「由后续拉账号列表补种登录态」
        if (!verify()) {
            bootstrapConsoleSession()
            if (!verify()) throw IllegalStateException("登录未生效，请稍后重试")
        }

        settings.saveSession(account = phone, nickname = nickname)
        return phone
    }

    /**
     * 静默续期：不需要验证码。网关会话过期或被顶掉后，只要 Keycloak 的 SSO cookie
     * 还有效，就能重走一次 OIDC 拿新的 `session`。
     */
    fun renew(): Boolean = runCatching {
        bootstrapConsoleSession() && verify()
    }.getOrDefault(false)

    // ---------- auth-v2 ----------

    /** 建登录会话：`GET {de}/phone/login?platform=web` → `{session_state}`。 */
    private fun createAuthSession(): String {
        val url = authV2(PHONE_LOGIN_PATH) + "?platform=" + PLATFORM
        val res = call("GET", url, null, follow = true, jsonLike = true)
        val state = runCatching { JSONObject(res.body).optString("session_state") }.getOrNull()
        if (state.isNullOrBlank()) throw IllegalStateException("无法发起登录，请检查网络后重试")
        return state
    }

    /** 拿 code 换会话 cookie。 */
    private fun shortSession(authCode: String) {
        val payload = JSONObject()
            .put("code", authCode)
            .put("issuer", base() + ISSUER_PATH)
            .put("redirect_uri", base() + CALLBACK_PATH)
            .toString()
        call("POST", authV2(SHORT_SESSION_PATH), payload, follow = true, jsonLike = true)
    }

    /**
     * 用 SSO 静默走一遍 OIDC，让网关把 `session` 种下来。
     * 关键：先丢掉失效的网关会话——实测网关只在完全没有 `session` / `session_2` 时
     * 才把入口 302 到 Keycloak。
     */
    private fun bootstrapConsoleSession(): Boolean = runCatching {
        settings.cookies.dropGatewaySession()
        val boot = call("GET", base() + OIDC_ENTRY, null, follow = false)
        val authUrl = boot.location
        if (authUrl.isNullOrBlank()) return@runCatching false
        call("GET", absolute(authUrl), null, follow = true)
        true
    }.getOrDefault(false)

    /** 用登录后的会话请求一次会话列表，确认登录真的生效。 */
    private fun verify(): Boolean {
        val res = call("GET", base() + SESSION_PROBE_PATH, null, follow = true)
        val envelope = runCatching { JSONObject(res.body).optInt("code", -1) }.getOrDefault(-1)
        return res.status in 200..299 && envelope == 0
    }

    /** 把短信通道的真实原因翻译成人话。 */
    private fun smsErrorText(json: JSONObject): String {
        val error = json.optString("error")
        val desc = json.optString("error_description")
        return when {
            desc.contains("PhoneNumberDailyLimit", ignoreCase = true) ->
                "该号码今日验证码次数已达上限，请明天再试"

            desc.contains("MinuteLimit", ignoreCase = true) ||
                desc.contains("ThirtySecondLimit", ignoreCase = true) ||
                desc.contains("SecondLimit", ignoreCase = true) -> "发送过于频繁，请稍后再试"

            error == "invalid_request" -> "手机号不可用，请检查后重试"
            error == "invalid_session" -> "登录会话已失效，请重新获取验证码"
            error == "account_unavailable" -> "该账号暂不可用，请更换手机号"
            desc.isNotBlank() -> "短信发送失败：$desc"
            error.isNotBlank() -> "短信发送失败（$error）"
            else -> "验证码发送失败，请稍后重试"
        }
    }

    /** 校验阶段的错误。 */
    private fun verifyErrorText(json: JSONObject?, status: Int): String {
        val error = json?.optString("error").orEmpty()
        val errno = json?.optString("errno").orEmpty()
        val desc = json?.optString("error_description").orEmpty()
        return when {
            error == "invalid_grant" || errno == "invalid_sms_code" -> "验证码错误或已失效，请重新输入"
            error == "invalid_session" || errno == "invalid_session" -> "登录会话已失效，请重新获取验证码"
            error == "account_unavailable" || errno == "account_unavailable" -> "该账号暂不可用，请更换手机号"
            desc.isNotBlank() -> "验证码校验失败（$desc）"
            errno.isNotBlank() -> "验证码校验失败（$errno）"
            error.isNotBlank() -> "验证码校验失败（$error）"
            json != null && json.optString("status").isNotBlank() ->
                "验证码校验失败（HTTP $status，status=${json.optString("status")}）"

            else -> "验证码校验失败（HTTP $status，响应无法解析）"
        }
    }

    private fun authV2(path: String) = base() + AUTH_V2 + path

    /** 统一加区号，与服务端一致（中国大陆 +86）。 */
    private fun phoneNumber(phone: String): String = "+86" + phone.trim().filter { it.isDigit() }

    // ---------- 内部：HTTP ----------

    private class Response(val status: Int, val location: String?, val body: String)

    private fun call(
        method: String,
        url: String,
        body: String?,
        follow: Boolean,
        jsonLike: Boolean = false
    ): Response {
        var target = url
        var verb = method
        var payload = body
        var hops = 0

        while (true) {
            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = verb
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("User-Agent", settings.userAgent)
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                if (jsonLike) {
                    setRequestProperty("Referer", settings.loginPageUrl())
                    setRequestProperty("Origin", originOf(base()))
                    setRequestProperty("Accept", "application/json, text/plain, */*")
                } else {
                    setRequestProperty("Referer", "${base()}/")
                    setRequestProperty("Accept", "text/html, application/json, */*")
                }
                settings.cookies.header(target)?.let { setRequestProperty("Cookie", it) }
                if (payload != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            val status: Int
            val text: String
            val location: String?
            try {
                if (payload != null) conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                status = conn.responseCode
                conn.headerFields?.let { settings.cookies.absorb(it, target) }
                location = conn.getHeaderField("Location")
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                text = stream?.let { s ->
                    BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() }
                }.orEmpty()
            } finally {
                conn.disconnect()
            }

            val redirect = status == 301 || status == 302 || status == 303 || status == 307 || status == 308
            if (!redirect || location.isNullOrBlank()) return Response(status, location, text)
            hops++
            if (!follow || hops > MAX_HOPS) return Response(status, location, text)

            if (status == 303 || (status == 302 && verb == "POST")) {
                verb = "GET"
                payload = null
            }
            target = URL(URL(target), location).toString()
        }
    }

    private fun base() = settings.baseUrl.trimEnd('/').ifBlank { CloudSettings.DEFAULT_BASE_URL }

    private fun absolute(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("/") -> base() + url
        else -> "${base()}/$url"
    }

    private fun originOf(url: String): String = runCatching {
        val u = URL(url)
        buildString {
            append(u.protocol).append("://").append(u.host)
            if (u.port > 0) append(':').append(u.port)
        }
    }.getOrDefault(url.trimEnd('/'))

    private fun enc(raw: String) = URLEncoder.encode(raw, "UTF-8")

    companion object {
        /** [sendCode] 的返回值：SSO 仍有效，已在无短信的情况下换到新会话。 */
        const val ALREADY_SIGNED_IN = 0

        private const val OIDC_ENTRY = "/console/accounts"
        private const val SESSION_PROBE_PATH = "/console/as/conversations/?type=cloud&page=1&size=1"

        private const val AUTH_V2 = "/auth-v2/realms/copilot/signin"
        private const val PHONE_LOGIN_PATH = "/phone/login"
        private const val SMS_PATH = "/phone/sms"
        private const val PHONE_VERIFY_PATH = "/phone/verify"
        private const val FINALIZE_PATH = "/finalize"
        private const val SHORT_SESSION_PATH = "/session"

        private const val PLATFORM = "web"
        private const val ISSUER_PATH = "/auth/realms/copilot"
        private const val CALLBACK_PATH = "/app"

        private const val DEFAULT_COOLDOWN = 60
        private const val CONNECT_TIMEOUT = 10_000
        private const val READ_TIMEOUT = 20_000
        private const val MAX_HOPS = 8
    }
}
