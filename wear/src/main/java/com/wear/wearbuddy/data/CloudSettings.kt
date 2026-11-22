package com.wear.wearbuddy.data

import android.content.Context
import java.net.URLEncoder

/**
 * 云端登录态与本机偏好。手表独立运行，状态保存在本机（[Context.MODE_PRIVATE]）。
 *
 * 登录凭据（网关 `session` + Keycloak SSO 等一整套 cookie）由原生登录 [NativeLogin]
 * 收进 [cookies]，后续请求按路径手动下发 `Cookie` 头，不依赖 WebView / 系统 CookieManager。
 */
class CloudSettings(context: Context, val profileId: String = AccountStore.activeId(context)) {

    private val prefs = context.applicationContext
        .getSharedPreferences(if (profileId == "legacy") "wearbuddy" else "wearbuddy_account_$profileId", Context.MODE_PRIVATE)

    /** 浏览器式 cookie 罐：登录与云端接口共用，登录后长期存续。 */
    val cookies = CookieStore(context, if (profileId == "legacy") "wearbuddy" else "wearbuddy_account_$profileId")

    /** WorkBuddy 云上模式站点；登录页与 console 接口同在此域下。 */
    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty()
        set(value) = prefs.edit().putString(KEY_BASE_URL, value.trim()).apply()

    /** 是否已完成登录。 */
    var loggedIn: Boolean
        get() = prefs.getBoolean(KEY_LOGGED_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_LOGGED_IN, value).apply()

    /** 登录账号提示（手机号），仅用于界面展示。 */
    var accountHint: String
        get() = prefs.getString(KEY_ACCOUNT, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ACCOUNT, value.trim()).apply()

    /** 登录时云端返回的昵称；界面资料卡展示用。 */
    var nickname: String
        get() = prefs.getString(KEY_NICKNAME, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_NICKNAME, value.trim()).apply()

    /** 已登录且本机确实还留着凭据，才算真正可用。 */
    val isLoggedIn: Boolean get() = loggedIn && cookies.hasAny()

    /** 置顶按站点和账号隔离，仅保存在手表上。 */
    var pinnedSessions: Set<String>
        get() = prefs.getStringSet("session_pins:${baseUrl}:${accountHint}", emptySet()).orEmpty().toSet()
        set(value) = prefs.edit().putStringSet("session_pins:${baseUrl}:${accountHint}", value).apply()

    /** Keep explicit stops across process restarts, scoped to the current account and site. */
    var stoppedSessions: Set<String>
        get() = prefs.getStringSet("session_stops:${baseUrl}:${accountHint}", emptySet()).orEmpty().toSet()
        set(value) = prefs.edit().putStringSet("session_stops:${baseUrl}:${accountHint}", value.toSet()).apply()

    var hasDraftChat: Boolean
        get() = prefs.getBoolean("empty_chat:${baseUrl}:${accountHint}", false)
        set(value) = prefs.edit().putBoolean("empty_chat:${baseUrl}:${accountHint}", value).apply()

    /** 当前选用的模型 ID（空表示用云端默认）。 */
    var selectedModel: String
        get() = prefs.getString(KEY_MODEL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_MODEL, value.trim()).apply()

    /** 账号所属企业 ID；个人账号为 "personal"。用于模型列表接口。 */
    var enterpriseId: String
        get() = prefs.getString(KEY_ENTERPRISE, ENTERPRISE_PERSONAL).orEmpty().ifBlank { ENTERPRISE_PERSONAL }
        set(value) = prefs.edit().putString(KEY_ENTERPRISE, value.trim()).apply()

    /** 息屏时是否停止轮询（极致省流）。 */
    var extremeSaver: Boolean
        get() = prefs.getBoolean(KEY_EXTREME_SAVER, false)
        set(value) = prefs.edit().putBoolean(KEY_EXTREME_SAVER, value).apply()

    /** 主题色（blue / green / purple / amber）。 */
    var themeColor: String
        get() = prefs.getString(KEY_THEME_COLOR, THEME_BLUE).orEmpty().ifBlank { THEME_BLUE }
        set(value) = prefs.edit().putString(KEY_THEME_COLOR, value).apply()

    /** 界面语言（zh / en）。 */
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, LANG_ZH).orEmpty().ifBlank { LANG_ZH }
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    /** 字体大小（small / standard / large）。 */
    var fontScale: String
        get() = prefs.getString(KEY_FONT_SCALE, FONT_STANDARD).orEmpty().ifBlank { FONT_STANDARD }
        set(value) = prefs.edit().putString(KEY_FONT_SCALE, value).apply()

    /** 朗读设置：任务完成后朗读结果。 */
    var ttsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TTS, false)
        set(value) = prefs.edit().putBoolean(KEY_TTS, value).apply()

    /** 触感反馈。 */
    var hapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    /** 官方登录页地址（auth-v2 链路，实测能收到短信的就是这一个）。 */
    fun loginPageUrl(): String {
        val root = baseUrl.trimEnd('/').ifBlank { DEFAULT_BASE_URL }
        return root + LOGIN_PATH + "?platform=" + LOGIN_PLATFORM +
            "&state=0&redirect_uri=" + URLEncoder.encode(root + LOGIN_REDIRECT_PATH, "UTF-8")
    }

    /** 原生登录成功后落盘：cookie 已由 [CookieStore] 逐条收集。 */
    fun saveSession(account: String = "", nickname: String = "") {
        prefs.edit()
            .putBoolean(KEY_LOGGED_IN, true)
            .putString(KEY_ACCOUNT, account.trim())
            .apply()
        if (nickname.isNotBlank()) this.nickname = nickname
    }

    fun signOut() {
        cookies.clear()
        prefs.edit()
            .putBoolean(KEY_LOGGED_IN, false)
            .putString(KEY_ACCOUNT, "")
            .putString(KEY_NICKNAME, "")
            .apply()
    }

    /**
     * 登录态已被网关判定失效：清掉登录标记，只丢掉失效的网关会话，
     **保留** Keycloak SSO cookie，SSO 仍有效时重新登录不必再收一次验证码。
     */
    fun markSessionExpired() {
        cookies.dropGatewaySession()
        prefs.edit().putBoolean(KEY_LOGGED_IN, false).apply()
    }

    /**
     * 请求网关 / Keycloak 统一使用的 User-Agent。
     * 网关签发的 `session` 会跟签发时的 User-Agent 绑定，登录与业务请求必须共用这一个值。
     */
    var userAgent: String
        get() = prefs.getString(KEY_USER_AGENT, "").orEmpty().ifBlank { DEFAULT_USER_AGENT }
        private set(value) = prefs.edit().putString(KEY_USER_AGENT, value.trim()).apply()

    companion object {
        /** console 接口与登录页所在域。 */
        const val DEFAULT_BASE_URL = "https://www.workbuddy.cn"

        /** 官方登录页路径。 */
        const val LOGIN_PATH = "/login/"

        /** 官方登录页的 `platform` 查询参数（能收到短信的官方地址用的就是 `agents`）。 */
        const val LOGIN_PLATFORM = "agents"

        /** 登录完成后回跳的地址。 */
        const val LOGIN_REDIRECT_PATH = "/app"

        /** 没有网页登录 UA 时使用的兜底 UA。 */
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230805.001) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /** 个人账号的企业 ID（模型列表接口的兜底取值，与 web 端一致）。 */
        const val ENTERPRISE_PERSONAL = "personal"

        const val THEME_BLUE = "blue"
        const val THEME_GREEN = "green"
        const val THEME_PURPLE = "purple"
        const val THEME_AMBER = "amber"

        const val LANG_ZH = "zh"
        const val LANG_EN = "en"

        const val FONT_SMALL = "small"
        const val FONT_STANDARD = "standard"
        const val FONT_LARGE = "large"

        private const val KEY_BASE_URL = "base_url"
        private const val KEY_LOGGED_IN = "logged_in"
        private const val KEY_ACCOUNT = "account_hint"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_USER_AGENT = "user_agent"
        private const val KEY_MODEL = "selected_model"
        private const val KEY_ENTERPRISE = "enterprise_id"
        private const val KEY_EXTREME_SAVER = "extreme_saver"
        private const val KEY_THEME_COLOR = "theme_color"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_TTS = "tts_enabled"
        private const val KEY_HAPTICS = "haptics_enabled"
    }
}
