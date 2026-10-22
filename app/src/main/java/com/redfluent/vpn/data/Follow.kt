package com.redfluent.vpn.data

/** "无感"跟随的轮询间隔：连接期间每 5 分钟重取一次运行配置。 */
const val FOLLOW_INTERVAL_MS = 5 * 60 * 1000L

/** 底层网络变化后等多久再取（合并连续的变化事件）。 */
const val FOLLOW_NETWORK_DEBOUNCE_MS = 5_000L

/** 两次检查之间的最短间隔：连接刚建立时的网络回调、或引擎重载重新登记回调引起的变化事件，不应立刻再取一次。 */
const val FOLLOW_MIN_RECHECK_MS = 30_000L

/**
 * 取配置时带不带 region 参数：
 *  - owner：用自己选的区域（若已不被提供则退回服务器默认区域）；
 *  - 普通用户：永远不带，区域由服务器按「分配给这个账号的区域」决定。
 */
fun runtimeRegionParam(isOwner: Boolean, selected: String?, regions: List<RegionOption>): String? =
    if (isOwner) resolveRegion(selected, regions) else null

/** 一次跟随检查的结果（只保留决策需要的信息）。 */
sealed interface FollowOutcome {
    /** 取到了配置；regionId 是服务器回显的区域（旧后端不回显则为空）。 */
    data class Fetched(val regionId: String?) : FollowOutcome

    /** 请求失败。code < 0 表示没连上服务器（网络错误）。 */
    data class Failed(val code: Int, val reason: String, val expiresAt: String? = null) : FollowOutcome
}

/** 决策：接下来做什么。 */
sealed interface FollowAction {
    /** 什么也不做（区域没变 / 暂时取不到，下个周期再试）。 */
    data object Keep : FollowAction

    /** 服务器给的区域和正在用的不同：用刚取到的配置热重载引擎。 */
    data object Reload : FollowAction

    /** 账号到期 / 被吊销：停掉隧道并告诉界面。 */
    data class Stop(val kind: StopKind, val expiresAt: String? = null) : FollowAction
}

private val REGION_ERRORS = setOf("region not allowed", "region unavailable", "unknown region")

/**
 * 跟随决策（纯函数）：
 *  - 取到配置：区域和正在用的不同 → 重载；相同、或比较不了（旧后端不回显区域）→ 保持；
 *  - 403 "invite expired" → 到期，停；
 *  - 401，或其它 403（设备 / 邀请码被停用）→ 吊销，停；
 *  - 区域类的 403 / 5xx / 网络错误 / 其它 → 保持运行，下个周期再试（绝不因为暂时的故障断开用户）。
 */
fun decideFollow(activeRegionId: String?, outcome: FollowOutcome): FollowAction = when (outcome) {
    is FollowOutcome.Fetched -> {
        val returned = normalizeRegionId(outcome.regionId)
        val active = normalizeRegionId(activeRegionId)
        if (returned.isNotEmpty() && active.isNotEmpty() && returned != active) FollowAction.Reload else FollowAction.Keep
    }

    is FollowOutcome.Failed -> {
        val reason = outcome.reason.trim().lowercase()
        when {
            outcome.code == 403 && reason.contains("expired") ->
                FollowAction.Stop(StopKind.EXPIRED, outcome.expiresAt)
            outcome.code == 401 -> FollowAction.Stop(StopKind.REVOKED)
            outcome.code == 403 && reason !in REGION_ERRORS -> FollowAction.Stop(StopKind.REVOKED)
            else -> FollowAction.Keep
        }
    }
}
