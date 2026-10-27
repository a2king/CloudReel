package com.cloudreel.tv.core.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Pan115Test {
    @Test
    fun recognizes115PickCodeAndIgnoresHttp() {
        val link = recognizePan115(
            "115原画",
            "抓特务.mkv",
            "swsmuow36dhjw843l3505343468312426329",
        )
        assertEquals("swsmuow36dhjw843l3505343468312426329", link?.pickCode)
        assertEquals("原画", link?.quality)
        assertEquals("抓特务.mkv", link?.fileName)
        assertNull(recognizePan115("115原画", "正片", "https://example.com/a.mp4"))
        assertNull(recognizePan115("线路1", "正片", "swsmuow36dhjw843l3505343468312426329"))
    }

    @Test
    fun parsesDownUrlPayload() {
        val file = parsePan115DownUrl(
            """
            {
              "state": true,
              "data": {
                "100": {
                  "file_name": "movie.mkv",
                  "file_size": "2147483648",
                  "url": {"url": "https://cdn.115.com/movie.mkv"}
                }
              }
            }
            """.trimIndent(),
        )
        assertEquals("movie.mkv", file.name)
        assertEquals(2147483648L, file.sizeBytes)
        assertTrue(file.url.startsWith("https://"))
        assertEquals("2.0GB", formatByteSize(file.sizeBytes))
    }

    @Test
    fun parsesQrLoginFlow() {
        val start = parsePan115QrStart("""{"state":1,"data":{"uid":"abc","time":12,"sign":"sig"}}""")
        assertEquals("abc", start.uid)
        assertEquals("https://115.com/scan/dg-abc", start.content)
        assertEquals(Pan115QrPhase.SCANNED, parsePan115QrStatus("""{"state":1,"data":{"status":1}}"""))
        assertEquals(Pan115QrPhase.CONFIRMED, parsePan115QrStatus("""{"state":1,"data":2}"""))
        assertEquals(Pan115QrPhase.EXPIRED, parsePan115QrStatus("""{"state":1,"data":{"status":-1}}"""))
        val cookie = parsePan115LoginCookie(
            """{"state":1,"data":{"cookie":{"UID":"1","CID":"2","SEID":"3"}}}""",
            listOf("KID=4; Path=/; Domain=.115.com"),
        )
        assertEquals("UID=1; CID=2; SEID=3; KID=4", cookie)
    }
}