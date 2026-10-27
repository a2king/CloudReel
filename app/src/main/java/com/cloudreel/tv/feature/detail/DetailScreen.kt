package com.cloudreel.tv.feature.detail

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cloudreel.tv.ui.Button
import com.cloudreel.tv.ui.OutlinedButton
import com.cloudreel.tv.ui.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.cloudreel.tv.core.catalog.ResumePolicy
import com.cloudreel.tv.core.drive.CloudLink
import com.cloudreel.tv.core.drive.cloudAccountLabel
import com.cloudreel.tv.core.drive.lineCloud
import com.cloudreel.tv.core.catalog.readableError
import com.cloudreel.tv.core.database.FavoriteEntity
import com.cloudreel.tv.core.database.JsonCodec
import com.cloudreel.tv.core.database.PlayHistoryEntity
import com.cloudreel.tv.core.model.AggregatedDetail
import com.cloudreel.tv.core.model.DetailRequest
import com.cloudreel.tv.core.model.PlayLine
import com.cloudreel.tv.core.model.PlaybackRequest
import com.cloudreel.tv.data.DetailNavStore
import com.cloudreel.tv.data.MediaRepository
import com.cloudreel.tv.feature.player.PlayerActivity
import com.cloudreel.tv.player.PlaybackRequestStore
import com.cloudreel.tv.ui.LocalContainer
import com.cloudreel.tv.ui.theme.YingShiColors
import com.cloudreel.tv.ui.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Ready(
        val detail: AggregatedDetail,
        val history: PlayHistoryEntity?,
        val favorite: Boolean,
    ) : DetailUiState

    data class Failed(val message: String, val history: PlayHistoryEntity?) : DetailUiState
}

class DetailViewModel(
    private val repository: MediaRepository,
    private val request: DetailRequest,
) : ViewModel() {
    private val _state = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state = _state.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            _state.value = DetailUiState.Loading
            val history = runCatching { repository.historyOf(request.mediaKey) }.getOrNull()
            try {
                val detail = repository.loadDetail(request.refs)
                _state.value = DetailUiState.Ready(detail, history, repository.isFavorite(request.mediaKey))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = DetailUiState.Failed(readableError(error), history)
            }
        }
    }

    fun refreshSide() {
        val current = _state.value as? DetailUiState.Ready ?: return
        viewModelScope.launch {
            _state.value = current.copy(
                history = repository.historyOf(request.mediaKey),
                favorite = repository.isFavorite(request.mediaKey),
            )
        }
    }

    fun toggleFavorite() {
        val current = _state.value as? DetailUiState.Ready ?: return
        viewModelScope.launch {
            val favorite = repository.toggleFavorite(current.detail.toFavorite(request), current.favorite)
            _state.value = current.copy(favorite = favorite)
        }
    }
}

private fun AggregatedDetail.toFavorite(request: DetailRequest): FavoriteEntity {
    val first = request.refs.first()
    return FavoriteEntity(
        mediaKey = request.mediaKey,
        title = title,
        poster = poster,
        year = year,
        providerId = first.providerId,
        sourceId = first.sourceId,
        refsJson = JsonCodec.encodeRefs(request.refs),
        addedAt = System.currentTimeMillis(),
    )
}

private data class PlayStart(
    val lineIndex: Int,
    val episodeIndex: Int,
    val positionMs: Long,
    val label: String,
)

private fun playStart(detail: AggregatedDetail, history: PlayHistoryEntity?): PlayStart {
    if (detail.lines.isEmpty()) return PlayStart(0, 0, 0, "立即播放")
    if (history != null && ResumePolicy.shouldContinue(history.positionMs, history.durationMs)) {
        val line = history.lineIndex.coerceIn(0, detail.lines.lastIndex)
        val episodes = detail.lines[line].episodes
        if (episodes.isNotEmpty()) {
            return PlayStart(line, history.episodeIndex.coerceIn(0, episodes.lastIndex), history.positionMs, "继续播放")
        }
    }
    if (history?.completed == true) {
        val line = history.lineIndex.coerceIn(0, detail.lines.lastIndex)
        val next = history.episodeIndex + 1
        if (next <= detail.lines[line].episodes.lastIndex) return PlayStart(line, next, 0, "播放下一集")
    }
    return PlayStart(0, 0, 0, "立即播放")
}

@Composable
fun DetailScreen(token: String, onBack: () -> Unit) {
    val request = DetailNavStore.get(token)
    if (request == null) {
        MessageScreen("这条内容已经失效，请返回重新打开", onBack)
        return
    }
    val repository = LocalContainer.current.repository
    val viewModel: DetailViewModel = viewModel(factory = viewModelFactory { DetailViewModel(repository, request) })
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshSide()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    when (val current = state) {
        DetailUiState.Loading -> MessageScreen("正在读取详情…", onBack, showBack = false)
        is DetailUiState.Failed -> FailedDetail(current, request, onBack, viewModel::reload)
        is DetailUiState.Ready -> ReadyDetail(current, request, onBack, viewModel::reload, viewModel::toggleFavorite)
    }
}

@Composable
private fun MessageScreen(message: String, onBack: () -> Unit, showBack: Boolean = true) {
    Column(modifier = Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(message, color = YingShiColors.Text, fontSize = 18.sp)
        if (showBack) OutlinedButton(onClick = onBack) { Text("返回") }
    }
}

@Composable
private fun FailedDetail(
    state: DetailUiState.Failed,
    request: DetailRequest,
    onBack: () -> Unit,
    onReload: () -> Unit,
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(state.message, color = YingShiColors.Error, fontSize = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onReload) { Text("重试") }
            val history = state.history
            if (history != null && history.playUrl.isNotBlank()) {
                Button(onClick = {
                    PlaybackRequestStore.set(history.toPlayback(request))
                    context.startActivity(Intent(context, PlayerActivity::class.java))
                }) { Text("使用上次地址继续") }
            }
            OutlinedButton(onClick = onBack) { Text("返回") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadyDetail(
    state: DetailUiState.Ready,
    request: DetailRequest,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val detail = state.detail
    val context = LocalContext.current
    val container = LocalContainer.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var loginTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) loginTick += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val signedIn = remember(loginTick) {
        setOf("115", "quark", "ali", "baidu").filter { container.cloudSecret(it).isNotBlank() }.toSet()
    }
    val start = playStart(detail, state.history)
    var lineIndex by remember(detail) { mutableIntStateOf(start.lineIndex.coerceAtLeast(0)) }
    var expanded by remember(detail) { mutableStateOf(false) }
    val multipleSources = detail.lines.map { it.providerId }.distinct().size > 1
    fun play(index: Int, episodeIndex: Int, positionMs: Long) {
        val line = detail.lines.getOrNull(index) ?: return
        if (line.episodes.isEmpty()) return
        PlaybackRequestStore.set(
            PlaybackRequest(
                mediaKey = request.mediaKey,
                title = detail.title,
                poster = detail.poster,
                year = detail.year,
                refs = request.refs,
                lines = detail.lines,
                lineIndex = index,
                episodeIndex = episodeIndex.coerceIn(0, line.episodes.lastIndex),
                resumePositionMs = positionMs,
            ),
        )
        context.startActivity(Intent(context, PlayerActivity::class.java))
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(28.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (detail.poster.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .width(220.dp)
                                .height(320.dp)
                                .padding(1.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(detail.title.take(1), fontSize = 40.sp, color = YingShiColors.Primary)
                        }
                    } else {
                        AsyncImage(
                            model = detail.poster,
                            contentDescription = detail.title,
                            modifier = Modifier
                                .width(220.dp)
                                .height(320.dp),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(detail.title, fontSize = 28.sp, color = YingShiColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(metaLine(detail), color = YingShiColors.Sub, fontSize = 14.sp)
                        if (detail.directors.isNotEmpty()) {
                            Text("导演  ${detail.directors.joinToString("、")}", color = YingShiColors.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (detail.actors.isNotEmpty()) {
                            Text("演员  ${detail.actors.joinToString("、")}", color = YingShiColors.Sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (detail.description.isNotBlank()) {
                            Text(
                                detail.description,
                                color = YingShiColors.Text,
                                maxLines = if (expanded) Int.MAX_VALUE else 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text("来自 ${detail.sourceCount} 个源", color = YingShiColors.Sub, fontSize = 13.sp)
                        if (detail.failures.isNotEmpty()) {
                            Text(detail.failures.joinToString("  "), color = YingShiColors.Warning, fontSize = 13.sp)
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { play(start.lineIndex, start.episodeIndex, start.positionMs) },
                        enabled = detail.lines.isNotEmpty(),
                    ) { Text(start.label) }
                    if (start.label != "立即播放" && detail.lines.isNotEmpty()) {
                        OutlinedButton(onClick = { play(lineIndex.coerceIn(0, detail.lines.lastIndex), 0, 0) }) {
                            Text("从头播放")
                        }
                    }
                    OutlinedButton(onClick = onToggleFavorite) { Text(if (state.favorite) "已收藏" else "收藏") }
                    OutlinedButton(onClick = onReload) { Text("刷新") }
                    if (detail.description.length > 80) {
                        OutlinedButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起简介" else "展开简介") }
                    }
                    OutlinedButton(onClick = onBack) { Text("返回") }
                }
                if (detail.lines.isEmpty()) {
                    Text("没有可播放地址", color = YingShiColors.Error)
                } else {
                    Text("播放线路", color = YingShiColors.Text, fontSize = 18.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(detail.lines) { index, line ->
                            val cloud = lineCloud(line.name, line.episodes.map { it.name to it.url })
                            val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                                LineCloudLabel(
                                    lineLabel(line, multipleSources, line.episodes.size),
                                    cloud,
                                    cloud != null && cloud.kind in signedIn,
                                )
                            }
                            if (index == lineIndex) {
                                Button(onClick = { lineIndex = index }, content = content)
                            } else {
                                OutlinedButton(onClick = { lineIndex = index }, content = content)
                            }
                        }
                    }
                    val current = detail.lines.getOrNull(lineIndex)
                    val cloud = current?.let { lineCloud(it.name, it.episodes.map { episode -> episode.name to episode.url }) }
                    if (cloud != null) {
                        val loggedIn = cloud.kind in signedIn
                        Text(
                            cloudAccountLabel(cloud.title, loggedIn),
                            color = if (loggedIn) YingShiColors.Success else YingShiColors.Warning,
                            fontSize = 14.sp,
                        )
                        if (!loggedIn) {
                            Text("请先到网盘页面扫码登录", color = YingShiColors.Warning, fontSize = 13.sp)
                        }
                    }
                    Text("选集", color = YingShiColors.Text, fontSize = 18.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        val episodes = detail.lines.getOrNull(lineIndex)?.episodes.orEmpty()
        itemsIndexed(episodes) { index, episode ->
            val watched = state.history?.lineIndex == lineIndex && state.history.episodeIndex == index
            Surface(selected = watched, onClick = { play(lineIndex, index, 0) }, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text(episode.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.LineCloudLabel(title: String, cloud: CloudLink?, loggedIn: Boolean) {
    Column {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

private fun lineLabel(line: PlayLine, multipleSources: Boolean, count: Int): String {
    val name = if (multipleSources) "${line.providerName} · ${line.name}" else line.name
    return "$name  ${count}集"
}

private fun metaLine(detail: AggregatedDetail): String {
    return listOfNotNull(
        detail.year,
        detail.area,
        detail.genres.joinToString(" / ").ifBlank { null },
        detail.remarks,
    ).joinToString("   ·   ")
}

private fun PlayHistoryEntity.toPlayback(request: DetailRequest): PlaybackRequest {
    val position = if (ResumePolicy.shouldContinue(positionMs, durationMs)) positionMs else 0L
    return PlaybackRequest(
        mediaKey = request.mediaKey,
        title = title,
        poster = poster,
        year = year,
        refs = request.refs,
        lines = listOf(
            PlayLine(
                providerId = providerId,
                providerName = lineName,
                name = lineName,
                episodes = listOf(
                    com.cloudreel.tv.core.model.Episode(episodeName, playUrl, JsonCodec.decodeHeaders(headersJson)),
                ),
            ),
        ),
        lineIndex = 0,
        episodeIndex = 0,
        resumePositionMs = position,
    )
}
