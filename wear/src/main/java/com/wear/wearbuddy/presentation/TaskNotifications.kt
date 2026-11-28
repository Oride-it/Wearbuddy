package com.wear.wearbuddy.presentation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.wear.wearbuddy.R
import java.util.UUID

internal class TaskNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val preferences = context.getSharedPreferences("task_notifications", Context.MODE_PRIVATE)

    fun createChannel(language: String) {
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, stringsOf(language).taskCompleteNotifications, NotificationManager.IMPORTANCE_DEFAULT
        ))
    }

    fun shouldRequestPermission(): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !preferences.getBoolean("requested", false)

    fun markRequested() {
        preferences.edit().putBoolean("requested", true).apply()
    }

    fun openSettings() {
        try {
            context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null)))
        }
    }

    fun show(profileId: String, taskId: String, title: String, language: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!manager.areNotificationsEnabled()) return
        createChannel(language)
        val target = Intent(context, MainActivity::class.java).apply {
            action = OPEN_TASK
            data = Uri.Builder().scheme("wearbuddy").authority("notification")
                .appendPath(profileId).appendPath(taskId).build()
            putExtra("token", notificationToken())
        }
        val pending = PendingIntent.getActivity(context, 0, target,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_task_complete)
            .setContentTitle(stringsOf(language).taskCompleted)
            .setContentText(title)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        try {
            manager.notify(target.data.toString(), 0, notification)
        } catch (_: SecurityException) {
            // Notification permission can change between the check and delivery.
        }
    }

    fun taskFrom(intent: Intent): Pair<String, String>? {
        val token = preferences.getString("launch_token", null) ?: return null
        if (intent.action != OPEN_TASK || intent.getStringExtra("token") != token) return null
        val parts = intent.data?.pathSegments ?: return null
        return if (parts.size == 2) parts[0] to parts[1] else null
    }

    private fun notificationToken(): String =
        preferences.getString("launch_token", null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString("launch_token", it).apply()
        }

    private companion object {
        const val CHANNEL = "task_completion"
        const val OPEN_TASK = "com.wear.wearbuddy.OPEN_COMPLETED_TASK"
    }
}
