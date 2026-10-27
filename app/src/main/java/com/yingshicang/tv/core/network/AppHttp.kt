package com.yingshicang.tv.core.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

data class HttpText(
    val body: String,
    val setCookies: List<String>,
)

data class HttpProbe(
    val code: Int,
    val contentType: String,
    val head: String,
)

class AppHttp {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun get(url: String): String = withContext(Dispatchers.IO) { exchange(url) }

    fun exchange(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        contentType: String? = null,
        maxBytes: Int = MAX_BYTES,
        callTimeoutSeconds: Int? = null,
        truncate: Boolean = false,
    ): String = exchangeDetailed(url, method, headers, body, contentType, maxBytes, callTimeoutSeconds, truncate).body

    fun exchangeDetailed(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        contentType: String? = null,
        maxBytes: Int = MAX_BYTES,
        callTimeoutSeconds: Int? = null,
        truncate: Boolean = false,
    ): HttpText {
        val normalized = url.lowercase()
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            throw IllegalArgumentException("只允许 http 或 https")
        }
        val builder = Request.Builder().url(toAsciiHttpUrl(url)).header("User-Agent", USER_AGENT)
        headers.forEach { (key, value) -> builder.header(key, value) }
        val requestBody = body?.toRequestBody(contentType?.toMediaType())
        val upper = method.uppercase()
        if (upper == "GET" || upper == "HEAD") {
            builder.method(upper, null)
        } else {
            builder.method(upper, requestBody ?: ByteArray(0).toRequestBody(null))
        }
        val call = client.newCall(builder.build())
        if (callTimeoutSeconds != null) {
            call.timeout().timeout(callTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        }
        call.execute().use { response ->
            val cookies = response.headers("Set-Cookie")
            return HttpText(readLimited(response, maxBytes, truncate), cookies)
        }
    }

    fun exchangeBytes(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        contentType: String? = null,
        maxBytes: Int = MAX_BYTES,
        callTimeoutSeconds: Int? = null,
    ): ByteArray {
        val normalized = url.lowercase()
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            throw IllegalArgumentException("只允许 http 或 https")
        }
        val builder = Request.Builder().url(toAsciiHttpUrl(url)).header("User-Agent", USER_AGENT)
        headers.forEach { (key, value) -> builder.header(key, value) }
        val requestBody = body?.toRequestBody(contentType?.toMediaType())
        val upper = method.uppercase()
        if (upper == "GET" || upper == "HEAD") {
            builder.method(upper, null)
        } else {
            builder.method(upper, requestBody ?: ByteArray(0).toRequestBody(null))
        }
        val call = client.newCall(builder.build())
        if (callTimeoutSeconds != null) {
            call.timeout().timeout(callTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        }
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val responseBody = response.body ?: throw IOException("空响应")
            val length = responseBody.contentLength()
            if (length > maxBytes) throw IOException("接口返回过长")
            val bytes = responseBody.bytes()
            if (bytes.size > maxBytes) throw IOException("接口返回过长")
            return bytes
        }
    }

    fun probeHead(url: String): HttpProbe {
        val normalized = url.lowercase()
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            throw IllegalArgumentException("只允许 http 或 https")
        }
        val request = Request.Builder()
            .url(toAsciiHttpUrl(url))
            .header("User-Agent", USER_AGENT)
            .build()
        val call = client.newCall(request)
        call.timeout().timeout(6, TimeUnit.SECONDS)
        call.execute().use { response ->
            val type = response.header("Content-Type").orEmpty()
            val source = response.body?.source()
            val head = if (source == null) {
                ""
            } else {
                source.request(512)
                val count = minOf(512L, source.buffer.size)
                if (count <= 0L) "" else source.buffer.readUtf8(count)
            }
            return HttpProbe(response.code, type, head.take(300))
        }
    }

    fun exchangeCaptured(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        contentType: String? = null,
        callTimeoutSeconds: Int? = null,
    ): HttpText {
        val normalized = url.lowercase()
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            throw IllegalArgumentException("只允许 http 或 https")
        }
        val store = mutableListOf<Cookie>()
        val jar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                store += cookies
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> = store.toList()
        }
        val builder = Request.Builder().url(toAsciiHttpUrl(url)).header("User-Agent", USER_AGENT)
        headers.forEach { (key, value) -> builder.header(key, value) }
        val requestBody = body?.toRequestBody(contentType?.toMediaType())
        val upper = method.uppercase()
        if (upper == "GET" || upper == "HEAD") builder.method(upper, null) else {
            builder.method(upper, requestBody ?: ByteArray(0).toRequestBody(null))
        }
        val call = client.newBuilder().cookieJar(jar).build().newCall(builder.build())
        if (callTimeoutSeconds != null) call.timeout().timeout(callTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        call.execute().use { response ->
            val text = readLimited(response, MAX_BYTES, false)
            val cookieHeader = store.distinctBy { it.name }.joinToString("; ") { "${it.name}=${it.value}" }
            return HttpText(text, listOf(cookieHeader).filter { it.isNotBlank() })
        }
    }

    private fun readLimited(response: Response, maxBytes: Int, truncate: Boolean): String {
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        val body = response.body ?: throw IOException("空响应")
        if (truncate) {
            val source = body.source()
            source.request(maxBytes.toLong())
            val byteCount = minOf(source.buffer.size, maxBytes.toLong())
            return source.buffer.readUtf8(byteCount)
        }
        val length = body.contentLength()
        if (length > maxBytes) throw IOException("接口返回过长")
        val text = body.string()
        if (text.length > maxBytes) throw IOException("接口返回过长")
        return text
    }

    private companion object {
        const val MAX_BYTES = 2_000_000
        const val USER_AGENT = "YingShiTV/0.1 (Android TV)"
    }
}

internal fun toAsciiHttpUrl(raw: String): String {
    val trimmed = raw.trim()
    val schemeMark = trimmed.indexOf("://")
    if (schemeMark <= 0) return trimmed
    val rest = trimmed.substring(schemeMark + 3)
    val cut = listOf(rest.indexOf('/'), rest.indexOf('?'), rest.indexOf('#'))
        .filter { it >= 0 }
        .minOrNull() ?: rest.length
    val authority = rest.substring(0, cut)
    if (authority.none { it.code > 127 } || authority.startsWith("[")) return trimmed
    val at = authority.lastIndexOf('@')
    val userInfo = if (at >= 0) authority.substring(0, at + 1) else ""
    val hostPort = if (at >= 0) authority.substring(at + 1) else authority
    val colon = hostPort.lastIndexOf(':')
    val host = if (colon >= 0 && hostPort.substring(colon + 1).all { it.isDigit() }) {
        hostPort.substring(0, colon)
    } else {
        hostPort
    }
    val port = hostPort.substring(host.length)
    val asciiHost = try {
        java.net.IDN.toASCII(host)
    } catch (_: Exception) {
        return trimmed
    }
    return trimmed.substring(0, schemeMark + 3) + userInfo + asciiHost + port + rest.substring(cut)
}
