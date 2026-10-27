package com.cloudreel.tv.core.provider

import android.content.res.AssetManager
import com.cloudreel.tv.core.catalog.normalizeTitle
import com.cloudreel.tv.core.model.Episode
import com.cloudreel.tv.core.model.MediaCard
import com.cloudreel.tv.core.model.MediaDetail
import com.cloudreel.tv.core.model.PlayLine
import com.cloudreel.tv.core.model.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class JsonCatalogProvider(
    private val assets: AssetManager,
    override val id: String,
    override val name: String,
    private val assetPath: String,
    override val priority: Int,
) : MediaProvider {
    override suspend fun home(): List<MediaCard> = load().map { it.toCard() }

    override suspend fun search(keyword: String): List<SearchHit> {
        val query = keyword.trim()
        if (query.isBlank()) return emptyList()
        val normalized = normalizeTitle(query)
        return load()
            .filter { item ->
                item.title.contains(query, ignoreCase = true) ||
                    normalizeTitle(item.title).contains(normalized)
            }
            .map { it.toHit() }
    }

    override suspend fun detail(sourceId: String): MediaDetail {
        return load().firstOrNull { it.sourceId == sourceId }?.toDetail()
            ?: throw IllegalStateException("片源里没有这条内容")
    }

    private suspend fun load(): List<CatalogItem> = withContext(Dispatchers.IO) {
        val text = assets.open(assetPath).bufferedReader().use { it.readText() }
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            throw IllegalStateException("测试片库格式错误")
        }
        val items = root.optJSONArray("items") ?: JSONArray()
        buildList {
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.let { add(parseItem(it)) }
            }
        }
    }

    private fun parseItem(obj: JSONObject): CatalogItem {
        val linesJson = obj.optJSONArray("lines") ?: JSONArray()
        val lines = buildList {
            for (index in 0 until linesJson.length()) {
                val line = linesJson.optJSONObject(index) ?: continue
                val episodesJson = line.optJSONArray("episodes") ?: JSONArray()
                val episodes = buildList {
                    for (episodeIndex in 0 until episodesJson.length()) {
                        val episode = episodesJson.optJSONObject(episodeIndex) ?: continue
                        val url = episode.optString("url")
                        if (url.isBlank()) continue
                        add(
                            Episode(
                                name = episode.optString("name").ifBlank { "正片" },
                                url = url,
                                headers = episode.optJSONObject("headers").toStringMap(),
                            ),
                        )
                    }
                }
                if (episodes.isNotEmpty()) {
                    add(
                        PlayLine(
                            providerId = id,
                            providerName = name,
                            name = line.optString("name").ifBlank { "线路${index + 1}" },
                            episodes = episodes,
                        ),
                    )
                }
            }
        }
        return CatalogItem(
            sourceId = obj.optString("id"),
            title = obj.optString("title").ifBlank { "未命名" },
            poster = obj.optString("poster").ifBlank { null },
            year = obj.optString("year").ifBlank { null },
            area = obj.optString("area").ifBlank { null },
            genres = (obj.optJSONArray("genres") ?: JSONArray()).toStrings(),
            directors = (obj.optJSONArray("directors") ?: JSONArray()).toStrings(),
            actors = (obj.optJSONArray("actors") ?: JSONArray()).toStrings(),
            description = obj.optString("description"),
            remarks = obj.optString("remarks").ifBlank { null },
            lines = lines,
        )
    }

    private fun CatalogItem.toCard() = MediaCard(
        providerId = id,
        providerName = name,
        sourceId = sourceId,
        title = title,
        poster = poster,
        year = year,
        area = area,
        remarks = remarks,
    )

    private fun CatalogItem.toHit() = SearchHit(
        providerId = id,
        providerName = name,
        priority = priority,
        sourceId = sourceId,
        title = title,
        poster = poster,
        year = year,
        area = area,
        genres = genres,
        remarks = remarks,
    )

    private fun CatalogItem.toDetail() = MediaDetail(
        providerId = id,
        providerName = name,
        sourceId = sourceId,
        title = title,
        poster = poster,
        year = year,
        area = area,
        genres = genres,
        directors = directors,
        actors = actors,
        description = description,
        remarks = remarks,
        lines = lines,
    )
}

private data class CatalogItem(
    val sourceId: String,
    val title: String,
    val poster: String?,
    val year: String?,
    val area: String?,
    val genres: List<String>,
    val directors: List<String>,
    val actors: List<String>,
    val description: String,
    val remarks: String?,
    val lines: List<PlayLine>,
)

private fun JSONArray.toStrings(): List<String> = buildList {
    for (index in 0 until length()) {
        optString(index).trim().takeIf { it.isNotEmpty() }?.let { add(it) }
    }
}

private fun JSONObject?.toStringMap(): Map<String, String> {
    if (this == null) return emptyMap()
    return buildMap {
        keys().forEach { key -> put(key, optString(key)) }
    }
}
