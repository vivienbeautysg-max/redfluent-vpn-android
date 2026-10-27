package com.redfluent.vpn.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

// 主页（"01 Soft Landing"）：明暗设置、IPv4-only Wi-Fi 提示、数字格式、本机本周期用量。纯逻辑，JVM 测试可跑。

// ---- 明暗 -----------------------------------------------------------------------------------

/** app 内选的明暗，记住。从不跟随系统：用户没选过深色就是浅色（与 iOS Appearance 一致）。 */
enum class Appearance(val stored: String, val title: String) {
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        /** 存的值（或还没有）→ 要用的明暗；不认识的一律浅色。 */
        fun from(stored: String?): Appearance = entries.firstOrNull { it.stored == stored?.trim() } ?: LIGHT
    }
}

private val APPEARANCE_KEY = stringPreferencesKey("appearance")

fun appearanceFlow(store: DataStore<Preferences>): Flow<Appearance> =
    store.data.map { Appearance.from(it[APPEARANCE_KEY]) }

suspend fun saveAppearance(store: DataStore<Preferences>, appearance: Appearance) {
    store.edit { it[APPEARANCE_KEY] = appearance.stored }
}

// ---- IPv4-only Wi-Fi 提示 ---------------------------------------------------------------------

/**
 * 东京的 IPv4 入口在中国被封，只有 IPv6 入口能通。连在没有 IPv6 的 Wi-Fi 上时，东京的设备连不上，
 * 主页用一行字提示改用移动数据（与 iOS IPv6Hint 同一规则、同一句话）。
 */
object IPv6Hint {
    const val TEXT = "Wi-Fi has no IPv6 — switch to cellular"

    /**
     * onWiFi：有一条（非 VPN 的）Wi-Fi 网络在用；wifiHasIPv6：它能走 IPv6；regionId：正在用的区域。
     * ipv4Blocked：上一次运行配置里的 runtimeMeta.ipv4Blocked。true → 照常提示；false（东京 IPv4 已解封）→ 不提示；
     * null（后端没给这个字段）→ 保持原来的规则。
     */
    fun shows(onWiFi: Boolean, wifiHasIPv6: Boolean, regionId: String?, ipv4Blocked: Boolean? = null): Boolean {
        if (!onWiFi || wifiHasIPv6 || regionId == null || ipv4Blocked == false) return false
        return normalizeRegionId(regionId) == REGION_TOKYO
    }
}

/** GET /device/runtime-config 信封里的 runtimeMeta.ipv4Blocked：只认 JSON 布尔；没有、null、字符串 → null（= 沿用原规则）。 */
fun parseIpv4Blocked(envelope: JsonObject): Boolean? =
    ((envelope["runtimeMeta"] as? JsonObject)?.get("ipv4Blocked") as? JsonPrimitive)
        ?.takeIf { it.isString.not() }
        ?.booleanOrNull

private val IPV4_BLOCKED_KEY = booleanPreferencesKey("last_runtime_ipv4_blocked")

/** 上一次运行配置里的 ipv4Blocked（记住，重启 app 后提示仍按它来）；没有 = null。 */
fun ipv4BlockedFlow(store: DataStore<Preferences>): Flow<Boolean?> = store.data.map { it[IPV4_BLOCKED_KEY] }

/** 每次取到运行配置都记下；后端没给这个字段时清掉（回到原规则）。 */
suspend fun saveIpv4Blocked(store: DataStore<Preferences>, value: Boolean?) {
    store.edit { if (value == null) it.remove(IPV4_BLOCKED_KEY) else it[IPV4_BLOCKED_KEY] = value }
}

/**
 * 一条网络"能走 IPv6"：有全局单播 IPv6 地址（2000::/3，排除文档前缀 2001:db8::/32），并且有 IPv6 默认路由。
 * 只有链路本地 fe80::、ULA fc00::/7 的网络到不了公网的 IPv6 入口，算没有。
 */
fun hasRoutableIPv6(addresses: List<InetAddress>, hasIPv6DefaultRoute: Boolean): Boolean {
    if (!hasIPv6DefaultRoute) return false
    return addresses.any { a ->
        a is Inet6Address && !a.isLinkLocalAddress && !a.isLoopbackAddress && !a.isSiteLocalAddress &&
            !a.isMulticastAddress && isGlobalUnicast(a.address)
    }
}

private fun isGlobalUnicast(b: ByteArray): Boolean {
    if (b.size != 16) return false
    val first = b[0].toInt() and 0xFF
    if (first and 0xE0 != 0x20) return false // 2000::/3
    val isDoc = first == 0x20 && (b[1].toInt() and 0xFF) == 0x01 && (b[2].toInt() and 0xFF) == 0x0D && (b[3].toInt() and 0xFF) == 0xB8
    return !isDoc
}

// ---- 数字格式（与 iOS HomeFormat 逐条一致）------------------------------------------------------

object HomeFormat {
    const val NONE = "—"

    /** 字节/秒 → "18.4"（MB/s，1 MB = 10^6 字节）；不知道 → "—"。 */
    fun megabytesPerSecond(bytesPerSecond: Long?): String {
        if (bytesPerSecond == null || bytesPerSecond < 0) return NONE
        val v = bytesPerSecond / 1_000_000.0
        return if (v < 100) String.format(Locale.US, "%.1f", v) else String.format(Locale.US, "%.0f", v)
    }

    fun ping(ms: Int?): String = if (ms == null || ms < 0) NONE else ms.toString()

    /** 已连接时长 "01:24:37"（小时超过 99 继续往上数）。 */
    fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    /** 已用 GB："12.4"（一位小数；1000 起不带小数）；不知道 / 不是数 → "—"。 */
    fun gigabytes(value: Double?): String {
        if (value == null || !value.isFinite()) return NONE
        val v = value.coerceAtLeast(0.0)
        return if (v < 1000) String.format(Locale.US, "%.1f", v) else String.format(Locale.US, "%.0f", v)
    }

    /** 配额："100"，不是整数时 "12.5"。 */
    fun quota(value: Double): String =
        if (value == Math.rint(value)) String.format(Locale.US, "%.0f", value) else String.format(Locale.US, "%.1f", value)

    /** 字节 → "1.8 GB"（二进制 GB，与 iOS 的用量卡一致）。 */
    fun bytesAsGB(bytes: Long): String = "${gigabytes(bytes.coerceAtLeast(0) / 1_073_741_824.0)} GB"

    /** 用量条位置 0..1；没有可用的配额 → null。 */
    fun fraction(used: Double, quota: Double): Double? {
        if (!quota.isFinite() || quota <= 0 || !used.isFinite()) return null
        return (used / quota).coerceIn(0.0, 1.0)
    }

    /** "12 days left" / "1 day left"（与 iOS ISODate.daysPhrase 一致）。 */
    fun daysPhrase(n: Int): String = if (n == 1) "1 day left" else "${n.coerceAtLeast(0)} days left"
}

// ---- 本机本周期用量（GET /device/quota，与 iOS 主页的 Bandwidth 同一个接口）------------------------------

data class DeviceQuota(
    val monthlyQuotaGB: Double,
    val usedGB: Double,
    val daysUntilReset: Int?,
    val incomingBytes: Long,
    val outgoingBytes: Long,
    /** 后端说没有月额度（isUnlimited / monthlyQuotaGB 为 0）。 */
    val unlimited: Boolean,
)

/** 宽松解析；usedGB 都没有（或不是数）就当拿不到，返回 null —— 主页显示 "—"，不编数字。 */
fun parseDeviceQuota(root: JsonObject): DeviceQuota? {
    fun num(key: String): Double? =
        (root[key] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toDoubleOrNull()?.takeIf { it.isFinite() }
    val used = num("usedGB") ?: return null
    val quota = num("monthlyQuotaGB") ?: 0.0
    val unlimitedFlag = (root["isUnlimited"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    return DeviceQuota(
        monthlyQuotaGB = quota,
        usedGB = used,
        daysUntilReset = num("daysUntilReset")?.toInt(),
        incomingBytes = num("incomingBytes")?.toLong() ?: 0L,
        outgoingBytes = num("outgoingBytes")?.toLong() ?: 0L,
        unlimited = unlimitedFlag == true || quota <= 0.0,
    )
}

/** 主页一排数字：下行 / 上行（字节/秒）与延迟（毫秒）；null = 没有读数。 */
data class LiveStats(val downBps: Long? = null, val upBps: Long? = null, val pingMs: Int? = null)
