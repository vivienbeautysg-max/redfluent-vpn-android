package com.redfluent.vpn.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
    // 1.2 新增：账号（邀请码）的到期状态，来自 /profile；旧后端没有这些字段，默认「没到期」。
    val daysLeft: Int? = null,
    val expired: Boolean = false,
) {
    /**
     * 滑动续期：只换 token，其余资料原样保留。
     * 只有本地存的 token 仍是「发请求时用的那个」才换——请求在途时若设备已被换成别的账号
     * （或已被另一次续期换过），迟到的续期结果必须丢掉，不能把别人的账号写坏。
     */
    fun withRenewedToken(oldToken: String, newToken: String): DeviceProfile =
        if (token == oldToken) copy(token = newToken) else this
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
    /** 响应里带了到期字段才有值（null = 旧后端 / 没有这组字段，本地原值保持不动）。 */
    val access: AccessInfo? = null,
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
    // 1.1 新增，旧后端不返回。故意声明成原始 JsonElement 而不是 Boolean / List：
    // /redeem-invite 的每个新用户都要走这个信封，这两个字段格式不对（字符串、对象、坏元素……）
    // 不能让整个激活失败——用 parseOwnerFlag / parseRegions 宽松解析，坏了就落到默认值。
    val isOwner: JsonElement? = null,
    val regions: JsonElement? = null,
)

/** 隧道运行状态。界面只反映真实状态，不做乐观显示。 */
enum class TunnelState { DISCONNECTED, CONNECTING, CONNECTED, STOPPING, ERROR }
