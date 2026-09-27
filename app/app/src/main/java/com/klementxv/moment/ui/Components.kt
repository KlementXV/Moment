package com.klementxv.moment.ui

import com.klementxv.moment.i18n.Message
import com.klementxv.moment.i18n.tr

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp



@Composable
internal fun MomentSheet(
    title: String,
    onClose: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f))
            .pointerInput(onClose) { detectTapGestures { onClose?.invoke() } })
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(PanelRaised)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(32.dp, 4.dp)
                .clip(CircleShape).background(Outline))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, Modifier.weight(1f), style = HeadlineSm)
                if (onClose != null) IconButton(onClick = onClose, modifier = Modifier.offset(x = 12.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = tr(Message.Close), tint = White)
                }
            }
            content()
        }
    }
}

@Composable
internal fun SheetNote(text: String) {
    Text(text, Modifier.fillMaxWidth(), style = BodySm, color = Muted, textAlign = TextAlign.Center)
}


@Composable
internal fun Snackbar(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().widthIn(max = 360.dp).heightIn(min = 48.dp)
        .clip(RadiusMd).background(InverseSurface).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, Modifier.weight(1f), style = BodyMd, color = InkInverse)
    }
}


@Composable
internal fun AmountPicker(
    value: Long,
    min: Long,
    step: Long,
    max: Long,
    presets: List<Long>,
    supporting: String,
    error: Boolean,
    onChange: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            StepButton(Icons.Outlined.Remove, tr(Message.DecreaseStake),
                enabled = value - step >= min) { onChange(value - step) }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AmountInput(value, max, error, onChange)
                Text("SKR", Modifier.padding(bottom = 8.dp), style = TitleSm, color = Muted)
            }
            StepButton(Icons.Outlined.Add, tr(Message.IncreaseStake),
                enabled = value + step <= max) { onChange(value + step) }
        }
        if (presets.isNotEmpty()) Row(Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            presets.forEach { preset ->
                Chip(skr(preset), selected = preset == value, enabled = preset <= max) { onChange(preset) }
            }
        }
        Text(supporting, Modifier.fillMaxWidth(), style = BodyMd,
            color = if (error) Danger else Muted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AmountInput(value: Long, max: Long, error: Boolean, onChange: (Long) -> Unit) {
    var text by remember { mutableStateOf(formatSkrInput(value)) }
    var emitted by remember { mutableLongStateOf(value) }
    LaunchedEffect(value) {
        if (value != emitted) { text = formatSkrInput(value); emitted = value }
    }
    val style = TextStyle(fontSize = 48.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold,
        letterSpacing = (-.96).sp, color = if (error) Muted else White,
        fontFeatureSettings = "tnum")
    Box(contentAlignment = Alignment.CenterStart) {
        Text(text.ifEmpty { "0" }, style = style, modifier = Modifier.alpha(0f))
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                val kept = raw.filter { it.isDigit() || it == ',' || it == '.' }
                val amount = parseSkrAmount(kept)
                val next = (amount ?: 0).coerceAtMost(max)
                text = if (amount != null && amount > max) formatSkrInput(max) else kept
                emitted = next
                onChange(next)
            },
            textStyle = style,
            cursorBrush = SolidColor(Accent),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.matchParentSize()
                .semantics { contentDescription = tr(Message.AmountInSkr) },
        )
    }
}

@Composable
private fun StepButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
        Icon(icon, contentDescription = null, tint = if (enabled) White else Muted.copy(alpha = .38f))
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.height(40.dp).widthIn(min = 64.dp).clip(CircleShape)
            .background(if (selected) AccentContainer else Color.Transparent)
            .then(if (selected) Modifier
                else Modifier.border(1.dp, Outline.copy(alpha = if (enabled) 1f else .38f), CircleShape))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center) {
            Text(label, style = TitleSm,
                color = when {
                    selected -> OnAccentContainer
                    enabled -> White
                    else -> Muted.copy(alpha = .38f)
                })
        }
    }
}


@Composable
internal fun Shutter(
    fraction: Float,
    timeLabel: String,
    hint: String,
    late: Boolean = false,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: String? = null,
    label: String = tr(Message.CaptureMyMoment),
    onCapture: (() -> Unit)? = null,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(if (pressed) .94f else 1f,
            tween(120, easing = CubicBezierEasing(.2f, 0f, 0f, 1f)), label = "shutterPress")
        val button = if (onCapture == null) Modifier else Modifier
            .clip(CircleShape)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null,
                role = androidx.compose.ui.semantics.Role.Button, onClick = onCapture)
            .semantics { contentDescription = label }
        Box(contentAlignment = Alignment.Center) {
            if (!late && (enabled || loading)) Canvas(Modifier.size(168.dp)) {
                val glow = center + Offset(0f, 8.dp.toPx())
                drawCircle(
                    Brush.radialGradient(
                        0f to Coral.copy(alpha = .42f),
                        .42f to Coral.copy(alpha = .30f),
                        1f to Color.Transparent,
                        center = glow, radius = 84.dp.toPx(),
                    ),
                    radius = 84.dp.toPx(), center = glow,
                )
            }
            Box(Modifier.size(96.dp).then(button), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 4.dp.toPx()
                    val inset = stroke / 2
                    drawArc(SurfaceHigh, 0f, 360f, false,
                        topLeft = Offset(inset, inset),
                        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke))
                    drawArc(if (late) Brush.linearGradient(listOf(Danger, Danger)) else GradientBrand,
                        -90f, 360f * fraction.coerceIn(0f, 1f), false,
                        topLeft = Offset(inset, inset),
                        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Box(Modifier.size(72.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(CircleShape)
                    .alpha(if (enabled || loading) 1f else .38f)
                    .background(if (late) SolidColor(SurfaceHigh) else GradientBrand),
                    contentAlignment = Alignment.Center) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.size(28.dp), color = OnBrand, strokeWidth = 2.dp)
                        icon != null -> Glyph(icon, OnBrand)
                    }
                }
            }
        }
        Text(timeLabel, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
        Text(hint, Modifier.widthIn(max = 240.dp), style = BodyMd, color = Muted, textAlign = TextAlign.Center)
    }
}


@Composable
internal fun MomentPhoto(
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
    image: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth().aspectRatio(4f / 5f).clip(RadiusXl).background(PhotoField)) {
        image()
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.3f)
            .background(GradientScrim))
        overlay()
    }
}

internal val PhotoField = Brush.linearGradient(listOf(Coral.copy(alpha = .24f), OnBrand))

@Composable
internal fun BoxScope.MomentSelfie(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.align(Alignment.TopStart).padding(12.dp).fillMaxWidth(.28f)
        .aspectRatio(3f / 4f).clip(RadiusLg).background(OnBrand)
        .border(2.dp, White, RadiusLg),
        contentAlignment = Alignment.Center, content = content)
}


@Composable
internal fun MomentTextField(
    value: String,
    label: String,
    supporting: String,
    maxChars: Int,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val count = value.codePointCount(0, value.length)
    val border by animateDpAsState(if (focused) 2.dp else 1.dp, tween(150), label = "fieldBorder")
    val borderColor by animateColorAsState(if (focused) Accent else Outline, tween(150), label = "fieldBorderColor")
    val raised = focused || value.isNotEmpty()
    val labelScale by animateFloatAsState(if (raised) .75f else 1f,
        tween(150, easing = CubicBezierEasing(.2f, 0f, 0f, 1f)), label = "fieldLabel")
    val labelOffset by animateDpAsState(if (raised) 5.dp else 16.dp,
        tween(150, easing = CubicBezierEasing(.2f, 0f, 0f, 1f)), label = "fieldLabelOffset")
    Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else .38f)) {
        BasicTextField(
            value = value,
            onValueChange = { next ->
                val trimmed = if (next.codePointCount(0, next.length) <= maxChars) next
                    else next.substring(0, next.offsetByCodePoints(0, maxChars))
                onValueChange(trimmed)
            },
            enabled = enabled,
            interactionSource = interaction,
            textStyle = BodyLg.copy(color = White),
            cursorBrush = SolidColor(Accent),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().height(56.dp)
                .semantics { contentDescription = label },
            decorationBox = { field ->
                Box(Modifier.fillMaxSize().clip(RadiusLg).background(Panel)
                    .border(border, borderColor, RadiusLg).padding(horizontal = 16.dp)) {
                    Text(label, Modifier.align(Alignment.TopStart).offset(y = labelOffset)
                        .graphicsLayer {
                            scaleX = labelScale; scaleY = labelScale
                            transformOrigin = TransformOrigin(0f, 0f)
                        },
                        style = BodyLg, color = if (focused) Accent else Muted)
                    Box(Modifier.align(Alignment.TopStart).padding(top = 20.dp)) { field() }
                }
            },
        )
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(supporting, style = BodySm, color = Muted)
            Text("$count/$maxChars", style = BodySm, color = Muted)
        }
    }
}


internal enum class TagTone { Neutral, Alert, Success, Danger }

@Composable
internal fun MomentTag(label: String, tone: TagTone = TagTone.Neutral, modifier: Modifier = Modifier) {
    val (fill, ink) = when (tone) {
        TagTone.Neutral -> SurfaceHigh to White
        TagTone.Alert -> AccentContainer to OnAccentContainer
        TagTone.Success -> SuccessContainer to OnSuccessContainer
        TagTone.Danger -> DangerContainer to OnDangerContainer
    }
    Text(label, modifier.clip(CircleShape).background(fill).padding(horizontal = 8.dp),
        color = ink, style = LabelMd, maxLines = 1)
}


@Composable
internal fun TonalButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(CircleShape)
        .background(if (enabled) SurfaceHigh else SurfaceHigh.copy(alpha = .38f))
        .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
        .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center) {
        Text(text, style = LabelLg, color = if (enabled) White else Muted)
    }
}

@Composable
internal fun SecondaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(CircleShape)
        .border(1.dp, if (enabled) Outline else White.copy(alpha = .12f), CircleShape)
        .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
        .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center) {
        Text(text, style = LabelLg, color = if (enabled) White else Muted)
    }
}

@Composable
internal fun CardTextButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).clip(CircleShape)
        .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center) {
        Text(text, style = LabelLg, color = if (enabled) Accent else Muted)
    }
}


@Composable
internal fun WalletAddress(address: String, copied: Boolean, onCopy: () -> Unit) {
    Row(Modifier.height(40.dp).clip(CircleShape).background(SurfaceHigh)
        .clickable(onClickLabel = tr(Message.CopyAddress), role = androidx.compose.ui.semantics.Role.Button,
            onClick = onCopy)
        .padding(start = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (copied) tr(Message.AddressCopied) else "${address.take(4)}…${address.takeLast(4)}",
            style = LabelLg, color = if (copied) Success else White,
            letterSpacing = .28.sp)
        Glyph(if (copied) "check" else "copy", if (copied) Success else Muted)
    }
}


@Composable
private fun MomentCardSurface(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(Radius2xl).background(Panel)
        .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable
internal fun StakeCard(
    amount: Long,
    tag: String,
    tone: TagTone,
    line: String,
    action: String? = null,
    actionEnabled: Boolean = true,
    onAction: () -> Unit = {},
    secondAction: String? = null,
    onSecondAction: () -> Unit = {},
) {
    MomentCardSurface {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(tr(Message.Stake), style = LabelLg, color = Muted)
            MomentTag(tag, tone)
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(skr(amount), style = SkrLg)
            Text("SKR", Modifier.padding(bottom = 3.dp), style = LabelLg, color = Muted,
                letterSpacing = .56.sp)
        }
        Text(line, style = BodyMd)
        if (action != null || secondAction != null) Row(Modifier.padding(top = 8.dp).offset(x = (-12).dp)) {
            if (secondAction != null) CardTextButton(secondAction, actionEnabled, onSecondAction)
            if (action != null) CardTextButton(action, actionEnabled, onAction)
        }
    }
}

@Composable
internal fun PoolCard(total: Long, counts: String?, mineLabel: String, mine: Long) {
    MomentCardSurface {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(SurfaceHigh),
                contentAlignment = Alignment.Center) { Glyph("pool", White) }
            Text(tr(Message.TodaySPool), style = LabelLg, color = Muted)
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(skr(total), style = SkrLg)
            Text("SKR", Modifier.padding(bottom = 3.dp), style = LabelLg, color = Muted,
                letterSpacing = .56.sp)
        }
        if (counts != null) Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(counts, style = BodyMd, color = Muted)
            MomentTag(tr(Message.Demo), TagTone.Alert)
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(mineLabel, style = LabelLg, color = Muted)
            Text("${skr(mine)} SKR", style = LabelLg, color = Success)
        }
    }
}
