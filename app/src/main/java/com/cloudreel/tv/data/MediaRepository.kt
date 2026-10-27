package com.cloudreel.tv.data

import android.content.Context
import com.cloudreel.tv.core.catalog.HomeShelf
import com.cloudreel.tv.core.catalog.HomeShelfPage
import com.cloudreel.tv.core.catalog.readableError
import com.cloudreel.tv.core.database.AppDao
import com.cloudreel.tv.core.database.FavoriteEntity
import com.cloudreel.tv.core.database.JsonCodec
import com.cloudreel.tv.core.database.PlayHistoryEntity
import com.cloudreel.tv.core.database.ProgressEntity
import com.cloudreel.tv.core.database.SearchHistoryEntity
import com.cloudreel.tv.core.database.SourceConfigEntity
import com.cloudreel.tv.core.database.SourceEntity
import com.cloudreel.tv.core.catalog.ResumePolicy
import com.cloudreel.tv.core.model.AggregatedDetail
import com.cloudreel.tv.core.model.SourceKinds
import com.cloudreel.tv.core.model.SourceRef
import com.cloudreel.tv.core.model.WatchProgress
import com.cloudreel.tv.core.network.AppHttp
import com.cloudreel.tv.core.database.DriveAccountEntity
import com.cloudreel.tv.core.database.LivePlaylistEntity
import com.cloudreel.tv.core.drive.CloudClient
import com.cloudreel.tv.core.drive.CloudLink
import com.cloudreel.tv.core.drive.CloudPlayback
import com.cloudreel.tv.core.drive.CloudPoll
import com.cloudreel.tv.core.drive.CloudQrSession
import com.cloudreel.tv.core.drive.PAN115_USER_AGENT
import com.cloudreel.tv.core.drive.Pan115File
import com.cloudreel.tv.core.drive.Pan115QrPhase
import com.cloudreel.tv.core.drive.Pan115QrStart
import com.cloudreel.tv.core.drive.pan115DownUrlBody
import com.cloudreel.tv.core.drive.pan115PlayHeaders
import com.cloudreel.tv.core.drive.parsePan115LoginCookie
import com.cloudreel.tv.core.drive.parsePan115QrStart
import com.cloudreel.tv.core.drive.parsePan115QrStatus
import com.cloudreel.tv.core.drive.parseAlistList
import com.cloudreel.tv.core.drive.parseAlistUrl
import com.cloudreel.tv.core.drive.parsePan115DownUrl
import com.cloudreel.tv.core.drive.parseWebDav
import com.cloudreel.tv.core.live.LiveVerdict
import com.cloudreel.tv.core.live.currentProgram
import com.cloudreel.tv.core.live.liveVerdict
import com.cloudreel.tv.core.live.parseLiveDocument
import com.cloudreel.tv.core.live.sampleLiveUrls
import com.cloudreel.tv.core.model.DriveEntry
import com.cloudreel.tv.core.model.DriveKinds
import com.cloudreel.tv.core.model.DriveRefresh
import com.cloudreel.tv.core.model.Episode
import com.cloudreel.tv.core.model.LiveChannel
import com.cloudreel.tv.core.model.LiveProgram
import com.cloudreel.tv.core.model.SearchHit
import com.cloudreel.tv.core.provider.CmsJsonProvider
import com.cloudreel.tv.core.provider.JsonCatalogProvider
import com.cloudreel.tv.core.provider.MediaProvider
import com.cloudreel.tv.core.provider.ScriptMediaProvider
import com.cloudreel.tv.core.show.AggregatePolicy
import com.cloudreel.tv.core.show.RawCatalogEntry
import com.cloudreel.tv.core.show.ShowRecord
import com.cloudreel.tv.core.show.nextFailCount
import com.cloudreel.tv.core.show.parseAssetEntries
import com.cloudreel.tv.core.script.ScriptRunner
import com.cloudreel.tv.core.security.SecretBox
import java.io.File
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

class MediaRepository(
    private val context: Context,
    private val dao: AppDao,
    private val http: AppHttp,
    private val secretBox: SecretBox,
    private val scope: CoroutineScope,
) {
    private val runner = ScriptRunner { url, headers -> http.exchange(url, headers = headers) }
    private val clouds = CloudClient(http)
    private val importer = SubmissionImporter(context, dao, http, secretBox, runner)
    private val showCatalog = ShowCatalog(dao) { config -> entriesFor(config) }
    private val ready = CompletableDeferred<Unit>()

    fun start() {
        scope.launch {
            try {
                if (dao.sourceCount() == 0) {
                    dao.upsertSource(builtin("open_movies", "公开测试片库", "sources/open_movies.json", 10))
                    dao.upsertSource(builtin("backup_movies", "备用测试片库", "sources/backup_movies.json", 20))
                    dao.upsertSource(
                        SourceConfigEntity(
                            id = "demo_script",
                            name = "内置脚本样例",
                            kind = SourceKinds.SCRIPT,
                            location = "asset:sources/demo_script.js",
                            enabled = true,
                            priority = 30,
                            builtin = true,
                        ),
                    )
                    context.assets.open("sources/demo_live.json").bufferedReader().use { it.readText() }.let { content ->
                        dao.upsertPlaylist(
                            LivePlaylistEntity(
                                id = "demo_live",
                                name = "公开测试频道",
                                content = content,
                                epg = "",
                                enabled = true,
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
                if (dao.allSources().none { it.id == TEST_TVBOX_ID }) {
                    dao.upsertSource(
                        SourceConfigEntity(
                            id = TEST_TVBOX_ID,
                            name = "TVBox 测试源",
                            kind = SourceKinds.TVBOX,
                            location = TEST_TVBOX_URL,
                            enabled = true,
                            priority = 5,
                            builtin = false,
                        ),
                    )
                }
                ready.complete(Unit)
            } catch (error: Throwable) {
                ready.completeExceptionally(error)
            }
        }
    }

    fun observeSources(): Flow<List<SourceConfigEntity>> = dao.observeSources()

    fun observeHistory(): Flow<List<PlayHistoryEntity>> = dao.observeHistory()

    fun observeFavorites(): Flow<List<FavoriteEntity>> = dao.observeFavorites()

    fun observeSearches(): Flow<List<SearchHistoryEntity>> = dao.observeSearches()

    suspend fun historyOf(mediaKey: String): PlayHistoryEntity? {
        gate()
        return dao.history(mediaKey)
    }

    suspend fun isFavorite(mediaKey: String): Boolean {
        gate()
        return dao.favorite(mediaKey) != null
    }

    suspend fun loadHomeShelf(sourceId: String, shelf: HomeShelf, force: Boolean = false): HomeShelfPage {
        gate()
        val config = dao.allSources().firstOrNull { it.id == sourceId && it.enabled }
            ?: throw IllegalStateException("这条线路已停用")
        if (config.kind == SourceKinds.TVBOX) {
            return withContext(Dispatchers.IO) {
                withTimeout(HOME_TIMEOUT_MS) {
                    if (force) lineCacheFile(config.id).delete()
                    val api = playableLineApi(config)
                    CmsJsonProvider(http, config.id, config.name, api, config.priority).browse(shelf)
                }
            }
        }
        val provider = enabledProviders().firstOrNull { it.id == sourceId }
            ?: throw IllegalStateException("这条线路已停用")
        return withTimeout(HOME_TIMEOUT_MS) {
            when (provider) {
                is CmsJsonProvider -> provider.browse(shelf)
                else -> HomeShelfPage(
                    cards = provider.home(),
                    notice = if (shelf == HomeShelf.HOT_DRAMA) null else "这条线路不支持分类，显示的是默认内容",
                )
            }
        }
    }

    suspend fun search(keyword: String, onUpdate: suspend (SearchSnapshot) -> Unit): SearchSnapshot {
        gate()
        val providers = enabledProviders()
        val accumulated = mutableListOf<com.cloudreel.tv.core.model.SearchHit>()
        val failures = mutableListOf<String>()
        val mutex = Mutex()
        var done = 0
        coroutineScope {
            providers.map { provider ->
                async {
                    val outcome = try {
                        SearchOutcome.Ok(withTimeout(SOURCE_TIMEOUT_MS) { provider.search(keyword) })
                    } catch (error: TimeoutCancellationException) {
                        SearchOutcome.Failed("${provider.name}：超时")
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        SearchOutcome.Failed("${provider.name}：${readableError(error)}")
                    }
                    mutex.withLock {
                        when (outcome) {
                            is SearchOutcome.Ok -> accumulated += outcome.hits
                            is SearchOutcome.Failed -> failures += outcome.message
                        }
                        done += 1
                        onUpdate(snapshot(done, providers.size, accumulated, failures, done < providers.size))
                    }
                }
            }.awaitAll()
        }
        return snapshot(done, providers.size, accumulated, failures, false)
    }

    suspend fun loadDetail(refs: List<SourceRef>): AggregatedDetail {
        gate()
        if (refs.isEmpty()) throw IllegalStateException("没有可查询的来源")
        val providers = enabledProviders().associateBy { it.id }
        val results = coroutineScope {
            refs.distinct().map { ref ->
                async {
                    val provider = providers[ref.providerId]
                        ?: tvBoxProvider(ref.providerId)
                        ?: return@async Result.failure(IllegalStateException("来源已停用"))
                    try {
                        Result.success(withTimeout(SOURCE_TIMEOUT_MS) { provider.detail(ref.sourceId) })
                    } catch (error: TimeoutCancellationException) {
                        Result.failure(IllegalStateException("超时"))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                }
            }.awaitAll()
        }
        val details = results.mapNotNull { it.getOrNull() }
        val failures = results.mapNotNull { result ->
            result.exceptionOrNull()?.let { readableError(it) }
        }
        if (details.isEmpty()) throw IllegalStateException(failures.firstOrNull() ?: "没有可用详情")
        val first = details.first()
        return AggregatedDetail(
            title = first.title,
            poster = details.firstOrNull { !it.poster.isNullOrBlank() }?.poster,
            year = details.firstOrNull { !it.year.isNullOrBlank() }?.year,
            area = details.firstOrNull { !it.area.isNullOrBlank() }?.area,
            genres = details.flatMap { it.genres }.distinct(),
            directors = details.flatMap { it.directors }.distinct(),
            actors = details.flatMap { it.actors }.distinct(),
            description = details.firstOrNull { it.description.isNotBlank() }?.description.orEmpty(),
            remarks = details.firstOrNull { !it.remarks.isNullOrBlank() }?.remarks,
            lines = details.flatMap { it.lines },
            failures = failures,
            sourceCount = details.size,
        )
    }

    suspend fun rememberSearch(keyword: String) {
        gate()
        val text = keyword.trim()
        if (text.isBlank()) return
        dao.upsertSearch(SearchHistoryEntity(text, System.currentTimeMillis()))
    }

    suspend fun saveHistory(progress: WatchProgress) {
        gate()
        if (progress.positionMs < ResumePolicy.START_SAVE_MS &&
            !ResumePolicy.isCompleted(progress.positionMs, progress.durationMs)
        ) {
            return
        }
        val first = progress.refs.firstOrNull()
        dao.upsertHistory(
            PlayHistoryEntity(
                mediaKey = progress.mediaKey,
                title = progress.title,
                poster = progress.poster,
                year = progress.year,
                providerId = first?.providerId.orEmpty(),
                sourceId = first?.sourceId.orEmpty(),
                refsJson = JsonCodec.encodeRefs(progress.refs),
                lineIndex = progress.lineIndex,
                episodeIndex = progress.episodeIndex,
                lineName = progress.lineName,
                episodeName = progress.episodeName,
                playUrl = progress.playUrl,
                headersJson = JsonCodec.encodeHeaders(progress.headers),
                positionMs = progress.positionMs,
                durationMs = progress.durationMs,
                lastPlayedAt = System.currentTimeMillis(),
                completed = ResumePolicy.isCompleted(progress.positionMs, progress.durationMs),
            ),
        )
        val episodeKey = progress.episodeName.ifBlank { "${progress.lineIndex}:${progress.episodeIndex}" }
        dao.upsertProgress(
            ProgressEntity(
                id = "prog:${progress.mediaKey}:$episodeKey",
                showId = progress.mediaKey,
                episodeKey = episodeKey,
                positionMs = progress.positionMs,
                durationMs = progress.durationMs,
                playLinkId = progress.playLinkId,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    fun observeShowRecords(): Flow<List<ShowRecord>> = showCatalog.observeShows()

    fun observeContinueRecords(): Flow<List<ShowRecord>> = showCatalog.observeContinue()

    fun observeContinueCards(): Flow<List<ContinueCard>> = showCatalog.observeContinueCards()

    suspend fun refreshShows(manual: Boolean = false, onShown: suspend (Int) -> Unit = {}) {
        gate()
        showCatalog.refresh(manual, onShown)
    }

    suspend fun loadShowSession(showId: String): ShowSession {
        gate()
        return showCatalog.loadSession(showId) { sourceId, vodId ->
            val providers = enabledProviders().associateBy { it.id }
            val provider = providers[sourceId] ?: tvBoxProvider(sourceId) ?: return@loadSession null
            try {
                withTimeout(AggregatePolicy.SOURCE_TIMEOUT_MS) { provider.detail(vodId) }
            } catch (error: TimeoutCancellationException) {
                null
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun materializeHits(hits: List<SearchHit>): String {
        gate()
        return showCatalog.materialize(hits.map { it.toRawEntry() })
    }

    suspend fun publishLive(page: LivePage) {
        gate()
        showCatalog.rememberLive(page) { channel -> programNow(page.programs, channel)?.title }
        val previous = dao.sourceHealth("live:${page.id}")
        val ok = page.channels.isNotEmpty()
        dao.upsertSourceHealth(
            SourceEntity(
                id = "live:${page.id}",
                name = page.name.ifBlank { "直播" },
                apiUrl = "",
                type = "live",
                enabled = true,
                priority = 0,
                lastOkAt = if (ok) System.currentTimeMillis() else previous?.lastOkAt ?: 0L,
                failCount = nextFailCount(previous?.failCount ?: 0, ok),
            ),
        )
    }

    fun observeLiveCategories(): Flow<List<com.cloudreel.tv.core.database.LiveCategoryEntity>> = dao.observeLiveCategories()

    fun observeLiveChannels(): Flow<List<com.cloudreel.tv.core.database.LiveChannelEntity>> = dao.observeLiveChannels()

    suspend fun probeLiveUrl(url: String): LiveVerdict {
        gate()
        return withContext(Dispatchers.IO) {
            try {
                val probe = http.probeHead(url)
                liveVerdict(probe.code, probe.contentType, probe.head)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                LiveVerdict.UNKNOWN
            }
        }
    }

    suspend fun toggleFavorite(entity: FavoriteEntity, currentlyFavorite: Boolean): Boolean {
        gate()
        return if (currentlyFavorite) {
            dao.deleteFavorite(entity.mediaKey)
            false
        } else {
            dao.upsertFavorite(entity.copy(addedAt = System.currentTimeMillis()))
            true
        }
    }

    suspend fun addCms(name: String, url: String, onProgress: suspend (String) -> Unit = {}): String {
        gate()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            importer.addCms(name, url, onProgress)
        }
    }

    suspend fun setSourceEnabled(id: String, enabled: Boolean) {
        gate()
        dao.setSourceEnabled(id, enabled)
    }

    suspend fun deleteSource(id: String) {
        gate()
        val source = dao.allSources().find { it.id == id }
        dao.deleteCustomSource(id)
        lineCacheFile(id).delete()
        lineRoleFile(id).delete()
        liveScanFile(id).delete()
        if (source?.kind == SourceKinds.SCRIPT && !source.location.startsWith("asset:")) {
            File(source.location).delete()
        }
    }

    suspend fun importSubmission(body: String): String {
        gate()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { importer.import(body) }
    }

    fun observePlaylistSummaries(): kotlinx.coroutines.flow.Flow<List<com.cloudreel.tv.core.database.LivePlaylistSummary>> {
        return dao.observePlaylistSummaries()
    }

    fun observePlaylists(): kotlinx.coroutines.flow.Flow<List<LivePlaylistEntity>> = dao.observePlaylists()

    fun observeDrives(): kotlinx.coroutines.flow.Flow<List<DriveAccountEntity>> = dao.observeDrives()

    suspend fun deletePlaylist(id: String) {
        gate()
        dao.deletePlaylist(id)
    }

    suspend fun deleteDrive(id: String) {
        gate()
        dao.deleteDrive(id)
    }

    suspend fun addDrive(provider: String, name: String, endpoint: String, username: String, secret: String) {
        gate()
        importer.import(
            JSONObject()
                .put("type", "drive")
                .put("provider", provider)
                .put("name", name)
                .put("endpoint", endpoint)
                .put("username", username)
                .put("secret", secret)
                .toString(),
        )
    }

    suspend fun addLiveText(name: String, text: String) {
        gate()
        importer.import(JSONObject().put("type", "live").put("name", name).put("text", text).toString())
    }

    suspend fun resolvePan115(cookie: String, pickCode: String): Pan115File {
        gate()
        val cleanCookie = cookie.trim()
        if (cleanCookie.isBlank()) throw IllegalStateException("请先在网盘页面扫码登录 115")
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val body = http.exchange(
                url = "https://proapi.115.com/app/chrome/downurl",
                method = "POST",
                headers = pan115PlayHeaders() + mapOf(
                    "Cookie" to cleanCookie,
                    "User-Agent" to PAN115_USER_AGENT,
                ),
                body = pan115DownUrlBody(pickCode),
                contentType = "application/x-www-form-urlencoded",
            )
            parsePan115DownUrl(body)
        }
    }

    suspend fun startCloudLogin(kind: String): CloudQrSession {
        gate()
        return withContext(Dispatchers.IO) { clouds.start(kind) }
    }

    suspend fun pollCloudLogin(kind: String, session: CloudQrSession): CloudPoll {
        gate()
        return withContext(Dispatchers.IO) { clouds.poll(kind, session) }
    }

    suspend fun finishCloudLogin(kind: String, session: CloudQrSession): String {
        gate()
        return withContext(Dispatchers.IO) { clouds.finish(kind, session) }
    }

    suspend fun resolveCloudLink(link: CloudLink, secret: String): CloudPlayback {
        gate()
        return withContext(Dispatchers.IO) { clouds.resolve(link, secret) }
    }

    suspend fun startPan115Qr(): Pan115QrStart {
        gate()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            parsePan115QrStart(http.exchange("https://qrcodeapi.115.com/api/1.0/web/1.0/token", headers = pan115PlayHeaders()))
        }
    }

    suspend fun pollPan115Qr(session: Pan115QrStart): Pan115QrPhase {
        gate()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = "https://qrcodeapi.115.com/get/status/?uid=" +
                java.net.URLEncoder.encode(session.uid, Charsets.UTF_8.name()) +
                "&time=" + java.net.URLEncoder.encode(session.time, Charsets.UTF_8.name()) +
                "&sign=" + java.net.URLEncoder.encode(session.sign, Charsets.UTF_8.name())
            parsePan115QrStatus(http.exchange(url, headers = pan115PlayHeaders()))
        }
    }

    suspend fun finishPan115Qr(session: Pan115QrStart): String {
        gate()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val result = http.exchangeDetailed(
                url = "https://passportapi.115.com/app/1.0/web/1.0/login/qrcode",
                method = "POST",
                headers = pan115PlayHeaders(),
                body = "account=" + java.net.URLEncoder.encode(session.uid, Charsets.UTF_8.name()),
                contentType = "application/x-www-form-urlencoded",
            )
            parsePan115LoginCookie(result.body, result.setCookies)
        }
    }

    suspend fun addLiveUrl(name: String, url: String, epgUrl: String): String {
        gate()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            importer.import(
                JSONObject()
                    .put("type", "live")
                    .put("name", name)
                    .put("url", url)
                    .put("epgUrl", epgUrl)
                    .toString(),
            )
        }
    }

    suspend fun loadPlaylist(id: String): LivePage? {
        gate()
        return withContext(Dispatchers.IO) {
            val playlist = dao.playlist(id) ?: return@withContext null
            val parsed = parseLiveDocument(playlist.content, maxChannels = LIVE_CHANNEL_CAP)
            val epg = if (playlist.epg.isBlank()) emptyList() else parseLiveDocument(playlist.epg, maxChannels = 1).second
            LivePage(
                id = playlist.id,
                name = playlist.name,
                channels = parsed.first,
                programs = epg + parsed.second,
                truncated = parsed.first.size >= LIVE_CHANNEL_CAP,
            )
        }
    }

    fun programNow(programs: List<LiveProgram>, channel: LiveChannel, now: Long = System.currentTimeMillis()): LiveProgram? {
        return currentProgram(programs, channel, now)
    }

    suspend fun listDrive(accountId: String, path: String): List<DriveEntry> {
        gate()
        val account = dao.drive(accountId) ?: throw IllegalArgumentException("网盘不存在")
        val secret = secretBox.decrypt(account.secretCipher)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            when (account.provider) {
                DriveKinds.WEBDAV -> {
                    val url = if (path.startsWith("http://") || path.startsWith("https://")) path else account.endpoint
                    val xml = http.exchange(
                        url = url,
                        method = "PROPFIND",
                        headers = webdavHeaders(account, secret) + mapOf("Depth" to "1"),
                        body = WEBDAV_BODY,
                        contentType = "application/xml",
                    )
                    parseWebDav(xml, url)
                }
                DriveKinds.ALIST -> {
                    val json = http.exchange(
                        url = account.endpoint + "/api/fs/list",
                        method = "POST",
                        headers = alistHeaders(secret),
                        body = JSONObject().put("path", path.ifBlank { "/" }).put("password", "").put("page", 1).put("per_page", 0).toString(),
                        contentType = "application/json",
                    )
                    parseAlistList(json, path.ifBlank { "/" })
                }
                else -> throw IllegalArgumentException("不认识的网盘类型")
            }
        }
    }

    suspend fun openDrive(accountId: String, path: String, name: String): Episode {
        gate()
        val account = dao.drive(accountId) ?: throw IllegalArgumentException("网盘不存在")
        val secret = secretBox.decrypt(account.secretCipher)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            when (account.provider) {
                DriveKinds.WEBDAV -> Episode(name, path, driveAuthorization(account, secret))
                DriveKinds.ALIST -> {
                    val json = http.exchange(
                        url = account.endpoint + "/api/fs/get",
                        method = "POST",
                        headers = alistHeaders(secret),
                        body = JSONObject().put("path", path).put("password", "").toString(),
                        contentType = "application/json",
                    )
                    Episode(name, parseAlistUrl(json))
                }
                else -> throw IllegalArgumentException("不认识的网盘类型")
            }
        }
    }

    suspend fun refreshDrive(refresh: DriveRefresh): Episode {
        val name = refresh.path.substringAfterLast('/').ifBlank { "网盘视频" }
        return openDrive(refresh.accountId, refresh.path, name)
    }

    suspend fun clearHistory() {
        gate()
        dao.clearHistory()
    }

    suspend fun clearFavorites() {
        gate()
        dao.clearFavorites()
    }

    suspend fun clearSearches() {
        gate()
        dao.clearSearches()
    }

    private suspend fun gate() {
        ready.await()
    }

    private suspend fun entriesFor(config: SourceConfigEntity): List<RawCatalogEntry> {
        val acc = java.util.Collections.synchronizedList(mutableListOf<RawCatalogEntry>())
        try {
            withTimeout(AggregatePolicy.SOURCE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    when (config.kind) {
                        SourceKinds.ASSET -> {
                            val text = context.assets.open(config.location).bufferedReader().use { it.readText() }
                            parseAssetEntries(text, config.id, config.name).forEach { acc.add(it) }
                        }
                        SourceKinds.CMS -> CmsJsonProvider(http, config.id, config.name, config.location, config.priority)
                            .collectCatalog { acc.add(it) }
                        SourceKinds.TVBOX -> {
                            val api = playableLineApi(config)
                            CmsJsonProvider(http, config.id, config.name, api, config.priority)
                                .collectCatalog { acc.add(it) }
                        }
                    }
                }
            }
        } catch (error: TimeoutCancellationException) {
            // 单源超时后仍回填已经拿到的条目。
        }
        return acc.toList()
    }

    private suspend fun enabledProviders(): List<MediaProvider> {
        return dao.enabledSources().mapNotNull { config ->
            when (config.kind) {
                SourceKinds.ASSET -> JsonCatalogProvider(
                    assets = context.assets,
                    id = config.id,
                    name = config.name,
                    assetPath = config.location,
                    priority = config.priority,
                )
                SourceKinds.CMS -> CmsJsonProvider(
                    http = http,
                    id = config.id,
                    name = config.name,
                    baseUrl = config.location,
                    priority = config.priority,
                )
                SourceKinds.TVBOX -> readLineCache(config.id)?.let { api ->
                    CmsJsonProvider(
                        http = http,
                        id = config.id,
                        name = config.name,
                        baseUrl = api,
                        priority = config.priority,
                    )
                }
                SourceKinds.SCRIPT -> ScriptMediaProvider(
                    runner = runner,
                    code = readScript(config.location),
                    id = config.id,
                    name = config.name,
                    priority = config.priority,
                )
                else -> null
            }
        }
    }

    private fun readScript(location: String): String {
        val text = if (location.startsWith("asset:")) {
            context.assets.open(location.removePrefix("asset:")).bufferedReader().use { it.readText() }
        } else {
            File(location).readText()
        }
        if (text.length > 512_000) throw IllegalStateException("脚本过长")
        return text
    }

    private fun driveAuthorization(account: DriveAccountEntity, secret: String): Map<String, String> {
        val authorization = basicAuthorization(account.username, secret)
        return if (authorization.isBlank()) emptyMap() else mapOf("Authorization" to authorization)
    }

    private fun webdavHeaders(account: DriveAccountEntity, secret: String): Map<String, String> {
        return driveAuthorization(account, secret)
    }

    private fun alistHeaders(secret: String): Map<String, String> {
        return mapOf("Authorization" to secret)
    }

    private fun snapshot(
        done: Int,
        total: Int,
        hits: List<com.cloudreel.tv.core.model.SearchHit>,
        failures: List<String>,
        searching: Boolean,
    ): SearchSnapshot {
        val sorted = hits.sortedWith(compareBy({ it.priority }, { it.providerName }, { it.title }))
        return SearchSnapshot(
            searching = searching,
            done = done,
            total = total,
            hits = sorted,
            failures = failures.toList(),
        )
    }

    suspend fun removeUnplayableLiveSources(): Int {
        gate()
        val summaries = dao.enabledPlaylistSummaries()
        if (summaries.isEmpty()) return 0
        return withContext(Dispatchers.IO) {
            val gate = Semaphore(3)
            val checked = coroutineScope {
                summaries.map { summary ->
                    async {
                        if (liveHealthFile(summary.id).isFile) return@async summary.id to false
                        val dead = try {
                            gate.withPermit { playlistIsDead(summary.id) }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            null
                        }
                        summary.id to dead
                    }
                }.awaitAll()
            }
            val networkUp = checked.any { it.second == false && !liveHealthFile(it.first).isFile }
            checked.sumOf { (id, dead) ->
                val remove = dead == true || (dead == null && networkUp)
                when {
                    remove -> {
                        dao.deletePlaylist(id)
                        1
                    }
                    dead == false -> {
                        liveHealthFile(id).writeText("ok")
                        0
                    }
                    else -> 0
                }
            }
        }
    }

    private suspend fun playlistIsDead(id: String): Boolean? {
        val playlist = dao.playlist(id) ?: return true
        val channels = parseLiveDocument(playlist.content, maxChannels = 40).first
        val urls = sampleLiveUrls(channels)
        if (urls.isEmpty()) return true
        var dead = 0
        var unknown = 0
        for (url in urls) {
            val verdict = try {
                val probe = http.probeHead(url)
                liveVerdict(probe.code, probe.contentType, probe.head)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                LiveVerdict.UNKNOWN
            }
            when (verdict) {
                LiveVerdict.PLAYABLE -> return false
                LiveVerdict.DEAD -> dead += 1
                LiveVerdict.UNKNOWN -> unknown += 1
            }
        }
        return if (dead > 0 && unknown == 0) true else null
    }

    suspend fun importLivesFromSavedLines(): Int {
        gate()
        val lines = dao.enabledSources().filter { it.kind == SourceKinds.TVBOX }
        if (lines.isEmpty()) return 0
        return withContext(Dispatchers.IO) {
            val gate = Semaphore(4)
            coroutineScope {
                lines.map { line ->
                    async {
                        if (liveScanFile(line.id).isFile) return@async 0
                        val count = try {
                            gate.withPermit { importer.importLivesAt(line.location) }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            null
                        }
                        if (count == null) return@async 0
                        liveScanFile(line.id).writeText("ok")
                        count
                    }
                }.awaitAll().sum()
            }
        }
    }

    suspend fun cachedLineRole(sourceId: String): String? = withContext(Dispatchers.IO) { readLineRole(sourceId) }

    suspend fun classifyLine(sourceId: String, location: String): String? {
        gate()
        readLineRole(sourceId)?.let { return it }
        return withContext(Dispatchers.IO) {
            val role = try {
                if (importer.lineCanScrape(location)) "cms" else "skip"
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@withContext null
            }
            writeLineRole(sourceId, role)
            role
        }
    }

    private suspend fun tvBoxProvider(id: String): MediaProvider? {
        val config = dao.allSources().find { it.id == id && it.kind == SourceKinds.TVBOX && it.enabled } ?: return null
        val api = withContext(Dispatchers.IO) { playableLineApi(config) }
        return CmsJsonProvider(http, config.id, config.name, api, config.priority)
    }

    private suspend fun playableLineApi(config: SourceConfigEntity): String {
        readLineCache(config.id)?.let { return it }
        return try {
            importer.firstPlayableCms(config.location).also {
                writeLineCache(config.id, it)
                writeLineRole(config.id, "cms")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val message = error.message.orEmpty()
            if (message.contains("脚本仓") || message.contains("多份配置")) writeLineRole(config.id, "skip")
            throw error
        }
    }

    private fun lineCacheFile(sourceId: String): File {
        return File(File(context.filesDir, "line-cms").apply { mkdirs() }, sourceId)
    }

    private fun readLineCache(sourceId: String): String? {
        val file = lineCacheFile(sourceId)
        if (!file.isFile) return null
        val text = file.readText().trim()
        return text.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun writeLineCache(sourceId: String, api: String) {
        lineCacheFile(sourceId).writeText(api)
    }

    private fun liveHealthFile(playlistId: String): File {
        return File(File(context.filesDir, "live-health").apply { mkdirs() }, playlistId)
    }

    private fun liveScanFile(sourceId: String): File {
        return File(File(context.filesDir, "line-lives").apply { mkdirs() }, sourceId)
    }

    private fun lineRoleFile(sourceId: String): File {
        return File(File(context.filesDir, "line-role").apply { mkdirs() }, sourceId)
    }

    private fun readLineRole(sourceId: String): String? {
        val text = lineRoleFile(sourceId).takeIf { it.isFile }?.readText()?.trim().orEmpty()
        return text.takeIf { it == "cms" || it == "skip" }
    }

    private fun writeLineRole(sourceId: String, role: String) {
        lineRoleFile(sourceId).writeText(role)
    }

    private fun builtin(id: String, name: String, path: String, priority: Int) = SourceConfigEntity(
        id = id,
        name = name,
        kind = SourceKinds.ASSET,
        location = path,
        enabled = true,
        priority = priority,
        builtin = true,
    )

    private sealed interface SearchOutcome {
        data class Ok(val hits: List<com.cloudreel.tv.core.model.SearchHit>) : SearchOutcome
        data class Failed(val message: String) : SearchOutcome
    }

    private companion object {
        const val SOURCE_TIMEOUT_MS = 8_000L
        const val HOME_TIMEOUT_MS = 28_000L
        const val LIVE_CHANNEL_CAP = 600
        const val TEST_TVBOX_ID = "gitlab_tvbox"
        const val TEST_TVBOX_URL = "https://gitlab.com/noimank/tvbox/-/raw/main/tvboxmuti.json"
        const val WEBDAV_BODY =
            """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getcontenttype/></d:prop></d:propfind>"""
    }
}

data class LivePage(
    val id: String,
    val name: String,
    val channels: List<LiveChannel>,
    val programs: List<LiveProgram>,
    val truncated: Boolean,
)

private fun SearchHit.toRawEntry(): RawCatalogEntry {
    return RawCatalogEntry(
        sourceId = providerId,
        sourceName = providerName,
        rawVodId = sourceId,
        title = title,
        yearText = year,
        typeText = remarks,
        genres = genres,
        posterUrl = poster,
        backdropUrl = poster,
        overview = "",
        rating = null,
        releaseDate = year,
        tmdbId = null,
        remarks = remarks,
    )
}

data class SearchSnapshot(
    val searching: Boolean,
    val done: Int,
    val total: Int,
    val hits: List<com.cloudreel.tv.core.model.SearchHit>,
    val failures: List<String>,
)
