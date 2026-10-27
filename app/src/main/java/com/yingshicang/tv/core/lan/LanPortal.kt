package com.yingshicang.tv.core.lan

import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class LanStatus(
    val running: Boolean = false,
    val url: String? = null,
    val message: String? = null,
    val error: String? = null,
)

class LanPortal(
    private val onImport: (String) -> String,
) {
    private val status = MutableStateFlow(LanStatus())
    val state: StateFlow<LanStatus> = status.asStateFlow()
    private var server: ServerSocket? = null
    private var thread: Thread? = null
    private var token: String = ""

    fun start() {
        if (status.value.running) return
        val address = lanIpv4()
        if (address == null) {
            status.value = LanStatus(error = "没有找到局域网地址，请先连上 Wi-Fi")
            return
        }
        token = randomToken()
        try {
            val socket = ServerSocket(PORT, 8)
            server = socket
            thread = Thread({
                while (!socket.isClosed) {
                    try {
                        socket.accept().use { client -> handle(client) }
                    } catch (_: Exception) {
                        if (socket.isClosed) break
                    }
                }
            }, "yingshi-lan").also { it.isDaemon = true; it.start() }
            status.value = LanStatus(running = true, url = "http://$address:$PORT/?token=$token")
        } catch (error: Exception) {
            status.value = LanStatus(error = "9978 端口无法打开")
        }
    }

    fun stop() {
        server?.close()
        server = null
        thread = null
        token = ""
        status.value = LanStatus(message = "已关闭手机提交")
    }

    private fun handle(client: Socket) {
        client.soTimeout = 8_000
        val input = client.getInputStream()
        val headerBytes = readUntilHeaderEnd(input)
        val headerText = headerBytes.toString(Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        val request = lines.firstOrNull().orEmpty().split(" ")
        val method = request.getOrNull(0).orEmpty()
        val target = request.getOrNull(1).orEmpty()
        val path = target.substringBefore("?")
        val queryToken = queryValue(target, "token")
        val headers = lines.drop(1).mapNotNull { line ->
            val index = line.indexOf(':')
            if (index <= 0) null else line.substring(0, index).trim().lowercase() to line.substring(index + 1).trim()
        }.toMap()
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > MAX_BODY) {
            write(client, 413, """{"ok":false,"message":"内容太大"}""", "application/json; charset=utf-8")
            return
        }
        val body = if (length > 0) readCount(input, length).toString(Charsets.UTF_8) else ""
        when {
            method == "GET" && path == "/health" -> write(client, 200, """{"ok":true}""", "application/json; charset=utf-8")
            method == "GET" && (path == "/" || path == "/index") && queryToken == token && token.isNotEmpty() ->
                write(client, 200, lanPage(token), "text/html; charset=utf-8")
            method == "POST" && path == "/upload" && queryToken == token && token.isNotEmpty() -> {
                val result = try {
                    onImport(body)
                } catch (error: Exception) {
                    val message = error.message?.take(80).orEmpty().ifBlank { "提交失败" }
                    write(client, 400, JSONObject().put("ok", false).put("message", message).toString(), "application/json; charset=utf-8")
                    status.value = status.value.copy(message = message)
                    return
                }
                status.value = status.value.copy(message = result)
                write(client, 200, JSONObject().put("ok", true).put("message", result).toString(), "application/json; charset=utf-8")
            }
            else -> write(client, 403, """{"ok":false,"message":"口令不正确"}""", "application/json; charset=utf-8")
        }
    }

    private companion object {
        const val PORT = 9978
        const val MAX_BODY = 1_000_000
    }
}

internal fun lanIpv4(): String? {
    val addresses = NetworkInterface.getNetworkInterfaces().toList().flatMap { nif ->
        if (!nif.isUp || nif.isLoopback) emptyList() else nif.inetAddresses.toList()
    }.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.filter { it != "127.0.0.1" }
    return addresses.firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
        ?: addresses.firstOrNull()
}

private fun randomToken(): String {
    val bytes = ByteArray(8)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

private fun queryValue(target: String, name: String): String? {
    val query = target.substringAfter("?", "")
    return query.split("&").firstNotNullOfOrNull { part ->
        val pieces = part.split("=", limit = 2)
        if (pieces.getOrNull(0) == name) pieces.getOrNull(1)?.replace("+", " ") else null
    }
}

private fun readUntilHeaderEnd(input: java.io.InputStream): ByteArray {
    val buffer = ByteArrayOutputStream()
    var matched = 0
    while (buffer.size() < 16_384) {
        val current = input.read()
        if (current == -1) break
        buffer.write(current)
        matched = when {
            matched == 0 && current == '\r'.code -> 1
            matched == 1 && current == '\n'.code -> 2
            matched == 2 && current == '\r'.code -> 3
            matched == 3 && current == '\n'.code -> return buffer.toByteArray()
            current == '\r'.code -> 1
            else -> 0
        }
    }
    return buffer.toByteArray()
}

private fun readCount(input: java.io.InputStream, count: Int): ByteArray {
    val buffer = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val read = input.read(buffer, offset, count - offset)
        if (read < 0) break
        offset += read
    }
    return if (offset == count) buffer else buffer.copyOf(offset)
}

private fun write(client: Socket, code: Int, body: String, contentType: String) {
    val payload = body.toByteArray(Charsets.UTF_8)
    val reason = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        403 -> "Forbidden"
        413 -> "Payload Too Large"
        else -> "Error"
    }
    val head = "HTTP/1.1 $code $reason\r\nContent-Type: $contentType\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
    val output = client.getOutputStream()
    output.write(head.toByteArray(Charsets.ISO_8859_1))
    output.write(payload)
    output.flush()
}
