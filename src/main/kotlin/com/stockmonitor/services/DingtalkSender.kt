package com.stockmonitor.services

import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// ─────────────────────────────────────────────────────────────
//  钉钉机器人推送（支持加签）
// ─────────────────────────────────────────────────────────────

object DingtalkSender {

    fun send(
        webhook: String,
        secret: String,
        keyword: String,
        title: String,
        content: String
    ): Pair<Boolean, String> {
        if (webhook.isBlank()) return false to "未配置 Webhook"

        // 确保消息含关键词
        val safeTitle = if (keyword.isNotBlank() && keyword !in title && keyword !in content)
            "【$keyword】$title" else title

        // 加签
        val finalUrl = if (secret.isNotBlank()) {
            val ts   = System.currentTimeMillis()
            val str  = "$ts\n$secret"
            val mac  = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val sign = Base64.getEncoder().encodeToString(mac.doFinal(str.toByteArray(Charsets.UTF_8)))
            val enc  = java.net.URLEncoder.encode(sign, "UTF-8")
            "$webhook&timestamp=$ts&sign=$enc"
        } else webhook

        val payload = """
            {
              "msgtype": "markdown",
              "markdown": {
                "title": "${safeTitle.replace("\"", "\\\"")}",
                "text": "## ${safeTitle.replace("\"", "\\\"")}\n\n${content.replace("\"", "\\\"").replace("\n", "\\n\\n")}"
              }
            }
        """.trimIndent()

        return try {
            val conn = URL(finalUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput      = true
            conn.connectTimeout = 8000
            conn.readTimeout    = 8000
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            conn.disconnect()

            val errcode = Regex("\"errcode\"\\s*:\\s*(\\d+)").find(resp)?.groupValues?.get(1)
            if (errcode == "0") true to "发送成功"
            else false to "钉钉返回: $resp"
        } catch (e: Exception) {
            false to "异常: ${e.message}"
        }
    }
}
