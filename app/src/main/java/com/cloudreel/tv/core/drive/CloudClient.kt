package com.cloudreel.tv.core.drive

import com.cloudreel.tv.core.network.AppHttp
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

class CloudClient(private val http: AppHttp) {
    suspend fun start(kind: String): CloudQrSession {
        return when (kind) {
            "115" -> start115()
            "quark" -> startQuark()
            "ali" -> startAli()
            "baidu" -> startBaidu()
            else -> throw IllegalArgumentException("不认识的网盘")
        }
    }

    suspend fun poll(kind: String, session: CloudQrSession): CloudPoll {
        return when (kind) {
            "115" -> poll115(session)
            "quark" -> pollQuark(session)
            "ali" -> pollAli(session)
            "baidu" -> pollBaidu(session)
            else -> throw IllegalArgumentException("不认识的网盘")
        }
    }

    suspend fun finish(kind: String, session: CloudQrSession): String {
        return when (kind) {
            "115" -> finish115(session)
            "quark" -> finishQuark(session)
            "ali" -> finishAli(session)
            "baidu" -> finishBaidu(session)
            else -> throw IllegalArgumentException("不认识的网盘")
        }
    }

    suspend fun resolve(link: CloudLink, secret: String): CloudPlayback {
        if (secret.isBlank()) throw IllegalStateException("请先在网盘页面扫码登录${link.title}")
        return when (link.kind) {
            "115" -> resolve115(link, secret)
            "quark" -> resolveQuark(link, secret)
            "ali" -> resolveAli(link, secret)
            "baidu" -> resolveBaidu(link, secret)
            else -> throw IllegalArgumentException("不认识的网盘")
        }
    }

    private fun start115(): CloudQrSession {
        val start = parsePan115QrStart(http.exchange(PAN115_QR_TOKEN, headers = pan115PlayHeaders()))
        val state = JSONObject().put("uid", start.uid).put("time", start.time).put("sign", start.sign).toString()
        return CloudQrSession(start.content, state)
    }

    private fun poll115(session: CloudQrSession): CloudPoll {
        val state = JSONObject(session.state)
        val start = Pan115QrStart(state.optString("uid"), state.optString("time"), state.optString("sign"), session.content)
        val url = "https://qrcodeapi.115.com/get/status/?uid=" + enc(start.uid) + "&time=" + enc(start.time) + "&sign=" + enc(start.sign)
        val phase = when (parsePan115QrStatus(http.exchange(url, headers = pan115PlayHeaders()))) {
            Pan115QrPhase.WAITING -> CloudPhase.WAITING
            Pan115QrPhase.SCANNED -> CloudPhase.SCANNED
            Pan115QrPhase.CONFIRMED -> CloudPhase.CONFIRMED
            Pan115QrPhase.EXPIRED -> CloudPhase.EXPIRED
            Pan115QrPhase.CANCELED -> CloudPhase.CANCELED
        }
        return CloudPoll(phase, session)
    }

    private fun finish115(session: CloudQrSession): String {
        val uid = JSONObject(session.state).optString("uid")
        val result = http.exchangeDetailed(
            url = "https://passportapi.115.com/app/1.0/web/1.0/login/qrcode",
            method = "POST",
            headers = pan115PlayHeaders(),
            body = "account=" + enc(uid),
            contentType = "application/x-www-form-urlencoded",
        )
        return parsePan115LoginCookie(result.body, result.setCookies)
    }

    private fun resolve115(link: CloudLink, secret: String): CloudPlayback {
        val body = http.exchange(
            url = "https://proapi.115.com/app/chrome/downurl",
            method = "POST",
            headers = pan115PlayHeaders() + mapOf("Cookie" to secret),
            body = pan115DownUrlBody(link.shareId),
            contentType = "application/x-www-form-urlencoded",
        )
        val file = parsePan115DownUrl(body)
        return CloudPlayback(file.name, file.sizeBytes, file.url, pan115PlayHeaders() + mapOf("Cookie" to secret))
    }

    private fun startQuark(): CloudQrSession {
        val requestId = UUID.randomUUID().toString()
        val url = q(
            "https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin",
            mapOf("client_id" to "532", "v" to "1.2", "request_id" to requestId),
        )
        return parseQuarkQrStart(http.exchange(url, headers = quarkPageHeaders()), requestId)
    }

    private fun pollQuark(session: CloudQrSession): CloudPoll {
        val state = JSONObject(session.state)
        val url = q(
            "https://uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken",
            mapOf(
                "client_id" to "532",
                "v" to "1.2",
                "token" to state.optString("token"),
                "request_id" to state.optString("request_id"),
            ),
        )
        return parseQuarkQrPoll(http.exchange(url, headers = quarkPageHeaders()), session)
    }

    private fun finishQuark(session: CloudQrSession): String {
        val ticket = JSONObject(session.state).optString("service_ticket")
        if (ticket.isBlank()) throw IllegalStateException("夸克没有返回登录信息")
        val url = q("https://pan.quark.cn/account/info", mapOf("st" to ticket, "lw" to "scan"))
        val result = http.exchangeCaptured(url, headers = quarkPageHeaders())
        val cookie = result.setCookies.firstOrNull { it.contains("=") }.orEmpty()
        if (cookie.isBlank()) throw IllegalStateException("夸克没有返回登录信息")
        return cookie
    }

    private suspend fun resolveQuark(link: CloudLink, secret: String): CloudPlayback {
        val tokenRoot = postJson(
            q("$QUARK_API/share/sharepage/token", quarkParams()),
            JSONObject().put("pwd_id", link.shareId).put("passcode", link.password).toString(),
            quarkApiHeaders(secret),
        )
        val stoken = tokenRoot.optJSONObject("data")?.optString("stoken").orEmpty()
        if (stoken.isBlank()) throw IllegalStateException(tokenRoot.optString("message").ifBlank { "夸克分享无法打开" })
        var entries = quarkEntries(link.shareId, stoken, "0", secret)
        var chosen = chooseCloudEntry(entries, link.fileName)
        if (chosen?.directory == true) {
            entries = quarkEntries(link.shareId, stoken, chosen.id, secret)
            chosen = entries.filter { !it.directory }.let { chooseCloudEntry(it, link.fileName) }
        }
        if (chosen == null || chosen.directory) throw IllegalStateException("夸克分享里没有视频")
        val savedId = saveQuark(link.shareId, stoken, chosen, secret)
        val download = postJson(
            q("$QUARK_API/file/download", quarkParams() + mapOf("sys" to "win32", "ve" to "2.5.56")),
            JSONObject().put("fids", JSONArray().put(savedId)).toString(),
            quarkApiHeaders(secret),
        )
        val file = download.optJSONArray("data")?.optJSONObject(0)
        val url = file?.optString("download_url").orEmpty()
        if (!url.startsWith("http")) throw IllegalStateException(download.optString("message").ifBlank { "夸克没有返回播放地址" })
        return CloudPlayback(
            name = file?.optString("file_name").orEmpty().ifBlank { chosen.name },
            sizeBytes = file?.optLong("size") ?: chosen.sizeBytes,
            url = url,
            headers = quarkApiHeaders(secret),
        )
    }

    private fun quarkEntries(shareId: String, stoken: String, parent: String, secret: String): List<CloudEntry> {
        val url = q(
            "$QUARK_API/share/sharepage/detail",
            quarkParams() + mapOf(
                "pwd_id" to shareId,
                "stoken" to stoken,
                "pdir_fid" to parent,
                "force" to "0",
                "_page" to "1",
                "_size" to "100",
                "_sort" to "file_type:asc,updated_at:desc",
            ),
        )
        val root = JSONObject(http.exchange(url, headers = quarkApiHeaders(secret)))
        val list = root.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                add(
                    CloudEntry(
                        id = item.optString("fid"),
                        name = item.optString("file_name").ifBlank { "未命名" },
                        sizeBytes = item.optLong("size"),
                        directory = item.optBoolean("dir") || item.optInt("dir") == 1,
                        extra = item.optString("share_fid_token"),
                    ),
                )
            }
        }
    }

    private suspend fun saveQuark(shareId: String, stoken: String, file: CloudEntry, secret: String): String {
        val body = JSONObject()
            .put("fid_list", JSONArray().put(file.id))
            .put("fid_token_list", JSONArray().put(file.extra))
            .put("to_pdir_fid", "0")
            .put("pwd_id", shareId)
            .put("stoken", stoken)
            .put("pdir_fid", "0")
            .put("scene", "link")
        val saved = postJson(q("$QUARK_API/share/sharepage/save", quarkParams()), body.toString(), quarkApiHeaders(secret))
        val taskId = saved.optJSONObject("data")?.optString("task_id").orEmpty()
        if (taskId.isBlank()) throw IllegalStateException(saved.optString("message").ifBlank { "夸克转存失败" })
        repeat(8) { attempt ->
            delay(800)
            val task = JSONObject(
                http.exchange(
                    q("$QUARK_API/task", quarkParams() + mapOf("task_id" to taskId, "retry_index" to attempt.toString())),
                    headers = quarkApiHeaders(secret),
                ),
            )
            val data = task.optJSONObject("data")
            if (data?.optInt("status") == 2) {
                val fid = data.optJSONObject("save_as")?.optJSONArray("save_as_top_fids")?.optString(0).orEmpty()
                if (fid.isNotBlank()) return fid
            }
            if (task.optInt("code") == 32003) throw IllegalStateException("夸克网盘空间不足，转存失败")
        }
        throw IllegalStateException("夸克转存超时")
    }

    private fun startAli(): CloudQrSession {
        val url = q(
            "https://passport.aliyundrive.com/newlogin/qrcode/generate.do",
            mapOf(
                "appName" to "aliyun_drive",
                "fromSite" to "52",
                "appEntrance" to "web",
                "isMobile" to "false",
                "lang" to "zh_CN",
                "_bx-v" to "2.2.3",
            ),
        )
        return parseAliQrStart(http.exchange(url, headers = aliPageHeaders()))
    }

    private fun pollAli(session: CloudQrSession): CloudPoll {
        val state = JSONObject(session.state)
        val body = listOf(
            "t" to state.optString("t"),
            "ck" to state.optString("ck"),
            "appName" to "aliyun_drive",
            "appEntrance" to "web",
            "isMobile" to "false",
            "lang" to "zh_CN",
            "fromSite" to "52",
            "navlanguage" to "zh-CN",
            "navPlatform" to "Win32",
            "navUserAgent" to PAN115_USER_AGENT,
        ).joinToString("&") { (key, value) -> "$key=${enc(value)}" }
        val url = "https://passport.aliyundrive.com/newlogin/qrcode/query.do?appName=aliyun_drive&fromSite=52&_bx-v=2.2.3"
        return parseAliQrPoll(
            http.exchange(url, "POST", aliPageHeaders(), body, "application/x-www-form-urlencoded"),
            session,
        )
    }

    private fun finishAli(session: CloudQrSession): String {
        val refresh = JSONObject(session.state).optString("refresh")
        if (refresh.isBlank()) throw IllegalStateException("阿里云盘没有返回登录信息")
        return encodeAliSecret(refreshAli(AliSecret(refresh, "", "", 0L)))
    }

    private fun resolveAli(link: CloudLink, secretRaw: String): CloudPlayback {
        val secret = refreshAli(parseAliSecret(secretRaw))
        val shareToken = parseAliShareToken(
            postJson(
                "https://api.alipan.com/v2/share_link/get_share_token",
                JSONObject().put("share_id", link.shareId).put("share_pwd", link.password).toString(),
                aliApiHeaders(secret.accessToken),
            ).toString(),
        )
        var entries = aliEntries(link.shareId, "root", secret.accessToken, shareToken)
        if (entries.isEmpty()) entries = aliAnonymous(link.shareId)
        var chosen = chooseCloudEntry(entries, link.fileName)
        if (chosen?.directory == true) {
            entries = aliEntries(link.shareId, chosen.id, secret.accessToken, shareToken)
            chosen = chooseCloudEntry(entries.filter { !it.directory }, link.fileName)
        }
        if (chosen == null || chosen.directory) throw IllegalStateException("阿里云盘分享里没有视频")
        val url = parseAliDownload(
            postJson(
                "https://api.alipan.com/v2/file/get_share_link_download_url",
                JSONObject()
                    .put("drive_id", chosen.extra.ifBlank { secret.driveId })
                    .put("file_id", chosen.id)
                    .put("share_id", link.shareId)
                    .put("expire_sec", 14400)
                    .toString(),
                aliApiHeaders(secret.accessToken, shareToken),
            ).toString(),
        )
        return CloudPlayback(
            name = chosen.name,
            sizeBytes = chosen.sizeBytes,
            url = url,
            headers = mapOf("Referer" to "https://www.alipan.com/", "User-Agent" to PAN115_USER_AGENT),
            updatedSecret = encodeAliSecret(secret),
        )
    }

    private fun refreshAli(secret: AliSecret): AliSecret {
        if (secret.accessToken.isNotBlank() && secret.expiresAt > System.currentTimeMillis()) return secret
        if (secret.refreshToken.isBlank()) throw IllegalStateException("请先在网盘页面扫码登录阿里云盘")
        val body = http.exchange(
            url = "https://auth.alipan.com/v2/account/token",
            method = "POST",
            headers = aliPageHeaders(),
            body = JSONObject().put("refresh_token", secret.refreshToken).put("grant_type", "refresh_token").toString(),
            contentType = "application/json",
        )
        return parseAliToken(body, System.currentTimeMillis())
    }

    private fun aliEntries(shareId: String, parent: String, access: String, shareToken: String): List<CloudEntry> {
        return parseAliEntries(
            postJson(
                "https://api.alipan.com/adrive/v2/file/list_by_share",
                JSONObject()
                    .put("share_id", shareId)
                    .put("parent_file_id", parent)
                    .put("limit", 100)
                    .put("order_by", "name")
                    .put("order_direction", "ASC")
                    .toString(),
                aliApiHeaders(access, shareToken),
            ).toString(),
        )
    }

    private fun aliAnonymous(shareId: String): List<CloudEntry> {
        return try {
            parseAliEntries(
                postJson(
                    "https://api.alipan.com/adrive/v3/share_link/get_share_by_anonymous",
                    JSONObject().put("share_id", shareId).toString(),
                    aliPageHeaders(),
                ).toString(),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun startBaidu(): CloudQrSession {
        val gid = UUID.randomUUID().toString().uppercase()
        val url = q(
            "https://passport.baidu.com/v2/api/getqrcode",
            mapOf("lp" to "pc", "qrloginfrom" to "pc", "gid" to gid, "apiver" to "v3", "tpl" to "netdisk"),
        )
        return parseBaiduQrStart(http.exchange(url, headers = baiduPageHeaders()), gid)
    }

    private fun pollBaidu(session: CloudQrSession): CloudPoll {
        val state = JSONObject(session.state)
        val url = q(
            "https://passport.baidu.com/channel/unicast",
            mapOf(
                "channel_id" to state.optString("sign"),
                "gid" to state.optString("gid"),
                "tpl" to "netdisk",
                "apiver" to "v3",
                "callback" to "cb",
            ),
        )
        return try {
            parseBaiduQrPoll(http.exchange(url, headers = baiduPageHeaders(), callTimeoutSeconds = 20), session)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val message = error.message.orEmpty()
            if (message.contains("timeout", ignoreCase = true) || message.contains("timed out", ignoreCase = true)) {
                CloudPoll(CloudPhase.WAITING, session)
            } else {
                throw error
            }
        }
    }

    private fun finishBaidu(session: CloudQrSession): String {
        val temp = JSONObject(session.state).optString("v")
        if (temp.isBlank()) throw IllegalStateException("百度网盘没有返回登录信息")
        val now = System.currentTimeMillis()
        val url = q(
            "https://passport.baidu.com/v3/login/main/qrbdusslogin",
            mapOf(
                "v" to now.toString(),
                "bduss" to temp,
                "u" to "https://pan.baidu.com/",
                "loginVersion" to "v4",
                "qrcode" to "1",
                "tpl" to "netdisk",
                "apiver" to "v3",
                "tt" to now.toString(),
                "time" to (now / 1000).toString(),
                "alg" to "v3",
            ),
        )
        val result = http.exchangeCaptured(url, headers = baiduPageHeaders())
        val cookie = result.setCookies.firstOrNull { it.contains("BDUSS=") }.orEmpty()
        if (!cookie.contains("BDUSS=")) throw IllegalStateException("百度网盘没有返回登录信息")
        return cookie
    }

    private fun resolveBaidu(link: CloudLink, secret: String): CloudPlayback {
        var cookie = secret
        if (link.password.isNotBlank()) {
            val verified = http.exchangeDetailed(
                url = q(
                    "https://pan.baidu.com/share/verify",
                    mapOf(
                        "surl" to link.shareId,
                        "t" to System.currentTimeMillis().toString(),
                        "channel" to "chunlei",
                        "web" to "1",
                        "app_id" to "250528",
                        "clienttype" to "0",
                    ),
                ),
                method = "POST",
                headers = baiduPageHeaders() + mapOf("Cookie" to cookie),
                body = "pwd=${enc(link.password)}&vcode=&vcode_str=",
                contentType = "application/x-www-form-urlencoded",
            )
            val randsk = JSONObject(verified.body).optString("randsk")
            val fromHeader = verified.setCookies.firstOrNull { it.substringBefore('=').equals("BDCLND", true) }
            val bdclnd = fromHeader?.substringBefore(';')?.substringAfter('=')?.takeIf { it.isNotBlank() } ?: randsk
            if (bdclnd.isNotBlank() && !cookie.contains("BDCLND=")) cookie = "$cookie; BDCLND=$bdclnd"
        }
        val listed = http.exchange(
            q(
                "https://pan.baidu.com/share/list",
                mapOf(
                    "web" to "1",
                    "app_id" to "250528",
                    "channel" to "chunlei",
                    "clienttype" to "0",
                    "shorturl" to link.shareId,
                    "root" to "1",
                    "page" to "1",
                    "num" to "100",
                    "order" to "time",
                    "desc" to "1",
                ),
            ),
            headers = baiduPlayHeaders(cookie),
        )
        var entries = parseBaiduEntries(listed)
        var chosen = chooseCloudEntry(entries, link.fileName)
        if (chosen?.directory == true) {
            val nested = http.exchange(
                q(
                    "https://pan.baidu.com/share/list",
                    mapOf(
                        "web" to "1",
                        "app_id" to "250528",
                        "channel" to "chunlei",
                        "clienttype" to "0",
                        "shorturl" to link.shareId,
                        "dir" to "/" + chosen.name.trim('/'),
                        "root" to "0",
                        "page" to "1",
                        "num" to "100",
                    ),
                ),
                headers = baiduPlayHeaders(cookie),
            )
            entries = parseBaiduEntries(nested)
            chosen = chooseCloudEntry(entries.filter { !it.directory }, link.fileName)
        }
        if (chosen == null || chosen.directory || !chosen.extra.startsWith("http")) {
            throw IllegalStateException("百度网盘没有返回播放地址")
        }
        return CloudPlayback(chosen.name, chosen.sizeBytes, chosen.extra, baiduPlayHeaders(cookie))
    }

    private fun postJson(url: String, json: String, headers: Map<String, String>): JSONObject {
        return JSONObject(http.exchange(url, "POST", headers, json, "application/json"))
    }

    private fun quarkParams(): Map<String, String> {
        return mapOf(
            "pr" to "ucpro",
            "fr" to "pc",
            "uc_param_str" to "",
            "__t" to System.currentTimeMillis().toString(),
        )
    }

    private fun quarkPageHeaders(): Map<String, String> = mapOf(
        "User-Agent" to PAN115_USER_AGENT,
        "Referer" to "https://pan.quark.cn/",
        "Origin" to "https://pan.quark.cn",
    )

    private fun quarkApiHeaders(cookie: String): Map<String, String> = quarkPageHeaders() + mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Cookie" to cookie,
    )

    private fun aliPageHeaders(): Map<String, String> = mapOf(
        "User-Agent" to PAN115_USER_AGENT,
        "Referer" to "https://www.alipan.com/",
        "Origin" to "https://www.alipan.com",
    )

    private fun aliApiHeaders(access: String, shareToken: String = ""): Map<String, String> {
        val headers = aliPageHeaders().toMutableMap()
        headers["X-Canary"] = "client=web,app=share,version=v2.3.1"
        if (access.isNotBlank()) headers["Authorization"] = "Bearer $access"
        if (shareToken.isNotBlank()) headers["x-share-token"] = shareToken
        return headers
    }

    private fun baiduPageHeaders(): Map<String, String> = mapOf(
        "User-Agent" to PAN115_USER_AGENT,
        "Referer" to "https://pan.baidu.com/",
    )

    private fun baiduPlayHeaders(cookie: String): Map<String, String> = mapOf(
        "User-Agent" to "pan.baidu.com",
        "Referer" to "https://pan.baidu.com/",
        "Cookie" to cookie,
    )

    private fun q(base: String, params: Map<String, String>): String {
        val query = params.entries.joinToString("&") { (key, value) -> "$key=${enc(value)}" }
        return if (base.contains('?')) "$base&$query" else "$base?$query"
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val PAN115_QR_TOKEN = "https://qrcodeapi.115.com/api/1.0/web/1.0/token"
        const val QUARK_API = "https://drive-pc.quark.cn/1/clouddrive"
    }
}
