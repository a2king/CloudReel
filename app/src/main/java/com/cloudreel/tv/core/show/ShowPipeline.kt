package com.cloudreel.tv.core.show

import com.cloudreel.tv.core.catalog.normalizeTitle
import com.cloudreel.tv.core.live.LiveVerdict
import org.json.JSONArray
import org.json.JSONObject

/**
 * 海报墙只认这一层的节目。聚合层把各源条目折进这里，界面不直接读采集站。
 */
data class ShowRecord(
    val id: String,
    val tmdbId: String?,
    val title: String,
    val normalizedTitle: String,
    val year: Int?,
    val mediaType: String,
    val genres: List<String>,
    val rating: Double?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val overview: String,
    val releaseDate: String?,
    val updatedAt: Long,
)

data class RawCatalogEntry(
    val sourceId: String,
    val sourceName: String,
    val rawVodId: String,
    val title: String,
    val yearText: String?,
    val typeText: String?,
    val genres: List<String>,
    val posterUrl: String?,
    val backdropUrl: String?,
    val overview: String,
    val rating: Double?,
    val releaseDate: String?,
    val tmdbId: String?,
    val remarks: String?,
)

data class PlayStub(
    val showId: String,
    val sourceId: String,
    val sourceName: String,
    val rawVodId: String,
)

data class FoldResult(
    val shows: List<ShowRecord>,
    val stubs: List<PlayStub>,
)

data class ProgressMark(
    val showId: String,
    val updatedAt: Long,
)

enum class ShowSort {
    RELEASE_DATE,
    RATING,
}

data class ShowQuery(
    val mediaType: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val minRating: Double? = null,
    val sort: ShowSort = ShowSort.RELEASE_DATE,
)

data class ShowShelves(
    val hot: List<ShowRecord>,
    val movies: List<ShowRecord>,
    val series: List<ShowRecord>,
    val variety: List<ShowRecord>,
    val docs: List<ShowRecord>,
    val anime: List<ShowRecord>,
)

data class LiveCursor(
    val categoryId: String,
    val channelId: String,
    val urlIndex: Int,
)

data class ChannelSlot(
    val id: String,
    val categoryId: String,
    val urls: List<String>,
)

object AggregatePolicy {
    const val FAIL_THRESHOLD = 3
    const val SOURCE_TIMEOUT_MS = 5_000L
    /** 源健康时间和节目元数据的自动刷新间隔。手动刷新不受此限制。 */
    const val METADATA_TTL_MS = 6L * 60 * 60 * 1000

    fun circuitOpen(failCount: Int): Boolean = failCount >= FAIL_THRESHOLD
}

fun sourceCacheFresh(lastOkAt: Long, now: Long): Boolean {
    return lastOkAt > 0L && now - lastOkAt < AggregatePolicy.METADATA_TTL_MS
}

fun showCacheFresh(updatedAt: Long, now: Long): Boolean {
    return updatedAt > 0L && now - updatedAt < AggregatePolicy.METADATA_TTL_MS
}

val GENRE_AXIS = listOf(
    "剧情", "喜剧", "动作", "爱情", "科幻", "动画", "悬疑", "恐怖",
    "战争", "家庭", "犯罪", "奇幻", "历史", "纪录", "惊悚", "冒险",
)

fun mediaTypeLabel(type: String): String = when (type) {
    "movie" -> "电影"
    "tv" -> "电视剧"
    "variety" -> "综艺"
    "doc" -> "纪录"
    "anime" -> "动漫"
    else -> "节目"
}

fun circuitOpen(failCount: Int): Boolean = AggregatePolicy.circuitOpen(failCount)

fun nextFailCount(previous: Int, ok: Boolean): Int = if (ok) 0 else previous + 1

fun parseYear(raw: String?): Int? {
    val digits = raw.orEmpty().filter { it.isDigit() }
    if (digits.length < 4) return null
    val year = digits.take(4).toIntOrNull() ?: return null
    return year.takeIf { it in 1900..2100 }
}

fun axisGenres(raw: List<String>): List<String> {
    val found = linkedSetOf<String>()
    raw.forEach { token ->
        val text = token.trim()
        if (text.isEmpty()) return@forEach
        GENRE_AXIS.forEach { axis ->
            if (text.contains(axis)) found += axis
        }
    }
    return found.toList()
}

fun classifyMediaType(typeText: String?, genres: List<String>, remarks: String?): String {
    val blob = listOfNotNull(typeText, remarks, genres.joinToString(" ")).joinToString(" ")
    return when {
        blob.contains("综艺") || blob.contains("真人秀") -> "variety"
        blob.contains("纪录") -> "doc"
        blob.contains("动漫") || blob.contains("番剧") -> "anime"
        blob.contains("动画") && blob.contains("剧") -> "anime"
        blob.contains("连续剧") || blob.contains("电视剧") || blob.contains("剧集") ||
            blob.contains("美剧") || blob.contains("韩剧") || blob.contains("日剧") ||
            blob.contains("国产剧") || blob.contains("网剧") -> "tv"
        blob.contains("电影") || blob.contains("影片") -> "movie"
        blob.contains("剧") -> "tv"
        else -> "movie"
    }
}

fun showId(tmdbId: String?, normalized: String, year: Int?, mediaType: String): String {
    val tmdb = tmdbId?.trim().orEmpty()
    if (tmdb.isNotEmpty()) return "tmdb:$tmdb"
    val name = normalized.ifBlank { "untitled" }
    return "show:$name:${year ?: 0}:$mediaType"
}

fun scrapeEntry(entry: RawCatalogEntry, now: Long): ShowRecord {
    val normalized = normalizeTitle(entry.title).ifBlank { "untitled" }
    val year = parseYear(entry.yearText) ?: parseYear(entry.releaseDate)
    val genres = axisGenres(entry.genres)
    val type = classifyMediaType(entry.typeText, entry.genres, entry.remarks)
    val tmdb = entry.tmdbId?.trim()?.ifBlank { null }
    val release = entry.releaseDate?.trim()?.ifBlank { null } ?: year?.toString()
    val poster = entry.posterUrl?.trim()?.ifBlank { null }
    val backdrop = entry.backdropUrl?.trim()?.ifBlank { null } ?: poster
    return ShowRecord(
        id = showId(tmdb, normalized, year, type),
        tmdbId = tmdb,
        title = entry.title.trim().ifBlank { "未命名" },
        normalizedTitle = normalized,
        year = year,
        mediaType = type,
        genres = genres,
        rating = entry.rating?.takeIf { it in 0.0..10.0 },
        posterUrl = poster,
        backdropUrl = backdrop,
        overview = entry.overview.trim().take(400),
        releaseDate = release,
        updatedAt = now,
    )
}

fun sameShow(left: ShowRecord, right: ShowRecord): Boolean {
    if (!left.tmdbId.isNullOrBlank() && left.tmdbId == right.tmdbId) return true
    if (left.normalizedTitle.isBlank() || left.normalizedTitle != right.normalizedTitle) return false
    if (left.mediaType != right.mediaType) return false
    if (left.year != null && right.year != null && left.year != right.year) return false
    return true
}

fun mergeShows(items: List<ShowRecord>): ShowRecord {
    val first = items.first()
    val tmdb = items.firstNotNullOfOrNull { it.tmdbId?.trim()?.ifBlank { null } }
    val year = items.firstNotNullOfOrNull { it.year }
    val id = showId(tmdb, first.normalizedTitle, year, first.mediaType)
    return ShowRecord(
        id = id,
        tmdbId = tmdb,
        title = items.maxBy { it.title.length }.title,
        normalizedTitle = first.normalizedTitle,
        year = year,
        mediaType = first.mediaType,
        genres = items.flatMap { it.genres }.distinct(),
        rating = items.mapNotNull { it.rating }.maxOrNull(),
        posterUrl = items.firstNotNullOfOrNull { it.posterUrl?.ifBlank { null } },
        backdropUrl = items.firstNotNullOfOrNull { it.backdropUrl?.ifBlank { null } }
            ?: items.firstNotNullOfOrNull { it.posterUrl?.ifBlank { null } },
        overview = items.maxBy { it.overview.length }.overview,
        releaseDate = items.firstNotNullOfOrNull { it.releaseDate?.ifBlank { null } },
        updatedAt = items.maxOf { it.updatedAt },
    )
}

/**
 * 缓存还在有效期内时，只补空白字段，不改已有海报、评分和更新时间。
 * 过期或手动刷新时合并两边，并把更新时间拨到现在。
 */
fun retainShow(existing: ShowRecord?, incoming: ShowRecord, now: Long, force: Boolean): ShowRecord {
    if (existing == null) return incoming.copy(updatedAt = now)
    if (force || !showCacheFresh(existing.updatedAt, now)) {
        return mergeShows(listOf(existing, incoming)).copy(updatedAt = now)
    }
    return existing.copy(
        tmdbId = existing.tmdbId?.trim()?.ifBlank { null } ?: incoming.tmdbId?.trim()?.ifBlank { null },
        title = existing.title.ifBlank { incoming.title },
        year = existing.year ?: incoming.year,
        genres = existing.genres.ifEmpty { incoming.genres },
        rating = existing.rating ?: incoming.rating,
        posterUrl = existing.posterUrl?.trim()?.ifBlank { null } ?: incoming.posterUrl?.trim()?.ifBlank { null },
        backdropUrl = existing.backdropUrl?.trim()?.ifBlank { null } ?: incoming.backdropUrl?.trim()?.ifBlank { null },
        overview = existing.overview.ifBlank { incoming.overview },
        releaseDate = existing.releaseDate?.trim()?.ifBlank { null } ?: incoming.releaseDate?.trim()?.ifBlank { null },
    )
}

fun foldCatalog(entries: List<RawCatalogEntry>, now: Long): FoldResult {
    data class Bucket(
        val drafts: MutableList<ShowRecord>,
        val stubs: MutableList<PlayStub>,
    )
    val groups = mutableListOf<Bucket>()
    entries.forEach { entry ->
        if (entry.title.isBlank() || entry.rawVodId.isBlank()) return@forEach
        val draft = scrapeEntry(entry, now)
        val existing = groups.firstOrNull { sameShow(it.drafts.first(), draft) }
        val bucket = if (existing == null) {
            Bucket(mutableListOf(draft), mutableListOf()).also { groups += it }
        } else {
            existing.drafts += draft
            existing
        }
        bucket.stubs += PlayStub(
            showId = "",
            sourceId = entry.sourceId,
            sourceName = entry.sourceName,
            rawVodId = entry.rawVodId,
        )
    }
    val shows = mutableListOf<ShowRecord>()
    val stubs = mutableListOf<PlayStub>()
    groups.forEach { bucket ->
        val show = mergeShows(bucket.drafts)
        shows += show
        bucket.stubs.forEach { stub ->
            stubs += stub.copy(showId = show.id)
        }
    }
    return FoldResult(
        shows = shows,
        stubs = stubs.distinctBy { "${it.showId}:${it.sourceId}:${it.rawVodId}" },
    )
}

fun applyShowQuery(shows: List<ShowRecord>, query: ShowQuery): List<ShowRecord> {
    val filtered = shows.filter { show ->
        (query.mediaType == null || show.mediaType == query.mediaType) &&
            (query.year == null || show.year == query.year) &&
            (query.genre == null || query.genre in show.genres) &&
            (query.minRating == null || (show.rating ?: -1.0) >= query.minRating)
    }
    return when (query.sort) {
        ShowSort.RATING -> filtered.sortedWith(
            compareByDescending<ShowRecord> { it.rating ?: -1.0 }.thenByDescending { it.releaseDate.orEmpty() },
        )
        ShowSort.RELEASE_DATE -> filtered.sortedWith(
            compareByDescending<ShowRecord> { it.releaseDate.orEmpty() }.thenByDescending { it.year ?: 0 },
        )
    }
}

fun buildShelves(shows: List<ShowRecord>, query: ShowQuery, limit: Int = 24): ShowShelves {
    val filtered = applyShowQuery(shows, query)
    fun of(type: String): List<ShowRecord> {
        if (query.mediaType != null && query.mediaType != type) return emptyList()
        return filtered.filter { it.mediaType == type }.take(limit)
    }
    val hot = filtered.sortedByDescending { it.rating ?: -1.0 }.take(limit)
    return ShowShelves(
        hot = hot,
        movies = of("movie"),
        series = of("tv"),
        variety = of("variety"),
        docs = of("doc"),
        anime = of("anime"),
    )
}

fun continueOrder(marks: List<ProgressMark>, shows: Map<String, ShowRecord>): List<ShowRecord> {
    return marks
        .sortedByDescending { it.updatedAt }
        .distinctBy { it.showId }
        .mapNotNull { shows[it.showId] }
}

fun linkTitle(sourceName: String, lineName: String): String {
    val line = lineName.trim()
    val generic = line.isEmpty() ||
        line.matches(Regex("^线路\\s*\\d+$")) ||
        line.equals("默认", ignoreCase = true) ||
        line.equals("default", ignoreCase = true)
    val source = sourceName.trim().ifBlank { "来源" }
    return if (generic || line == source) source else "$source · $line"
}

fun driveShortName(driveType: String): String = when (driveType) {
    "115" -> "115"
    "quark" -> "夸克"
    "ali" -> "阿里云盘"
    "baidu" -> "百度网盘"
    else -> "网盘"
}

fun displayTab(title: String, driveType: String?, loggedIn: Boolean): String {
    if (driveType.isNullOrBlank()) return title
    val name = driveShortName(driveType)
    return if (loggedIn) "$name · 已登录" else "$name · 未登录"
}

fun driveTabEnabled(driveType: String?, loggedIn: Boolean): Boolean {
    return driveType.isNullOrBlank() || loggedIn
}

fun shiftChannel(channels: List<ChannelSlot>, cursor: LiveCursor, delta: Int): LiveCursor {
    val group = channels.filter { it.categoryId == cursor.categoryId }
    if (group.isEmpty()) return cursor
    val index = group.indexOfFirst { it.id == cursor.channelId }.let { if (it < 0) 0 else it }
    val nextIndex = Math.floorMod(index + delta, group.size)
    val next = group[nextIndex]
    return LiveCursor(cursor.categoryId, next.id, 0)
}

fun advanceLiveUrl(cursor: LiveCursor, urlCount: Int): LiveCursor? {
    val next = cursor.urlIndex + 1
    if (next >= urlCount) return null
    return cursor.copy(urlIndex = next)
}

fun dropDeadHead(urls: List<String>, headVerdict: LiveVerdict): List<String> {
    if (urls.size > 1 && headVerdict == LiveVerdict.DEAD) return urls.drop(1)
    return urls
}

fun encodeGenres(genres: List<String>): String = JSONArray(genres).toString()

fun decodeGenres(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotEmpty()) add(value)
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

fun encodeUrlList(urls: List<String>): String = JSONArray(urls).toString()

fun decodeUrlList(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotEmpty()) add(value)
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

fun parseAssetEntries(text: String, sourceId: String, sourceName: String): List<RawCatalogEntry> {
    val root = try {
        JSONObject(text.trim().removePrefix("\uFEFF"))
    } catch (_: Exception) {
        return emptyList()
    }
    val items = root.optJSONArray("items") ?: return emptyList()
    return buildList {
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val title = item.optString("title").trim()
            val id = item.optString("id").ifBlank { item.optString("vod_id") }.trim()
            if (title.isEmpty() || id.isEmpty()) continue
            val genres = buildList {
                val array = item.optJSONArray("genres")
                if (array != null) {
                    for (genreIndex in 0 until array.length()) {
                        val genre = array.optString(genreIndex).trim()
                        if (genre.isNotEmpty()) add(genre)
                    }
                }
            }
            val poster = item.optString("poster").trim().ifBlank { null }
            add(
                RawCatalogEntry(
                    sourceId = sourceId,
                    sourceName = sourceName,
                    rawVodId = id,
                    title = title,
                    yearText = item.optString("year").ifBlank { null },
                    typeText = item.optString("type").ifBlank { item.optString("remarks") }.ifBlank { null },
                    genres = genres,
                    posterUrl = poster,
                    backdropUrl = item.optString("backdrop").trim().ifBlank { poster },
                    overview = item.optString("description").trim(),
                    rating = item.optDouble("rating").takeIf { item.has("rating") && !item.isNull("rating") },
                    releaseDate = item.optString("releaseDate").ifBlank { item.optString("year") }.ifBlank { null },
                    tmdbId = item.optString("tmdbId").ifBlank { null },
                    remarks = item.optString("remarks").ifBlank { null },
                ),
            )
        }
    }
}

fun stubId(showId: String, sourceId: String, rawVodId: String): String {
    return "link:$showId:$sourceId:$rawVodId"
}
