package com.wear.wearbuddy.presentation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class FilePickerTest {
    @get:Rule val compose = createAndroidComposeRule<FilePickerActivity>()

    @Test fun browsesAppFolderAndReadsSelectedFileThroughProviderWithoutSystemPicker() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "artifacts/picker-regression.txt")
        file.parentFile!!.mkdirs()
        file.writeText("file-picker-content")
        try {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("artifacts"))
            compose.onNodeWithText("artifacts").performClick()
            compose.waitForIdle()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(file.name))
            compose.onNodeWithText(file.name).assertIsDisplayed()
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "screencap -p /sdcard/Download/wearbuddy-file-picker.png"
            ).use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() } }
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            assertEquals("file-picker-content", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            val activity = compose.activity
            compose.onNodeWithText(file.name).performClick()
            compose.waitUntil(5000) { activity.isFinishing }
        } finally { file.delete() }
    }
}
