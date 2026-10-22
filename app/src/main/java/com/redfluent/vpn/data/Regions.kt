package com.redfluent.vpn.data

import kotlinx.serialization.Serializable

/** 默认区域。所有设备都有；非默认区域只给 owner 设备。 */
const val REGION_TOKYO = "tokyo"

/** /profile 的 regions 数组元素：这台设备可以使用的区域。 */
@Serializable
data class RegionOption(
    val id: String,
    val name: String = "",
    val isDefault: Boolean = false,
) {
    /** 界面上显示的名字；后端没给 name 时用 id 首字母大写兜底。 */
    val label: String
        get() = name.ifBlank { id.replaceFirstChar { it.uppercase() } }
}

val DEFAULT_REGIONS: List<RegionOption> = listOf(RegionOption(REGION_TOKYO, "Tokyo", true))

/** 旧后端没有 regions 字段、或返回空数组：一律按「只有东京」处理。 */
fun normalizeRegions(raw: List<RegionOption>?): List<RegionOption> =
    raw?.filter { it.id.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: DEFAULT_REGIONS

/**
 * 决定实际使用哪个区域：
 *  - 选中的区域仍被服务器提供 → 用它；
 *  - 已不再提供（被关闭 / 设备不再是 owner）→ 静默回到东京。
 */
fun resolveRegion(selected: String?, offered: List<RegionOption>): String {
    val sel = selected?.trim()?.lowercase().orEmpty()
    if (sel.isEmpty() || sel == REGION_TOKYO) return REGION_TOKYO
    return if (offered.any { it.id.lowercase() == sel }) sel else REGION_TOKYO
}

/**
 * 非默认区域必须由服务器在响应里明确确认 region。
 * 旧后端会忽略 ?region= 参数、照样返回东京的配置——如果不检查，
 * 界面写着 Singapore 实际却连在东京，这是不允许的。
 */
fun regionConfirmed(requested: String, returned: String?): Boolean =
    requested == REGION_TOKYO || returned?.trim()?.lowercase() == requested
