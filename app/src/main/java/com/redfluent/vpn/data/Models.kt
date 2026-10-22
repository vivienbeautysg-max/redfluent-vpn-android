package com.redfluent.vpn.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 与 iOS 版 APIClient.swift 的 DeviceProfile 逐字段对齐。 */
@Serializable
data class DeviceProfile(
    val profileId: String,
    val token: String,
    val ownerLabel: String = "",
    val serverRegion: String = "",
    val configVersion: String = "",
    val monthlyQuotaGB: Int? = null,
    val expiresAt: String? = null,
    // 1.1 新增。旧版本存下来的 profile 没有这两项，解码时落到默认值：非 owner、只有东京。
    val isOwner: Boolean = false,
    val regions: List<RegionOption> = DEFAULT_REGIONS,
) {
    /** 滑动续期：只换 token，其余资料原样保留。 */
    fun withToken(newToken: String) = copy(token = newToken)
}

@Serializable
data class ProfileStatus(
    val enabled: Boolean = false,
    val profileId: String = "",
    val ownerLabel: String = "",
    val serverRegion: String = "",
    val routingMode: String = "",
    val monthlyQuotaGB: Int? = null,
    val configVersion: String = "",
    val expiresAt: String? = null,
    val isOwner: Boolean = false,
    val regions: List<RegionOption> = DEFAULT_REGIONS,
)

@Serializable
data class QuotaSnapshot(
    val monthlyQuotaGB: Int? = null,
    val usedGB: Double? = null,
    val periodStart: String? = null,
    val periodEnd: String? = null,
)

/** 后端 /redeem-invite 与 /profile 的信封。ok=false 时 error 里是原因。 */
@Serializable
data class Envelope(
    val ok: Boolean = false,
    val error: String? = null,
    @SerialName("renewedToken") val renewedToken: String? = null,
    val profileId: String? = null,
    val token: String? = null,
    val ownerLabel: String? = null,
    val serverRegion: String? = null,
    val configVersion: String? = null,
    val monthlyQuotaGB: Int? = null,
    val expiresAt: String? = null,
    val enabled: Boolean? = null,
    val routingMode: String? = null,
    // 1.1 新增，旧后端不返回；全部可空，缺失即「非 owner、只有东京」。
    val isOwner: Boolean? = null,
    val regions: List<RegionOption>? = null,
)

/** 隧道运行状态。界面只反映真实状态，不做乐观显示。 */
enum class TunnelState { DISCONNECTED, CONNECTING, CONNECTED, STOPPING, ERROR }
