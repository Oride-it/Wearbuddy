package com.wear.wearbuddy.presentation

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.wear.wearbuddy.data.AccountStore
import com.wear.wearbuddy.vm.AppViewModel
import com.wear.wearbuddy.vm.Overlay
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AccountsScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun addButtonOpensFreshLoginAndPreservesCurrentAccount() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val registry = context.getSharedPreferences("wearbuddy_accounts", 0)
        val oldActive = registry.getString("active", null)
        val oldIds = registry.getStringSet("ids", emptySet())!!.toSet()
        val store = AccountStore(context)
        val first = store.create()
        val second = store.create()
        try {
            first.saveSession("13800000001", "账号一")
            first.language = "zh"
            second.saveSession("13800000002", "账号二")
            store.activate(second)
            store.activate(first)
            val vm = AppViewModel(context.applicationContext as Application)
            compose.setContent { WearbuddyApp(vm) }
            compose.runOnIdle { vm.openAccounts() }
            compose.onAllNodes(hasScrollAction()).onLast().performScrollToNode(hasText("账号一"))
            compose.onNodeWithText("账号一").assertIsDisplayed()
            compose.onAllNodes(hasScrollAction()).onLast().performScrollToIndex(2)
            compose.onAllNodes(hasScrollAction()).onLast().performTouchInput { swipeUp() }
            compose.onNodeWithText("添加账号").assertIsDisplayed()
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "screencap -p /sdcard/Download/wearbuddy-accounts.png"
            ).use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() } }
            compose.onNodeWithText("添加账号").performClick()
            compose.runOnIdle {
                assertEquals(Overlay.Login, vm.ui.value.overlay)
                assertEquals("", vm.ui.value.login.phone)
                assertEquals(first.profileId, AccountStore.activeId(context))
                assertEquals("13800000001", vm.ui.value.phone)
                vm.cancelLogin()
                vm.viewModelScopeForTestCleanup()
            }
        } finally {
            registry.edit().putString("active", oldActive).putStringSet("ids", oldIds).commit()
            context.deleteSharedPreferences("wearbuddy_account_${first.profileId}")
            context.deleteSharedPreferences("wearbuddy_account_${second.profileId}")
        }
    }
}

private fun AppViewModel.viewModelScopeForTestCleanup() {
    androidx.lifecycle.ViewModelStore().also { it.put("test", this); it.clear() }
}

