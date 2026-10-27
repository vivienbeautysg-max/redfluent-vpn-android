package com.redfluent.vpn.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.redfluent.vpn.ui.components.DotStyle
import com.redfluent.vpn.ui.components.DotTitle
import com.redfluent.vpn.ui.components.Haptic
import com.redfluent.vpn.ui.components.LandingArt
import com.redfluent.vpn.ui.components.PrimaryButton
import com.redfluent.vpn.ui.components.Wordmark
import com.redfluent.vpn.ui.theme.Gutter
import com.redfluent.vpn.ui.theme.Rf
import com.redfluent.vpn.ui.theme.RfType
import kotlinx.coroutines.launch

const val PRIVACY_URL = "https://www.redfluent.com/privacy"
const val TERMS_URL = "https://www.redfluent.com/terms"
const val SUPPORT_URL = "https://www.redfluent.com/support"

private val TITLES = listOf("Redfluent VPN", "Invite only", "Private")

/** 三页安静的引导：红点落到线上，每页一个词，不解释（同 iOS OnboardingView）。 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pager = rememberPagerState { TITLES.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == TITLES.lastIndex
    BoxWithConstraints(Modifier.fillMaxSize().background(Rf.colors.paper)) {
        val compact = maxHeight < 700.dp
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Box(Modifier.padding(horizontal = Gutter).height(50.dp), contentAlignment = Alignment.CenterStart) { Wordmark() }
            Spacer(Modifier.weight(1f))
            LandingArt(landed = last, glowing = last, height = if (compact) 190f else 236f, showsPower = false)
            Spacer(Modifier.weight(1f))
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().height(if (compact) 70.dp else 80.dp)) { i ->
                Box(Modifier.fillMaxSize().padding(horizontal = Gutter), contentAlignment = Alignment.CenterStart) {
                    DotTitle(TITLES[i], size = if (compact) 44f else 50f, dot = DotStyle.FILLED)
                }
            }
            Row(
                Modifier.padding(horizontal = Gutter).padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TITLES.indices.forEach { i ->
                    val on = i == pager.currentPage
                    val w by animateDpAsState(if (on) 22.dp else 8.dp, label = "dot")
                    Box(
                        Modifier.size(width = w, height = 8.dp)
                            .background(if (on) Rf.colors.red else Rf.colors.rule, RoundedCornerShape(50)),
                    )
                }
            }
            PrimaryButton(
                text = if (last) "Get started" else "Next",
                modifier = Modifier
                    .padding(horizontal = Gutter)
                    .padding(top = if (compact) 22.dp else 34.dp, bottom = if (compact) 16.dp else 28.dp),
            ) {
                if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            }
        }
    }
}

/** 激活页：字标、悬空的红点、"Invite code."、输入框、Activate（同 iOS InviteActivationView）。 */
@Composable
fun ActivationScreen(
    activating: Boolean,
    error: String?,
    onActivate: (String) -> Unit,
    /** 设备被 owner 登出后的一句话提示（平静的提示，不是错误）。 */
    notice: String? = null,
) {
    var code by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val view = LocalView.current
    val scroll = rememberScrollState()
    // 输入时跟着键盘把输入框、错误和 Activate 一起推到键盘上方：小屏键盘弹着也看得到按钮和结果
    LaunchedEffect(focused) {
        if (focused) snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Rf.colors.paper)) {
        val compact = maxHeight < 700.dp
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .clipToBounds()
                .verticalScroll(scroll)
                .padding(bottom = 20.dp),
        ) {
            Box(Modifier.padding(horizontal = Gutter).height(50.dp), contentAlignment = Alignment.CenterStart) { Wordmark() }
            LandingArt(
                landed = false,
                height = if (compact) 150f else 200f,
                showsPower = false,
                modifier = Modifier.padding(top = if (compact) 0.dp else 12.dp),
            )
            Column(Modifier.padding(horizontal = Gutter)) {
                DotTitle(
                    "Invite code",
                    size = if (compact) 44f else 50f,
                    modifier = Modifier.padding(top = if (compact) 10.dp else 20.dp),
                )
                if (notice != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.Logout,
                            contentDescription = null,
                            tint = Rf.colors.red,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(notice, style = RfType.text(14f, FontWeight.SemiBold), color = Rf.colors.red)
                    }
                }
                BasicTextField(
                    value = code,
                    onValueChange = { code = it },
                    enabled = activating.not(),
                    singleLine = true,
                    textStyle = RfType.mono(20f, medium = true).copy(color = Rf.colors.ink),
                    cursorBrush = SolidColor(Rf.colors.red),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { if (code.isNotBlank()) onActivate(code) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (compact) 16.dp else 26.dp)
                        .onFocusChanged { focused = it.isFocused }
                        .semantics { contentDescription = "Invite code" },
                    decorationBox = { inner ->
                        Column {
                            Box(Modifier.padding(vertical = 14.dp)) {
                                if (code.isEmpty()) {
                                    Text("RF-XXXX-XXXX", style = RfType.mono(20f, medium = true), color = Rf.colors.stone)
                                }
                                inner()
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(if (focused) 1.5.dp else 1.dp)
                                    .background(if (focused) Rf.colors.ink else Rf.colors.rule),
                            )
                        }
                    },
                )
                if (error != null) {
                    Text(
                        error,
                        style = RfType.text(13f, FontWeight.Medium),
                        color = Rf.colors.red,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                // 与 iOS 一样：只在忙的时候禁用；空着点一下只给一个「不行」的触感
                PrimaryButton(
                    text = "Activate",
                    enabled = activating.not(),
                    busy = activating,
                    modifier = Modifier.padding(top = 22.dp),
                ) {
                    if (code.isBlank()) Haptic.REJECT.perform(view) else onActivate(code)
                }
                FooterLinks(onOpen = { runCatching { uri.openUri(it) } }, modifier = Modifier.padding(top = 26.dp))
            }
        }
    }
}

/** Privacy · Terms · Support（同 iOS 激活页底部）。 */
@Composable
fun FooterLinks(onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        listOf("Privacy" to PRIVACY_URL, "Terms" to TERMS_URL, "Support" to SUPPORT_URL).forEach { (title, url) ->
            Text(
                title,
                style = RfType.text(13f, FontWeight.Medium),
                color = Rf.colors.stone,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button) { onOpen(url) }
                    .padding(vertical = 8.dp, horizontal = 2.dp),
            )
        }
    }
}
