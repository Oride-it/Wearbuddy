package com.wear.wearbuddy.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.wear.wearbuddy.vm.AppViewModel

/** 入口：整屏 Compose，主题与根路由见 [WearbuddyApp]。 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewModel = ViewModelProvider(this)[AppViewModel::class.java]
        setContent {
            WearbuddyApp(viewModel, ViewModelProvider(this)[com.wear.wearbuddy.vm.UpdateViewModel::class.java])
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
