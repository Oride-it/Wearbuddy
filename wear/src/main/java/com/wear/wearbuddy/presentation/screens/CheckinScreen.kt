package com.wear.wearbuddy.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.wear.wearbuddy.presentation.EmptyHint
import com.wear.wearbuddy.presentation.LocalStrings
import com.wear.wearbuddy.presentation.SectionCard
import com.wear.wearbuddy.presentation.plus
import com.wear.wearbuddy.presentation.rememberClickHaptic
import com.wear.wearbuddy.vm.AppViewModel

/** 每日签到（覆盖页）：显示签到状态并领取当日积分。 */
@Composable
fun CheckinScreen(actions: AppViewModel, modifier: Modifier = Modifier) {
    val state by actions.ui.collectAsState()
    val s = LocalStrings.current
    val listState = rememberTransformingLazyColumnState()
    val haptic = rememberClickHaptic()
    val info = state.checkin

    ScreenScaffold(scrollState = listState, modifier = modifier) { padding ->
        TransformingLazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = padding + PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = 26.dp,
                bottom = 40.dp
            )
        ) {
            item {
                ListHeader(
                    modifier = Modifier.fillMaxWidth(),
                    transformation = null
                ) {
                    Text(s.checkinTitle)
                }
            }

            item {
                SectionCard(
                    title = s.checkinTitle,
                    shape = RoundedCornerShape(16.dp),
                    transformation = null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when {
                        state.checkinLoading && info == null -> Text(
                            s.loading,
                            style = MaterialTheme.typography.bodySmall
                        )

                        state.checkinError != null && info == null -> Text(
                            state.checkinError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )

                        info == null -> EmptyHint(s.checkinInactive)

                        !info.active -> Text(
                            s.checkinInactive,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        else -> {
                            Text(
                                if (info.todayCheckedIn) s.checkinTodayDone else s.checkinTodayTodo,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (info.todayCheckedIn) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${s.checkinStreak} ${info.streakDays} ${s.days}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (info.dailyCredit > 0) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${s.checkinDaily} +${info.dailyCredit}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            if (info?.active == true) {
                item {
                    FilledTonalButton(
                        onClick = {
                            haptic()
                            actions.claimCheckin()
                        },
                        enabled = !state.checkinLoading && info?.todayCheckedIn != true,
                        modifier = Modifier.fillMaxWidth(),
                        transformation = null
                    ) {
                        Icon(
                            Icons.Rounded.EventAvailable,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            if (info?.todayCheckedIn == true) s.checkinTodayDone else s.checkinClaim,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
