package com.wear.wearbuddy.presentation.pages

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.SwitchButton
import com.wear.wearbuddy.presentation.theme.colorSchemeOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.ScreenScaffold
import com.wear.wearbuddy.presentation.ProfileAvatar
import com.wear.wearbuddy.presentation.plus
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.wear.wearbuddy.data.CloudSettings
import com.wear.wearbuddy.presentation.LocalStrings
import com.wear.wearbuddy.presentation.SectionCard
import com.wear.wearbuddy.presentation.SettingRow
import com.wear.wearbuddy.presentation.TaskNotifications
import com.wear.wearbuddy.presentation.formatPoints
import com.wear.wearbuddy.presentation.maskPhone
import com.wear.wearbuddy.presentation.rememberClickHaptic
import com.wear.wearbuddy.vm.AppViewModel
import com.wear.wearbuddy.vm.UiState
import androidx.compose.runtime.collectAsState

/** 第 3 页：我的（设置）。登录、资料、版本、积分、偏好设置、关于。 */
@Composable
fun SettingsPage(
    actions: AppViewModel,
    modifier: Modifier = Modifier,
    onCheckUpdate: () -> Unit = {}
) {
    val state by actions.ui.collectAsState()
    LaunchedEffect(state.loggedIn) { if (state.loggedIn) actions.loadCheckinIfNeeded() }
    SettingsContent(state, actions, modifier, onCheckUpdate)
}

@Composable
internal fun SettingsContent(state: UiState, actions: AppViewModel, modifier: Modifier = Modifier, onCheckUpdate: () -> Unit = {}) {
    val s = LocalStrings.current
    val listState = rememberTransformingLazyColumnState()
    val haptic = rememberClickHaptic()
    val context = LocalContext.current
    val notifications = remember(context) { TaskNotifications(context) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var dialog by remember { mutableStateOf<String?>(null) }

    ScreenScaffold(scrollState = listState, modifier = modifier, contentPadding = PaddingValues(0.dp)) { padding ->
        TransformingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = padding + PaddingValues(start = 12.dp, end = 12.dp, top = 26.dp, bottom = 40.dp)
        ) {
            item {
                ListHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        ,
                    transformation = null
                ) {
                    Text(s.tabSettings)
                }
            }


            if (!state.loggedIn) {
                item {
                    LoginCard(
                        transformation = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            ,
                        onClick = {
                            haptic()
                            actions.openLogin()
                        }
                    )
                }
            } else {
                // 资料卡：头像 + 昵称 + 手机号
                item {
                    ProfileCard(
                        onClick = actions::openAccounts,
                        state = state,
                        transformation = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            
                    )
                }
            item { SettingRow(if (state.language == "zh") "账号管理" else "Accounts", { actions.openAccounts() }, icon = Icons.Rounded.Person) }
                // 版本（套餐）框
                item {
                    PlanCard(
                        onClick = actions::openAccounts,
                        state = state,
                        transformation = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            
                    )
                }
                // 积分框
                item {
                    PointsCard(
                        state = state,
                        transformation = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            
                    )
                }
                // 每个偏好是独立圆弧行，避免一个大容器包住全部设置。
                item { SettingRow(s.theme, { dialog = "theme" }, icon = Icons.Rounded.Palette, value = themeName(state.themeColor)) }
                item { SettingRow(s.language, { dialog = "language" }, icon = Icons.Rounded.Language,
                    value = if (state.language == CloudSettings.LANG_EN) s.langEn else s.langZh) }
                item { SettingRow(s.fontSize, { dialog = "font" }, icon = Icons.Rounded.FontDownload, value = fontName(state.fontScale)) }
                item {
                    SettingRow(s.taskCompleteNotifications, {
                        notifications.createChannel(state.language)
                        if (notifications.shouldRequestPermission()) {
                            notifications.markRequested()
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else notifications.openSettings()
                    }, icon = Icons.Rounded.Notifications)
                }
                item {
                    SwitchButton(
                        checked = state.ttsEnabled,
                        onCheckedChange = actions::setTtsEnabled,
                        modifier = Modifier.fillMaxWidth(),
                        icon = { Icon(Icons.Rounded.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        label = { Text(s.ttsSetting) }
                    )
                }
                item {
                    SwitchButton(
                        checked = state.hapticsEnabled,
                        onCheckedChange = actions::setHapticsEnabled,
                        modifier = Modifier.fillMaxWidth(),
                        icon = { Icon(Icons.Rounded.Vibration, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        label = { Text(s.hapticsSetting) }
                    )
                }
                item { SettingRow(s.artifactsTitle, { actions.openArtifacts() }, icon = Icons.Rounded.Folder) }
                item { SettingRow(s.sessionsTitle, { actions.openSessions() }, icon = Icons.Rounded.History) }
                item { SettingRow(s.agentsTitle, { actions.openAgents() }, icon = Icons.Rounded.SmartToy) }
                item { SettingRow(s.checkinTitle, { actions.openCheckin() }, icon = Icons.Rounded.EventAvailable,
                    value = state.checkin?.takeIf { it.active }?.let { info ->
                        if (info.todayCheckedIn) s.checkinTodayDone else s.checkinTodayTodo
                    }) }
                item { SettingRow(s.storageSetting, { actions.openStorage() }, icon = Icons.Rounded.Storage) }
                item {
                    Button(
                        onClick = actions::openCloudTools,
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.wear.compose.material3.ButtonDefaults.filledTonalButtonColors(),
                        icon = { Icon(Icons.Rounded.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        label = { Text(s.cloudTools) }
                    )
                }
                item {
                    PlaceholderCard(
                        title = s.memoryBank,
                        icon = Icons.Rounded.Memory,
                        transformation = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            ,
                        onClick = { dialog = "unsupported" }
                    )
                }
                item { SettingRow(s.growthPlan, actions::openGrowthPlan, icon = Icons.Rounded.School) }

            }
            item { SettingRow(if (state.language == "zh") "检测更新" else "Check for updates", onCheckUpdate, icon = Icons.Rounded.SystemUpdate) }
            // 留出间隔，文字在圆弧按钮内上下居中；未登录也可查看关于。
            item {
                FilledTonalButton(
                    onClick = { haptic(); actions.openAbout() },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(6.dp))
                    Text(s.aboutApp, maxLines = 1)
                }
            }
            if (state.loggedIn) {
                // 退出登录
                item {
                    FilledTonalButton(
                        onClick = {
                            haptic()
                            actions.logout()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            ,
                        transformation = null
                    ) {
                        Icon(
                            Icons.Rounded.Logout,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaultsIconSize),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(s.logout, maxLines = 1)
                    }
                }
            }

        }
    }

    when (dialog) {
        "theme" -> OptionDialog(
            title = s.theme,
            options = listOf(
                s.themeBlue to CloudSettings.THEME_BLUE,
                s.themeGreen to CloudSettings.THEME_GREEN,
                s.themePurple to CloudSettings.THEME_PURPLE,
                s.themeAmber to CloudSettings.THEME_AMBER
            ),
            current = state.themeColor,
            showColors = true,
            onPick = {
                actions.setThemeColor(it)
                dialog = null
            },
            onDismiss = { dialog = null }
        )

        "language" -> OptionDialog(
            title = s.language,
            options = listOf(s.langZh to CloudSettings.LANG_ZH, s.langEn to CloudSettings.LANG_EN),
            current = state.language,
            onPick = {
                actions.setLanguage(it)
                dialog = null
            },
            onDismiss = { dialog = null }
        )

        "font" -> OptionDialog(
            title = s.fontSize,
            options = listOf(
                s.fontSmall to CloudSettings.FONT_SMALL,
                s.fontStandard to CloudSettings.FONT_STANDARD,
                s.fontLarge to CloudSettings.FONT_LARGE
            ),
            current = state.fontScale,
            onPick = {
                actions.setFontScale(it)
                dialog = null
            },
            onDismiss = { dialog = null }
        )

        "unsupported" -> AlertDialog(
            visible = true,
            onDismissRequest = { dialog = null },
            confirmButton = {
                AlertDialogDefaults.ConfirmButton(onClick = { dialog = null })
            },
            title = { Text(s.notSupported) }
        )
    }
}

private val ButtonDefaultsIconSize = 20.dp

@Composable
private fun themeName(color: String): String {
    val s = LocalStrings.current
    return when (color) {
        CloudSettings.THEME_GREEN -> s.themeGreen
        CloudSettings.THEME_PURPLE -> s.themePurple
        CloudSettings.THEME_AMBER -> s.themeAmber
        else -> s.themeBlue
    }
}

@Composable
private fun fontName(scale: String): String {
    val s = LocalStrings.current
    return when (scale) {
        CloudSettings.FONT_SMALL -> s.fontSmall
        CloudSettings.FONT_LARGE -> s.fontLarge
        else -> s.fontStandard
    }
}

/** 未登录时的引导卡片。 */
@Composable
private fun LoginCard(
    transformation: SurfaceTransformation?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val s = LocalStrings.current
    Card(
        onClick = onClick,
        modifier = modifier,
        transformation = transformation,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Text(s.loginTitle, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(2.dp))
        Text(
            s.loginSubtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            s.loginNow,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 资料卡：可选圆形头像 + 昵称 + 手机号。 */
@Composable
private fun ProfileCard(
    onClick: () -> Unit,
    state: UiState,
    transformation: SurfaceTransformation?,
    modifier: Modifier = Modifier
) {
    val initial = state.nickname.take(1).ifBlank { state.phone.takeLast(1).ifBlank { "W" } }
    Card(
        onClick = onClick,
        modifier = modifier,
        transformation = transformation,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ProfileAvatar(account = state.phone, initial = initial, remoteUrl = state.avatarUrl)
            Column(Modifier.weight(1f)) {
                Text(
                    state.nickname.ifBlank { "WorkBuddy 用户" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    maskPhone(state.phone),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Icon(
                Icons.Rounded.Person,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 版本（套餐）框：当前套餐 + 到期时间 + 今日请求。 */
@Composable
private fun PlanCard(
    onClick: () -> Unit,
    state: UiState,
    transformation: SurfaceTransformation?,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    val pack = state.usage?.packs?.firstOrNull()
    SectionCard(title = s.planTitle, modifier = modifier, transformation = transformation, onClick = onClick) {
        if (state.usageLoading && state.usage == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text(s.loading, style = MaterialTheme.typography.labelSmall)
            }
        } else {
            Text(
                pack?.name ?: s.planUnknown,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            pack?.expireAt?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            state.usage?.todayRequests?.let { count ->
                Text(
                    "${s.todayRequests}: $count",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 积分框：剩余 / 总量 + 进度条。 */
@Composable
private fun PointsCard(
    state: UiState,
    transformation: SurfaceTransformation?,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    val usage = state.usage
    SectionCard(title = s.pointsTitle, modifier = modifier, transformation = transformation, shape = RoundedCornerShape(16.dp)) {
        when {
            usage != null -> {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        formatPoints(usage.left),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "/ ${formatPoints(usage.total)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { if (usage.total > 0) (usage.left / usage.total).toFloat() else 0f },
                    modifier = Modifier.fillMaxWidth(),
                    strokeWidth = 8.dp
                )
            }

            state.usageLoading -> Text(s.loading, style = MaterialTheme.typography.labelSmall)
            else -> Text(
                s.signInToView,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 记忆库占位入口。 */
@Composable
private fun PlaceholderCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    transformation: SurfaceTransformation?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val s = LocalStrings.current
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        transformation = transformation
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    s.notSupported,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 选项对话框（主题 / 语言 / 字体大小）。 */
@Composable
private fun OptionDialog(
    title: String,
    options: List<Pair<String, String>>,
    current: String,
    showColors: Boolean = false,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = { Text(title, textAlign = TextAlign.Center) },
        content = {
            items(options, key = { it.second }) { (label, value) ->
                RadioButton(
                    selected = value == current,
                    onSelect = { onPick(value) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = if (showColors) {
                        { Box(Modifier.size(22.dp).clip(CircleShape).background(colorSchemeOf(value).primary)) }
                    } else null,
                    label = { Text(label, maxLines = 2) }
                )
            }
        }
    )
}
