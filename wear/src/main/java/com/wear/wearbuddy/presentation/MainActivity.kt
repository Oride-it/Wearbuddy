package com.wear.wearbuddy.presentation

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import com.wear.wearbuddy.data.TaskStatus
import com.wear.wearbuddy.vm.AppViewModel

/** 入口：整屏 Compose，主题与根路由见 [WearbuddyApp]。 */
class MainActivity : ComponentActivity() {
    private val notifications by lazy { TaskNotifications(this) }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewModel = ViewModelProvider(this)[AppViewModel::class.java]
        if (savedInstanceState == null) handleNotification(intent)
        setContent {
            val state by viewModel.ui.collectAsState()
            val task = state.activeTask
            LaunchedEffect(task?.id, task?.status) {
                if (task?.status == TaskStatus.WORKING && notifications.shouldRequestPermission()) {
                    notifications.createChannel(state.language)
                    notifications.markRequested()
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            WearbuddyApp(viewModel, ViewModelProvider(this)[com.wear.wearbuddy.vm.UpdateViewModel::class.java])
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotification(intent)
    }

    private fun handleNotification(intent: Intent) {
        notifications.taskFrom(intent)?.let { (profileId, taskId) ->
            ViewModelProvider(this)[AppViewModel::class.java].openCompletedTask(profileId, taskId)
        }
    }

    override fun onStart() {
        super.onStart()
        ViewModelProvider(this)[com.wear.wearbuddy.vm.UpdateViewModel::class.java].check()
        ViewModelProvider(this)[AppViewModel::class.java].setForeground(true)
    }

    override fun onStop() {
        super.onStop()
        ViewModelProvider(this)[AppViewModel::class.java].setForeground(false)
    }
}
