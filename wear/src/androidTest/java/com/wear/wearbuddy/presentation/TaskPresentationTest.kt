package com.wear.wearbuddy.presentation

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.compose.material3.AppScaffold
import com.wear.wearbuddy.data.*
import com.wear.wearbuddy.presentation.pages.TaskCard
import com.wear.wearbuddy.presentation.screens.AboutScreen
import com.wear.wearbuddy.presentation.theme.WearbuddyTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class TaskPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun compactCardsFitTheirTextAndLongPressCopiesTheFullTitle() {
        val title = "Analyze app ANR from main thread and inspect the complete trace"
        var clicks = 0
        compose.setContent { WearbuddyTheme { CompositionLocalProvider(LocalStrings provides stringsOf("zh")) {
            Column(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 12.dp, vertical = 44.dp)) {
                TaskCard(Task("done", title, TaskStatus.DONE, ExecEnv.CLOUD, 0, 0),
                    modifier = Modifier.fillMaxWidth().testTag("done"), onClick = { clicks++ })
                Spacer(Modifier.height(4.dp))
                TaskCard(Task("working", "Fix input method issue", TaskStatus.WORKING, ExecEnv.CLOUD, 0, 0),
                    modifier = Modifier.fillMaxWidth().testTag("working"), onClick = {})
            }
        } } }
        compose.onNodeWithTag("done").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("working").assertHeightIsAtLeast(48.dp)
        assertTrue(compose.onNodeWithTag("done").getUnclippedBoundsInRoot().let { it.bottom - it.top } <= 60.dp)
        assertTrue(compose.onNodeWithTag("working").getUnclippedBoundsInRoot().let { it.bottom - it.top } <= 60.dp)
        compose.onNodeWithTag("done").performTouchInput { longClick() }
        compose.runOnIdle {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(title, clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertEquals(0, clicks)
        }
        saveScreenshot("compact-tasks.png")
    }

    @Test fun messageLongPressCopiesAllText() {
        val text = "完整回复\n第二行：报告已经生成。"
        compose.setContent { WearbuddyTheme { CompositionLocalProvider(LocalStrings provides stringsOf("zh")) {
            Box(Modifier.padding(top = 50.dp, start = 16.dp, end = 16.dp)) {
                MessageBubble(ChatMessage(ChatRole.ASSISTANT, text), Modifier.testTag("message"))
            }
        } } }
        compose.onNodeWithTag("message").performTouchInput { longClick() }
        compose.runOnIdle {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(text, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test fun thoughtsExpandWithAFullCardHighlight() {
        val text = "正在整理工作区，确认文件路径。\n正在准备报告和摘要。\n正在核对最后一项结果。"
        compose.setContent { WearbuddyTheme { CompositionLocalProvider(LocalStrings provides stringsOf("zh")) {
            Box(Modifier.padding(top = 50.dp, start = 16.dp, end = 16.dp)) {
                MessageBubble(ChatMessage(ChatRole.THOUGHT, text), Modifier.testTag("thought"))
            }
        } } }
        val card = compose.onNodeWithTag("thought")
        val collapsed = card.getUnclippedBoundsInRoot()
        assertTrue(collapsed.bottom - collapsed.top <= 52.dp)
        compose.onNodeWithText(text).assertDoesNotExist()
        val collapsedPixels = card.captureToImage().toPixelMap()
        val collapsedColor = collapsedPixels[8, collapsedPixels.height / 2]
        card.performClick()
        compose.onNodeWithText(text).assertIsDisplayed()
        val expandedPixels = card.captureToImage().toPixelMap()
        val left = expandedPixels[8, expandedPixels.height / 2]
        assertTrue("Expanded background should be brighter", left.red > collapsedColor.red)
        assertEquals(left, expandedPixels[expandedPixels.width - 9, expandedPixels.height / 2])
        saveScreenshot("thought-expanded.png")
        card.performClick()
        compose.onNodeWithText(text).assertDoesNotExist()
    }

    @Test fun aboutScreenShowsIntroductionAndCanGoBackFromTheBottom() {
        var wentBack = false
        compose.setContent { WearbuddyTheme { CompositionLocalProvider(LocalStrings provides stringsOf("zh")) {
            AppScaffold { AboutScreen(onBack = { wentBack = true }) }
        } } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("应用介绍"))
        val pixels = compose.onNodeWithText("应用介绍").captureToImage().toPixelMap()
        var white = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            if (color.red > .98f && color.green > .98f && color.blue > .98f) white++
        }
        assertTrue("The introduction heading should contain white glyphs", white > 10)
        saveScreenshot("about-introduction.png")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Oride"))
        compose.onNodeWithText("Oride").assertIsDisplayed()
        compose.onNodeWithText("开发者").assertIsDisplayed()
        saveScreenshot("about-developer.png")
        compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        compose.onNodeWithText("返回").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(wentBack) }
    }

    @Test fun htmlWithoutAWebEngineOrBrowserDoesNotCrash() {
        compose.setContent { WearbuddyTheme { Box(Modifier.fillMaxSize()) } }
        val file = File(context.filesDir, "artifacts/preview-test/test.html")
        file.parentFile!!.mkdirs()
        file.writeText("<html><body><h1>Report</h1><p>Preview</p></body></html>")
        try {
            compose.runOnIdle {
                openHtmlPreview(context, Artifact("test.html", "agent:///workspace/test.html"), file.absolutePath)
            }
        } finally { file.delete() }
    }

    private fun saveScreenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/wearbuddy-review")
            })!!
        context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
