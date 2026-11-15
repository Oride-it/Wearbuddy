package com.wear.wearbuddy.presentation.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.wear.wearbuddy.presentation.FieldInput
import com.wear.wearbuddy.presentation.LocalStrings
import com.wear.wearbuddy.presentation.plus
import com.wear.wearbuddy.presentation.rememberClickHaptic
import com.wear.wearbuddy.vm.AppViewModel

/** 新建项目（覆盖页）：项目名称 + 命令 + 创建按钮，创建后留在项目页。 */
@Composable
fun AddProjectScreen(
    actions: AppViewModel,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    val listState = rememberTransformingLazyColumnState()
    val haptic = rememberClickHaptic()
    var name by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }

    ScreenScaffold(scrollState = listState, modifier = modifier) { padding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = padding + PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = 26.dp,
                bottom = 40.dp
            )
        ) {
            item {
                ListHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        ,
                    transformation = null
                ) {
                    Text(s.addProjectTitle)
                }
            }
            item {
                ListSubHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        ,
                    transformation = null,
                    label = { Text(s.projectName) }
                )
            }
            item {
                FieldInput(
                    value = name,
                    onValueChange = { name = it },
                    hint = s.projectName,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier
                        .fillMaxWidth()
                        
                )
            }
            item {
                ListSubHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        ,
                    transformation = null,
                    label = { Text(s.projectCommand) }
                )
            }
            item {
                FieldInput(
                    value = command,
                    onValueChange = { command = it },
                    hint = s.projectCommand,
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        
                )
            }
            item {
                Spacer(Modifier.height(6.dp))
            }
            item {
                Button(
                    onClick = {
                        haptic()
                        actions.addProject(name, command)
                    },
                    enabled = name.isNotBlank() && command.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        ,
                    transformation = null
                ) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(s.createProject, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
