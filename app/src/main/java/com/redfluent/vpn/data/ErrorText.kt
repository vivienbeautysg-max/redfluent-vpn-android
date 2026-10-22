package com.redfluent.vpn.data

/** 界面上显示的错误原因最长多少字符；超过就当「不是给人看的」。 */
const val MAX_ERROR_REASON_LENGTH = 100

/** HTTP 状态码的一句话说明。上游挂了时 Cloudflare 会返回整页 HTML，界面上只该看到这一句。 */
fun httpStatusText(code: Int): String = when (code) {
    400 -> "bad request"
    401 -> "unauthorized"
    403 -> "forbidden"
    404 -> "not found"
    429 -> "too many requests"
    500 -> "internal error"
    502 -> "bad gateway"
    503 -> "service unavailable"
    504 -> "gateway timeout"
    in 520..530 -> "upstream error"
    else -> "unexpected response"
}

private fun looksLikeMarkup(s: String): Boolean {
    val t = s.trimStart().lowercase()
    return t.startsWith("<") || t.contains("<!doctype") || t.contains("<html")
}

/**
 * 把失败响应变成一句短话：
 *  - 后端的 JSON {"error": "..."} 原因（合理长度、单行）→ 原样用；
 *  - 否则看响应正文：空 → "unknown"；像 HTML / 多行 / 太长 → 用状态码的短说明；其余原样。
 * 保证返回值不含 "<!DOCTYPE"、不含换行、不超过 [MAX_ERROR_REASON_LENGTH]。
 */
fun cleanErrorReason(code: Int, jsonError: String?, rawBody: String): String {
    fun ok(s: String) = s.isNotEmpty() && s.length <= MAX_ERROR_REASON_LENGTH &&
        '\n' !in s && '\r' !in s && !looksLikeMarkup(s)

    val fromJson = jsonError?.trim().orEmpty()
    if (ok(fromJson)) return fromJson
    val body = rawBody.trim()
    if (jsonError == null && body.isEmpty()) return "unknown"
    if (jsonError == null && ok(body)) return body
    return httpStatusText(code)
}
