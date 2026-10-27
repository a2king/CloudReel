package com.cloudreel.tv.core.model

enum class PlayType {
    DIRECT,
    PARSE,
    PROXY,
    DRIVE,
}

data class SourceRef(
    val providerId: String,
    val sourceId: String,
)

data class Episode(
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

data class PlayLine(
    val providerId: String,
    val providerName: String,
    val name: String,
    val episodes: List<Episode>,
)

data class MediaCard(
    val providerId: String,
    val providerName: String,
    val sourceId: String,
    val title: String,
    val poster: String?,
    val year: String?,
    val area: String?,
    val remarks: String?,
)

data class MediaDetail(
    val providerId: String,
    val providerName: String,
    val sourceId: String,
    val title: String,
    val poster: String?,
    val year: String?,
    val area: String?,
    val genres: List<String>,
    val directors: List<String>,
    val actors: List<String>,
    val description: String,
    val remarks: String?,
    val lines: List<PlayLine>,
)

data class SearchHit(
    val providerId: String,
    val providerName: String,
    val priority: Int,
    val sourceId: String,
    val title: String,
    val poster: String?,
    val year: String?,
    val area: String?,
    val genres: List<String>,
    val remarks: String?,
)

data class MergedHit(
    val title: String,
    val poster: String?,
    val year: String?,
    val area: String?,
    val genres: List<String>,
    val hits: List<SearchHit>,
)

data class AggregatedDetail(
    val title: String,
    val poster: String?,
    val year: String?,
    val area: String?,
    val genres: List<String>,
    val directors: List<String>,
    val actors: List<String>,
    val description: String,
    val remarks: String?,
    val lines: List<PlayLine>,
    val failures: List<String>,
    val sourceCount: Int,
)

data class DetailRequest(
    val mediaKey: String,
    val refs: List<SourceRef>,
)

data class PlaybackRequest(
    val mediaKey: String,
    val title: String,
    val poster: String?,
    val year: String?,
    val refs: List<SourceRef>,
    val lines: List<PlayLine>,
    val lineIndex: Int,
    val episodeIndex: Int,
    val resumePositionMs: Long,
    val liveChannel: Boolean = false,
    val driveRefresh: DriveRefresh? = null,
    val playLinkId: String = "",
    val playLinkIds: List<String> = emptyList(),
)

data class WatchProgress(
    val mediaKey: String,
    val title: String,
    val poster: String?,
    val year: String?,
    val refs: List<SourceRef>,
    val lineIndex: Int,
    val episodeIndex: Int,
    val lineName: String,
    val episodeName: String,
    val playUrl: String,
    val headers: Map<String, String>,
    val positionMs: Long,
    val durationMs: Long,
    val playLinkId: String = "",
)

data class DriveRefresh(
    val accountId: String,
    val path: String,
)

data class LiveChannel(
    val id: String,
    val name: String,
    val group: String,
    val logo: String?,
    val tvgId: String?,
    val urls: List<String>,
)

data class LiveProgram(
    val channelId: String,
    val title: String,
    val startAt: Long,
    val endAt: Long,
    val description: String,
)

data class DriveEntry(
    val name: String,
    val path: String,
    val directory: Boolean,
    val playable: Boolean,
)

object SourceKinds {
    const val ASSET = "asset"
    const val CMS = "cms"
    const val TVBOX = "tvbox"
    const val SCRIPT = "script"
    const val DRIVE = "drive"
}

object DriveKinds {
    const val WEBDAV = "webdav"
    const val ALIST = "alist"
}
