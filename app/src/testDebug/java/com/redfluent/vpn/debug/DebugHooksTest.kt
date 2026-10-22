package com.redfluent.vpn.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** F10：测试用 API 地址只接受 http://10.0.2.2:<端口>，主机名必须精确匹配。 */
class DebugHooksTest {

    @Test
    fun acceptsOnlyEmulatorHostWithPort() {
        assertEquals("http://10.0.2.2:18090", DebugHooks.allowedTestBase("http://10.0.2.2:18090"))
        assertEquals("http://10.0.2.2:18090", DebugHooks.allowedTestBase("http://10.0.2.2:18090/"))
        assertEquals("http://10.0.2.2:18090", DebugHooks.allowedTestBase("  http://10.0.2.2:18090  "))
        assertEquals("http://10.0.2.2:1", DebugHooks.allowedTestBase("http://10.0.2.2:1"))
        assertEquals("http://10.0.2.2:65535", DebugHooks.allowedTestBase("http://10.0.2.2:65535"))
    }

    @Test
    fun rejectsEverythingElse() {
        val bad = listOf(
            null,
            "",
            "https://vpn-api.redfluent.com",
            "https://10.0.2.2:18090",
            "http://10.0.2.2", // 没有端口
            "http://10.0.2.22:18090", // 相似主机
            "http://10.0.2.2.evil.com:18090",
            "http://evil.com:18090",
            "http://evil.com:18090@10.0.2.2:80",
            "http://user@10.0.2.2:18090",
            "http://10.0.2.2:18090/path",
            "http://10.0.2.2:18090?x=1",
            "http://10.0.2.2:0",
            "http://10.0.2.2:65536",
            "http://10.0.2.2:99999",
            "http://10.0.2.2:-1",
            "ftp://10.0.2.2:18090",
            "HTTP://10.0.2.2:18090",
            "http://127.0.0.1:18090",
            "http://localhost:18090",
        )
        for (url in bad) assertNull("should reject: $url", DebugHooks.allowedTestBase(url))
    }
}
