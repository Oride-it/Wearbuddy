package com.wear.wearbuddy.presentation

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.wear.compose.material3.AppScaffold
import com.wear.wearbuddy.data.RemoteModel
import com.wear.wearbuddy.presentation.screens.ModelPickerContent
import com.wear.wearbuddy.presentation.screens.ModelPickerState
import com.wear.wearbuddy.presentation.theme.WearbuddyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ModelPickerScrollTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pausedListResumesDownwardAndUpwardAndSelectsCorrectModel() {
        var chosen = ""
        val models = (1..30).map {
            RemoteModel("model-$it", "Model $it with a long descriptive name", credits = "2x")
        }
        compose.setContent {
            WearbuddyTheme {
                AppScaffold {
                    ModelPickerContent(ModelPickerState(models, "model-1", false, null), { chosen = it })
                }
            }
        }
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToIndex(4)
        compose.waitForIdle()
        // Let the long labels begin moving before checking both scroll directions.
        Thread.sleep(1800)
        fun offset() = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val pausedOffset = offset()
        list.performTouchInput { swipeUp(durationMillis = 400) }
        compose.waitForIdle()
        val downOffset = offset()
        assertTrue("List must advance after an idle pause", downOffset > pausedOffset)
        list.performTouchInput { swipeDown(durationMillis = 400) }
        compose.waitForIdle()
        assertTrue("List must also scroll upward", offset() < downOffset)
        list.performScrollToIndex(8)
        compose.onNodeWithText(models[7].name).performClick()
        compose.runOnIdle { assertEquals("model-8", chosen) }
    }
}
