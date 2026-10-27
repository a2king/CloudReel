package com.cloudreel.tv.feature.detail

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.cloudreel.tv.core.catalog.ResumePolicy
import com.cloudreel.tv.core.catalog.readableError
import com.cloudreel.tv.core.database.FavoriteEntity
import com.cloudreel.tv.core.database.JsonCodec
import com.cloudreel.tv.core.model.SourceRef
import com.cloudreel.tv.core.show.displayTab
import com.cloudreel.tv.core.show.mediaTypeLabel
import com.cloudreel.tv.data.ShowNavStore
import com.cloudreel.tv.data.ShowSession
import com.cloudreel.tv.data.StoredLink
import com.cloudreel.tv.data.playbackOf
import com.cloudreel.tv.feature.player.PlayerActivity
import com.cloudreel.tv.player.PlaybackRequestStore
import com.cloudreel.tv.ui.Button
import com.cloudreel.tv.ui.LocalContainer
import com.cloudreel.tv.ui.OutlinedButton
import com.cloudreel.tv.ui.PosterFace
import com.cloudreel.tv.ui.Surface
import com.cloudreel.tv.ui.isPhoneLayout
import com.cloudreel.tv.ui.theme.YingShiColors
import com.cloudreel.tv.ui.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ShowDetailUi {
    data object Loading : ShowDetailUi
    data class Ready(val session: ShowSession, val favorite: Boolean) : ShowDetailUi
    data class Failed(val message: String) : ShowDetailUi
}

class ShowDetailViewModel(
    private val repository: com.cloudreel.tv.data.MediaRepository,
    private val showId: String,
) : ViewModel() {
    private val _state = MutableStateFlow<ShowDetailUi>(ShowDetailUi.Loading)
    val state = _state.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            _state.value = ShowDetailUi.Loading
            try {
                val session = repository.loadShowSession(showId)
                _state.value = ShowDetailUi.Ready(session, repository.isFavorite(showId))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = ShowDetailUi.Failed(readableError(error))
            }
        }
    }

    fun toggleFavorite() {
        val current = _state.value as? ShowDetailUi.Ready ?: return
        viewModelScope.launch {
            val show = current.session.show
            val refs = current.session.links.map { SourceRef(it.sourceId, it.rawVodId) }
            val favorite = repository.toggleFavorite(
                FavoriteEntity(
                    mediaKey = show.id,
                    title = show.title,
                    poster = show.posterUrl,
                    year = show.year?.toString(),
                    providerId = refs.firstOrNull()?.providerId.orEmpty(),
                    sourceId = refs.firstOrNull()?.sourceId.orEmpty(),
                    refsJson = JsonCodec.encodeRefs(refs),
                    addedAt = System.currentTimeMillis(),
                ),
                current.favorite,
            )
            _state.value = current.copy(favorite = favorite)
        }
    }
}

@Composable
fun ShowDetailScreen(token: String, onBack: () -> Unit, onOpenDrive: () -> Unit) {
    val showId = ShowNavStore.get(token)
    if (showId.isNullOrBlank()) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("这条内容已经失效，请返回重新打开", color = YingShiColors.Text)
            OutlinedButton(onClick = onBack) { Text("返回") }
        }
        return
    }
    val repository = LocalContainer.current.repository
    val viewModel: ShowDetailViewModel = viewModel(factory = viewModelFactory { ShowDetailViewModel(repository, showId) })
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val current = state) {
        ShowDetailUi.Loading -> Text("正在读取播放地址…", color = YingShiColors.Sub, modifier = Modifier.padding(24.dp))
        is ShowDetailUi.Failed -> Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(current.message, color = YingShiColors.Error)
            Button(onClick = viewModel::reload) { Text("重试") }
            OutlinedButton(onClick = onBack) { Text("返回") }
        }
        is ShowDetailUi.Ready -> ReadyShow(current, onBack, onOpenDrive, viewModel::toggleFavorite, viewModel::reload)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadyShow(
    state: ShowDetailUi.Ready,
    onBack: () -> Unit,
    onOpenDrive: () -> Unit,
    onToggleFavorite: () -> Unit,
    onReload: () -> Unit,
) {
    val phone = isPhoneLayout()
    val show = state.session.show
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
    val links = state.session.links
    var linkIndex by remember(links.map { it.id }) { mutableIntStateOf(resumeLink(state.session, signedIn)) }
    val current = links.getOrNull(linkIndex)
    val resume = resumeEpisode(state.session, current)
    fun play(index: Int, episodeIndex: Int, positionMs: Long) {
        val link = links.getOrNull(index) ?: return
        if (link.driveType != null && link.driveType !in signedIn) {
            onOpenDrive()
            return
        }
        if (link.episodes.isEmpty()) return
        PlaybackRequestStore.set(playbackOf(show, link, episodeIndex, positionMs))
        context.startActivity(Intent(context, PlayerActivity::class.java))
    }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(modifier = Modifier.fillMaxWidth().height(if (phone) 220.dp else 360.dp)) {
            val art = show.backdropUrl ?: show.posterUrl
            if (art.isNullOrBlank()) {
                PosterFace(show.title, null, Modifier.fillMaxSize())
            } else {
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().let { if (Build.VERSION.SDK_INT >= 31) it.blur(if (phone) 8.dp else 24.dp) else it },
                    contentScale = ContentScale.Crop,
                )
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, YingShiColors.Bg))))
            Column(modifier = Modifier.align(Alignment.BottomStart).padding(if (phone) 12.dp else 24.dp)) {
                Text(show.title, color = YingShiColors.Text, fontSize = if (phone) 22.sp else 32.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(detailMeta(show), color = YingShiColors.Sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = if (phone) 12.dp else 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (show.overview.isNotBlank()) {
                Text(show.overview, color = YingShiColors.Text, fontSize = if (phone) 14.sp else 16.sp)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { play(resume.linkIndex, resume.episodeIndex, resume.positionMs) },
                    enabled = links.any { it.episodes.isNotEmpty() },
                ) { Text(resume.label) }
                OutlinedButton(onClick = onToggleFavorite) { Text(if (state.favorite) "已收藏" else "收藏") }
                OutlinedButton(onClick = onReload) { Text("刷新") }
                OutlinedButton(onClick = onBack) { Text("返回") }
            }
            if (links.isEmpty()) {
                Text("还没有播放地址", color = YingShiColors.Error)
            } else {
                Text("播放源", color = YingShiColors.Text, fontSize = 16.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    links.forEachIndexed { index, link ->
                        val loggedIn = link.driveType == null || link.driveType in signedIn
                        val label = displayTab(link.label, link.driveType, link.driveType != null && link.driveType in signedIn)
                        val click = {
                            if (!loggedIn) onOpenDrive() else linkIndex = index
                        }
                        if (index == linkIndex && loggedIn) {
                            Button(onClick = click) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        } else {
                            OutlinedButton(onClick = click) {
                                Text(
                                    label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (loggedIn) YingShiColors.Text else YingShiColors.Sub,
                                )
                            }
                        }
                    }
                }
                if (links.any { it.driveType != null && it.driveType !in signedIn }) {
                    Text("网盘未登录的源已灰掉，请到网盘页扫码登录。", color = YingShiColors.Warning, fontSize = 13.sp)
                }
                Text("选集", color = YingShiColors.Text, fontSize = 16.sp)
                val episodes = current?.episodes.orEmpty()
                if (episodes.isEmpty()) {
                    Text("这条源还没有可播放的地址", color = YingShiColors.Sub)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    episodes.forEachIndexed { index, episode ->
                        Surface(selected = resume.episodeIndex == index && resume.linkIndex == linkIndex, onClick = { play(linkIndex, index, 0) }) {
                            Text(episode.name, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

private data class ResumePoint(val linkIndex: Int, val episodeIndex: Int, val positionMs: Long, val label: String)

private fun resumeLink(session: ShowSession, signedIn: Set<String>): Int {
    val index = session.links.indexOfFirst { it.episodes.isNotEmpty() && (it.driveType == null || it.driveType in signedIn) }
    return index.coerceAtLeast(0)
}

private fun resumeEpisode(session: ShowSession, link: StoredLink?): ResumePoint {
    if (link == null || link.episodes.isEmpty()) return ResumePoint(0, 0, 0L, "立即播放")
    val progress = session.progress
    val episodeIndex = link.episodes.indexOfFirst { it.name == progress?.episodeKey }.let { if (it < 0) 0 else it }
    if (progress != null &&
        link.episodes[episodeIndex].name == progress.episodeKey &&
        ResumePolicy.shouldContinue(progress.positionMs, progress.durationMs)
    ) {
        return ResumePoint(session.links.indexOf(link).coerceAtLeast(0), episodeIndex, progress.positionMs, "继续播放")
    }
    return ResumePoint(session.links.indexOf(link).coerceAtLeast(0), episodeIndex, 0L, "立即播放")
}

private fun detailMeta(show: com.cloudreel.tv.core.show.ShowRecord): String {
    return listOfNotNull(
        show.year?.toString(),
        mediaTypeLabel(show.mediaType),
        show.rating?.let { "评分 ${"%.1f".format(it)}" },
        show.genres.take(3).joinToString(" / ").ifBlank { null },
    ).joinToString("  ·  ")
}
