package com.wear.wearbuddy.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.wear.wearbuddy.presentation.EmptyHint
import com.wear.wearbuddy.presentation.LocalStrings
import com.wear.wearbuddy.presentation.plus
import com.wear.wearbuddy.vm.AppViewModel

/** 云端 Agent 总览（覆盖页）：只读展示账号已获授权的 Agent。 */
@Composable
fun AgentsScreen(actions: AppViewModel, modifier: Modifier = Modifier) {
    val state by actions.ui.collectAsState()
    val s = LocalStrings.current
    val listState = rememberTransformingLazyColumnState()

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
                    Text(s.agentsTitle)
                }
            }

            when {
                state.agentsLoading && state.agents.isEmpty() -> item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(s.loading, style = MaterialTheme.typography.labelSmall)
                    }
                }

                state.agents.isEmpty() -> item {
                    EmptyHint(
                        state.agentsError ?: s.agentsEmpty,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                else -> items(state.agents, key = { it.id }) { agent ->
                    Card(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.SmartToy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                agent.name,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                if (agent.enabled) s.agentEnabled else s.agentDisabled,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (agent.enabled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        agent.description?.let { description ->
                            Text(
                                description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
