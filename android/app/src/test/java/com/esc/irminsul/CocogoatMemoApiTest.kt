package com.esc.irminsul

import org.junit.Assert.assertEquals
import org.junit.Test

class CocogoatMemoApiTest {
    @Test
    fun `buildCocogoatUrl returns correct memo URL from key`() {
        val key = "abc123"
        val url = buildCocogoatUrl(key)
        assertEquals("https://cocogoat.work/achievement?memo=abc123", url)
    }

    @Test
    fun `buildMemoApiUrl returns correct API endpoint`() {
        val url = buildMemoApiUrl()
        assertEquals("https://77.cocogoat.cn/v1/memo?source=all_achievement", url)
    }

    @Test
    fun `parseMemoResponse extracts key from JSON`() {
        val json = """{"key":"testkey456"}"""
        val key = parseMemoResponse(json)
        assertEquals("testkey456", key)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseMemoResponse throws on missing key`() {
        val json = """{"error":"not found"}"""
        parseMemoResponse(json)
    }

    @Test
    fun `buildCocogoatUrl with empty key still produces valid URL`() {
        val url = buildCocogoatUrl("")
        assertEquals("https://cocogoat.work/achievement?memo=", url)
    }
}
