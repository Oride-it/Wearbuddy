package com.wear.wearbuddy.presentation

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.compose.material3.AppScaffold
import com.wear.wearbuddy.data.*
import com.wear.wearbuddy.presentation.screens.TaskDetailContent
import com.wear.wearbuddy.presentation.theme.WearbuddyTheme
import com.wear.wearbuddy.vm.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ConversationFlowTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @Test fun emptyListThenDraftAndComposerReturnToChat() {
        val registry = app.getSharedPreferences("wearbuddy_accounts", 0)
        val oldActive = registry.getString("active", null)
        val oldIds = registry.getStringSet("ids", emptySet())!!.toSet()
        val accounts = AccountStore(app)
        val profile = accounts.create()
        val owner = ViewModelStore()
        try {
            profile.baseUrl = "https://127.0.0.1:1"
            profile.saveSession("test-local")
            profile.cookies.absorb(mapOf("Set-Cookie" to listOf("local=fixture; Path=/")), profile.baseUrl)
            accounts.activate(profile)
            val vm = AppViewModel(app)
            owner.put("vm", vm)
            compose.setContent { WearbuddyApp(vm) }
            compose.onNodeWithText("还没有会话").assertExists()
            compose.onNodeWithContentDescription("语音输入").assertDoesNotExist()
            compose.onNodeWithText("新对话").performClick()
            compose.onNodeWithContentDescription("语音输入").assertIsDisplayed()
            compose.onNodeWithContentDescription(stringsOf("zh").composeTitle).performClick()
            compose.runOnIdle { assertEquals(Overlay.Compose(null), vm.ui.value.overlay); vm.closeOverlay() }
            compose.onNodeWithContentDescription("选择模型").performClick()
            compose.runOnIdle { assertEquals(Overlay.ModelPicker, vm.ui.value.overlay); vm.closeOverlay() }
            compose.onNodeWithContentDescription("语音输入").assertIsDisplayed()
            compose.runOnIdle { vm.closeOverlay() }
            compose.onNodeWithText("还没有会话").assertDoesNotExist()
            compose.onNodeWithContentDescription("语音输入").assertDoesNotExist()
            compose.onNodeWithText("发送第一条消息").performClick()
            compose.runOnIdle { assertEquals(Overlay.NewChat, vm.ui.value.overlay) }
        } finally {
            compose.runOnIdle { owner.clear() }
            registry.edit().putString("active", oldActive).putStringSet("ids", oldIds).commit()
            app.deleteSharedPreferences("wearbuddy_account_${profile.profileId}")
        }
    }

    @Test fun newOutputPreservesReadingPositionAndButtonReachesArtifacts() {
        val task = Task("test", "历史记录", TaskStatus.DONE, ExecEnv.CLOUD, 0, 0,
            result = TaskResult(emptyList(), listOf(Artifact("报告.txt", "artifact:report"))))
        val messages = (1..24).map { ChatMessage(ChatRole.ASSISTANT, "历史消息 $it") }
        var state by mutableStateOf(UiState(overlay = Overlay.TaskDetail(task.id), tasks = listOf(task), history = messages))
        val owner = ViewModelStore()
        val vm = AppViewModel(app)
        owner.put("vm", vm)
        try {
            compose.setContent { WearbuddyTheme { AppScaffold { TaskDetailContent(state, vm) } } }
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("历史消息 10"))
            val reader = compose.onNodeWithText("历史消息 10")
            val top = reader.getUnclippedBoundsInRoot().top
            compose.runOnIdle {
                state = state.copy(history = messages + ChatMessage(ChatRole.ASSISTANT, "新增内容".repeat(100)))
            }
            reader.assertIsDisplayed()
            assertEquals(top, reader.getUnclippedBoundsInRoot().top)
            compose.onNodeWithContentDescription("回到底部").performClick()
            compose.onNodeWithText("报告.txt").assertIsDisplayed()
            compose.onNodeWithContentDescription("回到底部").assertDoesNotExist()
            compose.runOnIdle { state = state.copy(history = messages + ChatMessage(ChatRole.ASSISTANT, "又一段内容".repeat(200))) }
            compose.onNodeWithText("报告.txt").assertIsDisplayed()
        } finally { compose.runOnIdle { owner.clear() } }
    }
}
