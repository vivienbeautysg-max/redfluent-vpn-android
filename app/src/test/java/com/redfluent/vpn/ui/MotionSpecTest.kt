package com.redfluent.vpn.ui

import android.view.HapticFeedbackConstants
import com.redfluent.vpn.ui.components.Haptic
import com.redfluent.vpn.ui.components.Motion
import com.redfluent.vpn.ui.components.hapticConstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 动效规范的数值、触感映射、页面层级（推入 / 返回方向）。 */
class MotionSpecTest {

    @Test
    fun specNumbers() {
        assertEquals(0.96f, Motion.PRESS_SCALE)
        assertEquals(0.94f, Motion.POWER_PRESS_SCALE)
        assertEquals(250, Motion.PUSH_MS)
        assertEquals(150, Motion.EXIT_MS)
        assertEquals(250, Motion.THEME_MS)
        assertEquals(80, Motion.ROW_IN_MS)
        assertEquals(200, Motion.ROW_OUT_MS)
        assertEquals(1200, Motion.BREATH_MS)
        assertEquals(400, Motion.GLOW_IN_MS)
        assertEquals(8, Motion.ENTER_RISE_DP)
    }

    @Test
    fun staggerIsAtMost40msAndAtMostSixBlocks() {
        assertEquals(0L, Motion.staggerDelayMs(0))
        assertEquals(40L, Motion.staggerDelayMs(1))
        assertEquals(200L, Motion.staggerDelayMs(5))
        assertEquals(200L, Motion.staggerDelayMs(9)) // 第 7 块起不再往后拖
        assertEquals(0L, Motion.staggerDelayMs(-3))
        for (i in 1..10) assertTrue(Motion.staggerDelayMs(i) - Motion.staggerDelayMs(i - 1) <= 40L)
    }

    @Test
    fun hapticsPerApiLevel() {
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, hapticConstant(Haptic.TAP, 24))
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, hapticConstant(Haptic.CONFIRM, 29))
        assertEquals(HapticFeedbackConstants.CONFIRM, hapticConstant(Haptic.CONFIRM, 30))
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, hapticConstant(Haptic.REJECT, 29))
        assertEquals(HapticFeedbackConstants.REJECT, hapticConstant(Haptic.REJECT, 30))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, hapticConstant(Haptic.TICK, 33))
        assertEquals(HapticFeedbackConstants.SEGMENT_TICK, hapticConstant(Haptic.TICK, 34))
        // 没有用 LONG_PRESS（那是「长按」的语义，太重）
        for (k in Haptic.entries) for (sdk in 24..36) assertTrue(hapticConstant(k, sdk) != HapticFeedbackConstants.LONG_PRESS)
    }

    @Test
    fun screenDepthsDecidePushAndPop() {
        assertEquals(Screen.Onboarding, screenOf(onboarded = false, signedIn = true, adminOpen = true, route = AdminRoute.People))
        assertEquals(Screen.Activation, screenOf(onboarded = true, signedIn = false, adminOpen = true, route = AdminRoute.People))
        assertEquals(Screen.Home, screenOf(onboarded = true, signedIn = true, adminOpen = false, route = AdminRoute.Deleted))
        assertEquals(Screen.People, screenOf(true, true, true, AdminRoute.People))
        assertEquals(Screen.Person("C1"), screenOf(true, true, true, AdminRoute.Person("C1")))
        assertEquals(Screen.Deleted, screenOf(true, true, true, AdminRoute.Deleted))
        assertTrue(Screen.Person("x").depth > Screen.People.depth && Screen.People.depth > Screen.Home.depth)
        // 个人页改了邀请码不算换页（不做转场）
        assertEquals(Screen.Person("a").key, Screen.Person("b").key)
    }
}
