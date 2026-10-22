package com.redfluent.vpn.data

/** 把 /profile 的结果合并进本地 profile 之后的样子，以及此时应当生效的区域。 */
data class ProfileSyncResult(
    val profile: DeviceProfile,
    val region: String,
)

/**
 * 纯逻辑：/profile 的结果能不能写回本地、写成什么。
 *
 * 请求在途时，这台设备可能已经被换成别的账号（退出后用另一个邀请码重新激活）。
 * 只有同时满足下面三条才写回，否则丢弃（返回 null）：
 *  1. 本地现在还有 profile；
 *  2. 它的 profileId 仍是发请求时的那个；
 *  3. 服务器响应里的 profileId 也就是它（响应是给这个账号的）。
 * 区域的重置与 isOwner / regions 在同一次计算里完成，调用方把两者放进同一次 DataStore 写入。
 */
fun syncProfileFromServer(
    current: DeviceProfile?,
    storedRegion: String?,
    requestedProfileId: String,
    status: ProfileStatus,
): ProfileSyncResult? {
    if (current == null) return null
    if (current.profileId != requestedProfileId) return null
    if (status.profileId != current.profileId) return null
    val regions = normalizeRegions(status.regions)
    // 1.2：到期状态与「分配给这个账号的区域名」。响应里没带到期字段（旧后端 / owner）就保持本地原值，
    // 免得把激活时拿到的到期日冲成空。
    val access = status.access
    return ProfileSyncResult(
        profile = current.copy(
            isOwner = status.isOwner,
            regions = regions,
            serverRegion = status.serverRegion.ifBlank { current.serverRegion },
            expiresAt = if (access != null) access.expiresAt else current.expiresAt,
            daysLeft = if (access != null) access.daysLeft else current.daysLeft,
            expired = if (access != null) access.expired else current.expired,
        ),
        region = resolveRegion(storedRegion, regions),
    )
}

/**
 * /profile 的信封 → ProfileStatus。
 * enabled：响应里没有（或不是布尔）≠ 设备被登出——只有服务器明确说 false 才算，所以缺失按 true。
 */
fun profileStatusFrom(env: Envelope, raw: kotlinx.serialization.json.JsonObject): ProfileStatus = ProfileStatus(
    enabled = env.enabled ?: true,
    profileId = env.profileId.orEmpty(),
    ownerLabel = env.ownerLabel.orEmpty(),
    serverRegion = env.serverRegion.orEmpty(),
    routingMode = env.routingMode.orEmpty(),
    monthlyQuotaGB = env.monthlyQuotaGB,
    configVersion = env.configVersion.orEmpty(),
    expiresAt = env.expiresAt,
    isOwner = parseOwnerFlag(env.isOwner),
    regions = parseRegions(env.regions),
    access = parseAccessInfo(raw),
)
