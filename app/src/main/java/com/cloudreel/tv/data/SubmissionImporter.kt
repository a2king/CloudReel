package com.cloudreel.tv.data

import android.content.Context
import android.util.Base64
import com.cloudreel.tv.core.catalog.cmsProbeUrl
import com.cloudreel.tv.core.catalog.isCmsSite
import com.cloudreel.tv.core.catalog.isScriptAsset
import com.cloudreel.tv.core.catalog.isTvBoxLineIndex
import com.cloudreel.tv.core.catalog.isUsableCmsPayload
import com.cloudreel.tv.core.catalog.hasScrapeableCms
import com.cloudreel.tv.core.catalog.parseTvBoxDocument
import com.cloudreel.tv.core.catalog.resolveTvBoxUrl
import com.cloudreel.tv.core.catalog.TvBoxLive
import com.cloudreel.tv.core.database.AppDao
import com.cloudreel.tv.core.database.DriveAccountEntity
import com.cloudreel.tv.core.database.LivePlaylistEntity
import com.cloudreel.tv.core.database.SourceConfigEntity
import com.cloudreel.tv.core.drive.normalizeEndpoint
import com.cloudreel.tv.core.lan.Submission
import com.cloudreel.tv.core.lan.parseSubmission
import com.cloudreel.tv.core.live.parseLiveDocument
import com.cloudreel.tv.core.model.DriveKinds
import com.cloudreel.tv.core.model.SourceKinds
import com.cloudreel.tv.core.network.AppHttp
import com.cloudreel.tv.core.script.ScriptRunner
import com.cloudreel.tv.core.security.SecretBox
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class SubmissionImporter(
    private val context: Context,
    private val dao: AppDao,
    private val http: AppHttp,
    private val secretBox: SecretBox,
    private val runner: ScriptRunner,
) {
    suspend fun import(body: String): String {
        return when (val submission = parseSubmission(body)) {
            is Submission.Cms -> addCms(submission)
            is Submission.Live -> addLive(submission)
            is Submission.Drive -> addDrive(submission)
            is Submission.Script -> addScript(submission)
        }
    }

    suspend fun addCms(
        name: String,
        url: String,
        onProgress: suspend (String) -> Unit = {},
    ): String {
        val rootUrl = requireHttpUrl(url)
        val existing = dao.allSources().toMutableList()
        val existingLocations = existing
            .filter { it.kind == SourceKinds.CMS }
            .map { it.location.trimEnd('/') }
            .toMutableSet()
        val discovered = discoverCmsSources(rootUrl, name)
        if (discovered.lines.isNotEmpty()) {
            return saveLines(existing, discovered)
        }
        if (discovered.sources.isEmpty() && discovered.lives.isEmpty()) {
            throw IllegalArgumentException(discovered.emptyMessage())
        }
        val fresh = discovered.sources.filter { it.url.trimEnd('/') !in existingLocations }
        if (fresh.isEmpty() && discovered.lives.isEmpty()) {
            throw IllegalArgumentException("这些接口已经添加过")
        }

        val usable = if (fresh.isEmpty()) {
            emptyList()
        } else {
            onProgress("正在检查可用源 0/${fresh.size}")
            keepUsableSources(fresh, onProgress)
        }
        onProgress(if (discovered.lives.isEmpty()) "正在保存" else "正在读取直播源")
        val liveCount = importRemoteLives(discovered.lives)
        if (usable.isEmpty() && liveCount == 0) {
            throw IllegalArgumentException(
                when {
                    discovered.sources.isEmpty() -> discovered.emptyMessage()
                    fresh.size == 1 -> "这个接口没有返回可用片单"
                    else -> "这些接口都没有返回可用片单"
                },
            )
        }

        var priority = nextPriority(existing)
        var added = 0
        usable.forEach { candidate ->
            val location = candidate.url.trimEnd('/')
            if (!existingLocations.add(location)) return@forEach
            val sourceName = candidate.name.trim().take(30).ifBlank { "CMS 接口" }
            dao.upsertSource(
                SourceConfigEntity(
                    id = "cms_" + UUID.randomUUID().toString().take(8),
                    name = sourceName,
                    kind = SourceKinds.CMS,
                    location = location,
                    enabled = true,
                    priority = priority,
                    builtin = false,
                ),
            )
            priority += 10
            added += 1
        }
        if (added == 0 && liveCount == 0) throw IllegalArgumentException("这些接口已经添加过")
        val skipped = fresh.size - usable.size
        val cmsMessage = when {
            added == 0 -> null
            skipped > 0 -> "已添加 $added 个可用数据源，跳过 $skipped 个失效源"
            added == 1 -> "已添加数据源「${usable.first().name}」"
            else -> "已添加 $added 个可用数据源"
        }
        val liveMessage = if (liveCount > 0) "已导入 $liveCount 个直播源" else null
        return listOfNotNull(cmsMessage, liveMessage).joinToString("，")
    }

    private suspend fun addCms(submission: Submission.Cms): String = addCms(submission.name, submission.url)

    private suspend fun discoverCmsSources(rootUrl: String, rootName: String): Discovery {
        val results = linkedMapOf<String, DiscoveredCmsSource>()
        val lines = linkedMapOf<String, DiscoveredLine>()
        val lives = linkedMapOf<String, TvBoxLive>()
        var sawScriptSites = false
        val visited = mutableSetOf<String>()

        fun rememberLives(base: String, found: List<TvBoxLive>) {
            found.forEach { live ->
                val url = resolveTvBoxUrl(base, live.url) ?: return@forEach
                if (!url.startsWith("http://") && !url.startsWith("https://")) return@forEach
                val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
                if (host == "127.0.0.1" || host.equals("localhost", ignoreCase = true)) return@forEach
                lives.putIfAbsent(url, live.copy(url = url))
            }
        }

        suspend fun visit(url: String, nameHint: String, depth: Int) {
            if (depth > MAX_TVBOX_DEPTH || results.size >= MAX_TVBOX_SOURCES) return
            val normalized = url.trimEnd('/')
            if (!visited.add(normalized)) return

            val text = try {
                http.getConfig(normalized)
            } catch (error: Exception) {
                if (depth == 0) throw error
                return
            }
            val document = try {
                parseTvBoxDocument(text)
            } catch (error: Exception) {
                if (depth == 0) throw error
                return
            }
            if (document.isCmsResponse) {
                results.putIfAbsent(normalized, DiscoveredCmsSource(nameHint, normalized))
            }
            rememberLives(normalized, document.lives)
            if (depth == 0 && isTvBoxLineIndex(document)) {
                document.remoteSources.forEach { remote ->
                    val child = resolveTvBoxUrl(normalized, remote.url) ?: return@forEach
                    lines.putIfAbsent(child.trimEnd('/'), DiscoveredLine(remote.name, child.trim()))
                }
                collectChildLives(lines.values.toList(), ::rememberLives)
                return
            }
            document.sites.forEach { site ->
                if (!isCmsSite(site)) {
                    sawScriptSites = true
                    return@forEach
                }
                if (results.size >= MAX_TVBOX_SOURCES) return@forEach
                val api = resolveTvBoxUrl(normalized, site.api) ?: return@forEach
                if (isScriptAsset(api)) return@forEach
                results.putIfAbsent(api.trimEnd('/'), DiscoveredCmsSource(site.name, api))
            }
            document.remoteSources.forEach { remote ->
                if (results.size >= MAX_TVBOX_SOURCES) return@forEach
                val child = resolveTvBoxUrl(normalized, remote.url) ?: return@forEach
                visit(child, remote.name.ifBlank { rootName }, depth + 1)
            }
        }

        visit(rootUrl, rootName.trim().take(30).ifBlank { "CMS 接口" }, 0)
        return Discovery(results.values.toList(), lines.values.toList(), lives.values.toList(), sawScriptSites)
    }

    internal suspend fun importLivesAt(configUrl: String): Int? {
        val found = try {
            parseTvBoxDocument(http.getConfig(configUrl)).lives
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }
        val resolved = found.mapNotNull { live -> resolvedLive(configUrl, live) }
        return importRemoteLives(resolved)
    }

    private suspend fun collectChildLives(
        lines: List<DiscoveredLine>,
        remember: (String, List<TvBoxLive>) -> Unit,
    ) {
        if (lines.isEmpty()) return
        val gate = Semaphore(4)
        val found = coroutineScope {
            lines.map { line ->
                async {
                    gate.withPermit {
                        try {
                            line.url to parseTvBoxDocument(http.getConfig(line.url)).lives
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            }.awaitAll()
        }
        found.forEach { pair ->
            if (pair != null) remember(pair.first, pair.second)
        }
    }

    private fun resolvedLive(base: String, live: TvBoxLive): TvBoxLive? {
        val url = resolveTvBoxUrl(base, live.url) ?: return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
        if (host == "127.0.0.1" || host.equals("localhost", ignoreCase = true)) return null
        return live.copy(url = url)
    }

    internal suspend fun lineCanScrape(configUrl: String): Boolean {
        val text = http.getConfig(configUrl)
        return hasScrapeableCms(parseTvBoxDocument(text), configUrl)
    }

    internal suspend fun firstPlayableCms(configUrl: String): String {
        val text = http.getConfig(configUrl)
        val document = parseTvBoxDocument(text)
        val direct = configUrl.trimEnd('/')
        if (document.isCmsResponse && document.sites.none { isCmsSite(it) } && document.remoteSources.isEmpty()) {
            if (probeCms(direct)) return direct
            throw IllegalStateException("这个接口没有返回可用片单")
        }
        val apis = document.sites.mapNotNull { site ->
            if (!isCmsSite(site)) return@mapNotNull null
            val api = resolveTvBoxUrl(configUrl, site.api) ?: return@mapNotNull null
            if (isScriptAsset(api)) null else api.trimEnd('/')
        }.distinct().take(16)
        if (apis.isEmpty()) {
            throw IllegalStateException(
                if (document.remoteSources.isNotEmpty()) {
                    "这条线路下面还有多份配置，暂时不能直接播放"
                } else {
                    "这条线路是脚本仓，没有采集接口，首页不能直接刮削"
                },
            )
        }
        val usable = keepUsableSources(apis.map { DiscoveredCmsSource("", it) }) { }
            .map { it.url.trimEnd('/') }
            .toSet()
        return apis.firstOrNull { it in usable }
            ?: throw IllegalStateException("这条线路里的采集接口都没有返回片单")
    }

    private suspend fun saveLines(existing: List<SourceConfigEntity>, discovered: Discovery): String {
        var priority = nextPriority(existing)
        var added = 0
        discovered.lines.forEach { line ->
            val location = line.url.trimEnd('/')
            val id = "tvbox_" + location.hashCode().toUInt().toString(16)
            val previous = existing.find { it.id == id }
            if (previous == null) {
                added += 1
                priority += 10
            }
            dao.upsertSource(
                SourceConfigEntity(
                    id = id,
                    name = line.name.trim().take(40).ifBlank { "线路" },
                    kind = SourceKinds.TVBOX,
                    location = location,
                    enabled = previous?.enabled ?: true,
                    priority = previous?.priority ?: (priority - 10),
                    builtin = false,
                ),
            )
        }
        val liveCount = importRemoteLives(discovered.lives)
        if (added == 0 && liveCount == 0) throw IllegalArgumentException("这些线路已经添加过")
        val lineMessage = if (added > 0) "已添加 $added 条线路" else null
        val liveMessage = if (liveCount > 0) "已导入 $liveCount 个直播源" else null
        return listOfNotNull(lineMessage, liveMessage).joinToString("，")
    }

    private suspend fun importRemoteLives(lives: List<TvBoxLive>): Int {
        var added = 0
        lives.distinctBy { it.url }.forEach { live ->
            val content = try {
                http.getLimited(live.url, "直播地址无法读取")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@forEach
            }
            val channels = parseLiveDocument(content).first
            if (channels.isEmpty()) return@forEach
            dao.upsertPlaylist(
                LivePlaylistEntity(
                    id = "live_" + live.url.hashCode().toUInt().toString(16),
                    name = live.name.trim().take(30).ifBlank { "直播源" },
                    content = content,
                    epg = "",
                    enabled = true,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            added += 1
        }
        return added
    }

    private suspend fun keepUsableSources(
        candidates: List<DiscoveredCmsSource>,
        onProgress: suspend (String) -> Unit,
    ): List<DiscoveredCmsSource> {
        val gate = Semaphore(PROBE_PARALLELISM)
        val finished = AtomicInteger(0)
        val alive = coroutineScope {
            candidates.map { candidate ->
                async {
                    val ok = gate.withPermit { probeCms(candidate.url) }
                    onProgress("正在检查可用源 ${finished.incrementAndGet()}/${candidates.size}")
                    candidate to ok
                }
            }.awaitAll()
        }
        return alive.filter { it.second }.map { it.first }
    }

    private fun probeCms(url: String): Boolean {
        return try {
            val listed = http.exchange(
                cmsProbeUrl(url, "list"),
                maxBytes = PROBE_MAX_BYTES,
                callTimeoutSeconds = PROBE_TIMEOUT_SECONDS,
                truncate = true,
            )
            if (isUsableCmsPayload(listed)) return true
            if (!listed.trim().removePrefix("\uFEFF").startsWith("{")) return false
            val detailed = http.exchange(
                cmsProbeUrl(url, "detail"),
                maxBytes = PROBE_MAX_BYTES,
                callTimeoutSeconds = PROBE_TIMEOUT_SECONDS,
                truncate = true,
            )
            isUsableCmsPayload(detailed)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    private fun requireHttpUrl(value: String): String {
        val url = value.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw IllegalArgumentException("数据源地址需要以 http:// 或 https:// 开头")
        }
        return url
    }

    private suspend fun addLive(submission: Submission.Live): String {
        val content = submission.text?.trim().takeUnless { it.isNullOrBlank() }
            ?: http.getLimited(submission.url, "请粘贴直播内容或填写地址")
        val epg = submission.epg?.trim().takeUnless { it.isNullOrBlank() }
            ?: submission.epgUrl?.let { http.getLimited(it, "节目单地址无法读取") }.orEmpty()
        val (channels, programs) = parseLiveDocument(content)
        val epgPrograms = if (epg.isBlank()) emptyList() else parseLiveDocument(epg).second
        if (channels.isEmpty() && programs.isEmpty() && epgPrograms.isEmpty()) {
            throw IllegalArgumentException("没有解析出频道")
        }
        if (channels.isEmpty()) throw IllegalArgumentException("节目单需要和频道一起提交")
        val name = submission.name.trim().take(30).ifBlank { "直播源" }
        dao.upsertPlaylist(
            LivePlaylistEntity(
                id = "live_" + UUID.randomUUID().toString().take(8),
                name = name,
                content = content,
                epg = epg,
                enabled = true,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        val programNote = if (programs.isEmpty() && epgPrograms.isEmpty()) "" else "，含节目单"
        return "已添加直播「$name」，${channels.size} 个频道$programNote"
    }

    private suspend fun addDrive(submission: Submission.Drive): String {
        val provider = when (submission.provider) {
            DriveKinds.WEBDAV, DriveKinds.ALIST -> submission.provider
            else -> throw IllegalArgumentException("只支持 WebDAV 和 Alist")
        }
        val endpoint = normalizeEndpoint(submission.endpoint)
        if (provider == DriveKinds.ALIST && submission.secret.isBlank()) {
            throw IllegalArgumentException("Alist 需要填写 Token")
        }
        val existing = dao.allDrives()
        if (existing.any { it.provider == provider && it.endpoint == endpoint }) {
            throw IllegalArgumentException("这个网盘已经添加过")
        }
        val name = submission.name.trim().take(30).ifBlank { if (provider == DriveKinds.ALIST) "Alist" else "WebDAV" }
        dao.upsertDrive(
            DriveAccountEntity(
                id = "drive_" + UUID.randomUUID().toString().take(8),
                provider = provider,
                name = name,
                endpoint = endpoint,
                username = submission.username.trim(),
                secretCipher = secretBox.encrypt(submission.secret),
                enabled = true,
            ),
        )
        return "已添加网盘「$name」"
    }

    private suspend fun addScript(submission: Submission.Script): String {
        val code = submission.code?.trim().takeUnless { it.isNullOrBlank() }
            ?: http.getLimited(submission.url, "请粘贴脚本或填写脚本地址")
        if (code.length > 512_000) throw IllegalArgumentException("脚本过长")
        runner.invoke(code, "home", emptyList())
        val name = submission.name.trim().take(30).ifBlank { "脚本源" }
        val id = "script_" + UUID.randomUUID().toString().take(8)
        val directory = File(context.filesDir, "scripts").apply { mkdirs() }
        val file = File(directory, "$id.js")
        file.writeText(code)
        val existing = dao.allSources()
        dao.upsertSource(
            SourceConfigEntity(
                id = id,
                name = name,
                kind = SourceKinds.SCRIPT,
                location = file.absolutePath,
                enabled = true,
                priority = nextPriority(existing),
                builtin = false,
            ),
        )
        return "已添加脚本源「$name」"
    }

    private fun nextPriority(existing: List<SourceConfigEntity>): Int {
        return (existing.maxOfOrNull { it.priority } ?: 20) + 10
    }

    private data class DiscoveredCmsSource(
        val name: String,
        val url: String,
    )

    private data class DiscoveredLine(
        val name: String,
        val url: String,
    )

    private data class Discovery(
        val sources: List<DiscoveredCmsSource>,
        val lines: List<DiscoveredLine>,
        val lives: List<TvBoxLive>,
        val sawScriptSites: Boolean,
    ) {
        fun emptyMessage(): String {
            return if (sawScriptSites) {
                "这个单源是 TVBox 脚本仓，没有可直接使用的采集接口"
            } else {
                "没有识别出 CMS 或 TVBox 站点配置"
            }
        }
    }

    private companion object {
        const val MAX_TVBOX_DEPTH = 3
        const val MAX_TVBOX_SOURCES = 80
        const val PROBE_PARALLELISM = 6
        const val PROBE_TIMEOUT_SECONDS = 8
        const val PROBE_MAX_BYTES = 800_000
    }
}

private suspend fun AppHttp.getConfig(url: String): String {
    val first = readConfig(url, null)
    if (!isHtmlDocument(first)) return first
    return readConfig(url, "okhttp/3.15")
}

private suspend fun AppHttp.readConfig(url: String, userAgent: String?): String {
    val headers = if (userAgent.isNullOrBlank()) emptyMap() else mapOf("User-Agent" to userAgent)
    val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        exchangeBytes(url, headers = headers, maxBytes = 4_000_000)
    }
    return com.cloudreel.tv.core.catalog.configText(bytes)
}

private fun isHtmlDocument(text: String): Boolean {
    val trimmed = text.trim().removePrefix("\uFEFF").lowercase()
    return trimmed.startsWith("<!doctype") || trimmed.startsWith("<html") || trimmed.startsWith("<head") || trimmed.startsWith("<body")
}

private suspend fun AppHttp.getLimited(url: String?, emptyMessage: String): String {
    if (url.isNullOrBlank()) throw IllegalArgumentException(emptyMessage)
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        exchange(url, maxBytes = 4_000_000)
    }
}

internal fun basicAuthorization(username: String, password: String): String {
    if (username.isBlank() && password.isBlank()) return ""
    val raw = "$username:$password".toByteArray(Charsets.UTF_8)
    return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
}
