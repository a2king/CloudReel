package com.yingshicang.tv.core.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudTextTest {
    @Test
    fun recognizesShareLinksAndPasswords() {
        val quark = recognizeCloud("夸克原画", "正片", "https://pan.quark.cn/s/abcDEF123?pwd=abcd")
        assertEquals("quark", quark?.kind)
        assertEquals("abcDEF123", quark?.shareId)
        assertEquals("abcd", quark?.password)

        val ali = recognizeCloud("阿里", "电影.mkv", "https://www.alipan.com/s/share123")
        assertEquals("ali", ali?.kind)
        assertEquals("share123", ali?.shareId)

        val baidu = recognizeCloud("百度", "正片 提取码: wxyz", "https://pan.baidu.com/s/1abcdefgh")
        assertEquals("baidu", baidu?.kind)
        assertEquals("abcdefgh", baidu?.shareId)
        assertEquals("wxyz", baidu?.password)

        assertEquals("115", recognizeCloud("115原画", "a.mkv", "swsmuow36dhjw843l3505343468312426329")?.kind)
        assertNull(recognizeCloud("线路1", "正片", "https://example.com/a.mp4"))
        val marked = lineCloud(
            "feifan",
            listOf("第01集" to "https://pan.quark.cn/s/abcDEF123"),
        )
        assertEquals("quark", marked?.kind)
        assertEquals("夸克网盘 · 已登录", cloudAccountLabel("夸克网盘", true))
        assertEquals("115 网盘 · 未登录", cloudAccountLabel("115 网盘", false))
        assertNull(lineCloud("ffm3u8", listOf("第01集" to "https://example.com/a.m3u8")))
    }

    @Test
    fun picksLargestVideoAndParsesQrPayloads() {
        val chosen = chooseCloudEntry(
            listOf(
                CloudEntry("1", "介绍.txt", 10, false),
                CloudEntry("2", "小.mp4", 20, false),
                CloudEntry("3", "大.mkv", 50, false),
            ),
            "正片",
        )
        assertEquals("3", chosen?.id)
        val quark = parseQuarkQrStart("""{"status":2000000,"data":{"members":{"token":"tok"}}}""", "req")
        assertEquals("tok", org.json.JSONObject(quark.state).optString("token"))
        val ali = parseAliQrStart("""{"content":{"data":{"t":7,"ck":"ck1","codeContent":"https://ali.example/qr"}}}""")
        assertEquals("https://ali.example/qr", ali.content)
        val confirmed = parseAliQrPoll(
            """{"content":{"data":{"qrCodeStatus":"CONFIRMED","bizExt":"{\"pds_login_result\":{\"refreshToken\":\"ref\"}}"}}}""",
            ali,
        )
        assertEquals(CloudPhase.CONFIRMED, confirmed.phase)
        assertEquals("ref", org.json.JSONObject(confirmed.session.state).optString("refresh"))
        val baidu = parseBaiduQrPoll(
            """cb({"errno":0,"channel_v":"{\"status\":0,\"v\":\"temp\"}"})""",
            CloudQrSession("https://wappass.baidu.com/wp/?qrlogin&sign=s", """{"sign":"s","gid":"g"}"""),
        )
        assertEquals("temp", org.json.JSONObject(baidu.session.state).optString("v"))
    }
}