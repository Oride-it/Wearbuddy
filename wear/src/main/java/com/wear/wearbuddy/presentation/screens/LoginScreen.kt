package com.wear.wearbuddy.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Login
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.wear.wearbuddy.presentation.LocalStrings
import com.wear.wearbuddy.presentation.plus
import com.wear.wearbuddy.presentation.rememberClickHaptic
import com.wear.wearbuddy.vm.AppViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * 登录（覆盖页）：手机号在上、验证码在下的两个输入框，点击调起系统输入法录入。
 * 手机号栏按「下一步」直接跳到验证码栏；验证码栏按「完成」直接提交登录。
 */
@Composable
fun LoginScreen(
    actions: AppViewModel,
    modifier: Modifier = Modifier
) {
    val state by actions.ui.collectAsState()
    val s = LocalStrings.current
    val form = state.login
    val listState = rememberTransformingLazyColumnState()
    val haptic = rememberClickHaptic()
    val focusManager = LocalFocusManager.current

    // 离开页面时收起输入法
    DisposableEffect(Unit) {
        onDispose { focusManager.clearFocus(force = true) }
    }

    ScreenScaffold(scrollState = listState, modifier = modifier) { padding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = padding + PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = 26.dp,
                bottom = 30.dp
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item(key = "title") {
                ListHeader(modifier = Modifier.fillMaxWidth()) {
                    Text(s.loginTitle)
                }
            }

            // 手机号（上）
            item(key = "phone") {
                LoginField(
                    label = s.phonePlaceholder,
                    value = form.phone,
                    onValueChange = actions::setLoginPhone,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Next) }
                    )
                )
            }

            // 验证码（下）
            item(key = "code") {
                LoginField(
                    label = s.codePlaceholder,
                    value = form.code,
                    onValueChange = actions::setLoginCode,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            actions.submitLogin()
                        }
                    )
                )
            }

            // 获取验证码
            item(key = "send") {
                Button(
                    onClick = {
                        haptic()
                        actions.sendLoginCode()
                    },
                    enabled = form.phone.length == 11 && !form.sending && form.countdown <= 0,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (form.sending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    } else if (form.countdown > 0) {
                        Text("${form.countdown}s", maxLines = 1)
                    } else {
                        Text(s.getCode, maxLines = 1)
                    }
                }
            }

            form.error?.let { err ->
                item(key = "error") {
                    Text(
                        err,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 登录
            item(key = "submit") {
                Button(
                    onClick = {
                        haptic()
                        focusManager.clearFocus()
                        actions.submitLogin()
                    },
                    enabled = form.phone.length == 11 && form.code.length >= 4 && !form.submitting,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledVariantButtonColors()
                ) {
                    if (form.submitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Login,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(s.submitLogin, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * 登录输入框：标签 + 胶囊输入区。点击整框即聚焦并调起系统输入法；
 * 聚焦时容器换高亮底色提示当前输入位置。
 */
@Composable
private fun LoginField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions
) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(
                    if (focused) MaterialTheme.colorScheme.surfaceContainerHigh
                    else MaterialTheme.colorScheme.surfaceContainer
                )
                .clickable { focusRequester.requestFocus() }
                .defaultMinSize(minHeight = 40.dp)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (value.isEmpty()) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused }
            )
        }
    }
}
