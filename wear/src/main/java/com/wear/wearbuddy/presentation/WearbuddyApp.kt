package com.wear.wearbuddy.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.SwipeToDismissValue
import androidx.wear.compose.foundation.LocalScreenIsActive
import androidx.wear.compose.foundation.edgeSwipeToDismiss
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwipeToDismissBox
import androidx.wear.compose.material3.Text
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import com.wear.wearbuddy.presentation.pages.ProjectsPage
import com.wear.wearbuddy.presentation.pages.SettingsPage
import com.wear.wearbuddy.presentation.pages.TasksPage
import com.wear.wearbuddy.presentation.screens.AboutScreen
import com.wear.wearbuddy.presentation.screens.AddProjectScreen
import com.wear.wearbuddy.presentation.screens.AgentsScreen
import com.wear.wearbuddy.presentation.screens.ArtifactsScreen
import com.wear.wearbuddy.presentation.screens.CheckinScreen
import com.wear.wearbuddy.presentation.screens.CloudToolsScreen
import com.wear.wearbuddy.presentation.screens.CloudToolScreen
import com.wear.wearbuddy.presentation.screens.GrowthPlanScreen
import com.wear.wearbuddy.presentation.screens.ComposeScreen
import com.wear.wearbuddy.presentation.screens.LoginScreen
import com.wear.wearbuddy.presentation.screens.ModelPickerScreen
import com.wear.wearbuddy.presentation.screens.SessionsScreen
import com.wear.wearbuddy.presentation.screens.StorageScreen
import com.wear.wearbuddy.presentation.screens.TaskDetailScreen
import com.wear.wearbuddy.presentation.theme.WearbuddyTheme
import com.wear.wearbuddy.vm.AppViewModel
import com.wear.wearbuddy.vm.CloudModule
import com.wear.wearbuddy.vm.Overlay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 应用根：AppScaffold（顶部默认 TimeText 时钟）+ HorizontalPagerScaffold
 * （底部三点 HorizontalPageIndicator）。三页：任务 / 项目 / 我的；
 * 子页面以覆盖层显示，**左边缘**右滑返回（edgeSwipeToDismiss，防误退）。
 *
 * 性能：根组件只按字段订阅低频状态（主题/语言/覆盖层/提示），
 * 任务、历史等高频状态由各页面内部自行订阅，避免任何一处变化导致整树重组。
 */
@Composable
fun WearbuddyApp(viewModel: AppViewModel, updateModel: com.wear.wearbuddy.vm.UpdateViewModel? = null) {
    val ui = viewModel.ui

    val overlay by rememberStateOf(ui) { it.overlay }
    val themeColor by rememberStateOf(ui) { it.themeColor }
    val language by rememberStateOf(ui) { it.language }
    val fontScale by rememberStateOf(ui) { it.fontScale }
    val hapticsEnabled by rememberStateOf(ui) { it.hapticsEnabled }
    val toast by rememberStateOf(ui) { it.toast }

    CompositionLocalProvider(
        LocalStrings provides stringsOf(language),
        LocalHapticsEnabled provides hapticsEnabled
    ) {
        WearbuddyTheme(themeColor = themeColor, fontScale = fontScale) {
            AppScaffold {
                // Pager 状态必须放在覆盖层之外：否则打开/关闭子页面会重建 Pager，
                // 导致返回时总是落回任务首页。
                val pagerState = rememberPagerState(initialPage = 0) { 3 }
                val scope = rememberCoroutineScope()
                val currentOverlay = overlay
                // Keep the pager and its list states alive beneath child screens.
                Box(Modifier.fillMaxSize()) {
                    // 根页面返回：先退到上一页，只有停留在任务页时才交给系统退出应用
                    BackHandler(enabled = currentOverlay == null && pagerState.currentPage > 0) {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        }
                    }
                    CompositionLocalProvider(LocalScreenIsActive provides (currentOverlay == null)) {
                    HorizontalPagerScaffold(pagerState = pagerState) {
                        HorizontalPager(state = pagerState) { page ->
                            when (page) {
                                0 -> TasksPage(actions = viewModel)
                                1 -> ProjectsPage(actions = viewModel)
                                else -> SettingsPage(actions = viewModel, onCheckUpdate = { updateModel?.check(true) })
                            }
                        }
                    }
                    }
                    var swipedAway by remember { mutableStateOf(false) }
                    val lastOverlay = remember { mutableStateOf<Overlay?>(null) }
                    if (currentOverlay != null) lastOverlay.value = currentOverlay
                    LaunchedEffect(currentOverlay) {
                        if (currentOverlay != null) swipedAway = false
                    }
                    AnimatedVisibility(
                        visible = currentOverlay != null,
                        enter = fadeIn(tween(220)) + slideInHorizontally(tween(280)) { it / 5 },
                        // A completed gesture already animated the screen off the display.
                        exit = if (swipedAway) ExitTransition.None else
                            fadeOut(tween(180)) + slideOutHorizontally(tween(220)) { it / 5 }
                    ) {
                        BackHandler(enabled = currentOverlay != null) { viewModel.closeOverlay() }
                        Box(Modifier.fillMaxSize()) {
                            val stack = generateSequence(lastOverlay.value) { (it as? CloudModule)?.returnTo }
                                .toList().asReversed()
                            for (screen in stack) {
                                key(screen) {
                                    OverlayScreen(
                                        screen,
                                        active = screen == currentOverlay,
                                        onSwipedAway = {
                                            swipedAway = screen !is CloudModule
                                            viewModel.closeOverlay()
                                        },
                                        viewModel = viewModel
                                    )
                                }
                            }
                        }
                    }
                }

                if (updateModel != null) com.wear.wearbuddy.presentation.screens.UpdateDialog(updateModel)
                androidx.wear.compose.material3.AlertDialog(
                    visible = toast != null,
                    onDismissRequest = viewModel::clearToast,
                    title = { Text(if (language == "zh") "提示" else "Notice") },
                    text = { Text(toast.orEmpty()) },
                    confirmButton = {
                        androidx.wear.compose.material3.AlertDialogDefaults.ConfirmButton(onClick = viewModel::clearToast)
                    }
                )
            }
        }
    }
}

/** 覆盖层容器：只有从左边缘起的右滑才会返回，页面中间的横滑不再触发返回。 */
@Composable
private fun OverlayScreen(overlay: Overlay, active: Boolean, onSwipedAway: () -> Unit, viewModel: AppViewModel) {
    val dismissState = rememberSwipeToDismissBoxState()
    val edgeModifier = if (active) Modifier.edgeSwipeToDismiss(dismissState) else Modifier
    LaunchedEffect(active, dismissState.currentValue) {
        if (active && dismissState.currentValue == SwipeToDismissValue.Dismissed) onSwipedAway()
    }

    // The actual parent stays in the stack, visible beneath the foreground during the gesture.
    SwipeToDismissBox(state = dismissState, userSwipeEnabled = active) { isBackground ->
        if (isBackground) return@SwipeToDismissBox
        val visible = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        val layer = if (active) visible else visible.semantics { hideFromAccessibility() }
        CompositionLocalProvider(LocalScreenIsActive provides (active && LocalScreenIsActive.current)) {
        Box(layer) {
        when (overlay) {
            is Overlay.TaskDetail -> TaskDetailScreen(viewModel, edgeModifier)

            is Overlay.Compose -> ComposeScreen(viewModel, overlay.taskId, edgeModifier)

            Overlay.AddProject -> AddProjectScreen(viewModel, edgeModifier)

            Overlay.ModelPicker -> ModelPickerScreen(viewModel, edgeModifier)

            Overlay.Login -> LoginScreen(viewModel, edgeModifier)

            Overlay.Accounts -> com.wear.wearbuddy.presentation.screens.AccountsScreen(viewModel, edgeModifier)

            Overlay.About -> AboutScreen(edgeModifier)

            Overlay.Storage -> StorageScreen(edgeModifier)

            Overlay.Artifacts -> ArtifactsScreen(viewModel, edgeModifier)

            Overlay.Sessions -> SessionsScreen(viewModel, edgeModifier)

            Overlay.Agents -> AgentsScreen(viewModel, edgeModifier)

            Overlay.Checkin -> CheckinScreen(viewModel, edgeModifier)

            Overlay.GrowthPlan -> GrowthPlanScreen(viewModel, edgeModifier)

            Overlay.CloudTools -> CloudToolsScreen(viewModel, edgeModifier)

            is CloudModule -> CloudToolScreen(overlay.destination, viewModel, edgeModifier, overlay.entry)
        }
        }
        }
    }
}

/** 按选择器订阅 UiState 的单个字段，只有该字段真正变化时才重组读取处。 */
@Composable
private fun <T> rememberStateOf(
    flow: kotlinx.coroutines.flow.StateFlow<com.wear.wearbuddy.vm.UiState>,
    selector: (com.wear.wearbuddy.vm.UiState) -> T
): androidx.compose.runtime.State<T> =
    androidx.compose.runtime.remember(flow, selector) {
        flow.map(selector).distinctUntilChanged()
    }.collectAsState(initial = remember(flow) { selector(flow.value) })
