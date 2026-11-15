package com.wear.wearbuddy.data

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class AccountIsolationTest {
    @Test fun pendingAccountDoesNotReplaceActiveAndSwitchingKeepsCookiesIsolated() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val registry = context.getSharedPreferences("wearbuddy_accounts", 0)
        val previousActive = registry.getString("active", null)
        val previousIds = registry.getStringSet("ids", emptySet())!!.toSet()
        val store = AccountStore(context)
        val first = store.create()
        val second = store.create()
        try {
            first.cookies.absorb(mapOf("Set-Cookie" to listOf("session=account-A; Path=/")), "https://www.workbuddy.cn/")
            first.saveSession("13800000001", "First")
            first.stoppedSessions = setOf("stopped-task")
            assertEquals(setOf("stopped-task"), CloudSettings(context, first.profileId).stoppedSessions)
            assertTrue(second.stoppedSessions.isEmpty())
            store.activate(first)
            assertEquals(first.profileId, CloudSettings(context).profileId)
            // A second account has its own empty login jar before it is activated.
            assertFalse(second.cookies.hasAny())
            assertEquals(first.profileId, CloudSettings(context).profileId)
            second.cookies.absorb(mapOf("Set-Cookie" to listOf("session=account-B; Path=/")), "https://www.workbuddy.cn/")
            second.saveSession("13800000002", "Second")
            store.activate(second)
            assertEquals("session=account-B", CloudSettings(context).cookies.header("https://www.workbuddy.cn/console/accounts"))
            // A late response for A must never mutate B's jar.
            first.cookies.absorb(mapOf("Set-Cookie" to listOf("session=renewed-A; Path=/")), "https://www.workbuddy.cn/")
            assertEquals("session=account-B", CloudSettings(context).cookies.header("https://www.workbuddy.cn/console/accounts"))
            store.activate(first)
            assertEquals("session=renewed-A", CloudSettings(context).cookies.header("https://www.workbuddy.cn/console/accounts"))
            first.signOut()
            store.activate(second)
            assertTrue(CloudSettings(context).isLoggedIn)
        } finally {
            registry.edit().putString("active", previousActive).putStringSet("ids", previousIds).commit()
            context.deleteSharedPreferences("wearbuddy_account_${first.profileId}")
            context.deleteSharedPreferences("wearbuddy_account_${second.profileId}")
        }
    }
}
