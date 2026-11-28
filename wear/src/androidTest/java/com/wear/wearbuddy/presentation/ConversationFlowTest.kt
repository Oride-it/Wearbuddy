package com.wear.wearbuddy.presentation

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.compose.material3.AppScaffold
import com.wear.wearbuddy.data.*
import com.wear.wearbuddy.presentation.screens.TaskDetailContent
import com.wear.wearbuddy.presentation.screens.AskCard
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
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
            compose.onNodeWithContentDescription("语音输入").assertDoesNotExist()
            compose.onNodeWithContentDescription(stringsOf("zh").composeTitle).performClick()
            compose.onNodeWithText("语音输入").assertIsDisplayed()
            compose.runOnIdle { assertEquals(Overlay.Compose(null), vm.ui.value.overlay); vm.closeOverlay() }
            compose.onNodeWithContentDescription("选择模型").performClick()
            compose.runOnIdle { assertEquals(Overlay.ModelPicker, vm.ui.value.overlay); vm.closeOverlay() }
            compose.onNodeWithContentDescription(stringsOf("zh").composeTitle).assertIsDisplayed()
            compose.onNodeWithContentDescription("语音输入").assertDoesNotExist()
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

    @Test fun opensAtLatestAndKeepsArtifactsOutsideTheConversationTarget() {
        val task = Task("test", "历史记录", TaskStatus.DONE, ExecEnv.CLOUD, 0, 0,
            result = TaskResult(emptyList(), listOf(Artifact("报告.txt", "artifact:report")) +
                (2..6).map { Artifact("文件 $it.txt", "artifact:file-$it") }))
        val messages = (1..24).map { ChatMessage(ChatRole.ASSISTANT, "历史消息 $it") }
        var state by mutableStateOf(UiState(overlay = Overlay.TaskDetail(task.id), tasks = listOf(task), history = messages))
        val owner = ViewModelStore()
        val vm = AppViewModel(app)
        owner.put("vm", vm)
        try {
            compose.setContent { WearbuddyTheme { AppScaffold { TaskDetailContent(state, vm) } } }
            compose.onNodeWithText("历史消息 24").assertIsDisplayed()
            compose.onNodeWithText("报告.txt").assertDoesNotExist()
            compose.onNodeWithContentDescription("语音输入").assertDoesNotExist()
            val list = compose.onNode(hasScrollAction())
            list.performTouchInput { swipeDown() }
            list.performScrollToNode(hasText("历史消息 10"))
            val reader = compose.onNodeWithText("历史消息 10")
            val top = reader.getUnclippedBoundsInRoot().top
            compose.runOnIdle {
                state = state.copy(history = messages + ChatMessage(ChatRole.ASSISTANT, "最新消息"))
            }
            reader.assertIsDisplayed()
            assertEquals(top, reader.getUnclippedBoundsInRoot().top)
            compose.onNodeWithContentDescription("回到最新消息").performClick()
            compose.onNodeWithText("最新消息").assertIsDisplayed()
            compose.onNodeWithContentDescription("回到最新消息").assertDoesNotExist()
            compose.onNodeWithText("报告.txt").assertDoesNotExist()
            list.performScrollToNode(hasText("云端产物 · 6"))
            compose.onNodeWithText("云端产物 · 6").performClick()
            list.performScrollToNode(hasText("报告.txt"))
            compose.onNodeWithText("报告.txt").assertIsDisplayed()
            val indexForKey = list.fetchSemanticsNode().config[SemanticsProperties.IndexForKey]
            val latestMessageIndex = indexForKey("message:" + messages.size)
            assertTrue(latestMessageIndex >= 0)
            assertTrue(indexForKey("artifact:artifact:report") > indexForKey("latest-message"))
            list.performScrollToIndex(indexForKey("browse-workspace"))
            compose.onNodeWithContentDescription("回到最新消息").performClick()
            compose.onNodeWithText("最新消息").assertIsDisplayed()
        } finally { compose.runOnIdle { owner.clear() } }
    }

    @Test fun askCardWaitsForConfirmationAndHasASeparateCancelAction() {
        val option = AskOption("markdown", "Markdown", "纯文本")
        val ask = AskQuestion(listOf(AskItem("format", "想要什么格式？", options = listOf(option))))
        val submissions = mutableListOf<Map<String, AskPick>?>()
        compose.setContent {
            WearbuddyTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AskCard(ask, busy = false) { submissions.add(it) }
                }
            }
        }
        compose.onNodeWithText("想要什么格式？").assertIsDisplayed()
        val scroll = compose.onNode(hasScrollAction())
        scroll.performScrollToNode(hasText("确认"))
        compose.onNodeWithText("确认").assertIsNotEnabled()
        scroll.performScrollToNode(hasText("Markdown"))
        compose.onNodeWithText("Markdown").performClick().assertIsSelected()
        compose.runOnIdle { assertTrue(submissions.isEmpty()) }
        scroll.performScrollToNode(hasText("确认"))
        compose.onNodeWithText("确认").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle {
            assertEquals(listOf(mapOf("format" to AskPick(listOf(option))), null), submissions)
        }
    }
}
