package com.cloudreel.tv.core.live

import com.cloudreel.tv.core.drive.parseAlistList
import com.cloudreel.tv.core.drive.parseAlistUrl
import com.cloudreel.tv.core.drive.parseWebDav
import com.cloudreel.tv.core.lan.Submission
import com.cloudreel.tv.core.lan.parseSubmission
import com.cloudreel.tv.core.model.LiveChannel
import com.cloudreel.tv.core.script.ScriptRunner
import com.cloudreel.tv.core.script.scriptDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDriveScriptTest {
    @Test
    fun m3uKeepsQuotedCommaAndMergesSameChannel() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="cctv1" group-title="央视,综合",央视一台
            https://example.com/a.m3u8
            #EXTINF:-1 tvg-id="cctv1" group-title="央视,综合",央视一台
            https://example.com/b.m3u8
        """.trimIndent()
        val channels = parseM3u(text)
        assertEquals(1, channels.size)
        assertEquals("央视,综合", channels[0].group)
        assertEquals(listOf("https://example.com/a.m3u8", "https://example.com/b.m3u8"), channels[0].urls)
    }

    @Test
    fun judgesWhetherALiveResponseCanPlay() {
        assertEquals(LiveVerdict.PLAYABLE, liveVerdict(200, "application/vnd.apple.mpegurl", "#EXTM3U"))
        assertEquals(LiveVerdict.DEAD, liveVerdict(404, "text/plain", "missing"))
        assertEquals(LiveVerdict.DEAD, liveVerdict(200, "text/html", "<!DOCTYPE html><html></html>"))
        assertEquals(LiveVerdict.UNKNOWN, liveVerdict(403, "text/plain", "forbidden"))
        val channels = listOf(
            LiveChannel("a", "a", "央视", null, null, listOf("https://example.com/a.m3u8")),
            LiveChannel("b", "b", "卫视", null, null, listOf("https://example.com/b.m3u8")),
        )
        assertEquals(listOf("https://example.com/a.m3u8", "https://example.com/b.m3u8"), sampleLiveUrls(channels))
    }

    @Test
    fun readsTvBoxNameUrlLiveList() {
        val channels = parseTvBoxLiveText(
            """
            春晚1983,https://example.com/a.mp4
            央视,#genre#
            综合,https://example.com/1.m3u8#https://example.com/2.m3u8
            """.trimIndent(),
        )
        assertEquals(2, channels.size)
        assertEquals("未分组", channels[0].group)
        assertEquals("春晚1983", channels[0].name)
        assertEquals("央视", channels[1].group)
        assertEquals(listOf("https://example.com/1.m3u8", "https://example.com/2.m3u8"), channels[1].urls)
        val parsed = parseLiveDocument("春晚,https://example.com/a.mp4").first
        assertEquals(1, parsed.size)
        assertEquals("春晚", parsed[0].name)
    }

    @Test
    fun jsonAndXmltvExposeCurrentProgram() {
        val parsed = parseLiveJson(
            """
            {"channels":[{"name":"测试","group":"公开","tvgId":"demo","urls":["https://example.com/a.m3u8"]}],
             "programs":[{"channelId":"demo","title":"午间","start":"2026-10-26T04:00:00Z","end":"2026-10-26T05:00:00Z"}]}
            """.trimIndent(),
        )
        val programs = parsed.second + parseXmltv(
            """
            <tv><programme start="20261026130000 +0800" stop="20261026140000 +0800" channel="demo"><title>下午</title></programme></tv>
            """.trimIndent(),
        )
        val channel = parsed.first.single()
        val now = parseTime("2026-10-26T04:30:00Z")
        assertEquals("午间", currentProgram(programs, channel, now)?.title)
    }

    @Test
    fun webdavSkipsCurrentFolderAndMarksVideo() {
        val xml = """
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:displayname>dav</d:displayname><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
              <d:response><d:href>/dav/movie.mp4</d:href><d:propstat><d:prop><d:displayname>movie.mp4</d:displayname><d:resourcetype/><d:getcontenttype>video/mp4</d:getcontenttype></d:prop></d:propstat></d:response>
            </d:multistatus>
        """.trimIndent()
        val entries = parseWebDav(xml, "https://disk.example/dav/")
        assertEquals(1, entries.size)
        assertEquals("movie.mp4", entries[0].name)
        assertTrue(entries[0].playable)
    }

    @Test
    fun alistListAndUrl() {
        val entries = parseAlistList(
            """{"code":200,"data":{"content":[{"name":"影片","is_dir":true},{"name":"a.mkv","is_dir":false}]}}""",
            "/",
        )
        assertEquals("/影片", entries[0].path)
        assertTrue(entries[1].playable)
        assertEquals("https://cdn.example/a.mkv", parseAlistUrl("""{"code":200,"data":{"raw_url":"https://cdn.example/a.mkv"}}"""))
    }

    @Test
    fun scriptReturnsDetail() {
        val runner = ScriptRunner { _, _ -> error("示例脚本不需要网络") }
        val json = runner.invoke(
            """
            var source = {
              home: function (ctx) { return { items: [{ id: "1", title: "脚本样片", year: "2008" }] }; },
              detail: function (ctx, id) {
                return { id: id, title: "脚本样片", lines: [{ name: "默认", episodes: [{ name: "正片", url: "https://example.com/a.mp4" }] }] };
              }
            };
            """.trimIndent(),
            "home",
            emptyList(),
        )
        assertTrue(json.contains("脚本样片"))
        val detail = scriptDetail(
            runner.invoke(
                """
                var source = {
                  detail: function (ctx, id) {
                    return { id: id, title: "脚本样片", lines: [{ name: "默认", episodes: [{ name: "正片", url: "https://example.com/a.mp4" }] }] };
                  }
                };
                """.trimIndent(),
                "detail",
                listOf("1"),
            ),
            "script",
            "脚本",
        )
        assertEquals("https://example.com/a.mp4", detail.lines.single().episodes.single().url)
    }

    @Test
    fun submissionKeepsScriptBody() {
        val submission = parseSubmission("""{"type":"script","name":"我的脚本","code":"var source = {};"}""") as Submission.Script
        assertEquals("我的脚本", submission.name)
        assertEquals("var source = {};", submission.code)
    }

    @Test
    fun missingYearStyleChannelStillMatchesProgramByName() {
        val channel = LiveChannel("新闻", "新闻", "综合", null, null, listOf("https://example.com/a.m3u8"))
        val program = parseXmltv(
            """<programme start="20261026000000 +0000" stop="20261027000000 +0000" channel="新闻"><title>整点</title></programme>""",
        )
        assertEquals("整点", currentProgram(program, channel, parseTime("2026-10-26T08:00:00Z"))?.title)
    }
}
