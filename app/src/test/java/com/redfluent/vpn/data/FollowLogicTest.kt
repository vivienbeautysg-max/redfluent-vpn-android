package com.redfluent.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.2 "无感"跟随的决策（纯函数）与取配置时的 region 参数。 */
class FollowLogicTest {

    private val tokyo = RegionOption("tokyo", "Tokyo", true)
    private val singapore = RegionOption("singapore", "Singapore")

    // ---- 取到配置：区域变了才重载 -----------------------------------------------------------

    @Test
    fun reloadsWhenTheServerAssignsAnotherRegion() {
        assertEquals(FollowAction.Reload, decideFollow("tokyo", FollowOutcome.Fetched("singapore")))
        assertEquals(FollowAction.Reload, decideFollow("singapore", FollowOutcome.Fetched("tokyo")))
    }

    @Test
    fun keepsWhenTheRegionIsTheSame() {
        assertEquals(FollowAction.Keep, decideFollow("tokyo", FollowOutcome.Fetched("tokyo")))
        assertEquals(FollowAction.Keep, decideFollow("singapore", FollowOutcome.Fetched(" Singapore ")))
        assertEquals(FollowAction.Keep, decideFollow("Tokyo", FollowOutcome.Fetched("tokyo")))
    }

    @Test
    fun keepsWhenTheRegionCannotBeCompared() {
        // 旧后端不回显 region：绝不能因为「比不了」就重载
        assertEquals(FollowAction.Keep, decideFollow("tokyo", FollowOutcome.Fetched(null)))
        assertEquals(FollowAction.Keep, decideFollow("tokyo", FollowOutcome.Fetched("")))
        assertEquals(FollowAction.Keep, decideFollow("tokyo", FollowOutcome.Fetched("  ")))
        // 正在用的区域未知（旧后端起的隧道）：同样不重载
        assertEquals(FollowAction.Keep, decideFollow(null, FollowOutcome.Fetched("singapore")))
        assertEquals(FollowAction.Keep, decideFollow("", FollowOutcome.Fetched("singapore")))
    }

    // ---- 失败：到期 / 吊销才停，其余一律保持运行 ------------------------------------------------

    @Test
    fun stopsAsExpiredOn403InviteExpired() {
        val action = decideFollow("tokyo", FollowOutcome.Failed(403, "invite expired", "2026-11-03T00:00:00.000Z"))
        assertEquals(FollowAction.Stop(StopKind.EXPIRED, "2026-11-03T00:00:00.000Z"), action)
        assertEquals(
            FollowAction.Stop(StopKind.EXPIRED, null),
            decideFollow("tokyo", FollowOutcome.Failed(403, "  Invite Expired ")),
        )
    }

    @Test
    fun stopsAsRevokedOn401AndOtherForbidden() {
        assertEquals(FollowAction.Stop(StopKind.REVOKED), decideFollow("tokyo", FollowOutcome.Failed(401, "valid profile required")))
        assertEquals(FollowAction.Stop(StopKind.REVOKED), decideFollow("tokyo", FollowOutcome.Failed(403, "invite disabled")))
        assertEquals(FollowAction.Stop(StopKind.REVOKED), decideFollow("tokyo", FollowOutcome.Failed(403, "forbidden")))
    }

    @Test
    fun keepsRunningOnRegionErrors() {
        assertEquals(FollowAction.Keep, decideFollow("singapore", FollowOutcome.Failed(403, "region not allowed")))
        assertEquals(FollowAction.Keep, decideFollow("singapore", FollowOutcome.Failed(503, "region unavailable")))
        assertEquals(FollowAction.Keep, decideFollow("singapore", FollowOutcome.Failed(403, "region unavailable")))
        assertEquals(FollowAction.Keep, decideFollow("singapore", FollowOutcome.Failed(400, "unknown region")))
    }

    @Test
    fun keepsRunningOnNetworkAndServerErrors() {
        assertEquals(FollowAction.Keep, decideFollow("tokyo", FollowOutcome.Failed(-1, "Network error: timeout")))
        assertEquals(FollowAction.Keep, decideFollow("tokyo", FollowOutcome.Failed(500, "internal error")))
        assertEquals(FollowAction.Keep, decideFollow("tokyo", FollowOutcome.Failed(502, "bad gateway")))
        assertEquals(FollowAction.Keep, decideFollow("tokyo", FollowOutcome.Failed(504, "gateway timeout")))
        assertEquals(FollowAction.Keep, decideFollow("tokyo", FollowOutcome.Failed(404, "not found")))
        assertEquals(FollowAction.Keep, decideFollow("tokyo", FollowOutcome.Failed(502, "region mismatch")))
    }

    // ---- 取配置时带不带 region 参数 -----------------------------------------------------------------

    @Test
    fun nonOwnerNeverSendsARegion() {
        assertNull(runtimeRegionParam(false, "singapore", listOf(tokyo, singapore)))
        assertNull(runtimeRegionParam(false, null, listOf(tokyo)))
        assertNull(runtimeRegionParam(false, "tokyo", emptyList()))
    }

    @Test
    fun ownerUsesTheSelectedRegionIfStillOffered() {
        assertEquals("singapore", runtimeRegionParam(true, "singapore", listOf(tokyo, singapore)))
        assertEquals("tokyo", runtimeRegionParam(true, "tokyo", listOf(tokyo, singapore)))
        assertEquals("tokyo", runtimeRegionParam(true, "singapore", listOf(tokyo)))
        assertEquals("tokyo", runtimeRegionParam(true, null, listOf(tokyo, singapore)))
    }

    @Test
    fun timingConstantsAreSane() {
        assertEquals(5 * 60 * 1000L, FOLLOW_INTERVAL_MS)
        assertTrue(FOLLOW_NETWORK_DEBOUNCE_MS in 1_000L..30_000L)
        assertTrue(FOLLOW_MIN_RECHECK_MS >= FOLLOW_NETWORK_DEBOUNCE_MS)
    }
}
