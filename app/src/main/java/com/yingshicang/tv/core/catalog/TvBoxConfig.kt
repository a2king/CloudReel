package com.yingshicang.tv.core.catalog

import org.json.JSONArray
import org.json.JSONObject

/**
 * The small part of the TVBox configuration format which is useful to this
 * application. A TVBox subscription is a directory of sources; it is not a
 * CMS catalog response by itself.
 */
data class TvBoxRemoteSource(
    val name: String,
    val url: String,
)

data class TvBoxSite(
    val name: String,
    val api: String,
    val type: Int? = null,
)

data class TvBoxLive(
    val name: String,
    val url: String,
)

data class TvBoxDocument(
    val remoteSources: List<TvBoxRemoteSource>,
    val sites: List<TvBoxSite>,
    val isCmsResponse: Boolean,
    val lives: List<TvBoxLive> = emptyList(),
)

fun isTvBoxLineIndex(document: TvBoxDocument): Boolean = document.remoteSources.isNotEmpty()

fun parseTvBoxDocument(text: String): TvBoxDocument {
    val trimmed = relaxTvBoxJson(unwrapTvBoxBody(text))
    if (trimmed.startsWith("<")) throw IllegalArgumentException("这条线路返回的是网页，不是配置")
    val root = try {
        JSONObject(trimmed)
    } catch (_: Exception) {
        // A few subscriptions are published as a top-level array of URLs.
        val array = try {
            JSONArray(trimmed)
        } catch (_: Exception) {
            throw IllegalArgumentException("返回的不是 JSON 配置")
        }
        return TvBoxDocument(
            remoteSources = parseRemoteSources(array),
            sites = emptyList(),
            isCmsResponse = false,
        )
    }

    val remoteSources = parseRemoteSources(root.optJSONArray("urls"))
    val sites = parseSites(root.optJSONArray("sites"))
    val lives = parseLives(root.optJSONArray("lives"))
    val isCms = root.has("list") || root.optJSONObject("data")?.has("list") == true
    return TvBoxDocument(remoteSources, sites, isCms, lives)
}

private fun parseRemoteSources(array: JSONArray?): List<TvBoxRemoteSource> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.opt(index)
            val source = when (item) {
                is JSONObject -> {
                    val url = item.optString("url")
                        .ifBlank { item.optString("api") }
                        .ifBlank { item.optString("ext") }
                    TvBoxRemoteSource(
                        name = item.optString("name")
                            .ifBlank { item.optString("title") }
                            .ifBlank { "TVBox 子源 ${index + 1}" },
                        url = url,
                    )
                }
                is String -> TvBoxRemoteSource("TVBox 子源 ${index + 1}", item)
                else -> null
            }
            if (source != null && source.url.isNotBlank()) add(source)
        }
    }
}

private fun parseSites(array: JSONArray?): List<TvBoxSite> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val api = item.optString("api")
                .ifBlank { item.optString("url") }
            if (api.isBlank()) continue
            val type = if (item.has("type") && !item.isNull("type")) item.optInt("type") else null
            add(
                TvBoxSite(
                    name = item.optString("name")
                        .ifBlank { item.optString("key") }
                        .ifBlank { "TVBox 站点 ${index + 1}" },
                    api = api,
                    type = type,
                ),
            )
        }
    }
}

/**
 * TVBox subscriptions are often JSON with comments and trailing commas.
 * Strict parsers reject the whole file, so those extras are removed first.
 */
internal fun relaxTvBoxJson(raw: String): String {
    val text = raw.trim().removePrefix("\uFEFF")
    val out = StringBuilder()
    var index = 0
    var quoted = false
    var escaped = false
    while (index < text.length) {
        val char = text[index]
        if (quoted) {
            out.append(char)
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> quoted = false
            }
            index += 1
            continue
        }
        if (char == '"') {
            quoted = true
            out.append(char)
            index += 1
            continue
        }
        if (char == '/' && index + 1 < text.length && (text[index + 1] == '/' || text[index + 1] == '*')) {
            index = if (text[index + 1] == '/') {
                val lineEnd = text.indexOf('\n', index + 2)
                if (lineEnd < 0) text.length else lineEnd
            } else {
                val blockEnd = text.indexOf("*/", index + 2)
                if (blockEnd < 0) text.length else blockEnd + 2
            }
            continue
        }
        if (char == '}' || char == ']') {
            var trimAt = out.length
            while (trimAt > 0 && out[trimAt - 1].isWhitespace()) trimAt -= 1
            if (trimAt > 0 && out[trimAt - 1] == ',') out.delete(trimAt - 1, out.length)
        }
        out.append(char)
        index += 1
    }
    return out.toString().trim()
}

internal fun isCmsSite(site: TvBoxSite): Boolean {
    if (site.type != null && site.type != 0 && site.type != 1) return false
    return !isScriptAsset(site.api)
}

fun hasScrapeableCms(document: TvBoxDocument, pageUrl: String): Boolean {
    if (document.sites.any { cmsApi(pageUrl, it) != null }) return true
    if (document.sites.isNotEmpty() || document.remoteSources.isNotEmpty()) return false
    return document.isCmsResponse
}

private fun cmsApi(pageUrl: String, site: TvBoxSite): String? {
    if (!isCmsSite(site)) return null
    val api = resolveTvBoxUrl(pageUrl, site.api) ?: return null
    if (isScriptAsset(api)) return null
    return api
}

private fun parseLives(array: JSONArray?): List<TvBoxLive> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val url = item.optString("url").trim()
            if (url.isEmpty() || url.contains("://") && !url.startsWith("http://") && !url.startsWith("https://")) continue
            if (url.startsWith("http://") || url.startsWith("https://")) {
                val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
                if (host == "127.0.0.1" || host.equals("localhost", ignoreCase = true)) continue
            }
            add(TvBoxLive(item.optString("name").ifBlank { "直播 ${index + 1}" }, url))
        }
    }
}

internal fun configText(bytes: ByteArray): String {
    if (firstContentByte(bytes) == '{'.code || firstContentByte(bytes) == '['.code) {
        return bytes.toString(Charsets.UTF_8).trim().removePrefix("\uFEFF")
    }
    val marker = lastStarStar(bytes)
    if (marker >= 0) {
        decodeBase64Text(bytes.copyOfRange(marker + 2, bytes.size).toString(Charsets.ISO_8859_1))?.let { return it }
    }
    decodeBase64Text(bytes.toString(Charsets.ISO_8859_1))?.let { return it }
    return bytes.toString(Charsets.UTF_8).trim().removePrefix("\uFEFF")
}

internal fun unwrapTvBoxBody(text: String): String {
    val trimmed = text.trim().removePrefix("\uFEFF")
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed
    val marker = trimmed.lastIndexOf("**")
    if (marker >= 0) {
        decodeBase64Text(trimmed.substring(marker + 2))?.let { return it }
    }
    return decodeBase64Text(trimmed) ?: trimmed
}

private fun firstContentByte(bytes: ByteArray): Int {
    var index = 0
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        index = 3
    }
    while (index < bytes.size && bytes[index].toInt().toChar().isWhitespace()) index += 1
    return if (index < bytes.size) bytes[index].toInt() and 0xFF else -1
}

private fun lastStarStar(bytes: ByteArray): Int {
    var found = -1
    for (index in 0 until bytes.size - 1) {
        if (bytes[index] == 42.toByte() && bytes[index + 1] == 42.toByte()) found = index
    }
    return found
}

private fun decodeBase64Text(raw: String): String? {
    val start = raw.indexOfFirst { it in BASE64_ALPHABET }
    if (start < 0) return null
    val compact = buildString {
        for (index in start until raw.length) {
            val char = raw[index]
            if (char.isWhitespace()) continue
            if (char !in BASE64_ALPHABET && char != '=') break
            append(char)
        }
    }
    if (compact.length < 16) return null
    val bytes = decodeBase64(compact) ?: return null
    val decoded = bytes.toString(Charsets.UTF_8).trim().removePrefix("\uFEFF")
    return decoded.takeIf { it.startsWith("{") || it.startsWith("[") }
}

private fun decodeBase64(input: String): ByteArray? {
    val payload = input.trimEnd('=')
    if (payload.isEmpty() || payload.any { BASE64_ALPHABET.indexOf(it) < 0 }) return null
    val out = ArrayList<Byte>(payload.length * 3 / 4)
    var buffer = 0
    var bits = 0
    payload.forEach { char ->
        buffer = (buffer shl 6) or BASE64_ALPHABET.indexOf(char)
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out += ((buffer shr bits) and 0xFF).toByte()
        }
    }
    return out.toByteArray()
}

private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

internal fun isScriptAsset(url: String): Boolean {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return path.endsWith(".js") || path.endsWith(".mjs") || path.endsWith(".py") || path.endsWith(".jar")
}

fun resolveTvBoxUrl(base: String, raw: String): String? {
    val value = raw.trim()
    if (value.isBlank()) return null
    return try {
        val resolved = java.net.URI(base).resolve(value).toString()
        if (resolved.startsWith("http://") || resolved.startsWith("https://")) resolved else null
    } catch (_: Exception) {
        absolutizeUrl(base, value)?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }
}
