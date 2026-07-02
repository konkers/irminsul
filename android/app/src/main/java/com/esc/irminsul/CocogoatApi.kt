package com.esc.irminsul

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private const val MEMO_API_URL = "https://77.cocogoat.cn/v1/memo?source=all_achievement"
private const val COCOGOAT_BASE_URL = "https://cocogoat.work/achievement"

fun buildMemoApiUrl(): String = MEMO_API_URL

fun buildCocogoatUrl(key: String): String = "$COCOGOAT_BASE_URL?memo=$key"

fun configureMemoConnection(conn: HttpURLConnection, body: String) {
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
    conn.connectTimeout = 10_000
    conn.readTimeout = 15_000
    conn.doOutput = true
    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
}

fun parseMemoResponse(json: String): String {
    val keyPattern = """"key"\s*:\s*"([^"]+)"""".toRegex()
    val match = keyPattern.find(json)
        ?: throw IllegalArgumentException("No 'key' found in response: $json")
    return match.groupValues[1]
}

suspend fun postToMemoApi(uiafJson: String): String = withContext(Dispatchers.IO) {
    val url = URL(buildMemoApiUrl())
    val conn = url.openConnection() as HttpURLConnection
    try {
        configureMemoConnection(conn, uiafJson)
        if (conn.responseCode != 201) {
            throw RuntimeException("Cocogoat API returned ${conn.responseCode}")
        }
        val response = conn.inputStream.bufferedReader().readText()
        val key = parseMemoResponse(response)
        buildCocogoatUrl(key)
    } finally {
        conn.disconnect()
    }
}
