package com.redfluent.vpn.data

/**
 * "Server" 卡片里 Region 一行显示什么：
 *  - 已连接且服务器确认了区域：一律显示隧道真正连着的区域（单区域、多区域都一样）——
 *    界面永远不能写着一个区域、隧道实际在另一个；
 *  - 没连着：单区域（所有非 owner）和 1.0 一样显示 profile 里的 serverRegion，
 *    多区域显示当前选中的区域。
 */
fun regionShown(
    profile: DeviceProfile?,
    multiRegion: Boolean,
    connected: Boolean,
    selectedRegion: String,
    activeRegion: String?,
): String {
    if (connected && !activeRegion.isNullOrBlank()) return activeRegion
    if (!multiRegion) return profile?.serverRegion.orEmpty().ifBlank { "—" }
    val selected = normalizeRegionId(selectedRegion)
    return profile?.regions?.firstOrNull { normalizeRegionId(it.id) == selected }?.label ?: "—"
}
