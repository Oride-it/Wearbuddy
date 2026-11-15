package com.wear.wearbuddy.presentation.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.wear.wearbuddy.presentation.LocalStrings
import com.wear.wearbuddy.presentation.plus
import com.wear.wearbuddy.presentation.rememberClickHaptic
import com.wear.wearbuddy.vm.AppViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.wear.wearbuddy.data.RemoteModel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

/** 模型选择（覆盖页）：输入条左侧圆圈进入。当前选中高亮，选择后返回。 */
@Composable
fun ModelPickerScreen(
    actions: AppViewModel,
    modifier: Modifier = Modifier
) {
    val pickerState = remember(actions) {
        actions.ui.map { ModelPickerState(it.models, it.selectedModel, it.modelsLoading, it.modelsError) }
            .distinctUntilChanged()
    }
    val state by pickerState.collectAsState(initial = actions.ui.value.let {
        ModelPickerState(it.models, it.selectedModel, it.modelsLoading, it.modelsError)
    })
    ModelPickerContent(state, actions::chooseModel, modifier)
}

internal data class ModelPickerState(
    val models: List<RemoteModel>,
    val selectedModel: String,
    val modelsLoading: Boolean,
    val modelsError: String?
)

@Composable
internal fun ModelPickerContent(
    state: ModelPickerState,
    onChoose: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    val listState = rememberLazyListState()
    val haptic = rememberClickHaptic()

    ScreenScaffold(scrollState = listState, modifier = modifier) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = padding + PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 26.dp,
                bottom = 40.dp
            )
        ) {
            item(key = "header", contentType = "header") {
                ListHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        ,
                    transformation = null
                ) {
                    Text(s.modelPickerTitle)
                }
            }

            if (state.modelsLoading) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp))
                    }
                }
            }

            state.modelsError?.let { err ->
                item {
                    Text(
                        err,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            
                            .padding(vertical = 8.dp)
                    )
                }
            }

            items(state.models, key = { it.id }, contentType = { "model" }) { model ->
                val selected = model.id == state.selectedModel
                Button(
                    onClick = {
                        haptic()
                        onChoose(model.id)
                    },
                    modifier = Modifier
                        .fillMaxWidth().heightIn(min = 52.dp),
                    transformation = null,
                    colors = if (selected) {
                        ButtonDefaults.filledVariantButtonColors()
                    } else {
                        ButtonDefaults.filledTonalButtonColors()
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (selected) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize)
                            )
                        }
                        Text(
                            model.name,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        model.credits?.let { credits ->
                            Text(credits, maxLines = 1, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
