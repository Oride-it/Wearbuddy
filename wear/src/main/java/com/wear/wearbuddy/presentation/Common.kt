package com.wear.wearbuddy.presentation

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Input
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.wear.wearbuddy.data.ChatMessage
import com.wear.wearbuddy.data.ChatRole
import com.wear.wearbuddy.data.TaskStatus

/** 触感反馈开关（由设置驱动）。 */
val LocalHapticsEnabled = staticCompositionLocalOf { true }

/** Long press copies the complete text, including content hidden by ellipsis. */
@Composable
fun rememberCopyText(): (String) -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    val strings = LocalStrings.current
    return remember(context, strings) { { text: String ->
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(strings.copyTextLabel, text))
        android.widget.Toast.makeText(context, strings.copiedText, android.widget.Toast.LENGTH_SHORT).show()
    } }
}

@Composable
fun Modifier.copyOnLongPress(text: String): Modifier {
    val copy = rememberCopyText()
    return this.then(Modifier.combinedClickable(onClick = {}, onLongClickLabel = LocalStrings.current.copyTextLabel,
        onLongClick = { copy(text) }))
}

/** 主操作点击时的一次触感反馈。 */
@Composable
fun rememberClickHaptic(): () -> Unit {
    val feedback = LocalHapticFeedback.current
    val enabled = LocalHapticsEnabled.current
    return remember(feedback, enabled) {
        if (enabled) {
            { feedback.performHapticFeedback(HapticFeedbackType.LongPress) }
        } else {
            {}
        }
    }
}

@Composable
fun statusLabelOf(status: TaskStatus): String {
    val s = LocalStrings.current
    return when (status) {
        TaskStatus.WORKING -> s.statusExecuting
        TaskStatus.BLOCKED -> s.statusWaitingInput
        TaskStatus.DONE -> s.statusDone
        TaskStatus.FAILED -> s.statusFailed
        TaskStatus.STOPPED -> s.statusStopped
    }
}

@Composable
fun statusColorOf(status: TaskStatus): Color = when (status) {
    TaskStatus.WORKING -> MaterialTheme.colorScheme.primary
    TaskStatus.BLOCKED -> MaterialTheme.colorScheme.tertiary
    TaskStatus.DONE -> MaterialTheme.colorScheme.primary
    TaskStatus.FAILED -> MaterialTheme.colorScheme.error
    TaskStatus.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 状态小徽标：圆角胶囊文字（配合卡片使用）。 */
@Composable
fun StatusBadge(status: TaskStatus, modifier: Modifier = Modifier) {
    val color = statusColorOf(status)
    Box(
        modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            statusLabelOf(status),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/** 合并两份 PaddingValues（ScreenScaffold 的内边距 + 页面自己的边距）。 */
@Composable
operator fun PaddingValues.plus(other: PaddingValues): PaddingValues {
    val dir = androidx.compose.ui.platform.LocalLayoutDirection.current
    return PaddingValues(
        start = calculateLeftPadding(dir) + other.calculateLeftPadding(dir),
        top = calculateTopPadding() + other.calculateTopPadding(),
        end = calculateRightPadding(dir) + other.calculateRightPadding(dir),
        bottom = calculateBottomPadding() + other.calculateBottomPadding()
    )
}

/** 灰色分组卡片：默认圆弧外形，介绍等长文本可单独指定圆角矩形。 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    transformation: SurfaceTransformation? = null,
    shape: androidx.compose.ui.graphics.Shape = androidx.wear.compose.material3.CardDefaults.shape,
    onClick: () -> Unit = {},
    titleColor: Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        transformation = transformation,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = titleColor,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Spacer(Modifier.height(6.dp))
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

/** 分组内的一行：图标 + 标签 + 尾值 + 箭头，整行可点击。 */
@Composable
fun SettingRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    value: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val haptic = rememberClickHaptic()
    androidx.wear.compose.material3.FilledTonalButton(
        onClick = { haptic(); onClick() }, modifier = modifier.fillMaxWidth()
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (value != null) { Spacer(Modifier.width(6.dp)); Text(value, maxLines = 1) }
        trailing?.invoke()
    }
}

/** 底部输入条：左侧选模型，右侧打开「发消息」输入页。 */
@Composable
fun InputBar(
    onModelClick: () -> Unit,
    onOpenInput: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val haptic = rememberClickHaptic()
    val buttonShapes = IconButtonDefaults.shapes(shape = RoundedCornerShape(percent = 50))
    val buttonSize = Modifier.size(width = 58.dp, height = 48.dp)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        FilledTonalIconButton(
            onClick = {
                haptic()
                onModelClick()
            },
            modifier = buttonSize,
            shapes = buttonShapes,
            enabled = enabled
        ) {
            Icon(
                Icons.Rounded.SmartToy,
                contentDescription = LocalStrings.current.modelPickerTitle,
                modifier = Modifier.size(22.dp)
            )
        }
        FilledTonalIconButton(
            onClick = {
                haptic()
                onOpenInput()
            },
            modifier = buttonSize,
            shapes = buttonShapes,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            enabled = enabled
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.Input,
                contentDescription = LocalStrings.current.composeTitle,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** 多行文本输入（新建项目 / 登录表单）。 */
@Composable
fun FieldInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 4,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions =
        androidx.compose.foundation.text.KeyboardOptions.Default
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        if (value.isEmpty()) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            maxLines = maxLines,
            keyboardOptions = keyboardOptions,
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 对话消息：用户 / 助手正文使用灰色容器；思考过程和工具调用采用弱化样式。
 * 工具调用条目学官方移动端：扳手图标 + 等宽命令 + 右侧执行状态。
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    val copy = rememberCopyText()
    when (message.role) {
        ChatRole.THOUGHT -> ThoughtCard(message.text, copy, modifier)
        ChatRole.TOOL -> ToolCallRow(message.text, copy, modifier)
        else -> {
            Card(
                onClick = {},
                onLongClick = { copy(message.text) },
                onLongClickLabel = LocalStrings.current.copyTextLabel,
                modifier = modifier.fillMaxWidth(),
                transformation = null,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * 工具调用条目（学官方移动端的执行日志样式）：
 * 左侧扳手图标，中间等宽字体的工具名 / 命令，右侧执行状态（✓ / ✕ / 转圈）。
 */
@Composable
private fun ToolCallRow(
    text: String,
    copy: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val label = text.substringBefore('\n')
    val mark = if (text.contains('\n')) text.substringAfter('\n') else ""
    Row(
        verticalAlignment = Alignment.Top,
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClickLabel = LocalStrings.current.copyTextLabel,
                onLongClick = { copy(text) }
            )
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Icon(
            Icons.Rounded.Build,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        when (mark) {
            "✓" -> Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            "✕" -> Icon(
                Icons.Rounded.Close,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.error
            )
            "…" -> CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp
            )
        }
    }
}

/**
 * 思考过程：可折叠卡片（默认收起，学官方移动端）。
 * 标题行是「思考过程」+ 展开箭头，展开后显示灰色小字全文。
 */
@Composable
private fun ThoughtCard(
    text: String,
    copy: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val highlight = when {
        pressed -> 0.15f
        expanded -> 0.07f
        else -> 0f
    }
    val background by animateColorAsState(Color.White.copy(alpha = highlight).compositeOver(surface))
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = if (expanded) s.collapseThought else s.expandThought,
                onClick = { expanded = !expanded },
                onLongClick = { copy(text) },
                onLongClickLabel = s.copyTextLabel
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Psychology,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(6.dp))
            Text(
                s.thoughtLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            Text(
                text.trim(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 空态提示。 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 20.dp)
    )
}

/** 手机号打码：138****1234。 */
fun maskPhone(phone: String): String =
    if (phone.length == 11) phone.take(3) + "****" + phone.takeLast(4) else phone

/** 积分显示：整数不带小数点。 */
fun formatPoints(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString()
    else String.format(java.util.Locale.US, "%.1f", value)
