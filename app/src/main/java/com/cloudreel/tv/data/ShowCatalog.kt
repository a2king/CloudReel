package com.cloudreel.tv.data

import com.cloudreel.tv.core.database.AppDao
import com.cloudreel.tv.core.database.JsonCodec
import com.cloudreel.tv.core.database.LiveCategoryEntity
import com.cloudreel.tv.core.database.LiveChannelEntity
import com.cloudreel.tv.core.database.PlayLinkEntity
import com.cloudreel.tv.core.database.ProgressEntity
import com.cloudreel.tv.core.database.SourceConfigEntity
import com.cloudreel.tv.core.database.SourceEntity
import com.cloudreel.tv.core.database.toEntity
import com.cloudreel.tv.core.database.toRecord
import com.cloudreel.tv.core.drive.lineCloud
import com.cloudreel.tv.core.model.Episode
import com.cloudreel.tv.core.model.MediaDetail
import com.cloudreel.tv.core.model.PlayLine
import com.cloudreel.tv.core.model.PlaybackRequest
import com.cloudreel.tv.core.model.SourceKinds
import com.cloudreel.tv.core.model.SourceRef
import com.cloudreel.tv.core.show.PlayStub
import com.cloudreel.tv.core.show.ProgressMark
import com.cloudreel.tv.core.show.RawCatalogEntry
import com.cloudreel.tv.core.show.ShowRecord
import com.cloudreel.tv.core.show.circuitOpen
import com.cloudreel.tv.core.show.continueOrder
import com.cloudreel.tv.core.show.driveTabEnabled
import com.cloudreel.tv.core.show.encodeUrlList
import com.cloudreel.tv.core.show.foldCatalog
import com.cloudreel.tv.core.show.linkTitle
import com.cloudreel.tv.core.show.nextFailCount
import com.cloudreel.tv.core.show.retainShow
import com.cloudreel.tv.core.show.sourceCacheFresh
import com.cloudreel.tv.core.show.stubId
import com.cloudreel.tv.core.model.LiveChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class StoredLink(
    val id: String,
    val sourceId: String,
    val label: String,
    val driveType: String?,
    val episodes: List<Episode>,
    val rawVodId: String,
    val qualityHint: String?,
)

data class ShowSession(
    val show: ShowRecord,
    val links: List<StoredLink>,
    val progress: ProgressEntity?,
)

class ShowCatalog(
    private val dao: AppDao,
    private val fetchEntries: suspend (SourceConfigEntity) -> List<RawCatalogEntry>,
) {
    private val foldMutex = Mutex()

    fun observeShows(): Flow<List<ShowRecord>> {
        return dao.observeShows().map { rows -> rows.map { it.toRecord() } }
    }

    fun observeContinue(): Flow<List<ShowRecord>> {
        return combine(dao.observeShows(), dao.observeProgress()) { shows, progress ->
            val map = shows.associate { it.id to it.toRecord() }
            continueOrder(progress.map { ProgressMark(it.showId, it.updatedAt) }, map)
        }
    }

    suspend fun refresh(manual: Boolean = false, onShown: suspend (Int) -> Unit) {
        val configs = dao.enabledSources().filter { it.kind != SourceKinds.SCRIPT && it.kind != SourceKinds.DRIVE }
        val accumulated = mutableListOf<RawCatalogEntry>()
        val startedAt = System.currentTimeMillis()
        coroutineScope {
            configs.map { config ->
                async {
                    val health = ensureSource(config)
                    if (!manual && sourceCacheFresh(health.lastOkAt, startedAt)) return@async
                    if (!manual && circuitOpen(health.failCount)) return@async
                    val entries = try {
                        fetchEntries(config)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        record(config, ok = false)
                        return@async
                    }
                    record(config, ok = entries.isNotEmpty())
                    if (entries.isEmpty()) return@async
                    val count = foldMutex.withLock {
                        accumulated += entries
                        val fold = foldCatalog(accumulated.toList(), System.currentTimeMillis())
                        persist(fold, force = manual)
                        fold.shows.size
                    }
                    onShown(count)
                }
            }.awaitAll()
        }
    }

    suspend fun materialize(entries: List<RawCatalogEntry>): String {
        if (entries.isEmpty()) throw IllegalStateException("没有可打开的节目")
        val fold = foldCatalog(entries, System.currentTimeMillis())
        persist(fold, force = true)
        return fold.shows.first().id
    }

    suspend fun loadSession(
        showId: String,
        fetchDetail: suspend (sourceId: String, vodId: String) -> MediaDetail?,
    ): ShowSession {
        val show = dao.show(showId) ?: throw IllegalStateException("没有这个节目")
        val stubs = dao.linksFor(showId)
        val expanded = coroutineScope {
            stubs.map { link ->
                async {
                    if (JsonCodec.decodeEpisodes(link.episodes).isNotEmpty()) return@async listOf(link)
                    val detail = try {
                        fetchDetail(link.sourceId, link.rawVodId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    } ?: return@async listOf(link)
                    expand(link, detail)
                }
            }.awaitAll().flatten()
        }
        if (expanded.isNotEmpty()) dao.upsertPlayLinks(expanded)
        return ShowSession(
            show = show.toRecord(),
            links = expanded.map { it.toStored() },
            progress = dao.latestProgress(showId),
        )
    }

    suspend fun rememberLive(page: LivePage, hintOf: (LiveChannel) -> String?) {
        val groups = page.channels.map { it.group.ifBlank { "未分组" } }.distinct()
        dao.clearLiveChannels()
        dao.clearLiveCategories()
        if (groups.isEmpty()) return
        dao.upsertLiveCategories(groups.map { LiveCategoryEntity(id = it, name = it) })
        dao.upsertLiveChannels(
            page.channels.mapIndexed { index, channel ->
                val category = channel.group.ifBlank { "未分组" }
                LiveChannelEntity(
                    id = "$category:${channel.name}:$index",
                    categoryId = category,
                    name = channel.name,
                    urls = encodeUrlList(channel.urls),
                    epgHint = hintOf(channel),
                )
            },
        )
    }

    private suspend fun persist(fold: com.cloudreel.tv.core.show.FoldResult, force: Boolean) {
        val now = System.currentTimeMillis()
        val resolved = fold.shows.map { incoming ->
            retainShow(dao.show(incoming.id)?.toRecord(), incoming, now, force)
        }
        if (resolved.isNotEmpty()) dao.upsertShows(resolved.map { it.toEntity() })
        upsertStubs(fold.stubs)
    }

    private suspend fun upsertStubs(stubs: List<PlayStub>) {
        if (stubs.isEmpty()) return
        val existing = stubs.map { it.showId }.distinct().flatMap { dao.linksFor(it) }.associateBy { it.id }
        val now = System.currentTimeMillis()
        val rows = stubs.map { stub ->
            val id = stubId(stub.showId, stub.sourceId, stub.rawVodId)
            val prev = existing[id]
            PlayLinkEntity(
                id = id,
                showId = stub.showId,
                sourceId = stub.sourceId,
                label = prev?.label?.takeIf { it.isNotBlank() } ?: linkTitle(stub.sourceName, ""),
                driveType = prev?.driveType,
                episodes = prev?.episodes?.takeIf { it.isNotBlank() } ?: "[]",
                rawVodId = stub.rawVodId,
                qualityHint = prev?.qualityHint,
                updatedAt = now,
            )
        }
        dao.upsertPlayLinks(rows)
    }

    private fun expand(link: PlayLinkEntity, detail: MediaDetail): List<PlayLinkEntity> {
        if (detail.lines.isEmpty()) return listOf(link)
        val now = System.currentTimeMillis()
        return detail.lines.mapIndexed { index, line ->
            val cloud = lineCloud(line.name, line.episodes.map { it.name to it.url })
            PlayLinkEntity(
                id = if (index == 0) link.id else "${link.id}#$index",
                showId = link.showId,
                sourceId = link.sourceId,
                label = linkTitle(detail.providerName.ifBlank { line.providerName }, line.name),
                driveType = cloud?.kind,
                episodes = JsonCodec.encodeEpisodes(line.episodes),
                rawVodId = link.rawVodId,
                qualityHint = cloud?.quality,
                updatedAt = now,
            )
        }
    }

    private suspend fun ensureSource(config: SourceConfigEntity): SourceEntity {
        dao.sourceHealth(config.id)?.let { return it }
        val created = sourceRow(config, failCount = 0, lastOkAt = 0L)
        dao.upsertSourceHealth(created)
        return created
    }

    private suspend fun record(config: SourceConfigEntity, ok: Boolean) {
        val previous = dao.sourceHealth(config.id)
        dao.upsertSourceHealth(
            sourceRow(
                config,
                failCount = nextFailCount(previous?.failCount ?: 0, ok),
                lastOkAt = if (ok) System.currentTimeMillis() else previous?.lastOkAt ?: 0L,
            ),
        )
    }

    private fun sourceRow(config: SourceConfigEntity, failCount: Int, lastOkAt: Long): SourceEntity {
        return SourceEntity(
            id = config.id,
            name = config.name,
            apiUrl = config.location,
            type = if (config.kind == SourceKinds.DRIVE) "drive" else "site",
            enabled = config.enabled,
            priority = config.priority,
            lastOkAt = lastOkAt,
            failCount = failCount,
        )
    }
}

private fun PlayLinkEntity.toStored(): StoredLink {
    return StoredLink(
        id = id,
        sourceId = sourceId,
        label = label,
        driveType = driveType,
        episodes = JsonCodec.decodeEpisodes(episodes),
        rawVodId = rawVodId,
        qualityHint = qualityHint,
    )
}

fun playbackOf(show: ShowRecord, link: StoredLink, episodeIndex: Int, positionMs: Long): PlaybackRequest {
    val episodes = link.episodes
    val index = if (episodes.isEmpty()) 0 else episodeIndex.coerceIn(0, episodes.lastIndex)
    return PlaybackRequest(
        mediaKey = show.id,
        title = show.title,
        poster = show.posterUrl,
        year = show.year?.toString(),
        refs = listOf(SourceRef(link.sourceId, link.rawVodId)),
        lines = listOf(
            PlayLine(
                providerId = link.sourceId,
                providerName = link.label,
                name = link.label,
                episodes = episodes,
            ),
        ),
        lineIndex = 0,
        episodeIndex = index,
        resumePositionMs = positionMs,
        playLinkId = link.id,
    )
}

fun playbackFor(session: ShowSession, signedIn: Set<String>): PlaybackRequest? {
    fun playable(stored: StoredLink): Boolean {
        return stored.episodes.isNotEmpty() &&
            driveTabEnabled(stored.driveType, stored.driveType == null || stored.driveType in signedIn)
    }
    val preferred = session.progress?.playLinkId?.takeIf { it.isNotBlank() }
    val link = session.links.firstOrNull { it.id == preferred && playable(it) }
        ?: session.links.firstOrNull { playable(it) }
        ?: return null
    val episodeIndex = link.episodes.indexOfFirst { it.name == session.progress?.episodeKey }.let { if (it < 0) 0 else it }
    val sameEpisode = link.episodes.getOrNull(episodeIndex)?.name == session.progress?.episodeKey
    val position = if (sameEpisode) session.progress?.positionMs ?: 0L else 0L
    return playbackOf(session.show, link, episodeIndex, position)
}
