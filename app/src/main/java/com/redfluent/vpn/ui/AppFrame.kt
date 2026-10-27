package com.redfluent.vpn.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.redfluent.vpn.data.Banner
import com.redfluent.vpn.data.FeedbackMachine
import com.redfluent.vpn.ui.components.Haptic
import com.redfluent.vpn.ui.components.Motion
import com.redfluent.vpn.ui.components.rememberReduceMotion
import com.redfluent.vpn.ui.components.softClick
import com.redfluent.vpn.ui.theme.Gutter
import com.redfluent.vpn.ui.theme.Rf
import com.redfluent.vpn.ui.theme.RfType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 一屏一个：深度决定推入 / 返回的方向。 */
sealed interface Screen {
    val depth: Int

    /** AnimatedContent 的键：同一种页面之间（比如个人页改了邀请码）不做转场。 */
    val key: String

    data object Onboarding : Screen { override val depth = 0; override val key = "onboarding" }
    data object Activation : Screen { override val depth = 0; override val key = "activation" }
    data object Home : Screen { override val depth = 1; override val key = "home" }
    data object People : Screen { override val depth = 2; override val key = "people" }
    data class Person(val code: String) : Screen { override val depth = 3; override val key = "person" }
    data object Deleted : Screen { override val depth = 3; override val key = "deleted" }
}

/** 由 Owner Admin 的路由得到当前页面。 */
fun screenOf(onboarded: Boolean, signedIn: Boolean, adminOpen: Boolean, route: AdminRoute): Screen = when {
    onboarded.not() -> Screen.Onboarding
    signedIn.not() -> Screen.Activation
    adminOpen.not() -> Screen.Home
    route is AdminRoute.Person -> Screen.Person(route.code)
    route == AdminRoute.Deleted -> Screen.Deleted
    else -> Screen.People
}

/**
 * 整个 app 的外框：
 *  - 页面之间 250ms 的 shared-axis 推入 / 返回（横移 30dp + 淡入淡出，退出 150ms）；移除动画时只淡入淡出；
 *  - 推入的页面（People / 个人页 / Recently Deleted）支持系统返回和 Android 14+ 的预测性返回（拖动时页面跟着缩、移）；
 *  - 顶部横幅（所有要等服务器的操作的结果）：不用滚动、键盘弹着也看得到；成功收键盘 + CONFIRM，失败 REJECT。
 */
@Composable
fun AppFrame(
    screen: Screen,
    onBack: () -> Unit,
    banner: Banner?,
    onDismissBanner: (Long) -> Unit,
    content: @Composable (Screen) -> Unit,
) {
    val reduce = rememberReduceMotion()
    val scope = rememberCoroutineScope()
    val back = remember { Animatable(0f) }
    val shift = with(LocalDensity.current) { 30.dp.roundToPx() }
    val peek = with(LocalDensity.current) { 32.dp.toPx() }

    PredictiveBackHandler(enabled = screen.depth >= 2) { progress ->
        try {
            progress.collect { e -> back.snapTo(e.progress) }
            onBack()
            scope.launch { back.animateTo(0f, tween(Motion.PUSH_MS)) }
        } catch (e: CancellationException) {
            scope.launch { back.animateTo(0f, tween(Motion.EXIT_MS)) }
        }
    }

    Box(Modifier.fillMaxSize().background(Rf.colors.paper)) {
        AnimatedContent(
            targetState = screen,
            contentKey = { it.key },
            transitionSpec = {
                val forward = targetState.depth > initialState.depth
                val push = targetState.depth != initialState.depth && targetState.depth >= 1 && initialState.depth >= 1
                if (reduce || push.not()) {
                    fadeIn(tween(Motion.PUSH_MS)) togetherWith fadeOut(tween(Motion.EXIT_MS))
                } else {
                    val spec = tween<androidx.compose.ui.unit.IntOffset>(Motion.PUSH_MS, easing = FastOutSlowInEasing)
                    (slideInHorizontally(spec) { if (forward) shift else -shift } + fadeIn(tween(Motion.PUSH_MS, delayMillis = 60))) togetherWith
                        (slideOutHorizontally(spec) { if (forward) -shift else shift } + fadeOut(tween(Motion.EXIT_MS)))
                }
            },
            label = "nav",
            modifier = Modifier.graphicsLayer {
                val p = back.value
                if (p > 0f) {
                    translationX = p * peek
                    scaleX = 1f - 0.06f * p
                    scaleY = 1f - 0.06f * p
                    alpha = 1f - 0.25f * p
                }
            },
        ) { s -> content(s) }

        TopBanner(banner, onDismissBanner)
    }
}

/** 共用的 Soft Landing 顶部横幅：滑入淡入；成功 2.6s、失败 6s 后自己收起，点一下立刻收起。 */
@Composable
fun TopBanner(banner: Banner?, onDismiss: (Long) -> Unit) {
    val view = LocalView.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var shown by remember { mutableStateOf<Banner?>(null) }
    if (banner != null) shown = banner
    LaunchedEffect(banner?.seq) {
        val b = banner ?: return@LaunchedEffect
        if (b.success) {
            focus.clearFocus()
            keyboard?.hide()
            Haptic.CONFIRM.perform(view)
        } else {
            Haptic.REJECT.perform(view)
        }
        delay(FeedbackMachine.showMillis(b))
        onDismiss(b.seq)
    }
    AnimatedVisibility(
        visible = banner != null,
        enter = fadeIn(tween(180)) + slideInVertically(tween(Motion.PUSH_MS, easing = FastOutSlowInEasing)) { -it / 2 },
        exit = fadeOut(tween(Motion.EXIT_MS)) + slideOutVertically(tween(Motion.EXIT_MS)) { -it / 3 },
        modifier = Modifier.fillMaxWidth().displayCutoutPadding().statusBarsPadding().padding(horizontal = Gutter - 8.dp, vertical = 6.dp),
        label = "banner",
    ) {
        val b = shown ?: return@AnimatedVisibility
        val tint = if (b.success) Rf.colors.ink else Rf.colors.red
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .shadow(10.dp, RoundedCornerShape(16.dp), clip = false)
                .background(Rf.colors.paper, RoundedCornerShape(16.dp))
                .background(Rf.colors.soft, RoundedCornerShape(16.dp))
                .border(1.dp, Rf.colors.rule, RoundedCornerShape(16.dp))
                .heightIn(min = 52.dp)
                .softClick { onDismiss(b.seq) }
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Icon(
                if (b.success) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = Rf.colors.red,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(b.text, style = RfType.text(14.5f, FontWeight.SemiBold), color = tint, maxLines = 3)
        }
    }
}
