package com.wear.wearbuddy.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.wear.wearbuddy.data.AskOption
import com.wear.wearbuddy.data.AskPick
import com.wear.wearbuddy.data.AskQuestion
import com.wear.wearbuddy.presentation.LocalStrings
import com.wear.wearbuddy.presentation.rememberClickHaptic

@Composable
internal fun AskCard(
    ask: AskQuestion,
    busy: Boolean,
    onSubmit: (Map<String, AskPick>?) -> Unit
) {
    val s = LocalStrings.current
    val haptic = rememberClickHaptic()
    var picks by remember(ask) { mutableStateOf<Map<String, AskPick>>(emptyMap()) }
    var customFields by remember(ask) { mutableStateOf<Set<String>>(emptySet()) }
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (item in ask.items) {
            val pick = picks[item.id] ?: AskPick()
            val showCustom = !ask.permission && (item.options.isEmpty() || item.id in customFields)
            item.header?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            Text(
                item.question.ifBlank { if (ask.permission) s.askPermission else s.askPrompt },
                style = MaterialTheme.typography.titleSmall
            )
            for (option in item.options) {
                val selected = option in pick.options
                AskOptionButton(option.displayLabel(), option.description, selected, !busy) {
                    haptic()
                    val selection = when {
                        item.multiSelect && selected -> pick.options - option
                        item.multiSelect -> pick.options + option
                        else -> listOf(option)
                    }
                    if (!item.multiSelect) customFields = customFields - item.id
                    picks = picks + (item.id to if (item.multiSelect) pick.copy(options = selection) else AskPick(selection))
                }
            }
            if (!ask.permission && item.options.isNotEmpty()) {
                AskOptionButton(s.askOther, null, showCustom, !busy) {
                    haptic()
                    customFields = if (showCustom) customFields - item.id else customFields + item.id
                    picks = picks + (item.id to if (item.multiSelect) pick.copy(custom = "") else AskPick())
                }
            }
            if (showCustom) {
                val color = MaterialTheme.colorScheme.onSurface
                BasicTextField(
                    value = pick.custom,
                    onValueChange = { picks = picks + (item.id to pick.copy(custom = it)) },
                    enabled = !busy,
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = color),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 8.dp, vertical = 9.dp),
                    decorationBox = { input ->
                        Box {
                            if (pick.custom.isEmpty()) {
                                Text(s.askAnswerHint, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            input()
                        }
                    }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    haptic()
                    onSubmit(picks)
                },
                enabled = !busy && picks.values.any { !it.isEmpty },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                transformation = null
            ) { Text(s.askConfirm, style = MaterialTheme.typography.labelSmall) }
            FilledTonalButton(
                onClick = {
                    haptic()
                    onSubmit(null)
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                transformation = null
            ) { Text(s.cancel, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun AskOption.displayLabel(): String {
    val s = LocalStrings.current
    return when (kind) {
        "allow_once" -> s.askAllowOnce
        "allow_always" -> s.askAllowAlways
        "reject_once" -> s.askRejectOnce
        "reject_always" -> s.askRejectAlways
        else -> label
    }
}

@Composable
private fun AskOptionButton(
    label: String,
    description: String?,
    checked: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val modifier = Modifier.fillMaxWidth().semantics { selected = checked }
    val content: @Composable () -> Unit = {
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (checked) {
        Button(onClick, modifier, enabled = enabled, transformation = null) { content() }
    } else {
        FilledTonalButton(onClick, modifier, enabled = enabled, transformation = null) { content() }
    }
}
