package com.wear.wearbuddy.presentation.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.*
import androidx.wear.compose.material3.*
import com.wear.wearbuddy.presentation.plus
import com.wear.wearbuddy.vm.AppViewModel

@Composable
fun AccountsScreen(actions: AppViewModel, modifier: Modifier = Modifier) {
    val state by actions.ui.collectAsState()
    val listState = rememberTransformingLazyColumnState()
    val accounts = actions.savedAccounts()
    val zh = state.language == "zh"
    ScreenScaffold(scrollState = listState, modifier = modifier,
        edgeButton = {
            EdgeButton(onClick = actions::addAccount) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(if (zh) "添加账号" else "Add account")
            }
        }) { padding ->
        TransformingLazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(horizontal = 12.dp, vertical = 28.dp)) {
            item { ListHeader { Text(if (zh) "账号管理" else "Accounts") } }
            items(accounts, key = { it.profileId }) { account ->
                val selected = account.profileId == com.wear.wearbuddy.data.AccountStore.activeId(androidx.compose.ui.platform.LocalContext.current)
                Button(onClick = { actions.switchAccount(account.profileId) },
                    colors = if (selected) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(account.nickname.ifBlank { if (zh) "WorkBuddy 用户" else "WorkBuddy user" }) },
                    secondaryLabel = {
                        val phone = account.accountHint.let { if (it.length >= 7) it.take(3) + "****" + it.takeLast(4) else it }
                        Text(phone + if (selected) { if (zh) " · 当前账号" else " · Current" }
                            else if (!account.isLoggedIn) { if (zh) " · 需登录" else " · Sign in" } else "")
                    })
            }
            if (accounts.isEmpty()) item { Text(if (zh) "添加账号后可在此切换" else "Add accounts to switch here") }
        }
    }
}
