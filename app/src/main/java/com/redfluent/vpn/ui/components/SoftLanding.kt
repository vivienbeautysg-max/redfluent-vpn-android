package com.redfluent.vpn.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.redfluent.vpn.data.QuotaInput
import com.redfluent.vpn.ui.theme.Rf
import com.redfluent.vpn.ui.theme.RfFonts
import com.redfluent.vpn.ui.theme.RfType
import com.redfluent.vpn.ui.theme.fixedSp
import kotlinx.coroutines.launch

// "01 Soft Landing" 的积木，每个页面共用（对应 iOS App/Components/SoftLanding.swift）。

/** 品牌标：一条带浅凹的线，红点落在上面（120 x 120 网格）。 */
@Composable
fun BrandMark(size: Float = 30f) {
    val ink = Rf.colors.ink
    val red = Rf.colors.red
    Canvas(Modifier.size(size.dp)) {
        val k = this.size.width / 120f
        val line = Path().apply {
            moveTo(14 * k, 72 * k)
            lineTo(32 * k, 72 * k)
            cubicTo(43 * k, 72 * k, 44 * k, 84.5f * k, 60 * k, 84.5f * k)
            cubicTo(76 * k, 84.5f * k, 77 * k, 72 * k, 88 * k, 72 * k)
            lineTo(106 * k, 72 * k)
        }
        drawPath(line, ink, style = Stroke(width = 7 * k, cap = StrokeCap.Round))
        drawCircle(red, radius = 23 * k, center = Offset(60 * k, 58 * k))
    }
}

/** 标 + "Redfluent" + 小号 "VPN"。 */
@Composable
fun Wordmark(markSize: Float = 30f, nameSize: Float = 21f) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.clearAndSetSemantics { contentDescription = "Redfluent VPN" },
    ) {
        BrandMark(markSize)
        Text(
            "Redfluent",
            style = TextStyle(fontFamily = RfFonts.serif, fontSize = fixedSp(nameSize), letterSpacing = (-0.01).em),
            color = Rf.colors.ink,
        )
        Text(
            "VPN",
            style = RfType.text(nameSize * 0.5f, FontWeight.SemiBold).copy(letterSpacing = 0.2.em),
            color = Rf.colors.stone,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

enum class DotStyle { FILLED, HOLLOW, NONE }

/**
 * Fraunces 大标题，结尾一个点："Connected."（实心红点）/ "Disconnected."（空心）。
 * 点是文字里的内联元素，换行时跟着最后一个词走；放不下时字号自动缩小（最小 60%）。
 */
@Composable
fun DotTitle(
    text: String,
    size: Float = 50f,
    dot: DotStyle = DotStyle.FILLED,
    maxLines: Int = 1,
    modifier: Modifier = Modifier,
) {
    val red = Rf.colors.red
    val stone = Rf.colors.stone
    val annotated = buildAnnotatedString {
        append(text)
        if (dot != DotStyle.NONE) {
            append("⁠") // WORD JOINER：不留空格（同 iOS 贴着最后一个字母），换行时点也不会单独掉下去
            appendInlineContent("dot", ".")
        }
    }
    val inline = mapOf(
        "dot" to InlineTextContent(Placeholder(0.28.em, 0.22.em, PlaceholderVerticalAlign.AboveBaseline)) {
            Canvas(Modifier.fillMaxSize()) {
                val r = this.size.height / 2f
                val c = Offset(this.size.width - r, this.size.height - r)
                if (dot == DotStyle.FILLED) {
                    drawCircle(red, radius = r, center = c)
                } else {
                    val w = (size * 0.04f).coerceAtLeast(1.5f).dp.toPx()
                    drawCircle(stone, radius = r - w / 2f, center = c, style = Stroke(width = w))
                }
            }
        },
    )
    BasicText(
        text = annotated,
        inlineContent = inline,
        style = TextStyle(
            fontFamily = RfFonts.display,
            fontSize = fixedSp(size),
            letterSpacing = (-0.025).em,
            color = Rf.colors.ink,
            lineHeight = fixedSp(size * 1.12f),
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Clip,
        autoSize = TextAutoSize.StepBased(minFontSize = fixedSp(size * 0.6f), maxFontSize = fixedSp(size), stepSize = 1.sp),
        modifier = modifier,
    )
}

/** 1 px 细线（Rule 色）。 */
@Composable
fun Rule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Rf.colors.rule))
}

/** 圆形 "…" 按钮（菜单挂在它上面）：看起来 36dp，可点区域 48dp。 */
@Composable
fun MoreGlyph(onClick: () -> Unit) {
    val ink = Rf.colors.ink
    Box(
        Modifier
            .size(48.dp)
            .softClick(onClick = onClick)
            .semantics { contentDescription = "More" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(36.dp).border(1.dp, Rf.colors.rule, CircleShape), contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.5.dp)) {
                repeat(3) { Box(Modifier.size(3.4.dp).background(ink, CircleShape)) }
            }
        }
    }
}

/** 小号大写段标题（"DEVICES · 3"）。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = RfType.label(), color = Rf.colors.stone, modifier = modifier)
}

/** 描边胶囊标签："DEDICATED"（红）、"PAUSED"（Stone）。 */
@Composable
fun Tag(text: String, color: Color = Rf.colors.red) {
    Text(
        text.uppercase(),
        style = RfType.text(9.5f, FontWeight.SemiBold).copy(letterSpacing = 0.12.em),
        color = color,
        maxLines = 1,
        modifier = Modifier
            .border(1.dp, color, RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** 页面顶行：左边一个操作、居中标题、右边一个操作（48dp 高）。 */
@Composable
fun BarRow(
    title: String = "",
    leading: @Composable () -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Text(title, style = RfType.text(16.5f, FontWeight.SemiBold), color = Rf.colors.ink, maxLines = 1)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            leading()
            Spacer(Modifier.weight(1f).widthIn(min = 12.dp))
            trailing()
        }
    }
}

/** 顶行里的文字按钮：红色 Archivo 16.5，可点区域至少 48dp 高。 */
@Composable
fun BarButton(title: String, bold: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .softClick(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            style = RfType.text(16.5f, if (bold) FontWeight.SemiBold else FontWeight.Medium),
            color = if (enabled) Rf.colors.red else Rf.colors.stone,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/** "‹ People" 返回按钮。 */
@Composable
fun BackButton(title: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .softClick(onClick = onClick)
            .semantics { contentDescription = "Back to $title" },
    ) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = Rf.colors.red, modifier = Modifier.size(26.dp))
        Text(title, style = RfType.text(16.5f, FontWeight.Medium), color = Rf.colors.red, modifier = Modifier.padding(end = 4.dp))
    }
}

/** 红色胶囊主按钮，整行宽。按下缩到 0.96。 */
@Composable
fun PrimaryButton(
    text: String,
    enabled: Boolean = true,
    busy: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val fill by animateColorAsState(Rf.colors.red.copy(alpha = if (enabled) 1f else 0.4f), tween(Motion.CHIP_MS), label = "primary")
    Box(
        modifier
            .fillMaxWidth()
            .softClick(enabled = enabled && busy.not(), onClick = onClick)
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(50))
            .drawBehind { drawRect(fill) },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = busy, animationSpec = tween(Motion.EXIT_MS), label = "busy") { b ->
            if (b) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Rf.colors.onRed, strokeWidth = 2.dp)
            } else {
                Text(text, style = RfType.text(16f, FontWeight.SemiBold), color = Rf.colors.onRed)
            }
        }
    }
}

/** 描边胶囊按钮（Restore）：看起来 34dp 高，可点区域 48dp。 */
@Composable
fun OutlineButton(text: String, color: Color = Rf.colors.red, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).softClick(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .heightIn(min = 34.dp)
                .border(1.5.dp, color, RoundedCornerShape(50))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = RfType.text(14f, FontWeight.SemiBold), color = color)
        }
    }
}

/**
 * 几个选项里选一个：描边胶囊，选中的那段是一块 Ink 色药丸，切换时药丸滑过去（spring）。
 * 药丸的位置只在 draw 阶段读，滑动时不重组；每段的可点区域 48dp 高。选中变化有一下轻触感。
 */
@Composable
fun <T> SegmentedChoice(
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    val view = LocalView.current
    val reduce = rememberReduceMotion()
    val ink = Rf.colors.ink
    val rule = Rf.colors.rule
    val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val index = options.indexOfFirst { it.first == selected }
    val x = remember { Animatable(0f) }
    val w = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    val target = bounds[index]
    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        if (placed.not() || reduce) {
            x.snapTo(t.first)
            w.snapTo(t.second)
            placed = true
        } else {
            launch { x.animateTo(t.first, Motion.pressSpring()) }
            w.animateTo(t.second, Motion.pressSpring())
        }
    }
    Row(
        Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .drawBehind {
                val h = 36.dp.toPx()
                val top = (size.height - h) / 2f
                drawRoundRect(
                    rule,
                    topLeft = Offset(0.5f, top + 0.5f),
                    size = Size(size.width - 1f, h - 1f),
                    cornerRadius = CornerRadius(h / 2f),
                    style = Stroke(1.dp.toPx()),
                )
                if (placed && index >= 0) {
                    val ph = h - 4.dp.toPx()
                    drawRoundRect(
                        ink,
                        topLeft = Offset(x.value, top + 2.dp.toPx()),
                        size = Size(w.value, ph),
                        cornerRadius = CornerRadius(ph / 2f),
                    )
                }
            }
            .padding(horizontal = 2.dp),
    ) {
        options.forEachIndexed { i, (value, title) ->
            val on = i == index
            val color by animateColorAsState(if (on) Rf.colors.paper else Rf.colors.ink, tween(Motion.CHIP_MS), label = "seg")
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .onPlaced { c -> bounds[i] = c.positionInParent().x to c.size.width.toFloat() }
                    .heightIn(min = 48.dp)
                    .pressScale(interaction)
                    .clickable(interaction, indication = null, enabled = enabled, role = Role.Tab) {
                        if (on.not()) {
                            Haptic.TICK.perform(view)
                            onSelect(value)
                        }
                    }
                    .semantics { this.selected = on }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title,
                    style = RfType.text(14f, if (on) FontWeight.SemiBold else FontWeight.Medium),
                    color = color,
                    maxLines = 1,
                )
            }
        }
    }
}

/** − n + （设备上限这种小整数）。value 为 null 时显示 "—"，点 +/− 从 1 开始。 */
@Composable
fun NumberStepper(value: Int?, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        StepButton(Icons.Filled.Remove, "Decrease", enabled = value == null || value > range.first) {
            onChange(((value ?: range.first) - 1).coerceIn(range.first, range.last))
        }
        RollingText(
            value?.toString() ?: "—",
            style = TextStyle(fontFamily = RfFonts.serif, fontSize = fixedSp(20f)),
            color = Rf.colors.ink,
            modifier = Modifier.widthIn(min = 28.dp),
        )
        StepButton(Icons.Filled.Add, "Increase", enabled = value == null || value < range.last) {
            onChange(if (value == null) range.first else (value + 1).coerceIn(range.first, range.last))
        }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .softClick(enabled = enabled, haptic = Haptic.TICK, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(32.dp).border(1.dp, Rf.colors.rule, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = if (enabled) Rf.colors.ink else Rf.colors.stone, modifier = Modifier.size(16.dp))
        }
    }
}

/** 设置行：左边标签，右边控件，下面一条细线。 */
@Composable
fun SettingRow(label: String, control: @Composable RowScope.() -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = RfType.text(15f, FontWeight.Medium), color = Rf.colors.ink, maxLines = 1)
            Spacer(Modifier.width(12.dp))
            Spacer(Modifier.weight(1f))
            control()
        }
        Rule()
    }
}

/** 细条：已用部分 Ink，结尾一个红点。数值变化时平滑滑过去（只在 draw 阶段读）。 */
@Composable
fun UsageLine(fraction: Double, modifier: Modifier = Modifier) {
    val ink = Rf.colors.ink
    val soft = Rf.colors.soft
    val red = Rf.colors.red
    val f = animateFloatAsState(fraction.coerceIn(0.0, 1.0).toFloat(), tween(600, easing = FastOutSlowInEasing), label = "usage")
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        val w = size.width
        val cy = size.height / 2f
        val x = w * f.value
        val bar = 2.dp.toPx()
        drawLine(soft, Offset(0f, cy), Offset(w, cy), strokeWidth = bar, cap = StrokeCap.Round)
        if (x > 0f) drawLine(ink, Offset(0f, cy), Offset(x, cy), strokeWidth = bar, cap = StrokeCap.Round)
        val r = 7.dp.toPx()
        val dx = (x - r).coerceIn(0f, (w - 2 * r).coerceAtLeast(0f)) + r
        drawCircle(red, radius = r, center = Offset(dx, cy))
    }
}

/** 区域的小旗：东京日本旗，新加坡新加坡旗，其它一个素色块。 */
@Composable
fun RegionFlag(regionId: String?) {
    val rule = Rf.colors.rule
    val soft = Rf.colors.soft
    Canvas(
        Modifier
            .size(width = 22.dp, height = 15.dp)
            .clip(RoundedCornerShape(3.dp))
            .border(1.dp, rule, RoundedCornerShape(3.dp)),
    ) {
        when (regionId?.trim()?.lowercase()) {
            "tokyo" -> {
                drawRect(Color.White)
                drawCircle(Color(0xFFBC002D), radius = 4.dp.toPx())
            }
            "singapore" -> {
                drawRect(Color.White)
                drawRect(Color(0xFFEF3340), size = size.copy(height = size.height / 2f))
            }
            else -> drawRect(soft)
        }
    }
}

/** 主题里的纯文本输入框（没有框：行下面那条细线就是边）。 */
@Composable
fun ThemedField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    mono: Boolean = false,
    enabled: Boolean = true,
    numeric: Boolean = false,
    modifier: Modifier = Modifier,
    style: TextStyle? = null,
    align: TextAlign = TextAlign.End,
) {
    val base = style ?: if (mono) RfType.mono(15f) else RfType.text(15f)
    val textStyle = base.copy(color = if (enabled) Rf.colors.ink else Rf.colors.stone, textAlign = align)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(Rf.colors.red),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
            capitalization = if (mono || numeric) KeyboardCapitalization.None else KeyboardCapitalization.Words,
            autoCorrectEnabled = false,
        ),
        modifier = modifier.heightIn(min = 48.dp),
        decorationBox = { inner ->
            Box(
                Modifier.heightIn(min = 48.dp),
                contentAlignment = if (align == TextAlign.End) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(placeholder, style = textStyle.copy(color = Rf.colors.stone), maxLines = 1)
                inner()
            }
        },
    )
}

/** 月额度输入：1–2000 的任意整数，外加快捷值；哪里不对用一行短字说（淡入、微微上浮）。 */
@Composable
fun QuotaField(text: String, onChange: (String) -> Unit) {
    val result = QuotaInput.parse(text)
    val view = LocalView.current
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Monthly limit", style = RfType.text(15f, FontWeight.Medium), color = Rf.colors.ink, maxLines = 1)
            Spacer(Modifier.weight(1f))
            ThemedField(
                value = text,
                onValueChange = { onChange(it.take(8)) },
                placeholder = "200",
                numeric = true,
                style = TextStyle(fontFamily = RfFonts.serif, fontSize = fixedSp(22f), fontFeatureSettings = "tnum"),
                modifier = Modifier.widthIn(min = 60.dp, max = 110.dp).semantics { contentDescription = "Monthly limit in GB" },
            )
            Spacer(Modifier.width(12.dp))
            Text("GB", style = RfType.text(14f, FontWeight.Medium), color = Rf.colors.stone)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            QuotaInput.PRESETS.forEach { gb ->
                val on = result == QuotaInput.Result.Ok(gb)
                val fill by animateColorAsState(if (on) Rf.colors.ink else Color.Transparent, tween(Motion.CHIP_MS), label = "chip")
                val border by animateColorAsState(if (on) Color.Transparent else Rf.colors.rule, tween(Motion.CHIP_MS), label = "chipb")
                val label by animateColorAsState(if (on) Rf.colors.paper else Rf.colors.ink, tween(Motion.CHIP_MS), label = "chipt")
                Box(
                    Modifier
                        .heightIn(min = 48.dp)
                        .softClick(haptic = if (on) null else Haptic.TICK) { onChange(gb.toString()) }
                        .semantics { contentDescription = "$gb GB" },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .widthIn(min = 52.dp)
                            .heightIn(min = 32.dp)
                            .drawBehind {
                                val r = CornerRadius(size.height / 2f)
                                drawRoundRect(fill, cornerRadius = r)
                                drawRoundRect(border, cornerRadius = r, style = Stroke(1.dp.toPx()))
                            }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$gb", style = RfType.text(14f, if (on) FontWeight.SemiBold else FontWeight.Medium), color = label)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            val problem = (result as? QuotaInput.Result.Bad)?.problem
            AnimatedVisibility(
                visible = problem != null,
                enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 2 },
                exit = fadeOut(tween(Motion.EXIT_MS)),
                label = "quotaError",
            ) {
                // 退场动画期间 problem 已是 null：保留上一次的文字
                var last by remember { mutableStateOf(problem) }
                if (problem != null) last = problem
                Text(last?.message.orEmpty(), style = RfType.text(12.5f, FontWeight.Medium), color = Rf.colors.red, maxLines = 1)
            }
        }
    }
    Rule()
}
