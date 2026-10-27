package com.yingshicang.tv.core.drive

import com.yingshicang.tv.core.model.DriveEntry
import java.net.URI
import java.net.URLDecoder
import org.json.JSONObject

private val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "mpg", "mpeg", "ts", "flv", "m3u8")

fun parseWebDav(xml: String, requestUrl: String): List<DriveEntry> {
    val entries = mutableListOf<DriveEntry>()
    val pattern = Regex("""<(?:\w+:)?response\b[^>]*>(.*?)</(?:\w+:)?response>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    pattern.findAll(xml).forEach { match ->
        val body = match.groupValues[1]
        val href = tagText(body, "href") ?: return@forEach
        val absolute = resolveHref(requestUrl, href)
        if (sameLocation(absolute, requestUrl)) return@forEach
        val name = tagText(body, "displayname")?.takeIf { it.isNotBlank() }
            ?: decodeSegment(absolute)
        val directory = body.contains(Regex("""<(?:\w+:)?collection\s*/?>""", RegexOption.IGNORE_CASE)) || href.endsWith("/")
        val contentType = tagText(body, "getcontenttype").orEmpty()
        entries += DriveEntry(
            name = name.ifBlank { "未命名" },
            path = absolute,
            directory = directory,
            playable = !directory && playable(name, contentType),
        )
    }
    return entries.sortedWith(compareByDescending<DriveEntry> { it.directory }.thenBy { it.name.lowercase() })
}

fun parseAlistList(json: String, parent: String): List<DriveEntry> {
    val root = JSONObject(json)
    val code = root.optInt("code", 200)
    if (code != 200) throw IllegalStateException(root.optString("message").ifBlank { "网盘返回 $code" })
    val content = root.optJSONObject("data")?.optJSONArray("content") ?: return emptyList()
    val base = if (parent.isBlank() || parent == "/") "" else parent.trimEnd('/')
    return buildList {
        for (index in 0 until content.length()) {
            val item = content.optJSONObject(index) ?: continue
            val name = item.optString("name")
            if (name.isBlank()) continue
            val directory = item.optBoolean("is_dir")
            val path = "$base/$name"
            add(
                DriveEntry(
                    name = name,
                    path = path,
                    directory = directory,
                    playable = !directory && playable(name, item.optString("type")),
                ),
            )
        }
    }.sortedWith(compareByDescending<DriveEntry> { it.directory }.thenBy { it.name.lowercase() })
}

fun parseAlistUrl(json: String): String {
    val root = JSONObject(json)
    val code = root.optInt("code", 200)
    if (code != 200) throw IllegalStateException(root.optString("message").ifBlank { "网盘返回 $code" })
    val data = root.optJSONObject("data") ?: throw IllegalStateException("没有拿到播放地址")
    return data.optString("raw_url").ifBlank { data.optString("url") }.ifBlank {
        throw IllegalStateException("没有拿到播放地址")
    }
}

fun normalizeEndpoint(value: String): String {
    val text = value.trim().trimEnd('/')
    if (!text.startsWith("http://") && !text.startsWith("https://")) {
        throw IllegalArgumentException("地址需要以 http:// 或 https:// 开头")
    }
    return text
}

private fun playable(name: String, contentType: String): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase()
    if (extension in videoExtensions) return true
    val type = contentType.lowercase()
    return type.startsWith("video/") || type.contains("mpegurl")
}

private fun tagText(source: String, tag: String): String? {
    return Regex("""<(?:\w+:)?$tag\b[^>]*>(.*?)</(?:\w+:)?$tag>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .find(source)
        ?.groupValues
        ?.get(1)
        ?.replace(Regex("<[^>]+>"), "")
        ?.trim()
}

private fun resolveHref(requestUrl: String, href: String): String {
    val decoded = href.trim()
    if (decoded.startsWith("http://") || decoded.startsWith("https://")) return decoded
    val base = URI(requestUrl)
    val resolved = base.resolve(decoded).toString()
    return URLDecoder.decode(resolved, "UTF-8")
}

private fun sameLocation(left: String, right: String): Boolean {
    fun norm(value: String) = value.trimEnd('/')
    return norm(left) == norm(right)
}

private fun decodeSegment(url: String): String {
    val path = URI(url).path.orEmpty().trimEnd('/')
    val segment = path.substringAfterLast('/')
    return URLDecoder.decode(segment, "UTF-8")
}
