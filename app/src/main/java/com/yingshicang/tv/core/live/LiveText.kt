package com.yingshicang.tv.core.live

import com.yingshicang.tv.core.model.LiveChannel
import com.yingshicang.tv.core.model.LiveProgram
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

fun parseLiveDocument(text: String, maxChannels: Int = Int.MAX_VALUE): Pair<List<LiveChannel>, List<LiveProgram>> {
    val raw = text.trim().removePrefix("\uFEFF")
    if (raw.isBlank()) return emptyList<LiveChannel>() to emptyList()
    return when {
        raw.startsWith("#EXTM3U") || raw.startsWith("#EXTINF") -> parseM3u(raw, maxChannels) to emptyList()
        raw.startsWith("<") -> emptyList<LiveChannel>() to parseXmltv(raw)
        raw.startsWith("{") || raw.startsWith("[") -> parseLiveJson(raw, maxChannels)
        else -> parseTvBoxLiveText(raw, maxChannels) to emptyList()
    }
}

enum class LiveVerdict {
    PLAYABLE,
    DEAD,
    UNKNOWN,
}

fun liveVerdict(code: Int, contentType: String, head: String): LiveVerdict {
    if (code == 401 || code == 403 || code <= 0 || code in 500..599) return LiveVerdict.UNKNOWN
    if (code !in 200..299) return LiveVerdict.DEAD
    val type = contentType.lowercase()
    if (listOf("mpegurl", "video/", "audio/", "mp2t", "octet-stream", "mp4").any { type.contains(it) }) {
        return LiveVerdict.PLAYABLE
    }
    val text = head.trimStart().removePrefix("\uFEFF")
    if (text.startsWith("#EXTM3U") || text.startsWith("#EXTINF")) return LiveVerdict.PLAYABLE
    val lower = text.take(48).lowercase()
    if (lower.startsWith("<!doctype") || lower.startsWith("<html") || lower.startsWith("<head")) return LiveVerdict.DEAD
    if (text.isNotEmpty()) return LiveVerdict.PLAYABLE
    return LiveVerdict.DEAD
}

fun sampleLiveUrls(channels: List<LiveChannel>): List<String> {
    if (channels.isEmpty()) return emptyList()
    val picks = listOf(channels.first(), channels[channels.size / 2], channels.last())
    return picks.flatMap { it.urls }.filter { it.startsWith("http://") || it.startsWith("https://") }.distinct().take(3)
}

fun parseTvBoxLiveText(text: String, maxChannels: Int = Int.MAX_VALUE): List<LiveChannel> {
    var group = "未分组"
    val channels = mutableListOf<LiveChannel>()
    text.lineSequence().forEach { line ->
        if (channels.size >= maxChannels) return mergeChannels(channels)
        val trimmed = line.trim().removePrefix("\uFEFF")
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
        val name = trimmed.substringBefore(',').trim()
        val rest = trimmed.substringAfter(',', "").trim()
        if (name.isEmpty() || rest.isEmpty()) return@forEach
        if (rest.equals("#genre#", ignoreCase = true)) {
            group = name
            return@forEach
        }
        val urls = rest.split('#').map { it.trim() }.filter { it.startsWith("http://") || it.startsWith("https://") }
        if (urls.isEmpty()) return@forEach
        channels += LiveChannel(id = name, name = name, group = group, logo = null, tvgId = null, urls = urls)
    }
    return mergeChannels(channels)
}

fun parseM3u(text: String, maxChannels: Int = Int.MAX_VALUE): List<LiveChannel> {
    val channels = mutableListOf<LiveChannel>()
    var pending: Triple<String, String?, Map<String, String>>? = null
    text.lineSequence().forEach { line ->
        if (channels.size >= maxChannels) return mergeChannels(channels)
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#EXTM3U")) return@forEach
        if (trimmed.startsWith("#EXTINF")) {
            val (attrs, name) = splitExtinf(trimmed)
            pending = Triple(name.ifBlank { "未命名频道" }, attrs["tvg-id"], attrs)
            return@forEach
        }
        if (trimmed.startsWith("#")) return@forEach
        val meta = pending
        pending = null
        if (meta == null || !trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return@forEach
        val attrs = meta.third
        channels += LiveChannel(
            id = attrs["tvg-id"].orEmpty().ifBlank { meta.first },
            name = meta.first,
            group = attrs["group-title"].orEmpty().ifBlank { "未分组" },
            logo = attrs["tvg-logo"]?.ifBlank { null },
            tvgId = attrs["tvg-id"]?.ifBlank { null },
            urls = listOf(trimmed),
        )
    }
    return mergeChannels(channels)
}

fun parseLiveJson(text: String, maxChannels: Int = Int.MAX_VALUE): Pair<List<LiveChannel>, List<LiveProgram>> {
    val root = JSONObject(if (text.trim().startsWith("[")) JSONObject().put("channels", JSONArray(text)).toString() else text)
    val channels = mutableListOf<LiveChannel>()
    val array = root.optJSONArray("channels") ?: JSONArray()
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val urls = stringList(item, "urls").ifEmpty { listOfNotNull(item.optString("url").ifBlank { null }) }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
        if (urls.isEmpty()) continue
        if (channels.size >= maxChannels) break
        val name = item.optString("name").ifBlank { "未命名频道" }
        val tvgId = item.optString("tvgId").ifBlank { item.optString("tvg-id") }.ifBlank { null }
        channels += LiveChannel(
            id = item.optString("id").ifBlank { tvgId ?: name },
            name = name,
            group = item.optString("group").ifBlank { "未分组" },
            logo = item.optString("logo").ifBlank { null },
            tvgId = tvgId,
            urls = urls,
        )
    }
    val programs = mutableListOf<LiveProgram>()
    val programArray = root.optJSONArray("programs") ?: JSONArray()
    for (index in 0 until programArray.length()) {
        val item = programArray.optJSONObject(index) ?: continue
        val start = parseTime(item.opt("start") ?: item.opt("startAt"))
        val end = parseTime(item.opt("end") ?: item.opt("endAt"))
        val channelId = item.optString("channelId").ifBlank { item.optString("tvgId") }
        if (channelId.isBlank() || start <= 0L || end <= start) continue
        programs += LiveProgram(
            channelId = channelId,
            title = item.optString("title").ifBlank { "未命名节目" },
            startAt = start,
            endAt = end,
            description = item.optString("description"),
        )
    }
    return mergeChannels(channels) to programs
}

fun parseXmltv(text: String): List<LiveProgram> {
    val programs = mutableListOf<LiveProgram>()
    val pattern = Regex(
        """<programme\b([^>]*)>(.*?)</programme>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    pattern.findAll(text).forEach { match ->
        val attrs = match.groupValues[1]
        val body = match.groupValues[2]
        val channelId = attr(attrs, "channel") ?: return@forEach
        val start = parseTime(attr(attrs, "start"))
        val end = parseTime(attr(attrs, "stop"))
        if (start <= 0L || end <= start) return@forEach
        programs += LiveProgram(
            channelId = channelId,
            title = tagText(body, "title").ifBlank { "未命名节目" },
            startAt = start,
            endAt = end,
            description = tagText(body, "desc"),
        )
    }
    return programs
}

fun currentProgram(programs: List<LiveProgram>, channel: LiveChannel, now: Long): LiveProgram? {
    val keys = setOfNotNull(channel.tvgId, channel.id, channel.name).filter { it.isNotBlank() }.toSet()
    return programs
        .filter { it.channelId in keys && now >= it.startAt && now < it.endAt }
        .minByOrNull { it.startAt }
}

fun parseTime(value: Any?): Long {
    val text = when (value) {
        null, JSONObject.NULL -> return 0L
        is Number -> {
            val raw = value.toLong()
            return if (raw in 1..9_999_999_999L) raw * 1000 else raw
        }
        else -> value.toString().trim()
    }
    if (text.isBlank()) return 0L
    text.toLongOrNull()?.let { raw ->
        return if (raw in 1..9_999_999_999L) raw * 1000 else raw
    }
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ssX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSX",
        "yyyy-MM-dd HH:mm:ss",
        "yyyyMMddHHmmss Z",
        "yyyyMMddHHmmss",
    )
    patterns.forEach { pattern ->
        try {
            val format = SimpleDateFormat(pattern, Locale.US)
            format.isLenient = false
            format.timeZone = TimeZone.getTimeZone("UTC")
            format.parse(text)?.time?.let { return it }
        } catch (_: Exception) {
        }
    }
    return 0L
}

private fun mergeChannels(channels: List<LiveChannel>): List<LiveChannel> {
    val merged = linkedMapOf<String, LiveChannel>()
    channels.forEach { channel ->
        val key = "${channel.group}\u0000${channel.tvgId ?: channel.name}"
        val existing = merged[key]
        merged[key] = if (existing == null) {
            channel
        } else {
            existing.copy(urls = (existing.urls + channel.urls).distinct())
        }
    }
    return merged.values.toList()
}

private fun splitExtinf(line: String): Pair<Map<String, String>, String> {
    val body = line.substringAfter(":")
    var quoted = false
    var splitAt = -1
    for (index in body.indices) {
        when (body[index]) {
            '"' -> quoted = !quoted
            ',' -> if (!quoted) {
                splitAt = index
                break
            }
        }
    }
    val attrText = if (splitAt >= 0) body.substring(0, splitAt) else body
    val name = if (splitAt >= 0) body.substring(splitAt + 1).trim() else ""
    val attrs = Regex("""([\w-]+)="([^"]*)"""").findAll(attrText).associate { it.groupValues[1] to it.groupValues[2] }
    return attrs to name
}

private fun stringList(item: JSONObject, name: String): List<String> {
    val array = item.optJSONArray(name) ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val value = array.optString(index)
            if (value.isNotBlank()) add(value)
        }
    }
}

private fun attr(source: String, name: String): String? {
    return Regex("""\b${Regex.escape(name)}\s*=\s*"([^"]*)"""").find(source)?.groupValues?.get(1)
}

private fun tagText(source: String, tag: String): String {
    val raw = Regex("""<$tag\b[^>]*>(.*?)</$tag>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .find(source)
        ?.groupValues
        ?.get(1)
        .orEmpty()
    return raw.replace(Regex("<[^>]+>"), "").trim()
}
