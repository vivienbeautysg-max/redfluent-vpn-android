package com.redfluent.vpn.api

import com.redfluent.vpn.data.DeviceProfile
import com.redfluent.vpn.data.Envelope
import com.redfluent.vpn.data.InviteUpdate
import com.redfluent.vpn.data.OwnerDevicesResponse
import com.redfluent.vpn.data.OwnerInvitesResponse
import com.redfluent.vpn.data.ProfileStatus
import com.redfluent.vpn.data.REGION_TOKYO
import com.redfluent.vpn.data.cleanErrorReason
import com.redfluent.vpn.data.normalizeRegionId
import com.redfluent.vpn.data.parseAccessInfo
import com.redfluent.vpn.data.parseOwnerDevices
import com.redfluent.vpn.data.parseOwnerFlag
import com.redfluent.vpn.data.parseOwnerInvites
import com.redfluent.vpn.data.parseRegions
import com.redfluent.vpn.data.regionConfirmed
import com.redfluent.vpn.data.toJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiException(
    val code: Int,
    val reason: String,
    /** 403 "invite expired" 时服务器附带的到期时间（ISO），其它错误为 null。 */
    val expiresAt: String? = null,
) : Exception(userMessage(code, reason)) {
    companion object {
        /** 与 iOS 版 APIClient.statusMessage 逐字一致，两端提示语相同。 */
        fun userMessage(code: Int, reason: String): String = when (reason) {
            "invalid invite" ->
                "Invite code was not found. Check the code and try again."
            "invite disabled" ->
                "This invite has been disabled by the owner."
            "invite expired" ->
                "This invite has expired."
            "invite device limit reached" ->
                "This invite has reached its device limit. Ask the owner to raise the limit or revoke an old device."
            "owner device cannot be revoked here" ->
                "Owner devices are protected and cannot be revoked here."
            // 1.1：区域相关
            "region not allowed" ->
                "This region is not available for this device."
            "region unavailable" ->
                "This region is unavailable right now."
            "unknown region" ->
                "Unknown region."
            REGION_MISMATCH ->
                "The server did not confirm the region."
            // 没连上服务器（code < 0）时原因本身就是一句完整的话，不加 "Server error -1:" 前缀
            else -> if (code < 0) reason else "Server error $code: $reason"
        }

        const val REGION_MISMATCH = "region mismatch"
    }
}

/** 一次取回的运行配置，连同服务器确认的区域，用于界面显示「实际在用哪个区域」。 */
data class RuntimeConfig(
    val configJson: String,
    val regionId: String,
    val regionName: String,
)

object ApiClient {

    const val DEFAULT_BASE = "https://vpn-api.redfluent.com"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 后端在 token 接近过期时，会在任意认证响应里附带 renewedToken；在这里统一接住。
     * 回调带上 (发请求时用的旧 token, 新 token)：接收方只有在本地存的 token 仍是旧 token 时才能替换，
     * 否则（设备已换账号 / 已被别的续期换过）必须丢弃。
     */
    @Volatile
    var onTokenRenewed: (suspend (oldToken: String, newToken: String) -> Unit)? = null

    private var base: String = DEFAULT_BASE

    fun setBaseUrl(url: String) {
        base = url.trimEnd('/')
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JsonObject? = null,
        token: String? = null,
    ): JsonObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url("$base$path")
        if (token != null) builder.header("Authorization", "Bearer $token")
        when (method) {
            "POST" -> builder.post((body?.toString() ?: "{}").toRequestBody(jsonMedia))
            else -> builder.get()
        }

        val raw = try {
            http.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw ApiException(-1, "Network error: ${e.message}")
        }

        raw.use { response ->
            val text = response.body?.string().orEmpty()
            val obj = runCatching { json.parseToJsonElement(text).jsonObjectOrNull() }.getOrNull()

            if (!response.isSuccessful) {
                // 上游挂了时可能是整页 HTML（Cloudflare 错误页）：界面上只该看到一句短话
                val jsonError = (obj?.get("error") as? JsonPrimitive)?.contentOrNull
                val expiresAt = (obj?.get("expiresAt") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                throw ApiException(response.code, cleanErrorReason(response.code, jsonError, text), expiresAt)
            }
            val result = obj ?: throw ApiException(response.code, "Bad response")

            // 滑动续期：任何认证响应都可能带回新 token（没带 token 的请求，如激活，不会有）
            if (token != null) {
                (result["renewedToken"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { onTokenRenewed?.invoke(token, it) }
            }

            result
        }
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
        this as? JsonObject

    suspend fun redeemInvite(
        code: String,
        devicePublicId: String,
        deviceName: String,
        appVersion: String,
    ): DeviceProfile {
        val body = buildJsonObject {
            put("inviteCode", code)
            put("devicePublicId", devicePublicId)
            put("deviceName", deviceName)
            put("appVersion", appVersion)
        }
        val env = json.decodeFromJsonElement(Envelope.serializer(), request("POST", "/redeem-invite", body))
        if (!env.ok || env.token.isNullOrBlank() || env.profileId.isNullOrBlank()) {
            throw ApiException(500, env.error ?: "Malformed response")
        }
        return DeviceProfile(
            profileId = env.profileId,
            token = env.token,
            ownerLabel = env.ownerLabel.orEmpty(),
            serverRegion = env.serverRegion.orEmpty(),
            configVersion = env.configVersion.orEmpty(),
            monthlyQuotaGB = env.monthlyQuotaGB,
            expiresAt = env.expiresAt,
            isOwner = parseOwnerFlag(env.isOwner),
            regions = parseRegions(env.regions),
        )
    }

    suspend fun fetchProfile(token: String): ProfileStatus {
        val raw = request("GET", "/profile", token = token)
        val env = json.decodeFromJsonElement(Envelope.serializer(), raw)
        if (!env.ok) throw ApiException(500, env.error ?: "Malformed response")
        return ProfileStatus(
            enabled = env.enabled ?: false,
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
    }

    /**
     * 取本设备专属的 sing-box 运行配置（每台设备独立 UUID，吊销才真正有效）。
     *
     * 后端返回的是信封 {ok, profileId, serverRegion, region, configVersion, runtimeConfig, runtimeMeta}，
     * 引擎只认里面的 runtimeConfig。整包喂进去会报 `decode config: json: unknown field "ok"`。
     *
     * region：
     *  - null（普通用户）：不带参数，区域由服务器按「分配给这个账号的区域」决定，以响应里回显的 region 为准；
     *  - 东京（owner 选的默认区域）：完全沿用 1.0 的请求，不带参数；
     *  - 其它区域（owner）：带 `?region=<id>`，且服务器必须在响应里确认同一个 region，否则抛错——绝不退回东京配置。
     */
    suspend fun fetchRuntimeConfig(token: String, region: String?): RuntimeConfig {
        val regionId = region?.let { normalizeRegionId(it).ifEmpty { REGION_TOKYO } }
        val path = if (regionId == null || regionId == REGION_TOKYO) {
            "/device/runtime-config"
        } else {
            "/device/runtime-config?region=" + URLEncoder.encode(regionId, "UTF-8")
        }
        val env = request("GET", path, token = token)
        val returnedRegion = (env["region"] as? JsonPrimitive)?.contentOrNull
        if (regionId != null && !regionConfirmed(regionId, returnedRegion)) {
            throw ApiException(502, ApiException.REGION_MISMATCH)
        }
        val cfg = env["runtimeConfig"] as? JsonObject
            ?: throw ApiException(500, "runtimeConfig missing in response")
        val name = (env["serverRegion"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val effectiveId = normalizeRegionId(returnedRegion).ifEmpty { regionId.orEmpty() }
        return RuntimeConfig(
            configJson = adaptForAndroid(cfg).toString(),
            regionId = effectiveId,
            regionName = name ?: effectiveId.replaceFirstChar { it.uppercase() },
        )
    }

    /** Owner 专用：所有邀请码（= 用户）及其区域 / 使用时长。逐条宽松解码。 */
    suspend fun fetchOwnerInvites(token: String): OwnerInvitesResponse {
        val resp = parseOwnerInvites(request("GET", "/owner/invites", token = token))
        if (!resp.ok) throw ApiException(500, resp.error ?: "Malformed response")
        return resp
    }

    /** Owner 专用：当前的完整字段 + 改了的 region / durationDays（见 [InviteUpdate.toJson]）。失败抛 ApiException，原因在 reason 里。 */
    suspend fun updateInvite(token: String, update: InviteUpdate) {
        val resp = request("POST", "/owner/invites/update", update.toJson(), token)
        val ok = (resp["ok"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: false
        if (!ok) throw ApiException(500, (resp["error"] as? JsonPrimitive)?.contentOrNull ?: "Malformed response")
    }

    /** Owner 专用：所有设备 + 当前周期用量。非 owner 的 token 会得到 403。逐台设备宽松解码。 */
    suspend fun fetchOwnerDevices(token: String): OwnerDevicesResponse {
        val resp = parseOwnerDevices(request("GET", "/owner/devices", token = token))
        if (!resp.ok) throw ApiException(500, resp.error ?: "Malformed response")
        return resp
    }

    /**
     * 后端那份配置模板是按 iOS 的 NetworkExtension 写的，两处对 Android 不适用：
     *  - strict_route：给桌面 Linux/Windows 用的，Android 的 VpnService.Builder
     *    已经自己接管了路由表，再让引擎去改会互相打架。
     *  - interface_name：Android 不允许自定义隧道接口名，系统固定给 tun0，
     *    留着它会让引擎按一个不存在的接口名去找。
     * 只在客户端做这层适配，后端一行不改（iOS 继续用原样配置）。
     */
    private fun adaptForAndroid(config: JsonObject): JsonObject {
        val inbounds = config["inbounds"] as? JsonArray ?: return config
        val patched = inbounds.map { entry ->
            val ib = entry as? JsonObject ?: return@map entry
            if (ib["type"]?.toString()?.trim('"') != "tun") return@map entry
            buildJsonObject {
                // 对照实验：只去掉这两项（strict_route 是桌面 Linux 参数，Android 的
                // VpnService.Builder 自己管路由；interface_name 在 Android 上无效，系统固定给 tun0）。
                // stack / mtu 一律沿用后端原值，先确认基线行为。
                ib.forEach { (k, v) ->
                    if (k != "strict_route" && k != "interface_name") put(k, v)
                }
            }
        }
        return buildJsonObject {
            config.forEach { (k, v) ->
                if (k == "inbounds") put(k, JsonArray(patched)) else put(k, v)
            }
        }
    }

    suspend fun heartbeat(token: String, upBytes: Long, downBytes: Long) {
        val body = buildJsonObject {
            put("connected", true)
            put("totalUp", upBytes)
            put("totalDown", downBytes)
        }
        try {
            request("POST", "/device/heartbeat", body, token)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 心跳失败不影响使用
        }
    }
}
