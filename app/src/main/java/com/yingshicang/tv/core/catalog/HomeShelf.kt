package com.yingshicang.tv.core.catalog

import com.yingshicang.tv.core.model.MediaCard
import org.json.JSONObject

enum class HomeShelf(val label: String) {
    HOT_DRAMA("热播剧"),
    MOVIE("电影"),
    SERIES("电视剧"),
    VARIETY("综艺"),
    RANK("榜单"),
}

enum class CmsKind {
    MOVIE,
    SERIES,
    VARIETY,
}

data class CmsType(
    val id: String,
    val parentId: String,
    val name: String,
)

data class HomeShelfPage(
    val cards: List<MediaCard>,
    val notice: String?,
)

fun homeShelfKind(shelf: HomeShelf): CmsKind? = when (shelf) {
    HomeShelf.MOVIE -> CmsKind.MOVIE
    HomeShelf.HOT_DRAMA, HomeShelf.SERIES -> CmsKind.SERIES
    HomeShelf.VARIETY -> CmsKind.VARIETY
    HomeShelf.RANK -> null
}

fun parseCmsClasses(text: String): List<CmsType> {
    val trimmed = text.trim().removePrefix("\uFEFF")
    if (trimmed.isEmpty()) return emptyList()
    val root = try {
        JSONObject(trimmed)
    } catch (_: Exception) {
        return emptyList()
    }
    val array = root.optJSONArray("class")
        ?: root.optJSONObject("data")?.optJSONArray("class")
        ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("type_id").ifBlank { item.optString("id") }.trim()
            val name = item.optString("type_name").ifBlank { item.optString("name") }.trim()
            if (id.isEmpty() || name.isEmpty()) continue
            add(CmsType(id, item.optString("type_pid").ifBlank { item.optString("pid") }.trim(), name))
        }
    }
}

fun cmsTypeId(types: List<CmsType>, kind: CmsKind): String? {
    if (types.isEmpty()) return null
    val tops = types.filter { it.parentId.isBlank() || it.parentId == "0" }
    tops.firstOrNull { typeNameMatches(it.name, kind) }?.let { return it.id }
    return types.firstOrNull { typeNameMatches(it.name, kind) }?.id
}

fun cmsShelfTypeIds(types: List<CmsType>, kind: CmsKind): List<String> {
    val rootId = cmsTypeId(types, kind) ?: return emptyList()
    val children = types.filter { it.parentId == rootId }.map { it.id }
    return (if (children.isEmpty()) listOf(rootId) else children).distinct().take(8)
}

fun typeNameMatches(name: String, kind: CmsKind): Boolean {
    val value = name.trim()
    return when (kind) {
        CmsKind.MOVIE -> when {
            value.contains("电视剧") || value.contains("连续剧") || value.contains("综艺") || value.contains("动漫") -> false
            value.contains("电影") -> true
            else -> value.endsWith("片") && !value.contains("纪录")
        }
        CmsKind.VARIETY -> value.contains("综艺")
        CmsKind.SERIES -> when {
            value.contains("综艺") || value.contains("动漫") || value.contains("电影") || value.contains("纪录") -> false
            value.endsWith("片") -> false
            value.contains("连续剧") || value.contains("电视剧") || value.contains("剧集") || value.contains("剧") -> true
            else -> false
        }
    }
}

fun missingShelfNotice(label: String): String = "这条线路没有「$label」分类，显示的是最新内容"

fun cmsShelfQuery(typeId: String?, hours: String?): Map<String, String> {
    return buildMap {
        put("ac", "detail")
        put("pg", "1")
        if (!typeId.isNullOrBlank()) put("t", typeId)
        if (!hours.isNullOrBlank()) put("h", hours)
    }
}

fun <T> List<T>.sortedByCmsRank(hits: (T) -> Long, score: (T) -> Double): List<T> {
    return sortedWith(compareByDescending(hits).thenByDescending(score))
}
