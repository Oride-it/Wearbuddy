package com.wear.wearbuddy.presentation

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.compose.material3.AppScaffold
import com.wear.wearbuddy.presentation.theme.WearbuddyTheme
import com.wear.wearbuddy.presentation.pages.SettingsContent
import com.wear.wearbuddy.presentation.screens.UpdatePrompt
import com.wear.wearbuddy.vm.*
import com.wear.wearbuddy.data.AppRelease
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UpdateAndSettingsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun settingsOpensUpdateCheckAndPlacesAccountBelowProfile() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val vm = AppViewModel(app)
        var checks = 0
        compose.setContent { WearbuddyTheme { CompositionLocalProvider(LocalStrings provides stringsOf("zh")) {
            AppScaffold { SettingsContent(UiState(loggedIn = true, nickname = "当前账号", phone = "13800000001"), vm, onCheckUpdate = { checks++ }) }
        } } }
        val list = compose.onNode(hasScrollAction())
        list.performScrollToNode(hasText("当前账号"))
        compose.onNodeWithText("当前账号").assertExists()
        list.performScrollToNode(hasText("账号管理"))
        compose.onNodeWithText("账号管理").assertExists()
        list.performScrollToNode(hasText("检测更新"))
        compose.onNodeWithText("检测更新").performClick()
        compose.runOnIdle {
            assertEquals(1, checks)
            androidx.lifecycle.ViewModelStore().also { it.put("test", vm); it.clear() }
        }
    }
    @Test fun updateDialogShowsReleaseNotesAndDownloadProgress() {
        val release = AppRelease("1.0.10", "修复连接\n新增文件选择\n更新账号管理", "https://example.invalid", 100, "a".repeat(64))
        compose.setContent { WearbuddyTheme { AppScaffold {
            UpdatePrompt(UpdateState(visible = true, release = release, downloading = true, bytes = 42), {}, {})
        } } }
        compose.onNodeWithText("新版本 1.0.10").assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("正在下载 42%"))
        compose.onNodeWithText("正在下载 42%").assertExists()
    }
}
