package com.cloudreel.tv.feature.player

import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.cloudreel.tv.ui.Button
import com.cloudreel.tv.ui.OutlinedButton
import com.cloudreel.tv.ui.touchActivate
import androidx.tv.material3.Text
import com.cloudreel.tv.YingShiApp
import com.cloudreel.tv.core.catalog.ResumePolicy
import com.cloudreel.tv.core.catalog.readableError
import com.cloudreel.tv.core.drive.CloudLink
import com.cloudreel.tv.core.drive.Pan115File
import com.cloudreel.tv.core.drive.formatByteSize
import com.cloudreel.tv.core.drive.cloudAccountLabel
import com.cloudreel.tv.core.drive.lineCloud
import com.cloudreel.tv.core.drive.recognizeCloud
import com.cloudreel.tv.core.model.PlayLine
import com.cloudreel.tv.core.model.PlaybackRequest
import com.cloudreel.tv.core.model.WatchProgress
import com.cloudreel.tv.core.show.failoverCursor
import com.cloudreel.tv.player.PlaybackRequestStore
import com.cloudreel.tv.ui.theme.YingShiColors
import com.cloudreel.tv.ui.theme.YingShiTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            YingShiTheme {
                PlayerScreen(onExit = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(onExit: () -> Unit) {
    val request = PlaybackRequestStore.request
    if (request == null || request.lines.isEmpty() || request.lines.all { it.episodes.isEmpty() }) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("没有可播放的内容", color = YingShiColors.Text)
                Button(onClick = onExit) { Text("返回") }
            }
        }
        return
    }
    val context = LocalContext.current
    val app = context.applicationContext as YingShiApp
    val signedIn = remember {
        setOf("115", "quark", "ali", "baidu").filter { app.container.cloudSecret(it).isNotBlank() }.toSet()
    }
    val scope = rememberCoroutineScope()
    val dataSourceFactory = remember {
        DefaultHttpDataSource.Factory()
            .setUserAgent("YingShiTV/0.1 (Android TV)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
    }
    val player = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
    }
    var lineIndex by remember { mutableIntStateOf(request.lineIndex.coerceIn(0, request.lines.lastIndex)) }
    var episodeIndex by remember {
        val count = request.lines[lineIndex].episodes.size
        mutableIntStateOf(if (count == 0) 0 else request.episodeIndex.coerceIn(0, count - 1))
    }
    val cursor = remember { intArrayOf(0, 0) }
    cursor[0] = lineIndex
    cursor[1] = episodeIndex
    var pendingSeek by remember { mutableStateOf(request.resumePositionMs.takeIf { it > 0 }) }
    var playToken by remember { mutableIntStateOf(0) }
    var cloud by remember { mutableStateOf<CloudLink?>(null) }
    var cloudFile by remember { mutableStateOf<Pan115File?>(null) }
    var resolvingCloud by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var errorDetail by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsTick by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(1f) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var playbackState by remember { mutableIntStateOf(Player.STATE_IDLE) }
    val playFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }

    fun currentLine(): PlayLine? = request.lines.getOrNull(lineIndex)
    fun persist() {
        if (request.liveChannel) return
        val line = currentLine() ?: return
        val episode = line.episodes.getOrNull(episodeIndex) ?: return
        val pos = player.currentPosition.coerceAtLeast(0L)
        val dur = player.duration.takeIf { it > 0 } ?: 0L
        if (pos < ResumePolicy.START_SAVE_MS && !ResumePolicy.isCompleted(pos, dur)) return
        PlaybackRequestStore.updateCursor(lineIndex, episodeIndex, pos)
        val refresh = request.driveRefresh
        app.container.scope.launchCatching {
            app.container.repository.saveHistory(
                WatchProgress(
                    mediaKey = request.mediaKey,
                    title = request.title,
                    poster = request.poster,
                    year = request.year,
                    refs = if (refresh == null) request.refs else listOf(
                        com.cloudreel.tv.core.model.SourceRef(
                            com.cloudreel.tv.core.model.SourceKinds.DRIVE,
                            refresh.accountId,
                        ),
                    ),
                    lineIndex = lineIndex,
                    episodeIndex = episodeIndex,
                    lineName = refresh?.path ?: line.name,
                    episodeName = episode.name,
                    playUrl = episode.url,
                    headers = episode.headers,
                    positionMs = pos,
                    durationMs = dur,
                    playLinkId = request.playLinkIds.getOrNull(lineIndex)?.takeIf { it.isNotBlank() }
                        ?: request.playLinkId,
                ),
            )
        }
    }

    fun retryCurrent() {
        val refresh = request.driveRefresh
        if (refresh == null) {
            pendingSeek = player.currentPosition.coerceAtLeast(0L)
            playToken += 1
            return
        }
        scope.launch {
            try {
                val episode = app.container.repository.refreshDrive(refresh)
                PlaybackRequestStore.replaceCurrentUrl(episode.url, episode.headers)
            } catch (_: Exception) {
            }
            pendingSeek = player.currentPosition.coerceAtLeast(0L)
            playToken += 1
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val names = request.lines.map { line -> line.episodes.map { it.name } }
                val next = failoverCursor(names, cursor[0], cursor[1], request.liveChannel)
                if (next != null) {
                    cursor[0] = next.first
                    cursor[1] = next.second
                    lineIndex = next.first
                    episodeIndex = next.second
                    notice = if (request.liveChannel) {
                        "当前地址失败，已切换下一条"
                    } else {
                        "当前地址失败，已切换${request.lines[next.first].name}"
                    }
                    errorText = null
                    errorDetail = null
                } else {
                    errorText = readablePlaybackError(error)
                    errorDetail = error.errorCodeName
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    errorText = null
                    errorDetail = null
                }
            }
        }
        player.addListener(listener)
        onDispose {
            persist()
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        while (isActive) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.takeIf { it > 0 } ?: 0L
            playing = player.isPlaying
            playbackState = player.playbackState
            delay(500)
        }
    }
    LaunchedEffect(player) {
        while (isActive) {
            delay(8_000)
            persist()
        }
    }
    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }
    LaunchedEffect(lineIndex, episodeIndex, playToken) {
        val line = request.lines.getOrNull(lineIndex)
        val episode = line?.episodes?.getOrNull(episodeIndex)
        if (episode == null || episode.url.isBlank()) {
            cloud = null
            cloudFile = null
            resolvingCloud = false
            errorText = "这条线路没有可播放地址"
            errorDetail = null
            return@LaunchedEffect
        }
        val pan = recognizeCloud(line.name, episode.name, episode.url)
        cloud = pan
        cloudFile = null
        errorText = null
        errorDetail = null
        val playUrl: String
        val headers: Map<String, String>
        if (pan == null) {
            resolvingCloud = false
            playUrl = episode.url
            headers = episode.headers
        } else {
            resolvingCloud = true
            val secret = app.container.cloudSecret(pan.kind)
            if (secret.isBlank()) {
                resolvingCloud = false
                errorText = "这是 ${pan.title} 文件。请到网盘页面扫码登录后再播放。"
                errorDetail = null
                return@LaunchedEffect
            }
            try {
                val play = app.container.repository.resolveCloudLink(pan, secret)
                play.updatedSecret?.let { app.container.saveCloudSecret(pan.kind, it) }
                cloudFile = Pan115File(play.name, play.sizeBytes, play.url)
                playUrl = play.url
                headers = play.headers
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                resolvingCloud = false
                errorText = readableError(error)
                errorDetail = null
                return@LaunchedEffect
            }
            resolvingCloud = false
        }
        dataSourceFactory.setDefaultRequestProperties(headers)
        val builder = MediaItem.Builder().setUri(playUrl)
        mimeOf(playUrl)?.let { builder.setMimeType(it) }
        val seek = pendingSeek ?: 0L
        pendingSeek = null
        player.setMediaItem(builder.build(), seek)
        player.prepare()
        player.playWhenReady = true
    }
    LaunchedEffect(controlsVisible, controlsTick, playing, errorText) {
        if (controlsVisible && playing && errorText == null) {
            delay(6_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2_500)
            notice = null
        }
    }
    LaunchedEffect(controlsVisible, errorText) {
        if (errorText != null) retryFocus.requestFocus() else if (controlsVisible) playFocus.requestFocus()
    }
    BackHandler {
        if (controlsVisible && errorText == null) controlsVisible = false else onExit()
    }
    fun showControls() {
        controlsVisible = true
        controlsTick += 1
    }
    fun seekBy(delta: Long) {
        val target = (player.currentPosition + delta).coerceAtLeast(0L)
        val limit = player.duration.takeIf { it > 0 }
        player.seekTo(if (limit == null) target else target.coerceAtMost(limit))
    }
    fun switchLine(keepPosition: Boolean) {
        if (request.lines.size < 2) return
        val pos = if (keepPosition) player.currentPosition.coerceAtLeast(0L) else 0L
        val next = (lineIndex + 1) % request.lines.size
        lineIndex = next
        val count = request.lines[next].episodes.size
        if (count == 0) episodeIndex = 0 else if (episodeIndex > count - 1) episodeIndex = 0
        pendingSeek = pos
        notice = "已切换到 ${request.lines[next].name}"
        showControls()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || errorText != null || controlsVisible) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        player.playWhenReady = !player.playWhenReady
                        showControls()
                        true
                    }
                    Key.DirectionLeft -> {
                        seekBy(-10_000)
                        showControls()
                        true
                    }
                    Key.DirectionRight -> {
                        seekBy(10_000)
                        showControls()
                        true
                    }
                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    this.player = player
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (errorText == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .touchActivate {
                        if (controlsVisible) controlsVisible = false else showControls()
                    },
            )
        }
        if (resolvingCloud && errorText == null) {
            CloudCard(
                link = cloud,
                file = cloudFile,
                status = "正在获取${cloud?.title ?: "网盘"}播放地址…",
                modifier = Modifier.align(Alignment.Center),
            )
        } else if (playbackState == Player.STATE_BUFFERING && errorText == null) {
            Text("正在缓冲…", color = Color.White, modifier = Modifier.align(Alignment.Center))
        }
        notice?.let { text ->
            Text(
                text,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 28.dp)
                    .background(Color(0xCC15191F))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (errorText != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xEE0B0D10))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (cloud != null) {
                    Text("${cloud?.title.orEmpty()} · ${cloud?.quality.orEmpty()}", color = YingShiColors.Text, fontSize = 18.sp)
                    Text(cloudFile?.name ?: cloud?.fileName.orEmpty(), color = YingShiColors.Sub, fontSize = 14.sp)
                    Text(cloud?.shareId.orEmpty(), color = YingShiColors.Sub, fontSize = 13.sp)
                    formatByteSize(cloudFile?.sizeBytes ?: 0L).takeIf { it.isNotBlank() }?.let {
                        Text(it, color = YingShiColors.Sub, fontSize = 14.sp)
                    }
                }
                Text(errorText.orEmpty(), color = YingShiColors.Error, fontSize = 20.sp)
                errorDetail?.let { Text(it, color = YingShiColors.Sub, fontSize = 13.sp) }
                Button(
                    onClick = { retryCurrent() },
                    modifier = Modifier.focusRequester(retryFocus),
                ) { Text(if (request.driveRefresh == null && cloud == null) "重试当前地址" else "重新获取地址") }
                if (request.lines.size > 1) Button(onClick = { switchLine(false) }) { Text("切换线路") }
                OutlinedButton(onClick = onExit) { Text("返回") }
            }
        } else if (controlsVisible) {
            val line = currentLine()
            val episode = line?.episodes?.getOrNull(episodeIndex)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xCC0B0D10))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(request.title, color = YingShiColors.Text, fontSize = 20.sp)
                Text(
                    listOfNotNull(
                        cloud?.let { "${it.title} · ${it.quality}" } ?: line?.name?.takeIf { it.isNotBlank() },
                        cloudFile?.name ?: episode?.name?.takeIf { it.isNotBlank() },
                        formatByteSize(cloudFile?.sizeBytes ?: 0L).ifBlank { null },
                        "${formatClock(position)} / ${formatClock(duration)}",
                        "倍速 $speed",
                    ).joinToString("    "),
                    color = YingShiColors.Sub,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { player.playWhenReady = !player.isPlaying },
                        modifier = Modifier.focusRequester(playFocus),
                    ) { Text(if (playing) "暂停" else "播放") }
                    OutlinedButton(onClick = { seekBy(-10_000) }) { Text("后退") }
                    OutlinedButton(onClick = { seekBy(10_000) }) { Text("前进") }
                    OutlinedButton(onClick = {
                        val options = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 2f)
                        speed = options[(options.indexOfFirst { it == speed } + 1).mod(options.size)]
                    }) { Text("倍速") }
                    if (request.lines.size > 1) OutlinedButton(onClick = { switchLine(true) }) { Text("切换线路") }
                    OutlinedButton(onClick = onExit) { Text("返回") }
                }
                if ((line?.episodes?.size ?: 0) > 1) {
                    Text("选集", color = YingShiColors.Sub)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(line?.episodes.orEmpty()) { index, item ->
                            if (index == episodeIndex) {
                                Button(onClick = {
                                    pendingSeek = 0
                                    episodeIndex = index
                                }) { Text(item.name) }
                            } else {
                                OutlinedButton(onClick = {
                                    pendingSeek = 0
                                    episodeIndex = index
                                }) { Text(item.name) }
                            }
                        }
                    }
                }
                if (request.lines.size > 1) {
                    Text("线路", color = YingShiColors.Sub)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(request.lines) { index, item ->
                            val cloud = lineCloud(item.name, item.episodes.map { it.name to it.url })
                            val loggedIn = cloud != null && cloud.kind in signedIn
                            val content: @Composable RowScope.() -> Unit = {
                                Column {
                                    Text("${item.providerName} · ${item.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (cloud != null) {
                                        Text(
                                            cloudAccountLabel(cloud.title, loggedIn),
                                            color = if (loggedIn) YingShiColors.Success else YingShiColors.Warning,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            if (index == lineIndex) {
                                Button(onClick = {
                                    pendingSeek = player.currentPosition.coerceAtLeast(0L)
                                    lineIndex = index
                                }, content = content)
                            } else {
                                OutlinedButton(onClick = {
                                    pendingSeek = player.currentPosition.coerceAtLeast(0L)
                                    lineIndex = index
                                }, content = content)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudCard(link: CloudLink?, file: Pan115File?, status: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xEE0B0D10))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${link?.title.orEmpty()} · ${link?.quality.orEmpty()}", color = YingShiColors.Text, fontSize = 20.sp)
        Text(file?.name ?: link?.fileName.orEmpty(), color = YingShiColors.Sub)
        Text(link?.shareId.orEmpty(), color = YingShiColors.Sub, fontSize = 13.sp)
        formatByteSize(file?.sizeBytes ?: 0L).takeIf { it.isNotBlank() }?.let {
            Text(it, color = YingShiColors.Sub)
        }
        Text(status, color = Color.White)
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchCatching(block: suspend () -> Unit) {
    launch {
        try {
            block()
        } catch (_: Exception) {
        }
    }
}

private fun mimeOf(url: String): String? = when {
    url.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
    url.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
    url.contains(".mp4", ignoreCase = true) -> MimeTypes.VIDEO_MP4
    else -> null
}

private fun readablePlaybackError(error: PlaybackException): String = when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    -> "网络连接失败"
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "播放地址返回错误"
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "找不到播放地址"
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    -> "这个格式暂时解不了"
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    -> "解码失败，可以换一条线路"
    else -> "播放失败"
}

private fun formatClock(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val total = ms / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}
