package com.redfluent.vpn.debug

import android.content.Context
import android.content.Intent
import android.util.Log
import com.redfluent.vpn.api.ApiClient
import com.redfluent.vpn.data.DeviceProfile
import com.redfluent.vpn.data.ProfileStore
import java.io.File

/**
 * 仅存在于 debug 变体的测试钩子（release 源集里是同名空实现，整段不会进 release 包）。
 *
 * 用途：不走邀请码激活，直接把一个测试设备 token 塞成当前 profile，
 * 用来在模拟器里验证 owner 路径（区域选择 / Usage 页 / 连接新加坡）。
 *
 * token 来源（按顺序）：
 *   1. adb 启动参数 `--es rfv_test_token <token>`
 *   2. 文件 /data/local/tmp/rfv-test-token
 *   3. 文件 <app files>/rfv-test-token（`adb shell run-as` 写入，SELinux 拦 /data/local/tmp 时的退路）
 * API 地址覆盖（指到本机 mock 用，只接受 https 或模拟器宿主 10.0.2.2）：
 *   `--es rfv_test_base <url>` / /data/local/tmp/rfv-test-base / <app files>/rfv-test-base
 * 日志里绝不打印 token。
 */
object DebugHooks {

    private const val TAG = "RfvDebugHooks"
    private const val NAME_TOKEN = "rfv-test-token"
    private const val NAME_BASE = "rfv-test-base"
    private const val EXTRA_TOKEN = "rfv_test_token"
    private const val EXTRA_BASE = "rfv_test_base"
    private const val TMP_DIR = "/data/local/tmp"

    /** Application.onCreate 里调用：只处理 API 地址覆盖（必须赶在任何网络请求之前）。 */
    fun onAppCreate(context: Context) {
        val base = readFile(File(TMP_DIR, NAME_BASE)) ?: readFile(File(context.filesDir, NAME_BASE))
        applyBase(base)
    }

    /** MainActivity 启动 / 收到新 Intent 时调用。 */
    suspend fun onActivityCreate(context: Context, intent: Intent?) {
        applyBase(intent?.getStringExtra(EXTRA_BASE))

        val token = intent?.getStringExtra(EXTRA_TOKEN)?.trim()?.takeIf { it.isNotEmpty() }
            ?: readFile(File(TMP_DIR, NAME_TOKEN))
            ?: readFile(File(context.filesDir, NAME_TOKEN))
            ?: return

        val store = ProfileStore(context)
        val status = try {
            ApiClient.fetchProfile(token)
        } catch (e: Exception) {
            Log.w(TAG, "token injection failed: ${e.message}")
            return
        }
        store.saveProfile(
            DeviceProfile(
                profileId = status.profileId.ifBlank { "debug-injected" },
                token = token,
                ownerLabel = status.ownerLabel,
                serverRegion = status.serverRegion,
                configVersion = status.configVersion,
                monthlyQuotaGB = status.monthlyQuotaGB,
                expiresAt = status.expiresAt,
                isOwner = status.isOwner,
                regions = status.regions,
            ),
        )
        store.reconcileRegion(status.regions)
        store.setOnboarded()
        Log.i(TAG, "profile injected: owner=${status.isOwner} regions=${status.regions.map { it.id }}")
    }

    private fun applyBase(raw: String?) {
        val url = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (url.startsWith("https://") || url.startsWith("http://10.0.2.2")) {
            ApiClient.setBaseUrl(url)
            Log.i(TAG, "API base overridden: $url")
        } else {
            Log.w(TAG, "API base override rejected (only https or 10.0.2.2 allowed)")
        }
    }

    private fun readFile(file: File): String? = runCatching {
        if (file.isFile && file.canRead()) file.readText().trim().takeIf { it.isNotEmpty() } else null
    }.getOrNull()
}
