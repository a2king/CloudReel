package com.yingshicang.tv.core.drive

import java.net.URLEncoder
import org.json.JSONObject

data class Pan115Link(
    val pickCode: String,
    val quality: String,
    val fileName: String,
)

data class Pan115File(
    val name: String,
    val sizeBytes: Long,
    val url: String,
)

private val pickCodePattern = Regex("^[A-Za-z0-9]{6,80}$")

const val PAN115_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

fun recognizePan115(lineName: String, episodeName: String, url: String): Pan115Link? {
    val raw = url.trim()
    if (raw.startsWith("http://") || raw.startsWith("https://")) return null
    val pickCode = raw.removePrefix("115://").substringBefore('?').substringBefore('#').trim()
    val marked = raw.startsWith("115://") || lineName.contains("115")
    if (!marked || !pickCodePattern.matches(pickCode)) return null
    val quality = when {
        lineName.contains("原画") -> "原画"
        lineName.contains("4K") || lineName.contains("2160") -> "4K"
        lineName.contains("超清") -> "超清"
        lineName.contains("高清") -> "高清"
        lineName.contains("标清") -> "标清"
        else -> "原画"
    }
    return Pan115Link(
        pickCode = pickCode,
        quality = quality,
        fileName = episodeName.trim().ifBlank { "115 文件" },
    )
}

fun pan115DownUrlBody(pickCode: String): String {
    return "pickcode=" + URLEncoder.encode(pickCode, Charsets.UTF_8.name())
}

fun pan115PlayHeaders(): Map<String, String> = mapOf(
    "User-Agent" to PAN115_USER_AGENT,
    "Referer" to "https://115.com/",
)

fun parsePan115DownUrl(body: String): Pan115File {
    val root = JSONObject(body.trim())
    if (!root.optBoolean("state", false)) {
        val message = root.optString("msg").ifBlank { root.optString("error") }.ifBlank { "115 没有返回播放地址" }
        throw IllegalStateException(message)
    }
    val data = root.optJSONObject("data") ?: throw IllegalStateException("115 没有返回文件信息")
    val keys = data.keys()
    var item: JSONObject? = null
    while (keys.hasNext()) {
        val next = data.optJSONObject(keys.next())
        if (next != null) {
            item = next
            break
        }
    }
    val file = item ?: throw IllegalStateException("115 没有返回文件信息")
    val url = when (val node = file.opt("url")) {
        is JSONObject -> node.optString("url")
        is String -> node
        else -> ""
    }.trim()
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
        throw IllegalStateException("115 没有返回播放地址")
    }
    return Pan115File(
        name = file.optString("file_name").ifBlank { "115 文件" },
        sizeBytes = longValue(file.opt("file_size")),
        url = url,
    )
}

data class Pan115QrStart(
    val uid: String,
    val time: String,
    val sign: String,
    val content: String,
)

enum class Pan115QrPhase {
    WAITING,
    SCANNED,
    CONFIRMED,
    EXPIRED,
    CANCELED,
}

fun pan115QrContent(uid: String): String = "https://115.com/scan/dg-$uid"

fun parsePan115QrStart(body: String): Pan115QrStart {
    val root = JSONObject(body.trim())
    if (root.has("state") && !jsonOk(root)) {
        throw IllegalStateException(pan115Message(root, "115 没有返回二维码"))
    }
    val data = root.optJSONObject("data") ?: root
    val uid = data.optString("uid").ifBlank { data.optString("UID") }.trim()
    if (uid.isEmpty()) throw IllegalStateException("115 没有返回二维码")
    val time = when (val raw = data.opt("time")) {
        is Number -> raw.toLong().toString()
        is String -> raw.trim()
        else -> ""
    }
    return Pan115QrStart(uid, time, data.optString("sign").trim(), pan115QrContent(uid))
}

fun parsePan115QrStatus(body: String): Pan115QrPhase {
    val root = JSONObject(body.trim())
    if (root.has("state") && !jsonOk(root)) {
        throw IllegalStateException(pan115Message(root, "115 二维码状态获取失败"))
    }
    val status = when (val data = root.opt("data")) {
        is Number -> data.toInt()
        is String -> data.toIntOrNull()
        is JSONObject -> intField(data, "status") ?: intField(root, "status")
        else -> intField(root, "status")
    } ?: 0
    return when (status) {
        1 -> Pan115QrPhase.SCANNED
        2 -> Pan115QrPhase.CONFIRMED
        -1 -> Pan115QrPhase.EXPIRED
        -2 -> Pan115QrPhase.CANCELED
        else -> Pan115QrPhase.WAITING
    }
}

fun parsePan115LoginCookie(body: String, setCookies: List<String>): String {
    val values = linkedMapOf<String, String>()
    fun put(name: String, value: String) {
        val key = when (name.trim().uppercase()) {
            "UID", "CID", "SEID", "KID" -> name.trim().uppercase()
            else -> return
        }
        val clean = value.trim().trim('"')
        if (clean.isNotEmpty()) values[key] = clean
    }
    setCookies.forEach { header -> readCookiePairs(header, ::put) }
    val root = try {
        JSONObject(body.trim())
    } catch (_: Exception) {
        null
    }
    if (root != null) {
        if (root.has("state") && !jsonOk(root)) {
            throw IllegalStateException(pan115Message(root, "115 登录失败"))
        }
        readCookiePairs(root.optString("cookie"), ::put)
        collectCookieFields(root, ::put)
        root.optJSONObject("data")?.let { data ->
            readCookiePairs(data.optString("cookie"), ::put)
            collectCookieFields(data, ::put)
            data.optJSONObject("cookie")?.let { collectCookieFields(it, ::put) }
        }
    }
    val cookie = listOf("UID", "CID", "SEID", "KID").mapNotNull { key ->
        values[key]?.let { "$key=$it" }
    }.joinToString("; ")
    if (cookie.isBlank()) throw IllegalStateException("115 没有返回登录信息")
    return cookie
}

fun formatByteSize(bytes: Long): String {
    if (bytes <= 0L) return ""
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index += 1
    }
    return if (index == 0) "${bytes}B" else String.format(java.util.Locale.US, "%.1f%s", value, units[index])
}

private fun jsonOk(root: JSONObject): Boolean = when (val raw = root.opt("state")) {
    is Boolean -> raw
    is Number -> raw.toInt() != 0
    is String -> raw == "1" || raw.equals("true", ignoreCase = true)
    else -> true
}

private fun pan115Message(root: JSONObject, fallback: String): String {
    return root.optString("message").ifBlank { root.optString("msg") }.ifBlank { root.optString("error") }.ifBlank { fallback }
}

private fun intField(root: JSONObject, key: String): Int? {
    if (!root.has(key) || root.isNull(key)) return null
    return when (val raw = root.opt(key)) {
        is Number -> raw.toInt()
        is String -> raw.toIntOrNull()
        else -> null
    }
}

private fun readCookiePairs(header: String, put: (String, String) -> Unit) {
    header.split(';').forEach { part ->
        val pair = part.trim()
        val eq = pair.indexOf('=')
        if (eq > 0) put(pair.substring(0, eq), pair.substring(eq + 1))
    }
}

private fun collectCookieFields(root: JSONObject, put: (String, String) -> Unit) {
    val keys = root.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        when (val raw = root.opt(key)) {
            is String -> put(key, raw)
            is Number -> put(key, raw.toString())
        }
    }
}

private fun longValue(raw: Any?): Long = when (raw) {
    is Number -> raw.toLong()
    is String -> raw.toLongOrNull() ?: 0L
    else -> 0L
}
