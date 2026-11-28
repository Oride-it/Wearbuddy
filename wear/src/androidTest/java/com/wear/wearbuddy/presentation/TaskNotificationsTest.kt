package com.wear.wearbuddy.presentation

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class TaskNotificationsTest {
    @Test fun completionPostsToTheSystemChannel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        assumeTrue(manager.areNotificationsEnabled())
        assumeTrue(Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

        val notifications = TaskNotifications(context)
        val tag = "wearbuddy://notification/test-profile/completion-fixture"
        try {
            notifications.show("test-profile", "completion-fixture", "测试报告", "zh")
            notifications.show("test-profile", "completion-fixture", "测试报告", "zh")
            val deadline = SystemClock.elapsedRealtime() + 3_000
            while (manager.activeNotifications.none { it.tag == tag } && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(50)
            }
            val posted = manager.activeNotifications.single { it.tag == tag }.notification
            assertEquals("task_completion", posted.channelId)
            assertEquals("任务已完成", posted.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertEquals("测试报告", posted.extras.getCharSequence(Notification.EXTRA_TEXT))
            assertNotNull(posted.contentIntent)
        } finally {
            manager.cancel(tag, 0)
        }
    }
}
