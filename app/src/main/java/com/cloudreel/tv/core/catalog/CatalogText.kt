package com.cloudreel.tv.core.catalog

import com.cloudreel.tv.core.model.MergedHit
import com.cloudreel.tv.core.model.SearchHit
import java.net.URI
import java.net.URLEncoder
import org.json.JSONObject

data class ParsedEpisode(
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

data class ParsedLine(
    val name: String,
    val episodes: List<ParsedEpisode>,
)

private val headerPattern = Regex("(?i)@(Referer|User-Agent|Origin|Cookie)=")

fun normalizeTitle(raw: String): String {
    val withoutNotes = raw.lowercase()
        .replace(Regex("（[^）]*）"), "")
        .replace(Regex("\\([^)]*\\)"), "")
    return withoutNotes.replace(Regex("[\\s\\p{Punct}：:·・，。！？、'\"“”‘’—_]+"), "")
}

fun yearKey(year: String?): String? = year.orEmpty().filter { it.isDigit() }.take(4).ifBlank { null }

fun stableMediaKey(title: String, year: String?): String {
    val name = normalizeTitle(title).ifBlank { "untitled" }
    return "agg:$name:${yearKey(year).orEmpty()}"
}

fun shouldMerge(left: SearchHit, right: SearchHit): Boolean {
    val a = normalizeTitle(left.title)
    val b = normalizeTitle(right.title)
    if (a.isBlank() || a != b) return false
    val yearA = yearKey(left.year)
    val yearB = yearKey(right.year)
    return yearA == null || yearB == null || yearA == yearB
}

fun mergeHits(hits: List<SearchHit>): List<MergedHit> {
    val groups = mutableListOf<MutableList<SearchHit>>()
    hits.forEach { hit ->
        val group = groups.firstOrNull { shouldMerge(it.first(), hit) }
        if (group == null) groups += mutableListOf(hit) else group += hit
    }
    return groups.map { group ->
        val first = group.first()
        MergedHit(
            title = first.title,
            poster = group.firstOrNull { !it.poster.isNullOrBlank() }?.poster,
            year = group.firstOrNull { yearKey(it.year) != null }?.year ?: first.year,
            area = group.firstOrNull { !it.area.isNullOrBlank() }?.area,
            genres = group.flatMap { it.genres }.distinct(),
            hits = group,
        )
    }
}

fun stripHtml(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return raw
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p>"), "\n")
        .replace(Regex("(?is)<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("[\\t\\x0B\\f\\r ]+"), " ")
        .replace(Regex("\\n{2,}"), "\n")
        .trim()
}

fun splitPeople(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(',', '，', '/', '|', '、')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
}

fun splitUrlAndHeaders(raw: String): Pair<String, Map<String, String>> {
    val trimmed = raw.trim()
    val first = headerPattern.find(trimmed) ?: return trimmed to emptyMap()
    val url = trimmed.substring(0, first.range.first).trim()
    val blob = trimmed.substring(first.range.first)
    val marks = headerPattern.findAll(blob).toList()
    val headers = linkedMapOf<String, String>()
    marks.forEachIndexed { index, mark ->
        val valueStart = mark.range.last + 1
        val valueEnd = if (index + 1 < marks.size) marks[index + 1].range.first else blob.length
        val value = blob.substring(valueStart, valueEnd).trim().trimEnd('@')
        if (value.isNotEmpty()) headers[canonicalHeader(mark.groupValues[1])] = value
    }
    return url to headers
}

fun parseCmsPlay(from: String?, playUrl: String?): List<ParsedLine> {
    if (playUrl.isNullOrBlank()) return emptyList()
    val names = from?.split("$$$")?.map { it.trim() }.orEmpty()
    return playUrl.replace("\r\n", "\n").trim().split("$$$").mapIndexedNotNull { index, body ->
        val episodes = body.split("#").mapNotNull { piece ->
            val part = piece.trim()
            if (part.isEmpty()) return@mapNotNull null
            val dollar = part.indexOf('$')
            val name = if (dollar >= 0) part.substring(0, dollar).trim().ifBlank { "正片" } else "正片"
            val rawUrl = if (dollar >= 0) part.substring(dollar + 1).trim() else part
            val (url, headers) = splitUrlAndHeaders(rawUrl)
            if (url.isBlank()) null else ParsedEpisode(name, url, headers)
        }
        if (episodes.isEmpty()) null else ParsedLine(names.getOrNull(index)?.ifBlank { null } ?: "线路${index + 1}", episodes)
    }
}

fun absolutizeUrl(base: String, raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val value = raw.trim()
    if (value.startsWith("http://") || value.startsWith("https://")) return value
    if (value.startsWith("//")) return "https:$value"
    if (!value.startsWith("/")) return value
    return try {
        val uri = URI(base.substringBefore("?"))
        if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) value else "${uri.scheme}://${uri.authority}$value"
    } catch (_: Exception) {
        value
    }
}

fun normalizeCmsBase(input: String): String {
    val trimmed = input.trim()
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        throw IllegalArgumentException("接口地址需要以 http:// 或 https:// 开头")
    }
    return trimmed.substringBefore("?").trimEnd('/')
}

fun buildCmsUrl(base: String, params: Map<String, String>): String {
    val root = normalizeCmsBase(base)
    val query = params.entries.joinToString("&") { (key, value) ->
        "$key=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
    }
    return "$root/?$query"
}

fun cmsProbeUrl(base: String, action: String): String {
    return buildCmsUrl(base, mapOf("ac" to action, "pg" to "1"))
}

fun isUsableCmsPayload(text: String): Boolean {
    val trimmed = text.trim().removePrefix("\uFEFF")
    if (trimmed.isEmpty()) return false
    val root = try {
        JSONObject(trimmed)
    } catch (_: Exception) {
        return trimmed.startsWith("{") && hasCmsEntryMarker(trimmed)
    }
    val list = root.optJSONArray("list")
        ?: root.optJSONObject("data")?.optJSONArray("list")
        ?: return false
    for (index in 0 until list.length()) {
        val item = list.optJSONObject(index) ?: continue
        if (item.optString("vod_name").isNotBlank() || item.optString("vod_id").isNotBlank()) return true
    }
    return false
}

fun cmsListHasPoster(text: String): Boolean {
    val trimmed = text.trim().removePrefix("\uFEFF")
    if (trimmed.isEmpty()) return false
    val root = try {
        JSONObject(trimmed)
    } catch (_: Exception) {
        return false
    }
    val list = root.optJSONArray("list")
        ?: root.optJSONObject("data")?.optJSONArray("list")
        ?: return false
    for (index in 0 until list.length()) {
        val item = list.optJSONObject(index) ?: continue
        val pic = item.optString("vod_pic").trim()
        if (pic.startsWith("http://") || pic.startsWith("https://")) return true
    }
    return false
}

private fun hasCmsEntryMarker(text: String): Boolean {
    return text.contains("\"vod_name\"") || text.contains("\"vod_id\"")
}

object ResumePolicy {
    const val START_SAVE_MS = 5_000L

    fun isCompleted(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L || positionMs <= 0L) return false
        return positionMs * 100 >= durationMs * 95
    }

    fun shouldContinue(positionMs: Long, durationMs: Long): Boolean {
        return positionMs >= START_SAVE_MS && !isCompleted(positionMs, durationMs)
    }
}

fun readableError(error: Throwable): String {
    val message = generateSequence(error) { it.cause }
        .firstNotNullOfOrNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
        .orEmpty()
        .take(80)
    val lower = message.lowercase()
    return when {
        lower.contains("timeout") || lower.contains("timed out") || message == "超时" -> "超时"
        message.startsWith("HTTP ") -> "接口$message"
        message.isNotBlank() -> message
        else -> "加载失败"
    }
}

private fun canonicalHeader(name: String): String = when (name.lowercase()) {
    "referer" -> "Referer"
    "user-agent" -> "User-Agent"
    "origin" -> "Origin"
    "cookie" -> "Cookie"
    else -> name
}
