package com.cloudreel.tv.core.provider

import com.cloudreel.tv.core.catalog.CmsKind
import com.cloudreel.tv.core.catalog.HomeShelf
import com.cloudreel.tv.core.catalog.HomeShelfPage
import com.cloudreel.tv.core.catalog.absolutizeUrl
import com.cloudreel.tv.core.catalog.buildCmsUrl
import com.cloudreel.tv.core.catalog.cmsShelfQuery
import com.cloudreel.tv.core.catalog.cmsShelfTypeIds
import com.cloudreel.tv.core.catalog.homeShelfKind
import com.cloudreel.tv.core.catalog.missingShelfNotice
import com.cloudreel.tv.core.catalog.parseCmsClasses
import com.cloudreel.tv.core.catalog.parseCmsPlay
import com.cloudreel.tv.core.catalog.sortedByCmsRank
import com.cloudreel.tv.core.catalog.splitPeople
import com.cloudreel.tv.core.catalog.stripHtml
import com.cloudreel.tv.core.model.Episode
import com.cloudreel.tv.core.model.MediaCard
import com.cloudreel.tv.core.model.MediaDetail
import com.cloudreel.tv.core.model.PlayLine
import com.cloudreel.tv.core.model.SearchHit
import com.cloudreel.tv.core.network.AppHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class CmsJsonProvider(
    private val http: AppHttp,
    override val id: String,
    override val name: String,
    private val baseUrl: String,
    override val priority: Int,
) : MediaProvider {
    override suspend fun home(): List<MediaCard> = browse(HomeShelf.HOT_DRAMA).cards

    suspend fun browse(shelf: HomeShelf): HomeShelfPage {
        val types = loadTypes()
        val kind = homeShelfKind(shelf)
        if (kind == null) return rankPage(types)
        val typeIds = cmsShelfTypeIds(types, kind)
        if (typeIds.isEmpty()) {
            return HomeShelfPage(fetchCards(null, null), missingShelfNotice(shelf.label))
        }
        if (shelf == HomeShelf.HOT_DRAMA) {
            val recent = fetchShelf(typeIds, "72")
            if (recent.isNotEmpty()) return HomeShelfPage(recent, null)
        }
        val typed = fetchShelf(typeIds, null)
        if (typed.isNotEmpty()) return HomeShelfPage(typed, null)
        val latest = fetchCards(null, null)
        return HomeShelfPage(latest, if (latest.isEmpty()) null else "这个分类没有按类型返回，显示的是最新内容")
    }

    override suspend fun search(keyword: String): List<SearchHit> {
        val query = keyword.trim()
        if (query.isBlank()) return emptyList()
        return fetchItems(mapOf("ac" to "detail", "wd" to query, "pg" to "1")).map { it.toHit() }
    }

    override suspend fun detail(sourceId: String): MediaDetail {
        var item = fetchItems(mapOf("ac" to "detail", "ids" to sourceId)).firstOrNull()
            ?: throw IllegalStateException("片源里没有这条内容")
        if (item.optString("vod_play_url").isBlank()) {
            fetchItems(mapOf("ac" to "videolist", "ids" to sourceId)).firstOrNull()?.let { item = it }
        }
        return item.toDetail()
    }

    private suspend fun fetchItems(params: Map<String, String>): List<JSONObject> {
        val text = http.get(buildCmsUrl(baseUrl, params)).trim().removePrefix("\uFEFF")
        val root = try {
            JSONObject(text)
        } catch (_: JSONException) {
            throw IllegalStateException("返回的不是 JSON 接口")
        }
        val list = root.optJSONArray("list")
            ?: root.optJSONObject("data")?.optJSONArray("list")
            ?: JSONArray()
        return buildList {
            for (index in 0 until list.length()) {
                list.optJSONObject(index)?.let { add(it) }
            }
        }
    }

    private fun JSONObject.toCard(): MediaCard {
        return MediaCard(
            providerId = id,
            providerName = name,
            sourceId = sourceId(),
            title = optString("vod_name").ifBlank { "未命名" },
            poster = absolutizeUrl(baseUrl, optString("vod_pic")),
            year = optString("vod_year").ifBlank { null },
            area = optString("vod_area").ifBlank { null },
            remarks = optString("vod_remarks").ifBlank { null },
        )
    }

    private fun JSONObject.toHit(): SearchHit {
        return SearchHit(
            providerId = id,
            providerName = name,
            priority = priority,
            sourceId = sourceId(),
            title = optString("vod_name").ifBlank { "未命名" },
            poster = absolutizeUrl(baseUrl, optString("vod_pic")),
            year = optString("vod_year").ifBlank { null },
            area = optString("vod_area").ifBlank { null },
            genres = splitPeople(optString("vod_class")),
            remarks = optString("vod_remarks").ifBlank { null },
        )
    }

    private fun JSONObject.toDetail(): MediaDetail {
        val lines = parseCmsPlay(optString("vod_play_from"), optString("vod_play_url")).map { line ->
            PlayLine(
                providerId = id,
                providerName = name,
                name = line.name,
                episodes = line.episodes.map { episode ->
                    Episode(
                        name = episode.name,
                        url = absolutizeUrl(baseUrl, episode.url) ?: episode.url,
                        headers = episode.headers,
                    )
                },
            )
        }
        return MediaDetail(
            providerId = id,
            providerName = name,
            sourceId = sourceId(),
            title = optString("vod_name").ifBlank { "未命名" },
            poster = absolutizeUrl(baseUrl, optString("vod_pic")),
            year = optString("vod_year").ifBlank { null },
            area = optString("vod_area").ifBlank { null },
            genres = splitPeople(optString("vod_class")),
            directors = splitPeople(optString("vod_director")),
            actors = splitPeople(optString("vod_actor")),
            description = stripHtml(optString("vod_content")),
            remarks = optString("vod_remarks").ifBlank { null },
            lines = lines,
        )
    }

    private suspend fun loadTypes(): List<com.cloudreel.tv.core.catalog.CmsType> {
        return try {
            parseCmsClasses(http.get(buildCmsUrl(baseUrl, mapOf("ac" to "list"))))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchCards(typeId: String?, hours: String?): List<MediaCard> {
        return fetchItems(cmsShelfQuery(typeId, hours)).map { it.toCard() }
    }

    private suspend fun fetchShelf(typeIds: List<String>, hours: String?): List<MediaCard> {
        val batches = coroutineScope {
            typeIds.map { typeId ->
                async {
                    try {
                        fetchTyped(typeId, hours)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll()
        }
        return batches.flatten()
            .distinctBy { it.sourceId() }
            .sortedByDescending { it.optString("vod_time") }
            .take(36)
            .map { it.toCard() }
    }

    private suspend fun fetchTyped(typeId: String?, hours: String?): List<JSONObject> {
        val params = cmsShelfQuery(typeId, hours)
        val detailed = fetchSafely(params)
        if (detailed.isNotEmpty() || params["ac"] != "detail") return detailed
        return fetchSafely(params + ("ac" to "list"))
    }

    private suspend fun fetchSafely(params: Map<String, String>): List<JSONObject> {
        return try {
            fetchItems(params)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun rankPage(types: List<com.cloudreel.tv.core.catalog.CmsType>): HomeShelfPage {
        val typeIds = listOf(CmsKind.MOVIE, CmsKind.SERIES)
            .flatMap { cmsShelfTypeIds(types, it).take(4) }
            .distinct()
        val targets: List<String?> = if (typeIds.isEmpty()) listOf(null) else typeIds
        val batches = coroutineScope {
            targets.map { typeId ->
                async {
                    try {
                        fetchTyped(typeId, null)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll()
        }
        val items = batches.flatten().distinctBy { it.sourceId() }
        if (items.isEmpty()) return HomeShelfPage(emptyList(), "这条线路没有返回榜单")
        val ranked = items.sortedByCmsRank({ it.hitCount() }, { it.scoreValue() }).take(36).map { it.toCard() }
        val notice = if (items.none { it.hitCount() > 0L }) "这条线路没有点击量，按接口返回顺序显示" else null
        return HomeShelfPage(ranked, notice)
    }

    private fun JSONObject.sourceId(): String = optString("vod_id").ifBlank { optString("id") }

    private fun JSONObject.hitCount(): Long = optString("vod_hits").toLongOrNull() ?: 0L

    private fun JSONObject.scoreValue(): Double {
        return optString("vod_score").toDoubleOrNull()
            ?: optString("vod_douban_score").toDoubleOrNull()
            ?: 0.0
    }
}
