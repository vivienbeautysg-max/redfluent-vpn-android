package com.redfluent.vpn.api

import com.redfluent.vpn.data.DeviceProfile
import com.redfluent.vpn.data.Envelope
import com.redfluent.vpn.data.ProfileStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, val reason: String) : Exception(userMessage(code, reason)) {
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
            else -> "Server error $code: $reason"
        }
    }
}

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

    /** 后端在 token 接近过期时，会在任意认证响应里附带 renewedToken；在这里统一接住。 */
    @Volatile
    var onTokenRenewed: (suspend (String) -> Unit)? = null

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
                val reason = obj?.get("error")?.toString()?.trim('"') ?: text.ifBlank { "unknown" }
                throw ApiException(response.code, reason)
            }
            val result = obj ?: throw ApiException(response.code, "Bad response")

            // 滑动续期：任何认证响应都可能带回新 token
            result["renewedToken"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }
                ?.let { onTokenRenewed?.invoke(it) }

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
        )
    }

    suspend fun fetchProfile(token: String): ProfileStatus {
        val env = json.decodeFromJsonElement(Envelope.serializer(), request("GET", "/profile", token = token))
        if (!env.ok) throw ApiException(500, env.error ?: "Malformed response")
        return ProfileStatus(
            enabled = env.enabled ?: false,
            profileId = env.profileId.orEmpty(),
            ownerLabel = env.ownerLabel.orEmpty(),
            serverRegion = env.serverRegion.orEmpty(),
            routingMode = env.routingMode.orEmpty(),
            monthlyQuotaGB = env.monthlyQuotaGB,
        )
    }

    /**
     * 取本设备专属的 sing-box 运行配置（每台设备独立 UUID，吊销才真正有效）。
     *
     * 后端返回的是信封 {ok, profileId, serverRegion, configVersion, runtimeConfig, runtimeMeta}，
     * 引擎只认里面的 runtimeConfig。整包喂进去会报 `decode config: json: unknown field "ok"`。
     */
    suspend fun fetchRuntimeConfig(token: String): String {
        val env = request("GET", "/device/runtime-config", token = token)
        val cfg = env["runtimeConfig"] as? JsonObject
            ?: throw ApiException(500, "runtimeConfig missing in response")
        return adaptForAndroid(cfg).toString()
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
        runCatching { request("POST", "/device/heartbeat", body, token) }
    }
}
