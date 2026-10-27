package com.cloudreel.tv.core.catalog

import com.cloudreel.tv.core.model.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTextTest {
    @Test
    fun mergeIgnoresPunctuationAndNotes() {
        val merged = mergeHits(
            listOf(
                hit("大雄兔", "2008", "a"),
                hit("大雄兔（4K）", "2008年", "b"),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals(2, merged.first().hits.size)
    }

    @Test
    fun differentYearsStaySeparate() {
        val merged = mergeHits(
            listOf(
                hit("大雄兔", "2008", "a"),
                hit("大雄兔", "2010", "b"),
            ),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun missingYearCanMerge() {
        assertTrue(shouldMerge(hit("辛特尔", null, "a"), hit("辛特尔", "2010", "b")))
    }

    @Test
    fun parseCmsLinesAndHeaders() {
        val dollar = "${'$'}"
        val separator = dollar + dollar + dollar
        val lines = parseCmsPlay(
            "线路A${separator}线路B",
            "第1集${dollar}https://a/1.mp4#第2集${dollar}https://a/2.m3u8${separator}第1集${dollar}https://b/1.mp4?token=1&x=2@Referer=https://example.com/@User-Agent=TvTest",
        )
        assertEquals(2, lines.size)
        assertEquals("线路A", lines[0].name)
        assertEquals(2, lines[0].episodes.size)
        assertEquals("https://b/1.mp4?token=1&x=2", lines[1].episodes[0].url)
        assertEquals("https://example.com/", lines[1].episodes[0].headers["Referer"])
        assertEquals("TvTest", lines[1].episodes[0].headers["User-Agent"])
    }

    @Test
    fun queryStringStaysInUrl() {
        val (url, headers) = splitUrlAndHeaders("https://cdn.example/a.mp4?token=1&x=2")
        assertEquals("https://cdn.example/a.mp4?token=1&x=2", url)
        assertTrue(headers.isEmpty())
    }

    @Test
    fun resumeRules() {
        assertFalse(ResumePolicy.shouldContinue(4_000, 100_000))
        assertTrue(ResumePolicy.shouldContinue(20_000, 100_000))
        assertTrue(ResumePolicy.isCompleted(96_000, 100_000))
        assertFalse(ResumePolicy.shouldContinue(96_000, 100_000))
        assertFalse(ResumePolicy.isCompleted(10_000, 0))
    }

    @Test
    fun stripHtmlKeepsLineBreak() {
        assertEquals("你好\n世界", stripHtml("<p>你好<br>世界</p>"))
    }

    @Test
    fun absolutizeAndCmsUrl() {
        assertEquals(
            "https://cdn.example/a.jpg",
            absolutizeUrl("https://example.com/api.php/provide/vod/", "//cdn.example/a.jpg"),
        )
        assertEquals(
            "https://example.com/pic.jpg",
            absolutizeUrl("https://example.com/api.php/provide/vod/", "/pic.jpg"),
        )
        assertEquals(
            "https://example.com/api.php/provide/vod/?ac=detail&wd=%E5%A4%A7%E9%9B%84%E5%85%94",
            buildCmsUrl("https://example.com/api.php/provide/vod/?ac=list", mapOf("ac" to "detail", "wd" to "大雄兔")),
        )
    }

    @Test
    fun usableCmsPayloadNeedsAnEpisode() {
        assertTrue(isUsableCmsPayload("""{"code":1,"list":[{"vod_id":1,"vod_name":"片子"}]}"""))
        assertTrue(isUsableCmsPayload("""{"data":{"list":[{"vod_id":"8"}]}}"""))
        assertTrue(isUsableCmsPayload("""{"list":[{"vod_name":"截断"""))
        assertFalse(isUsableCmsPayload("""{"code":1,"list":[]}"""))
        assertFalse(isUsableCmsPayload("<html>not json</html>"))
    }

    @Test
    fun rejectCmsWithoutHttp() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            normalizeCmsBase("ftp://example.com/api")
        }
        assertTrue(error.message!!.contains("http"))
    }

    private fun hit(title: String, year: String?, providerId: String) = SearchHit(
        providerId = providerId,
        providerName = providerId,
        priority = 1,
        sourceId = title,
        title = title,
        poster = null,
        year = year,
        area = null,
        genres = emptyList(),
        remarks = null,
    )
}
