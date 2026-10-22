package com.redfluent.vpn.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

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

/** 区域 id 在本地一律 trim + 小写保存和比较，避免 "Singapore" 与 "singapore" 被当成两个区域。 */
fun normalizeRegionId(raw: String?): String = raw?.trim()?.lowercase().orEmpty()

/**
 * 整理服务器给的区域列表：id 做 trim + 小写，丢掉空 id，按 id 去重（先出现的保留）。
 * 旧后端没有 regions 字段、或整理后一个也不剩：一律按「只有东京」处理。
 */
fun normalizeRegions(raw: List<RegionOption>?): List<RegionOption> {
    val seen = HashSet<String>()
    val cleaned = ArrayList<RegionOption>()
    for (r in raw.orEmpty()) {
        val id = normalizeRegionId(r.id)
        if (id.isEmpty() || !seen.add(id)) continue
        cleaned += r.copy(id = id, name = r.name.trim())
    }
    return cleaned.ifEmpty { DEFAULT_REGIONS }
}

/** 服务器认定的默认区域：标了 isDefault 的第一个，没有就取列表第一个。 */
fun defaultRegionId(offered: List<RegionOption>): String =
    offered.firstOrNull { it.isDefault }?.id?.let(::normalizeRegionId)?.takeIf { it.isNotEmpty() }
        ?: offered.firstOrNull()?.id?.let(::normalizeRegionId)?.takeIf { it.isNotEmpty() }
        ?: REGION_TOKYO

/**
 * 决定实际使用哪个区域：
 *  - 选中的区域仍被服务器提供 → 用它；
 *  - 没选过、或已不再提供（被关闭 / 设备不再是 owner）→ 回到服务器的默认区域
 *    （正常就是东京；万一连东京都不提供，则用服务器标的默认或列表第一个）。
 */
fun resolveRegion(selected: String?, offered: List<RegionOption>): String {
    val sel = normalizeRegionId(selected)
    if (sel.isNotEmpty() && offered.any { normalizeRegionId(it.id) == sel }) return sel
    return defaultRegionId(offered)
}

/**
 * 非默认区域必须由服务器在响应里明确确认 region。
 * 旧后端会忽略 ?region= 参数、照样返回东京的配置——如果不检查，
 * 界面写着 Singapore 实际却连在东京，这是不允许的。
 */
fun regionConfirmed(requested: String, returned: String?): Boolean {
    val req = normalizeRegionId(requested)
    return req == REGION_TOKYO || normalizeRegionId(returned) == req
}

/**
 * 服务器不再提供「隧道正在用的区域」时必须断开：
 * 否则界面会显示已退回东京，而隧道还悬在新加坡。
 */
fun shouldDisconnectForRegions(inUseRegionId: String?, offered: List<RegionOption>): Boolean {
    val inUse = normalizeRegionId(inUseRegionId)
    return inUse.isNotEmpty() && offered.none { normalizeRegionId(it.id) == inUse }
}

// ---- 宽松解码（后端字段格式不对时回落默认值，绝不让整个响应解码失败）------------------------

private val lenientJson = Json { ignoreUnknownKeys = true }

/** 只认真正的 JSON 布尔；字符串 "true"、数字、对象、null 一律当 false。 */
fun parseOwnerFlag(element: JsonElement?): Boolean {
    val p = element as? JsonPrimitive ?: return false
    return if (p.isString) false else p.booleanOrNull ?: false
}

/** 不是数组 → 默认；数组里坏掉的元素被丢掉，剩下的照常整理；一个不剩 → 默认。 */
fun parseRegions(element: JsonElement?): List<RegionOption> {
    val array = element as? JsonArray ?: return DEFAULT_REGIONS
    val parsed = array.mapNotNull { item ->
        runCatching { lenientJson.decodeFromJsonElement(RegionOption.serializer(), item) }.getOrNull()
    }
    return normalizeRegions(parsed)
}
