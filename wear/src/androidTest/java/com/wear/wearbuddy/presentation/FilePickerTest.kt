package com.wear.wearbuddy.presentation

import android.os.Build
import android.os.Environment
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class FilePickerTest {
    @get:Rule val compose = createAndroidComposeRule<FilePickerActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun loaded(folder: File? = null): SemanticsNodeInteraction {
        val tag = "picker:${folder?.absolutePath.orEmpty()}"
        compose.waitUntil(10000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNodeWithTag(tag)
    }

    private fun open(folder: File?, file: File, label: String = file.name) {
        loaded(folder).performScrollToKey(file.absolutePath)
        compose.onNodeWithText(label).performClick()
    }

    @Test fun browsesAppFolderAndReadsSelectedFileThroughProviderWithoutSystemPicker() {
        val file = File(context.filesDir, "artifacts/picker-regression.txt")
        file.parentFile!!.mkdirs()
        file.writeText("file-picker-content")
        try {
            open(null, file.parentFile!!, "应用内产物")
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            assertEquals("file-picker-content", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            val activity = compose.activity
            open(file.parentFile, file)
            compose.waitUntil(5000) { activity.isFinishing }
        } finally { file.delete() }
    }

    @Test fun appOpsGrantShowsCustomSharedFoldersAndDistinguishesUnreadableFromEmpty() {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        // Grant from the host before starting instrumentation. Changing this AppOp while the
        // test is running can kill the app (including the instrumentation) on Wear OS.
        assumeTrue("Requires MANAGE_EXTERNAL_STORAGE via appops", Environment.isExternalStorageManager())
        val dir = File(Environment.getExternalStorageDirectory(), "Wearbuddy-picker-${UUID.randomUUID()}")
        val nested = File(dir, "nested")
        try {
            assertTrue(nested.mkdirs())
            compose.activityRule.scenario.recreate()
            loaded().performScrollToIndex(0)
            compose.onNodeWithText("授权浏览手表文件").assertDoesNotExist()
            open(null, dir)
            open(dir, nested)
            loaded(nested).performScrollToNode(hasText("此文件夹没有可选文件"))
            compose.onNodeWithText("此文件夹没有可选文件").assertExists()
            loaded(nested).performScrollToNode(hasText("上一级"))
            compose.onNodeWithText("上一级").performClick()

            // A directory can disappear between listing and tapping. A failed read is not an empty directory.
            loaded(dir).performScrollToKey(nested.absolutePath)
            assertTrue(nested.delete())
            compose.onNodeWithText(nested.name).performClick()
            loaded(nested).performScrollToNode(hasText("无法列出此目录", substring = true))
            compose.onNodeWithText("此文件夹没有可选文件").assertDoesNotExist()
        } finally {
            nested.delete()
            dir.delete()
        }
    }
}
