package com.cloudreel.tv.core.database

import com.cloudreel.tv.core.model.Episode
import com.cloudreel.tv.core.model.SourceRef
import org.json.JSONArray
import org.json.JSONObject

object JsonCodec {
    fun encodeHeaders(headers: Map<String, String>): String {
        val obj = JSONObject()
        headers.forEach { (key, value) -> obj.put(key, value) }
        return obj.toString()
    }

    fun decodeHeaders(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { key -> put(key, obj.optString(key)) }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun encodeRefs(refs: List<SourceRef>): String {
        val array = JSONArray()
        refs.forEach { ref ->
            array.put(JSONObject().put("providerId", ref.providerId).put("sourceId", ref.sourceId))
        }
        return array.toString()
    }

    fun decodeRefs(raw: String?): List<SourceRef> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val providerId = item.optString("providerId")
                    val sourceId = item.optString("sourceId")
                    if (providerId.isNotBlank() && sourceId.isNotBlank()) {
                        add(SourceRef(providerId, sourceId))
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encodeEpisodes(episodes: List<Episode>): String {
        val array = JSONArray()
        episodes.forEach { episode ->
            val headers = JSONObject()
            episode.headers.forEach { (key, value) -> headers.put(key, value) }
            array.put(
                JSONObject()
                    .put("name", episode.name)
                    .put("url", episode.url)
                    .put("headers", headers),
            )
        }
        return array.toString()
    }

    fun decodeEpisodes(raw: String?): List<Episode> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val url = item.optString("url")
                    if (url.isBlank()) continue
                    val headers = item.optJSONObject("headers")
                    add(
                        Episode(
                            name = item.optString("name").ifBlank { "正片" },
                            url = url,
                            headers = if (headers == null) {
                                emptyMap()
                            } else {
                                buildMap {
                                    headers.keys().forEach { key -> put(key, headers.optString(key)) }
                                }
                            },
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
