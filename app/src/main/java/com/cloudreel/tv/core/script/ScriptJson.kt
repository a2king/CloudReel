package com.cloudreel.tv.core.script

import com.cloudreel.tv.core.model.Episode
import com.cloudreel.tv.core.model.MediaCard
import com.cloudreel.tv.core.model.MediaDetail
import com.cloudreel.tv.core.model.PlayLine
import com.cloudreel.tv.core.model.SearchHit
import org.json.JSONArray
import org.json.JSONObject

data class ScriptPlay(val url: String, val headers: Map<String, String>)

fun scriptCards(json: String, providerId: String, providerName: String): List<MediaCard> {
    return scriptItems(json).map { item ->
        MediaCard(
            providerId = providerId,
            providerName = providerName,
            sourceId = item.id,
            title = item.title,
            poster = item.poster,
            year = item.year,
            area = item.area,
            remarks = item.remarks,
        )
    }
}

fun scriptHits(json: String, providerId: String, providerName: String, priority: Int): List<SearchHit> {
    return scriptItems(json).map { item ->
        SearchHit(
            providerId = providerId,
            providerName = providerName,
            priority = priority,
            sourceId = item.id,
            title = item.title,
            poster = item.poster,
            year = item.year,
            area = item.area,
            genres = emptyList(),
            remarks = item.remarks,
        )
    }
}

fun scriptDetail(json: String, providerId: String, providerName: String): MediaDetail {
    val root = asObject(json)
    val lines = root.optJSONArray("lines") ?: JSONArray()
    return MediaDetail(
        providerId = providerId,
        providerName = providerName,
        sourceId = root.optString("id"),
        title = root.optString("title").ifBlank { "未命名" },
        poster = root.optString("poster").ifBlank { null },
        year = root.optString("year").ifBlank { null },
        area = root.optString("area").ifBlank { null },
        genres = emptyList(),
        directors = splitPeople(root.optString("director")),
        actors = splitPeople(root.optString("actor")),
        description = root.optString("desc").ifBlank { root.optString("description") },
        remarks = root.optString("remarks").ifBlank { null },
        lines = buildList {
            for (index in 0 until lines.length()) {
                val line = lines.optJSONObject(index) ?: continue
                val episodes = line.optJSONArray("episodes") ?: JSONArray()
                add(
                    PlayLine(
                        providerId = providerId,
                        providerName = providerName,
                        name = line.optString("name").ifBlank { "线路 ${index + 1}" },
                        episodes = buildList {
                            for (episodeIndex in 0 until episodes.length()) {
                                val episode = episodes.optJSONObject(episodeIndex) ?: continue
                                add(
                                    Episode(
                                        name = episode.optString("name").ifBlank { episode.optString("id").ifBlank { "第${episodeIndex + 1}集" } },
                                        url = episode.optString("url"),
                                        headers = headerMap(episode.optJSONObject("headers")),
                                    ),
                                )
                            }
                        },
                    ),
                )
            }
        },
    )
}

fun scriptPlay(json: String): ScriptPlay {
    val root = asObject(json)
    return ScriptPlay(root.optString("url"), headerMap(root.optJSONObject("headers")))
}

private data class ScriptItem(
    val id: String,
    val title: String,
    val poster: String?,
    val year: String?,
    val area: String?,
    val remarks: String?,
)

private fun scriptItems(json: String): List<ScriptItem> {
    val array = when {
        json.trim().startsWith("[") -> JSONArray(json)
        else -> asObject(json).optJSONArray("items") ?: JSONArray()
    }
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val title = item.optString("title")
            if (title.isBlank()) continue
            add(
                ScriptItem(
                    id = item.optString("id").ifBlank { title },
                    title = title,
                    poster = item.optString("poster").ifBlank { null },
                    year = item.optString("year").ifBlank { null },
                    area = item.optString("area").ifBlank { null },
                    remarks = item.optString("remarks").ifBlank { null },
                ),
            )
        }
    }
}

private fun asObject(json: String): JSONObject {
    val text = json.trim()
    if (!text.startsWith("{")) throw IllegalStateException("脚本返回的不是对象")
    return JSONObject(text)
}

private fun headerMap(obj: JSONObject?): Map<String, String> {
    if (obj == null) return emptyMap()
    return buildMap {
        obj.keys().forEach { key ->
            val value = obj.optString(key)
            if (key.isNotBlank() && value.isNotBlank()) put(key, value)
        }
    }
}

private fun splitPeople(value: String): List<String> {
    return value.split(',', '，', '/', '、').map { it.trim() }.filter { it.isNotEmpty() }
}
