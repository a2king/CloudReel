package com.cloudreel.tv.core.drive

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

data class CloudLink(
    val kind: String,
    val title: String,
    val shareId: String,
    val password: String,
    val fileName: String,
    val quality: String,
)

data class CloudQrSession(
    val content: String,
    val state: String,
)

enum class CloudPhase {
    WAITING,
    SCANNED,
    CONFIRMED,
    EXPIRED,
    CANCELED,
}

data class CloudPoll(
    val phase: CloudPhase,
    val session: CloudQrSession,
)

data class CloudEntry(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val directory: Boolean,
    val extra: String = "",
)

data class CloudPlayback(
    val name: String,
    val sizeBytes: Long,
    val url: String,
    val headers: Map<String, String>,
    val updatedSecret: String? = null,
)

data class AliSecret(
    val refreshToken: String,
    val accessToken: String,
    val driveId: String,
    val expiresAt: Long,
)

private val videoExtensions = setOf("mp4", "mkv", "avi", "ts", "flv", "mov", "wmv", "rmvb", "webm", "m4v", "mpg", "mpeg", "m3u8")
private val codeInText = Regex("(?:提取码|密码)\\s*[:：]?\\s*([A-Za-z0-9]{4})")

fun recognizeCloud(lineName: String, episodeName: String, url: String): CloudLink? {
    val pan = recognizePan115(lineName, episodeName, url)
    if (pan != null) {
        return CloudLink("115", "115 网盘", pan.pickCode, "", pan.fileName, pan.quality)
    }
    val raw = url.trim()
    if (!raw.startsWith("http://") && !raw.startsWith("https://")) return null
    val kind = shareKind(raw) ?: return null
    val (shareId, password) = parseShareTarget(raw, episodeName)
    if (shareId.isBlank()) return null
    return CloudLink(kind, cloudTitle(kind), shareId, password, episodeName.trim().ifBlank { "网盘文件" }, "分享")
}

fun lineCloud(lineName: String, episodes: List<Pair<String, String>>): CloudLink? {
    return episodes.firstNotNullOfOrNull { (name, url) -> recognizeCloud(lineName, name, url) }
}

fun cloudAccountLabel(title: String, loggedIn: Boolean): String {
    return if (loggedIn) "$title · 已登录" else "$title · 未登录"
}

fun cloudTitle(kind: String): String = when (kind) {
    "115" -> "115 网盘"
    "quark" -> "夸克网盘"
    "ali" -> "阿里云盘"
    "baidu" -> "百度网盘"
    else -> "网盘"
}

fun parseShareTarget(url: String, episodeName: String): Pair<String, String> {
    val trimmed = url.trim()
    val passwordFromTail = trimmed.substringAfter('|', "").trim().takeIf { it.matches(Regex("[A-Za-z0-9]{4,8}")) }.orEmpty()
    val bare = trimmed.substringBefore('|')
    val uri = try {
        URI(bare)
    } catch (_: Exception) {
        return "" to passwordFromTail
    }
    val query = uri.rawQuery.orEmpty()
    val queryPwd = queryValue(query, "pwd").ifBlank { queryValue(query, "password") }
    val textPwd = codeInText.find(episodeName)?.groupValues?.getOrNull(1).orEmpty()
    val password = queryPwd.ifBlank { passwordFromTail }.ifBlank { textPwd }
    val path = uri.path.orEmpty()
    val marker = path.indexOf("/s/")
    val id = if (marker >= 0) path.substring(marker + 3).trim('/') .substringBefore('/') else ""
    val shareId = if (shareKind(bare) == "baidu") baiduSurl(id) else id
    return shareId to password
}

fun baiduSurl(id: String): String = if (id.startsWith("1") && id.length > 1) id.substring(1) else id

fun isVideoName(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in videoExtensions
}

fun chooseCloudEntry(entries: List<CloudEntry>, hint: String): CloudEntry? {
    val files = entries.filter { !it.directory && (isVideoName(it.name) || it.extra == "video") }
    val wanted = hint.trim()
    if (wanted.isNotBlank() && wanted != "正片") {
        files.firstOrNull { it.name.contains(wanted) || wanted.contains(it.name) }?.let { return it }
    }
    if (files.isNotEmpty()) return files.maxBy { it.sizeBytes }
    return entries.firstOrNull { it.directory }
}

fun parseQuarkQrStart(body: String, requestId: String): CloudQrSession {
    val root = JSONObject(body.trim())
    val token = root.optJSONObject("data")?.optJSONObject("members")?.optString("token").orEmpty().trim()
    if (root.optInt("status") != 2_000_000 || token.isEmpty()) {
        throw IllegalStateException(root.optString("message").ifBlank { "夸克没有返回二维码" })
    }
    val state = JSONObject().put("token", token).put("request_id", requestId).toString()
    return CloudQrSession("https://su.quark.cn/4_eMHBJ?token=$token&client_id=532&ssb=weblogin", state)
}

fun parseQuarkQrPoll(body: String, session: CloudQrSession): CloudPoll {
    val root = JSONObject(body.trim())
    val ticket = root.optJSONObject("data")?.optJSONObject("members")?.optString("service_ticket").orEmpty().trim()
    if (root.optInt("status") == 2_000_000 && ticket.isNotEmpty()) {
        val state = JSONObject(session.state).put("service_ticket", ticket)
        return CloudPoll(CloudPhase.CONFIRMED, session.copy(state = state.toString()))
    }
    val message = root.optString("message")
    val phase = when {
        message.contains("过期") -> CloudPhase.EXPIRED
        message.contains("取消") -> CloudPhase.CANCELED
        message.contains("确认") || message.contains("已扫") -> CloudPhase.SCANNED
        else -> CloudPhase.WAITING
    }
    return CloudPoll(phase, session)
}

fun parseAliQrStart(body: String): CloudQrSession {
    val data = JSONObject(body.trim()).optJSONObject("content")?.optJSONObject("data")
        ?: throw IllegalStateException("阿里云盘没有返回二维码")
    val content = data.optString("codeContent").trim()
    val ticket = when (val raw = data.opt("t")) {
        is Number -> raw.toLong().toString()
        else -> data.optString("t")
    }
    val ck = data.optString("ck")
    if (content.isBlank() || ticket.isBlank() || ck.isBlank()) throw IllegalStateException("阿里云盘没有返回二维码")
    val state = JSONObject().put("t", ticket).put("ck", ck).toString()
    return CloudQrSession(content, state)
}

fun parseAliQrPoll(body: String, session: CloudQrSession): CloudPoll {
    val content = JSONObject(body.trim()).optJSONObject("content")
    val data = content?.optJSONObject("data")
    if (content?.optBoolean("success", true) == false && data == null) {
        throw IllegalStateException(content.optString("message").ifBlank { "阿里云盘二维码状态获取失败" })
    }
    return when (data?.optString("qrCodeStatus").orEmpty()) {
        "SCANED", "SCANNED" -> CloudPoll(CloudPhase.SCANNED, session)
        "CONFIRMED" -> {
            val refresh = decodeAliRefresh(data?.optString("bizExt").orEmpty())
            val state = JSONObject(session.state).put("refresh", refresh)
            CloudPoll(CloudPhase.CONFIRMED, session.copy(state = state.toString()))
        }
        "EXPIRED" -> CloudPoll(CloudPhase.EXPIRED, session)
        "CANCELED", "CANCEL" -> CloudPoll(CloudPhase.CANCELED, session)
        else -> CloudPoll(CloudPhase.WAITING, session)
    }
}

fun parseAliSecret(raw: String): AliSecret {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("{")) return AliSecret(trimmed, "", "", 0L)
    val root = JSONObject(trimmed)
    return AliSecret(
        refreshToken = root.optString("refresh_token"),
        accessToken = root.optString("access_token"),
        driveId = root.optString("drive_id"),
        expiresAt = root.optLong("expires_at"),
    )
}

fun encodeAliSecret(secret: AliSecret): String {
    return JSONObject()
        .put("refresh_token", secret.refreshToken)
        .put("access_token", secret.accessToken)
        .put("drive_id", secret.driveId)
        .put("expires_at", secret.expiresAt)
        .toString()
}

fun parseAliToken(body: String, nowMs: Long): AliSecret {
    val root = JSONObject(body.trim())
    val message = root.optString("message").ifBlank { root.optString("msg") }
    if (root.optString("code").isNotBlank() && root.optString("access_token").isBlank()) {
        throw IllegalStateException(message.ifBlank { "阿里云盘登录已失效" })
    }
    val access = root.optString("access_token")
    val refresh = root.optString("refresh_token")
    if (access.isBlank() || refresh.isBlank()) throw IllegalStateException(message.ifBlank { "阿里云盘没有返回登录信息" })
    val expires = nowMs + root.optLong("expires_in", 7200L) * 1000L - 60_000L
    return AliSecret(refresh, access, root.optString("default_drive_id"), expires)
}

fun parseAliShareToken(body: String): String {
    val root = JSONObject(body.trim())
    val token = root.optString("share_token").ifBlank { root.optJSONObject("data")?.optString("share_token").orEmpty() }
    if (token.isBlank()) throw IllegalStateException(root.optString("message").ifBlank { "阿里云盘分享无法打开" })
    return token
}

fun parseAliEntries(body: String): List<CloudEntry> {
    val root = JSONObject(body.trim())
    if (root.optString("code").isNotBlank() && !root.has("items") && !root.has("file_infos")) {
        throw IllegalStateException(root.optString("message").ifBlank { "阿里云盘没有返回文件" })
    }
    val array = root.optJSONArray("items")
        ?: root.optJSONArray("file_infos")
        ?: root.optJSONArray("file_list")
        ?: JSONArray()
    return jsonEntries(array, "file_id", "name", "file_name", "type", "size", "drive_id")
}

fun parseAliDownload(body: String): String {
    val root = JSONObject(body.trim())
    val url = root.optString("download_url").ifBlank { root.optString("url") }
    if (!url.startsWith("http")) throw IllegalStateException(root.optString("message").ifBlank { "阿里云盘没有返回播放地址" })
    return url
}

fun parseBaiduQrStart(body: String, gid: String): CloudQrSession {
    val root = JSONObject(body.trim())
    if (root.optInt("errno", -1) != 0) throw IllegalStateException("百度网盘没有返回二维码")
    val sign = root.optString("sign").trim()
    if (sign.isEmpty()) throw IllegalStateException("百度网盘没有返回二维码")
    val state = JSONObject().put("sign", sign).put("gid", gid).toString()
    return CloudQrSession("https://wappass.baidu.com/wp/?qrlogin&sign=$sign&tpl=netdisk", state)
}

fun parseBaiduQrPoll(body: String, session: CloudQrSession): CloudPoll {
    val payload = body.trim().substringAfter('(').substringBeforeLast(')')
    if (payload.isBlank() || !payload.startsWith("{")) return CloudPoll(CloudPhase.WAITING, session)
    val root = JSONObject(payload)
    if (root.optInt("errno", 1) != 0) return CloudPoll(CloudPhase.WAITING, session)
    val channel = root.optString("channel_v")
    if (channel.isBlank()) return CloudPoll(CloudPhase.WAITING, session)
    val inner = JSONObject(channel)
    return when (inner.optInt("status", 1)) {
        0 -> {
            val temp = inner.optString("v")
            if (temp.isBlank()) CloudPoll(CloudPhase.WAITING, session)
            else CloudPoll(CloudPhase.CONFIRMED, session.copy(state = JSONObject(session.state).put("v", temp).toString()))
        }
        2 -> CloudPoll(CloudPhase.CANCELED, session)
        else -> CloudPoll(CloudPhase.WAITING, session)
    }
}

fun parseBaiduEntries(body: String): List<CloudEntry> {
    val root = JSONObject(body.trim())
    if (root.optInt("errno", -1) != 0) {
        throw IllegalStateException(root.optString("show_msg").ifBlank { root.optString("errmsg") }.ifBlank { "百度网盘分享无法打开" })
    }
    return jsonEntries(root.optJSONArray("list") ?: JSONArray(), "fs_id", "server_filename", "server_filename", "isdir", "size", "dlink")
}

private fun shareKind(url: String): String? {
    val host = try {
        URI(url.substringBefore('|')).host.orEmpty().lowercase()
    } catch (_: Exception) {
        return null
    }
    return when {
        host.contains("quark.cn") -> "quark"
        host.contains("alipan.com") || host.contains("aliyundrive.com") -> "ali"
        host.contains("pan.baidu.com") || host.contains("yun.baidu.com") -> "baidu"
        else -> null
    }
}

private fun queryValue(query: String, key: String): String {
    return query.split('&').firstNotNullOfOrNull { part ->
        val pieces = part.split('=', limit = 2)
        if (pieces.size == 2 && pieces[0] == key) pieces[1] else null
    }.orEmpty()
}

private fun jsonEntries(
    array: JSONArray,
    idKey: String,
    nameKey: String,
    altNameKey: String,
    typeKey: String,
    sizeKey: String,
    extraKey: String,
): List<CloudEntry> {
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString(nameKey).ifBlank { item.optString(altNameKey) }.ifBlank { "未命名" }
            val type = item.opt(typeKey)
            val directory = type == true || type == 1 || type == "1" || type == "folder"
            val extra = when (val raw = item.opt(extraKey)) {
                is String -> raw
                is Number -> raw.toString()
                else -> ""
            }
            add(
                CloudEntry(
                    id = item.optString(idKey).ifBlank { extra },
                    name = name,
                    sizeBytes = item.optLong(sizeKey),
                    directory = directory,
                    extra = if (extraKey == "dlink" || extraKey == "drive_id") extra else "",
                ),
            )
        }
    }
}

private fun decodeAliRefresh(bizExt: String): String {
    val text = bizExt.trim()
    if (text.isEmpty()) throw IllegalStateException("阿里云盘没有返回登录信息")
    val json = if (text.startsWith("{")) text else decodeBase64Text(text) ?: text
    val root = try {
        JSONObject(json)
    } catch (_: Exception) {
        throw IllegalStateException("阿里云盘没有返回登录信息")
    }
    val nested = root.optJSONObject("pds_login_result")
    val refresh = nested?.optString("refreshToken").orEmpty().ifBlank { root.optString("refreshToken") }
    if (refresh.isBlank()) throw IllegalStateException("阿里云盘没有返回登录信息")
    return refresh
}

private fun decodeBase64Text(input: String): String? {
    val payload = input.trim().replace('-', '+').replace('_', '/').trimEnd('=')
    if (payload.isEmpty() || payload.any { BASE64.indexOf(it) < 0 }) return null
    val out = ArrayList<Byte>(payload.length * 3 / 4)
    var buffer = 0
    var bits = 0
    payload.forEach { char ->
        buffer = (buffer shl 6) or BASE64.indexOf(char)
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out += ((buffer shr bits) and 0xFF).toByte()
        }
    }
    return out.toByteArray().toString(Charsets.UTF_8)
}

private const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
