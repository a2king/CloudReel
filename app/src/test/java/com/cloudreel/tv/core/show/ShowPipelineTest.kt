package com.cloudreel.tv.core.show

import com.cloudreel.tv.core.database.ProgressEntity
import com.cloudreel.tv.core.live.LiveVerdict
import com.cloudreel.tv.core.model.Episode
import com.cloudreel.tv.data.ShowSession
import com.cloudreel.tv.data.StoredLink
import com.cloudreel.tv.data.playbackFor
import com.cloudreel.tv.data.playbackOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowPipelineTest {
    @Test
    fun mergesSameTitleAndDropsSiteJunk() {
        val now = 1_700_000_000_000L
        val fold = foldCatalog(
            listOf(
                entry("a", "源甲", "1", "流浪地球", "2019", "电影", listOf("科幻", "蓝光", "更新至"), poster = null),
                entry("b", "源乙", "9", "流浪地球", "2019", "电影", listOf("科幻", "灾难片"), poster = "https://img/earth.jpg", tmdb = "299534"),
            ),
            now,
        )
        assertEquals(1, fold.shows.size)
        val show = fold.shows.single()
        assertEquals("tmdb:299534", show.id)
        assertEquals("movie", show.mediaType)
        assertEquals(listOf("科幻"), show.genres)
        assertEquals("https://img/earth.jpg", show.posterUrl)
        assertEquals(2, fold.stubs.size)
        assertTrue(fold.stubs.all { it.showId == show.id })
    }

    @Test
    fun filterAndSortReadOnlyShowFields() {
        val shows = listOf(
            record("m1", "movie", 2019, 8.2, listOf("科幻"), "2019-02-05"),
            record("t1", "tv", 2020, 9.1, listOf("剧情"), "2020-01-01"),
            record("m2", "movie", 2010, 6.0, listOf("喜剧"), "2010-08-01"),
        )
        val movies = applyShowQuery(shows, ShowQuery(mediaType = "movie", sort = ShowSort.RATING))
        assertEquals(listOf("m1", "m2"), movies.map { it.id })
        val sciFi = applyShowQuery(shows, ShowQuery(genre = "科幻"))
        assertEquals(listOf("m1"), sciFi.map { it.id })
        val byDate = applyShowQuery(shows, ShowQuery(sort = ShowSort.RELEASE_DATE))
        assertEquals("t1", byDate.first().id)
        val shelves = buildShelves(shows, ShowQuery(mediaType = "movie"))
        assertTrue(shelves.series.isEmpty())
        assertEquals(listOf("m1", "m2"), shelves.movies.map { it.id })
    }

    @Test
    fun continueWatchingFollowsProgressTime() {
        val shows = listOf(record("a", "movie", 2008, 7.0, emptyList(), "2008"), record("b", "tv", 2010, 8.0, emptyList(), "2010"))
        val ordered = continueOrder(
            listOf(ProgressMark("b", 20), ProgressMark("a", 50), ProgressMark("b", 10), ProgressMark("missing", 99)),
            shows.associateBy { it.id },
        )
        assertEquals(listOf("a", "b"), ordered.map { it.id })
        val movies = filterShows(ordered, ShowQuery(mediaType = "movie"))
        assertEquals(listOf("a"), movies.map { it.id })
    }

    @Test
    fun circuitOpensAfterThreeFailures() {
        assertFalse(circuitOpen(2))
        assertTrue(circuitOpen(AggregatePolicy.FAIL_THRESHOLD))
        assertEquals(0, nextFailCount(4, ok = true))
        assertEquals(3, nextFailCount(2, ok = false))
        assertEquals(5_000L, AggregatePolicy.SOURCE_TIMEOUT_MS)
    }

    @Test
    fun freshCacheKeepsPosterAndOnlyFillsBlanks() {
        val ttl = AggregatePolicy.METADATA_TTL_MS
        assertEquals(6L * 60 * 60 * 1000, ttl)
        val now = 10_000_000_000L
        assertTrue(sourceCacheFresh(now - 1_000, now))
        assertFalse(sourceCacheFresh(0L, now))
        assertFalse(sourceCacheFresh(now - ttl, now))
        assertFalse(showCacheFresh(now - ttl, now))
        val existing = record("earth", "movie", 2019, 8.0, listOf("科幻"), "2019-02-05").copy(
            posterUrl = "https://img/kept.jpg",
            overview = "已有简介",
            backdropUrl = null,
            updatedAt = now - 60_000,
        )
        val incoming = existing.copy(
            posterUrl = "https://img/worse.jpg",
            overview = "",
            rating = 1.0,
            backdropUrl = "https://img/back.jpg",
            updatedAt = now,
        )
        val kept = retainShow(existing, incoming, now, force = false)
        assertEquals("https://img/kept.jpg", kept.posterUrl)
        assertEquals("已有简介", kept.overview)
        assertEquals(8.0, kept.rating!!, 0.01)
        assertEquals("https://img/back.jpg", kept.backdropUrl)
        assertEquals(existing.updatedAt, kept.updatedAt)
        val forced = retainShow(existing, incoming, now, force = true)
        assertEquals(now, forced.updatedAt)
        assertEquals(8.0, forced.rating!!, 0.01)
        assertEquals("https://img/kept.jpg", forced.posterUrl)
        val stale = retainShow(
            existing.copy(updatedAt = now - ttl - 1, rating = 6.0),
            incoming.copy(rating = 9.0, posterUrl = "https://img/new.jpg"),
            now,
            force = false,
        )
        assertEquals(now, stale.updatedAt)
        assertEquals(9.0, stale.rating!!, 0.01)
        assertEquals("https://img/kept.jpg", stale.posterUrl)
    }

    @Test
    fun playTabUsesSourceOrDriveName() {
        assertEquals("快看", linkTitle("快看", "线路1"))
        assertEquals("快看 · 超清", linkTitle("快看", "超清"))
        assertEquals("夸克 · 已登录", displayTab("快看", "quark", loggedIn = true))
        assertEquals("夸克 · 未登录", displayTab("快看", "quark", loggedIn = false))
        assertFalse(driveTabEnabled("quark", loggedIn = false))
        assertTrue(driveTabEnabled(null, loggedIn = false))
        assertEquals("movie", classifyMediaType("电影", emptyList(), null))
        assertEquals("variety", classifyMediaType("综艺", emptyList(), null))
        assertTrue(axisGenres(listOf("HD", "国语", "喜剧")).contains("喜剧"))
        assertFalse(axisGenres(listOf("蓝光", "更新")).contains("蓝光"))
    }

    @Test
    fun liveRemoteMovesInsideCategoryAndSkipsDeadUrl() {
        val channels = listOf(
            ChannelSlot("c1", "卫视", listOf("http://a", "http://b")),
            ChannelSlot("c2", "卫视", listOf("http://c")),
            ChannelSlot("c3", "地方", listOf("http://d")),
        )
        val start = LiveCursor("卫视", "c1", 1)
        assertEquals("c2", shiftChannel(channels, start, 1).channelId)
        assertEquals(0, shiftChannel(channels, start, 1).urlIndex)
        assertEquals("c2", shiftChannel(channels, LiveCursor("卫视", "c1", 0), -1).channelId)
        assertEquals(1, advanceLiveUrl(start.copy(urlIndex = 0), 2)?.urlIndex)
        assertNull(advanceLiveUrl(start.copy(channelId = "c2", urlIndex = 0), 1))
        assertEquals(listOf("http://b"), dropDeadHead(listOf("http://a", "http://b"), LiveVerdict.DEAD))
        assertEquals(listOf("http://a"), dropDeadHead(listOf("http://a"), LiveVerdict.DEAD))
    }

    @Test
    fun resumeUsesTheLinkThatWasPlaying() {
        val show = record("earth", "movie", 2019, 8.0, listOf("科幻"), "2019")
        val first = StoredLink("link-a", "src-a", "快看", null, listOf(Episode("正片", "https://a")), "1", null)
        val second = StoredLink("link-b", "src-b", "超清", null, listOf(Episode("正片", "https://b")), "2", null)
        val session = ShowSession(
            show = show,
            links = listOf(first, second),
            progress = ProgressEntity(
                id = "prog:earth:正片",
                showId = show.id,
                episodeKey = "正片",
                positionMs = 12_000,
                durationMs = 90_000,
                playLinkId = "link-b",
                updatedAt = 20,
            ),
        )
        val request = playbackFor(session, emptySet())
        assertEquals("link-b", request?.playLinkId)
        assertEquals("超清", request?.lines?.first()?.name)
        assertEquals(listOf("link-b", "link-a"), request?.playLinkIds)
        assertEquals(12_000L, request?.resumePositionMs)
        assertEquals("link-a", playbackOf(show, first, 0, 0).playLinkId)
        val withBackup = playbackOf(show, first, 0, 0, listOf(second))
        assertEquals(listOf("快看", "超清"), withBackup.lines.map { it.name })
        assertEquals(listOf("link-a", "link-b"), withBackup.playLinkIds)
        val names = withBackup.lines.map { line -> line.episodes.map { it.name } }
        assertEquals(1 to 0, failoverCursor(names, 0, 0, live = false))
        assertEquals(0 to 1, failoverCursor(listOf(listOf("正片", "备用")), 0, 0, live = true))
        assertNull(failoverCursor(names, 1, 0, live = false))
    }

    @Test
    fun assetListDoesNotCarryPlayUrls() {
        val text = """
            {"items":[{"id":"bbb","title":"大雄兔","year":"2008","poster":"https://img/bbb.jpg","remarks":"电影","genres":["动画","短片"],"description":"测试","lines":[{"name":"线路1","episodes":[{"name":"正片","url":"https://cdn/bbb.mp4"}]}]}]}
        """.trimIndent()
        val entries = parseAssetEntries(text, "open", "公开测试片库")
        assertEquals(1, entries.size)
        assertEquals("bbb", entries.single().rawVodId)
        assertEquals("movie", scrapeEntry(entries.single(), 1L).mediaType)
        assertFalse(entries.single().toString().contains("bbb.mp4"))
    }

    private fun entry(
        sourceId: String,
        sourceName: String,
        vodId: String,
        title: String,
        year: String,
        type: String,
        genres: List<String>,
        poster: String?,
        tmdb: String? = null,
    ) = RawCatalogEntry(
        sourceId = sourceId,
        sourceName = sourceName,
        rawVodId = vodId,
        title = title,
        yearText = year,
        typeText = type,
        genres = genres,
        posterUrl = poster,
        backdropUrl = poster,
        overview = "简介",
        rating = 8.0,
        releaseDate = year,
        tmdbId = tmdb,
        remarks = type,
    )

    private fun record(id: String, type: String, year: Int, rating: Double, genres: List<String>, release: String) = ShowRecord(
        id = id,
        tmdbId = null,
        title = id,
        normalizedTitle = id,
        year = year,
        mediaType = type,
        genres = genres,
        rating = rating,
        posterUrl = null,
        backdropUrl = null,
        overview = "",
        releaseDate = release,
        updatedAt = 1L,
    )
}
