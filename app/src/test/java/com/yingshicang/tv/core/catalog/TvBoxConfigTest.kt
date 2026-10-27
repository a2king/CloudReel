package com.yingshicang.tv.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvBoxConfigTest {
    @Test
    fun parsesRemoteSubscription() {
        val document = parseTvBoxDocument(
            """
            {
              "urls": [
                {"name":"源一","url":"https://example.com/one.json"},
                "https://example.com/two.json"
              ]
            }
            """.trimIndent(),
        )

        assertEquals(2, document.remoteSources.size)
        assertEquals("源一", document.remoteSources[0].name)
        assertEquals("https://example.com/two.json", document.remoteSources[1].url)
        assertTrue(document.sites.isEmpty())
        assertTrue(!document.isCmsResponse)
        assertTrue(isTvBoxLineIndex(document))
    }

    @Test
    fun parsesSitesAndCmsResponses() {
        val document = parseTvBoxDocument(
            """
            {
              "sites": [
                {"key":"a","name":"站点 A","type":1,"api":"https://example.com/api"},
                {"name":"站点 B","url":"https://example.com/b"}
              ],
              "list": []
            }
            """.trimIndent(),
        )

        assertEquals(2, document.sites.size)
        assertEquals("站点 A", document.sites[0].name)
        assertEquals("https://example.com/api", document.sites[0].api)
        assertTrue(document.isCmsResponse)
        assertTrue(!isTvBoxLineIndex(document))
    }

    @Test
    fun acceptsCommentsAndKeepsCmsSites() {
        val document = parseTvBoxDocument(
            """
            {
              "urls": [
                {"name":"源一","url":"https://example.com/a.json"},
              ],
              // 注释不能破坏 https:// 链接
              "sites": [
                {"name":"脚本","type":3,"api":"https://example.com/a.js"},
                {"name":"采集","type":1,"api":"https://example.com/api.php/provide/vod/"},
              ]
            }
            """.trimIndent(),
        )

        assertEquals(1, document.remoteSources.size)
        assertEquals("https://example.com/a.json", document.remoteSources[0].url)
        assertEquals(2, document.sites.size)
        assertTrue(!isCmsSite(document.sites[0]))
        assertTrue(isCmsSite(document.sites[1]))
    }

    @Test
    fun unwrapsImageWrappedSingleConfig() {
        val json = """
            {
              // 图片包装里的注释
              "sites":[{"name":"脚本","type":3,"api":"csp_Demo"}],
              "lives":[
                {"name":"直播","url":"https://example.com/live.m3u"},
                {"name":"本机","url":"http://127.0.0.1:9978/proxy"}
              ]
            }
        """.trimIndent()
        val wrapped = "\u0000JFIF**" + java.util.Base64.getEncoder().encodeToString(json.toByteArray())
        val document = parseTvBoxDocument(wrapped)

        assertEquals(1, document.sites.size)
        assertTrue(!isCmsSite(document.sites[0]))
        assertEquals(1, document.lives.size)
        assertEquals("直播", document.lives[0].name)
        assertEquals("https://example.com/live.m3u", document.lives[0].url)
    }

    @Test
    fun tellsScriptWarehousesFromScrapeableLines() {
        val script = parseTvBoxDocument(
            """
            {"sites":[
              {"name":"脚本","type":3,"api":"csp_Demo"},
              {"name":"本地","type":3,"api":"./a.js"}
            ]}
            """.trimIndent(),
        )
        assertTrue(!hasScrapeableCms(script, "https://example.com/tv"))

        val nested = parseTvBoxDocument(
            """{"urls":[{"name":"子仓","url":"https://example.com/child.json"}]}""",
        )
        assertTrue(!hasScrapeableCms(nested, "https://example.com/index.json"))

        val cms = parseTvBoxDocument(
            """
            {"sites":[
              {"name":"脚本","type":3,"api":"csp_Demo"},
              {"name":"采集","type":1,"api":"https://example.com/api.php/provide/vod/"}
            ]}
            """.trimIndent(),
        )
        assertTrue(hasScrapeableCms(cms, "https://example.com/line.json"))
    }

    @Test
    fun readsWrappedConfigFromRawBytes() {
        val json = """{"sites":[{"name":"脚本","type":3,"api":"csp_Demo"}]}"""
        val encoded = java.util.Base64.getEncoder().encodeToString(json.toByteArray())
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00, 42, 42) +
            encoded.toByteArray(Charsets.US_ASCII)
        val document = parseTvBoxDocument(configText(bytes))
        assertEquals(1, document.sites.size)
        assertEquals("脚本", document.sites[0].name)
        assertTrue(!isCmsSite(document.sites[0]))
    }

    @Test
    fun resolvesRelativeChildUrls() {
        assertEquals(
            "https://example.com/config/child.json",
            resolveTvBoxUrl("https://example.com/config/main.json", "child.json"),
        )
        assertEquals(
            "https://example.com/child.json",
            resolveTvBoxUrl("https://example.com/config/main.json", "/child.json"),
        )
    }
}
