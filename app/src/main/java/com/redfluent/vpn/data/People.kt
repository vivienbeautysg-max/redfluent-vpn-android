package com.redfluent.vpn.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

// 1.3 Owner Admin "People"：一个邀请码 = 一个人，这个人的设备在他自己的页面上（合并了旧的 Usage / Users 两页）。
// 与 iOS App/OwnerPeople.swift 同一套规则。纯逻辑，JVM 测试可跑。

// ---- 月额度输入 --------------------------------------------------------------------------------

/** 月额度：1–2000 之间的任意整数 GB（例如 48），外加快捷值。48.5 之类不是整数的一律拒绝，绝不悄悄取整。 */
object QuotaInput {
    val RANGE = 1..2000
    val PRESETS = listOf(10, 50, 100, 200)

    enum class Problem(val message: String) {
        EMPTY("1–2000 GB"),
        OUT_OF_RANGE("1–2000 GB"),
        NOT_WHOLE_NUMBER("Whole GB only"),
        NOT_A_NUMBER("Numbers only"),
    }

    sealed interface Result {
        data class Ok(val gb: Int) : Result
        data class Bad(val problem: Problem) : Result
    }

    fun parse(text: String): Result {
        val t = text.trim()
        if (t.isEmpty()) return Result.Bad(Problem.EMPTY)
        val isDigit = { c: Char -> c in '0'..'9' }
        if (t.all(isDigit)) {
            val v = t.toIntOrNull() ?: return Result.Bad(Problem.OUT_OF_RANGE)
            return if (v in RANGE) Result.Ok(v) else Result.Bad(Problem.OUT_OF_RANGE)
        }
        // "48.5"、"48,5"、"48."：是数，但不是整数
        val sep = if ('.' in t) '.' else ','
        val parts = t.split(sep)
        if (parts.size == 2 && parts.all { p -> p.all(isDigit) } && parts.any { it.isNotEmpty() }) {
            return Result.Bad(Problem.NOT_WHOLE_NUMBER)
        }
        return Result.Bad(Problem.NOT_A_NUMBER)
    }

    fun valueOf(text: String): Int? = (parse(text) as? Result.Ok)?.gb
}

// ---- 设备的 IPv6 入口 -----------------------------------------------------------------------------

/** 设备的 IPv6 入口怎么显示：共用的 /64 前半段淡色，设备自己的后半段深色；没有入口 = "IPv4 only"。 */
sealed interface IPv6Display {
    data object IPv4Only : IPv6Display

    /** prefix + host 是完整地址；不是 IPv6 地址的文字 prefix 为空。 */
    data class Address(val prefix: String, val host: String) : IPv6Display

    companion object {
        const val IPV4_ONLY_TEXT = "IPv4 only"

        fun make(entry: String?): IPv6Display {
            val raw = entry?.trim().orEmpty()
            if (raw.isEmpty()) return IPv4Only
            val g = groups(raw) ?: return Address("", raw)
            val prefix = g.subList(0, 4).joinToString(":") { it.toString(16) } + ":"
            return Address(prefix, compressedHost(g.subList(4, 8)))
        }

        private const val HEX = "0123456789abcdefABCDEF"

        /** 8 个 16 位组；不是普通 IPv6 地址 → null。 */
        fun groups(text: String): List<Int>? {
            var s = text
            if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length - 1)
            s = s.substringBefore("%").substringBefore("/")
            if (s.contains(".") || !s.contains(":")) return null

            fun hexGroups(part: String): List<Int>? {
                if (part.isEmpty()) return emptyList()
                return part.split(":").map { p ->
                    if (p.length < 1 || p.length > 4 || p.any { ch -> HEX.indexOf(ch) < 0 }) return null
                    p.toInt(16)
                }
            }

            val halves = s.split("::")
            return when (halves.size) {
                1 -> hexGroups(s)?.takeIf { it.size == 8 }
                2 -> {
                    val head = hexGroups(halves[0]) ?: return null
                    val tail = hexGroups(halves[1]) ?: return null
                    if (head.size + tail.size > 7) return null
                    head + List(8 - head.size - tail.size) { 0 } + tail
                }
                else -> null
            }
        }

        /** 后四组按 RFC 5952：最长的一串 ≥2 个 0 组写成 "::"；前缀已以 ":" 结尾，所以开头的一串只写一个 ":"。 */
        fun compressedHost(groups: List<Int>): String {
            val hex = groups.map { it.toString(16) }
            var bestStart = -1
            var bestLen = 0
            var i = 0
            while (i < groups.size) {
                if (groups[i] != 0) { i++; continue }
                var j = i
                while (j < groups.size && groups[j] == 0) j++
                if (j - i >= 2 && j - i > bestLen) { bestStart = i; bestLen = j - i }
                i = j
            }
            if (bestStart < 0) return hex.joinToString(":")
            val left = hex.subList(0, bestStart).joinToString(":")
            val right = hex.subList(bestStart + bestLen, hex.size).joinToString(":")
            return if (bestStart == 0) ":$right" else "$left::$right"
        }
    }
}

// ---- People ------------------------------------------------------------------------------------

data class PersonDevice(
    val device: OwnerDevice,
    /** 本周期用量；后端没给可用的用量 → null。 */
    val usedGB: Double?,
    val lastUsedAtMs: Long?,
    /** 现在在线（已登出的设备永远不算）。 */
    val online: Boolean,
    val ipv6: IPv6Display,
    /** 这台设备拨的是它自己专属的 IPv6 入口，而不是共用的。 */
    val dedicated: Boolean,
) {
    val signedIn: Boolean get() = device.isActive()

    /** "Now" | "2h ago" | "Never"。 */
    fun activity(nowMs: Long): String = when {
        online -> "Now"
        lastUsedAtMs == null -> "Never"
        else -> relativeAgo(lastUsedAtMs, nowMs)
    }

    /** "v1.2 (43) · 2h ago"（+ "Signed out"）。 */
    fun detail(nowMs: Long): String {
        val version = device.appVersion?.trim()?.takeIf { it.isNotEmpty() }?.let { "v$it" }
        return listOfNotNull(version, activity(nowMs), if (signedIn) null else "Signed out").joinToString(" · ")
    }
}

data class Person(
    val invite: OwnerInvite,
    val devices: List<PersonDevice>,
    /** owner 自己的邀请码：这里不能暂停、删除、全部登出，邀请码不能改。 */
    val isOwner: Boolean,
) {
    val code: String get() = invite.code
    val label: String get() = invite.label.ifBlank { "—" }
    val paused: Boolean get() = invite.enabled.not()
    val devicesInUse: Int get() = devices.count { it.signedIn }
    val online: Boolean get() = devices.any { it.online }

    /** 这个人本周期的总用量（已登出的设备也算）；一台都没报 → null。 */
    val usedGB: Double?
        get() = devices.mapNotNull { it.usedGB }.takeIf { it.isNotEmpty() }?.sum()

    val lastUsedAtMs: Long? get() = devices.mapNotNull { it.lastUsedAtMs }.maxOrNull()

    fun activity(nowMs: Long): String {
        val last = lastUsedAtMs
        return when {
            online -> "Now"
            last == null -> "Never"
            else -> relativeAgo(last, nowMs)
        }
    }

    /** "2/3 devices"：已登录 / 设备上限。 */
    val devicesText: String
        get() = invite.maxDevices?.let { max -> "$devicesInUse/$max device" + if (max == 1) "" else "s" }
            ?: ("$devicesInUse device" + if (devicesInUse == 1) "" else "s")

    /** 这个人所在的区域（后端报了才有；owner 用全部区域，不显示）。 */
    val regionName: String?
        get() = if (isOwner) null else invite.region?.trim()?.takeIf { it.isNotEmpty() }?.let { RegionOption(normalizeRegionId(it)).label }

    /** "Tokyo · 2/3 devices · 2h ago"：People 列表第二行。 */
    fun summary(nowMs: Long): String = listOfNotNull(regionName, devicesText, activity(nowMs)).joinToString(" · ")

    /** 有区域 / 时长信息（1.2 后端）才显示 Region / Access。 */
    val hasAccessInfo: Boolean get() = invite.region != null
}

/** 认出 owner 自己的邀请码：本机激活用的码，或本机这台设备所属的码。 */
fun ownerCodes(devices: List<OwnerDevice>, ownProfileId: String?, storedCode: String?): Set<String> {
    val out = HashSet<String>()
    storedCode?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
    if (ownProfileId.isNullOrBlank().not()) {
        devices.firstOrNull { it.profileId == ownProfileId }?.inviteCode?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
    }
    return out
}

/**
 * 一个邀请码一个人，带上用这个码激活的设备。暂停的人照样列出；邀请码不在列表里的设备（已删除的人）不显示。
 * 排序：本周期用得多的在前，再按最近使用，再按名字。
 * 标签与 owner 自己的标签一致也算 owner（1.2 的 protectedKind 同一规则）。
 */
fun buildPeople(
    invites: List<OwnerInvite>,
    devices: List<OwnerDevice>,
    ownerCodes: Set<String>,
    ownerLabel: String? = null,
): List<Person> {
    val byCode = devices.groupBy { normCode(it.inviteCode) }
    return invites.map { invite ->
        val rows = byCode[normCode(invite.code)].orEmpty().map { d ->
            val ipv6 = IPv6Display.make(d.ipv6Entry)
            PersonDevice(
                device = d,
                usedGB = d.usage?.usedGB?.takeIf { it.isFinite() }?.coerceAtLeast(0.0),
                lastUsedAtMs = parseInstantMillis(d.lastUsedAt),
                online = d.isActive() && d.online,
                ipv6 = ipv6,
                dedicated = d.ipv6EntryDedicated == true && ipv6 != IPv6Display.IPv4Only,
            )
        }.sortedWith(DEVICE_ORDER)
        val label = ownerLabel?.trim().orEmpty()
        val owner = ownerCodes.any { sameInviteCode(it, invite.code) } ||
            (label.isNotEmpty() && invite.label.trim().equals(label, ignoreCase = true))
        Person(invite = invite, devices = rows, isOwner = owner)
    }.sortedWith(PERSON_ORDER)
}

private fun normCode(code: String?): String =
    java.text.Normalizer.normalize(code.orEmpty().trim(), java.text.Normalizer.Form.NFKC).lowercase()

private val PERSON_ORDER = Comparator<Person> { a, b ->
    val ua = a.usedGB ?: -1.0
    val ub = b.usedGB ?: -1.0
    if (ua != ub) return@Comparator ub.compareTo(ua)
    val la = a.lastUsedAtMs
    val lb = b.lastUsedAtMs
    if (la != lb) {
        if (la == null) return@Comparator 1
        if (lb == null) return@Comparator -1
        return@Comparator lb.compareTo(la)
    }
    a.label.lowercase().compareTo(b.label.lowercase())
}

/** 已登录的在前，在线的在前，再按用量，再按名字。 */
private val DEVICE_ORDER = Comparator<PersonDevice> { a, b ->
    if (a.signedIn != b.signedIn) return@Comparator if (a.signedIn) -1 else 1
    if (a.online != b.online) return@Comparator if (a.online) -1 else 1
    val ua = a.usedGB ?: -1.0
    val ub = b.usedGB ?: -1.0
    if (ua != ub) return@Comparator ub.compareTo(ua)
    a.device.title().lowercase().compareTo(b.device.title().lowercase())
}

/** 个人页里的 "since 1 Oct"（周期开始那天，按 ISO 里写的日期，不做时区换算）。 */
fun sinceText(period: UsagePeriod?): String? {
    val day = formatDay(period?.start) ?: return null
    return "since " + day.substringBeforeLast(" ")
}

// ---- 个人页编辑 --------------------------------------------------------------------------------

/** 个人页的 Access 选择（与 iOS AccessPreset 同一组选项）。 */
sealed interface AccessPick {
    data object NoLimit : AccessPick
    data class Preset(val days: Int) : AccessPick
    data object Custom : AccessPick

    val label: String
        get() = when (this) {
            NoLimit -> "No limit"
            is Preset -> "$days days"
            Custom -> "Custom"
        }

    companion object {
        val ALL: List<AccessPick> = listOf(NoLimit) + ACCESS_PRESETS.map { Preset(it) } + Custom

        fun of(durationDays: Int?): AccessPick = when {
            durationDays == null -> NoLimit
            ACCESS_PRESETS.contains(durationDays) -> Preset(durationDays)
            else -> Custom
        }
    }
}

/** 个人页正在编辑的内容。保存时走 [buildInviteUpdate]：六个字段每次都带，region / durationDays 只在改了时才带。 */
data class PersonDraft(
    val code: String,
    val label: String,
    val quotaText: String,
    /** null = 后端没给当前上限：必须先选一个值才能保存（绝不悄悄用默认值）。 */
    val maxDevices: Int?,
    val enabled: Boolean,
    val region: String,
    val access: AccessPick,
    val customDaysText: String,
) {
    val quota: QuotaInput.Result get() = QuotaInput.parse(quotaText)

    /** Access 实际的天数；自定义天数不合法 → null。 */
    fun durationChoice(): AccessChoice? = when (val a = access) {
        AccessPick.NoLimit -> AccessChoice.NoLimit
        is AccessPick.Preset -> AccessChoice.Days(a.days)
        AccessPick.Custom -> parseCustomDays(customDaysText)?.let { AccessChoice.Days(it) }
    }

    override fun toString(): String = "PersonDraft(label=$label, quota=$quotaText, max=$maxDevices, enabled=$enabled, region=$region, access=$access)"

    companion object {
        val DEVICE_LIMIT = 1..100

        fun from(invite: OwnerInvite): PersonDraft = PersonDraft(
            code = invite.code,
            label = invite.label,
            // 没有额度记录：输入框空着，必须填了才能保存
            quotaText = invite.monthlyQuotaGB?.takeIf { it >= 1 }?.toString().orEmpty(),
            maxDevices = invite.maxDevices?.takeIf { DEVICE_LIMIT.contains(it) },
            enabled = invite.enabled,
            region = invite.regionId,
            access = AccessPick.of(invite.durationDays),
            customDaysText = invite.durationDays?.takeIf { ACCESS_PRESETS.contains(it).not() }?.toString().orEmpty(),
        )
    }
}

/** Save 要发出的值。 */
data class PersonEdit(
    val code: String,
    val label: String,
    val maxDevices: Int,
    val monthlyQuotaGB: Int,
    val enabled: Boolean,
    /** 不显示 Region / Access 时为 null。 */
    val region: String?,
    val access: AccessChoice?,
) {
    override fun toString(): String = "PersonEdit(label=$label, region=$region, access=$access)"
}

/** 不合法（额度、空的邀请码 / 名字、上限、自定义天数）时为 null，保存按钮禁用。 */
fun PersonDraft.edit(showsRegion: Boolean, showsAccess: Boolean): PersonEdit? {
    val c = code.trim()
    val l = label.trim()
    val gb = QuotaInput.valueOf(quotaText) ?: return null
    val max = maxDevices?.takeIf { PersonDraft.DEVICE_LIMIT.contains(it) } ?: return null
    if (c.isEmpty() || l.isEmpty() || c.length > 128 || l.length > 80) return null
    val access = if (showsAccess) (durationChoice() ?: return null) else null
    return PersonEdit(
        code = c, label = l, maxDevices = max, monthlyQuotaGB = gb, enabled = enabled,
        region = if (showsRegion) region else null, access = access,
    )
}

// ---- Recently Deleted --------------------------------------------------------------------------

/** GET /owner/invites/deleted 的一条。只有 code 是必需的；其余字段类型不对就当没有。 */
data class DeletedInvite(
    val code: String,
    val label: String,
    val deletedAt: String?,
    val maxDevices: Int? = null,
    val monthlyQuotaGB: Int? = null,
    val region: String? = null,
    val deviceCount: Int? = null,
) {
    val key: String get() = code + "|" + deletedAt.orEmpty()

    override fun toString(): String = "DeletedInvite(label=$label)"
}

data class DeletedInvitesResponse(val ok: Boolean, val error: String?, val invites: List<DeletedInvite>, val skipped: Int)

fun parseDeletedInvites(root: JsonObject): DeletedInvitesResponse {
    val ok = (root["ok"] as? JsonPrimitive)?.takeIf { it.isString.not() }?.booleanOrNull ?: false
    val error = (root["error"] as? JsonPrimitive)?.contentOrNull
    val raw = (root["invites"] as? JsonArray).orEmpty()
    var skipped = 0
    val out = ArrayList<DeletedInvite>(raw.size)
    for (item in raw) {
        val o = item as? JsonObject
        val code = (o?.get("code") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
        if (o == null || code == null) { skipped++; continue }
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun int(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString.not() }?.contentOrNull?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it == Math.rint(it) }?.toInt()
        out += DeletedInvite(
            code = code,
            label = str("label")?.takeIf { it.isNotBlank() } ?: code,
            deletedAt = str("deletedAt"),
            maxDevices = int("maxDevices"),
            monthlyQuotaGB = int("monthlyQuotaGB"),
            region = str("region")?.takeIf { it.isNotBlank() },
            deviceCount = int("deviceCount"),
        )
    }
    return DeletedInvitesResponse(ok, error, out, skipped)
}

object RecentlyDeleted {
    const val KEEP_DAYS = 30
    const val RESTORED_NOTE = "Devices need to sign in again"
    const val DELETE_MESSAGE = "Restorable from Recently Deleted for 30 days."
    const val DELETE_MESSAGE_OLD_BACKEND = "Its devices will be signed out."

    /** 没有 GET /owner/invites/deleted 的旧后端回 404：入口隐藏，不报错。 */
    fun isUnsupported(httpCode: Int): Boolean = httpCode == 404

    /** 最近删除的在前（读不出日期的放最后）。 */
    fun sorted(list: List<DeletedInvite>): List<DeletedInvite> =
        list.sortedWith(compareByDescending<DeletedInvite> { parseInstantMillis(it.deletedAt) ?: Long.MIN_VALUE })

    /** "Deleted 3d ago · 1 device · 100 GB · Tokyo"。 */
    fun detail(item: DeletedInvite, nowMs: Long): String {
        val parts = ArrayList<String>()
        parseInstantMillis(item.deletedAt)?.let { parts += "Deleted " + relativeAgo(it, nowMs) }
        item.deviceCount?.let { parts += if (it == 1) "1 device" else "$it devices" }
        item.monthlyQuotaGB?.let { parts += "$it GB" }
        item.region?.let { parts += RegionOption(normalizeRegionId(it)).label }
        return parts.joinToString(" · ")
    }

    /** 恢复失败时说什么：404 / 409 / 403 有自己的短句，其余用服务器的短原因。 */
    fun restoreFailureText(httpCode: Int, reason: String): String = when (httpCode) {
        404 -> "No longer in Recently Deleted"
        409 -> "This code is in use by another invite"
        403 -> "The owner invite cannot be restored"
        else -> inviteErrorText(httpCode, reason)
    }
}
