package com.wear.wearbuddy.data

import android.content.Context
import java.util.UUID

/** Each settings instance stays bound to one account, including in-flight requests. */
class AccountStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("wearbuddy_accounts", Context.MODE_PRIVATE)
    fun accounts(): List<CloudSettings> =
        (prefs.getStringSet("ids", emptySet()).orEmpty() + "legacy")
            .map { CloudSettings(context, it) }.filter { it.accountHint.isNotBlank() }
            .sortedBy { it.accountHint }
    fun create() = CloudSettings(context, UUID.randomUUID().toString())
    fun activate(settings: CloudSettings) {
        val ids = prefs.getStringSet("ids", emptySet()).orEmpty() + settings.profileId
        prefs.edit().putStringSet("ids", ids).putString("active", settings.profileId).apply()
    }
    companion object {
        fun activeId(context: Context): String = context.getSharedPreferences("wearbuddy_accounts", Context.MODE_PRIVATE)
            .getString("active", "legacy") ?: "legacy"
    }
}
