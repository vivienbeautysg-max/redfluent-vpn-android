package com.redfluent.vpn.ui.components

import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.redfluent.vpn.ui.theme.Rf
import kotlinx.coroutines.delay

/**
 * 动效规范（与 iOS 同一份 motion spec）：
 * 按压缩放 0.96（电源 0.94）、spring(0.8, MediumLow) 可打断；行按下 80ms 亮、200ms 退；
 * 推入/返回 250ms；退出 150ms；换主题 250ms 渐变；进场透明度 + 8dp 上浮、错开 ≤40ms、最多 6 块。
 * 一律只改 graphicsLayer / draw 阶段的值，避免每帧重组整页。
 */
object Motion {
    const val PRESS_SCALE = 0.96f
    const val POWER_PRESS_SCALE = 0.94f
    const val PUSH_MS = 250
    const val EXIT_MS = 150
    const val THEME_MS = 250
    const val ROW_IN_MS = 80
    const val ROW_OUT_MS = 200
    const val CHIP_MS = 150
    const val STAGGER_MS = 40L
    const val MAX_STAGGERED_BLOCKS = 6
    const val ENTER_RISE_DP = 8
    const val BREATH_MS = 1200
    const val GLOW_IN_MS = 400
    const val RELEASE_MS = 250

    fun <T> pressSpring() = spring<T>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)

    /** 第 index 块进场前等多久（超过 6 块的一起进，不再往后拖）。 */
    fun staggerDelayMs(index: Int): Long = index.coerceIn(0, MAX_STAGGERED_BLOCKS - 1) * STAGGER_MS
}

/** 系统「移除动画」（动画时长缩放 = 0）时为 true：位移类动画改成直接到位 / 只做淡入淡出，触感照旧。 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/** 触感：电源按下 / 连上 / 失败 / 选中变化（分段、快捷值、明暗）。 */
enum class Haptic {
    TAP, CONFIRM, REJECT, TICK;

    fun perform(view: View) {
        view.performHapticFeedback(hapticConstant(this, Build.VERSION.SDK_INT))
    }
}

/** 每种触感在某个系统版本上用哪个 HapticFeedbackConstants（纯函数，便于单测）。 */
fun hapticConstant(kind: Haptic, sdk: Int): Int = when (kind) {
    Haptic.TAP -> HapticFeedbackConstants.KEYBOARD_TAP
    Haptic.CONFIRM -> if (sdk >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
    Haptic.REJECT -> if (sdk >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.CONTEXT_CLICK
    Haptic.TICK -> if (sdk >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
}

/** 按下时缩一点（只动 graphicsLayer，不重组）；松手回弹，可被下一次按下打断。 */
fun Modifier.pressScale(interaction: InteractionSource, pressed: Float = Motion.PRESS_SCALE): Modifier = composed {
    val isPressed by interaction.collectIsPressedAsState()
    val s = animateFloatAsState(if (isPressed) pressed else 1f, Motion.pressSpring(), label = "press")
    graphicsLayer {
        scaleX = s.value
        scaleY = s.value
    }
}

/** Soft Landing 的可点控件：按压缩放代替 Material 涟漪，可带触感。 */
fun Modifier.softClick(
    enabled: Boolean = true,
    role: Role? = Role.Button,
    pressed: Float = Motion.PRESS_SCALE,
    haptic: Haptic? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val view = LocalView.current
    pressScale(interaction, pressed)
        .clickable(interaction, indication = null, enabled = enabled, role = role) {
            haptic?.perform(view)
            onClick()
        }
}

/** 列表行：按下 80ms 内浮出一层淡底，松手 200ms 退去（只在 draw 阶段读动画值）。 */
fun Modifier.rowClick(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val a = animateFloatAsState(
        if (isPressed) 1f else 0f,
        tween(if (isPressed) Motion.ROW_IN_MS else Motion.ROW_OUT_MS),
        label = "row",
    )
    val soft: Color = Rf.colors.soft
    drawBehind { if (a.value > 0f) drawRect(soft.copy(alpha = soft.alpha * a.value)) }
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
}

/** 进场：透明度 + 8dp 上浮，按 index 错开 40ms（最多 6 块）。移除动画时直接到位。 */
fun Modifier.enterRise(index: Int): Modifier = composed {
    val reduce = rememberReduceMotion()
    val a = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduce.not()) {
            delay(Motion.staggerDelayMs(index))
            a.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
        }
    }
    val rise = with(LocalDensity.current) { Motion.ENTER_RISE_DP.dp.toPx() }
    graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * rise
    }
}

/**
 * 数字：等宽数字（tnum），变了的那一位上下滑入（里程表式），其余位不动——不会左右跳。
 * 从右往左对位，位数变化时右边的位保持对齐。
 */
@Composable
fun RollingText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val reduce = rememberReduceMotion()
    val tnum = style.copy(fontFeatureSettings = "tnum")
    Row(modifier.clearAndSetSemantics { contentDescription = text }) {
        val n = text.length
        text.forEachIndexed { i, ch ->
            key(n - i) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        if (reduce) {
                            fadeIn(tween(90)) togetherWith fadeOut(tween(90))
                        } else {
                            (slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(180))) togetherWith
                                (slideOutVertically(tween(180)) { -it / 2 } + fadeOut(tween(120)))
                        }.using(SizeTransform(clip = true))
                    },
                    label = "digit",
                ) { c ->
                    Text(c.toString(), style = tnum, color = color, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
