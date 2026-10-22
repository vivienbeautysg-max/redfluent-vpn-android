package com.redfluent.vpn.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "redfluent_vpn")

/**
 * 设备身份与凭据的本地存储。
 * 注意：manifest 已关闭云备份与设备迁移——令牌属于凭据，不该离开这台机器。
 */
class ProfileStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val PROFILE = stringPreferencesKey("profile_json")
        val DEVICE_ID = stringPreferencesKey("device_public_id")
        val ONBOARDED = booleanPreferencesKey("onboarding_done")
        val WANT_CONNECTED = booleanPreferencesKey("want_connected")
        val LAST_INVITE = stringPreferencesKey("last_invite_code")
    }

    val profileFlow: Flow<DeviceProfile?> = context.dataStore.data.map { prefs ->
        prefs[Keys.PROFILE]?.let { runCatching { json.decodeFromString<DeviceProfile>(it) }.getOrNull() }
    }

    val onboardedFlow: Flow<Boolean> = context.dataStore.data.map { it[Keys.ONBOARDED] ?: false }

    /** 用户上次是否处于「想连接」状态——开机恢复只在为 true 时进行。 */
    val wantConnectedFlow: Flow<Boolean> = context.dataStore.data.map { it[Keys.WANT_CONNECTED] ?: false }

    /**
     * 用户激活时用过的邀请码。保存它是为了换手机时用户能自己看到、复制、重输，
     * 不必回头找管理员要——安卓没有 Sign in with Apple 那种恢复入口。
     */
    val lastInviteFlow: Flow<String?> = context.dataStore.data.map { it[Keys.LAST_INVITE] }

    suspend fun profile(): DeviceProfile? = profileFlow.first()

    suspend fun saveProfile(profile: DeviceProfile) {
        context.dataStore.edit { it[Keys.PROFILE] = json.encodeToString(DeviceProfile.serializer(), profile) }
    }

    suspend fun clearProfile() {
        context.dataStore.edit {
            it.remove(Keys.PROFILE)
            it[Keys.WANT_CONNECTED] = false
        }
    }

    suspend fun setOnboarded() {
        context.dataStore.edit { it[Keys.ONBOARDED] = true }
    }

    suspend fun setWantConnected(value: Boolean) {
        context.dataStore.edit { it[Keys.WANT_CONNECTED] = value }
    }

    suspend fun saveInviteCode(code: String) {
        context.dataStore.edit { it[Keys.LAST_INVITE] = code }
    }

    /** 设备公开标识：本机随机生成一次后固定，不含任何硬件标识符。 */
    suspend fun devicePublicId(): String {
        val existing = context.dataStore.data.map { it[Keys.DEVICE_ID] }.first()
        if (!existing.isNullOrBlank()) return existing
        val fresh = "android-" + UUID.randomUUID().toString()
        context.dataStore.edit { it[Keys.DEVICE_ID] = fresh }
        return fresh
    }
}
