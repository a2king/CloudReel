package com.cloudreel.tv.feature.home

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.cloudreel.tv.core.catalog.readableError
import com.cloudreel.tv.core.show.ShowQuery
import com.cloudreel.tv.core.show.ShowRecord
import com.cloudreel.tv.core.show.ShowSort
import com.cloudreel.tv.core.show.axisGenres
import com.cloudreel.tv.core.show.buildShelves
import com.cloudreel.tv.core.show.mediaTypeLabel
import com.cloudreel.tv.data.MediaRepository
import com.cloudreel.tv.data.playbackFor
import com.cloudreel.tv.ui.Button
import com.cloudreel.tv.ui.LocalContainer
import com.cloudreel.tv.ui.OutlinedButton
import com.cloudreel.tv.ui.PosterFace
import com.cloudreel.tv.ui.SectionTitle
import com.cloudreel.tv.ui.Surface
import com.cloudreel.tv.ui.isPhoneLayout
import com.cloudreel.tv.ui.openShow
import com.cloudreel.tv.ui.startPlayback
import com.cloudreel.tv.ui.theme.YingShiColors
import com.cloudreel.tv.ui.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val syncing: Boolean = false,
    val shows: List<ShowRecord> = emptyList(),
    val continuing: List<ShowRecord> = emptyList(),
    val mediaType: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val sort: ShowSort = ShowSort.RELEASE_DATE,
    val focusedId: String? = null,
    val error: String? = null,
)

class HomeViewModel(
    private val repository: MediaRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state = _state.asStateFlow()
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            repository.observeShowRecords().collect { shows ->
                _state.update { it.copy(shows = shows, loading = false) }
            }
        }
        viewModelScope.launch {
            repository.observeContinueRecords().collect { rows ->
                _state.update { it.copy(continuing = rows) }
            }
        }
        refresh()
    }

    fun refresh(manual: Boolean = false) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(syncing = true, error = null) }
            try {
                repository.refreshShows(manual = manual)
                _state.update { it.copy(syncing = false, loading = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(syncing = false, loading = false, error = readableError(error)) }
            }
        }
    }

    fun setType(type: String?) = _state.update { it.copy(mediaType = type) }

    fun setGenre(genre: String?) = _state.update { it.copy(genre = genre) }

    fun setYear(year: Int?) = _state.update { it.copy(year = year) }

    fun setSort(sort: ShowSort) = _state.update { it.copy(sort = sort) }

    fun focus(id: String) = _state.update { it.copy(focusedId = id) }
}

@Composable
fun HomeScreen(navController: NavHostController) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val phone = isPhoneLayout()
    val viewModel: HomeViewModel = viewModel(factory = viewModelFactory { HomeViewModel(container.repository) })
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query = ShowQuery(state.mediaType, state.genre, state.year, sort = state.sort)
    val shelves = remember(state.shows, query) { buildShelves(state.shows, query) }
    val hero = state.shows.firstOrNull { it.id == state.focusedId }
        ?: shelves.hot.firstOrNull()
        ?: state.continuing.firstOrNull()
        ?: state.shows.firstOrNull()
    val posterWidth = if (phone) 112.dp else 168.dp
    val pad = if (phone) 12.dp else 24.dp
    val years = remember(state.shows) { state.shows.mapNotNull { it.year }.distinct().sortedDescending().take(8) }
    val genres = remember(state.shows) { axisGenres(state.shows.flatMap { it.genres }) }

    fun play(show: ShowRecord) {
        scope.launch {
            try {
                val session = container.repository.loadShowSession(show.id)
                val signedIn = setOf("115", "quark", "ali", "baidu").filter { container.cloudSecret(it).isNotBlank() }.toSet()
                val request = playbackFor(session, signedIn)
                if (request == null) navController.openShow(show.id) else context.startPlayback(request)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                navController.openShow(show.id)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = if (phone) 12.dp else 28.dp),
        verticalArrangement = Arrangement.spacedBy(if (phone) 8.dp else 14.dp),
    ) {
        item {
            hero?.let { Hero(it, phone, onPlay = { play(it) }) }
        }
        item {
            Column(modifier = Modifier.padding(horizontal = pad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (state.syncing) "正在更新片单" else "映视", color = YingShiColors.Sub, fontSize = 13.sp)
                state.error?.let { Text(it, color = YingShiColors.Error, fontSize = 13.sp) }
                ChipRow(
                    listOf(null to "全部", "movie" to "电影", "tv" to "电视剧", "variety" to "综艺", "doc" to "纪录", "anime" to "动漫"),
                    selected = state.mediaType,
                    onSelect = viewModel::setType,
                )
                if (years.isNotEmpty()) {
                    ChipRow(
                        listOf(null to "全部年份") + years.map { it to it.toString() },
                        selected = state.year,
                        onSelect = viewModel::setYear,
                    )
                }
                if (genres.isNotEmpty()) {
                    ChipRow(
                        listOf(null to "全部类型") + genres.map { it to it },
                        selected = state.genre,
                        onSelect = viewModel::setGenre,
                    )
                }
                ChipRow(
                    listOf(ShowSort.RELEASE_DATE to "按发行日", ShowSort.RATING to "按评分"),
                    selected = state.sort,
                    onSelect = viewModel::setSort,
                )
            }
        }
        if (state.continuing.isNotEmpty() && state.mediaType == null && state.genre == null && state.year == null) {
            item { Shelf("继续观看", state.continuing, posterWidth, pad, viewModel::focus) { navController.openShow(it.id) } }
        }
        if (shelves.hot.isNotEmpty()) {
            item { Shelf("热播榜", shelves.hot, posterWidth, pad, viewModel::focus) { navController.openShow(it.id) } }
        }
        if (shelves.movies.isNotEmpty()) {
            item { Shelf("电影", shelves.movies, posterWidth, pad, viewModel::focus) { navController.openShow(it.id) } }
        }
        if (shelves.series.isNotEmpty()) {
            item { Shelf("电视剧", shelves.series, posterWidth, pad, viewModel::focus) { navController.openShow(it.id) } }
        }
        if (shelves.variety.isNotEmpty()) {
            item { Shelf("综艺", shelves.variety, posterWidth, pad, viewModel::focus) { navController.openShow(it.id) } }
        }
        if (shelves.docs.isNotEmpty()) {
            item { Shelf("纪录", shelves.docs, posterWidth, pad, viewModel::focus) { navController.openShow(it.id) } }
        }
        if (shelves.anime.isNotEmpty()) {
            item { Shelf("动漫", shelves.anime, posterWidth, pad, viewModel::focus) { navController.openShow(it.id) } }
        }
        if (!state.loading && !state.syncing && state.shows.isEmpty()) {
            item {
                Text(
                    "片单还是空的。内置公开测试片会先出现在这里。",
                    color = YingShiColors.Sub,
                    modifier = Modifier.padding(horizontal = pad),
                )
            }
        }
        item {
            Box(modifier = Modifier.padding(horizontal = pad)) {
                OutlinedButton(onClick = { viewModel.refresh(manual = true) }, enabled = !state.syncing) {
                    Text(if (state.syncing) "更新中" else "刷新片单")
                }
            }
        }
    }
}

@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options, key = { it.second }) { (value, label) ->
            if (value == selected) {
                Button(onClick = { onSelect(value) }) { Text(label, maxLines = 1) }
            } else {
                OutlinedButton(onClick = { onSelect(value) }) { Text(label, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun Shelf(
    title: String,
    shows: List<ShowRecord>,
    posterWidth: Dp,
    pad: Dp,
    onFocus: (String) -> Unit,
    onOpen: (ShowRecord) -> Unit,
) {
    Column(modifier = Modifier.padding(start = pad)) {
        SectionTitle(title)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = pad)) {
            items(shows, key = { it.id }) { show ->
                WallPoster(show, posterWidth, onFocus = { onFocus(show.id) }, onClick = { onOpen(show) })
            }
        }
    }
}

@Composable
private fun WallPoster(show: ShowRecord, width: Dp, onFocus: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val height = if (width < 140.dp) 156.dp else 236.dp
    Surface(
        selected = focused,
        onClick = onClick,
        modifier = Modifier
            .width(width)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .graphicsLayer {
                val scale = if (focused) 1.06f else 1f
                scaleX = scale
                scaleY = scale
            }
            .border(if (focused) 2.dp else 0.dp, Color.White, RoundedCornerShape(8.dp)),
    ) {
        Column {
            PosterFace(show.title, show.posterUrl, Modifier.fillMaxWidth().height(height))
            Text(
                show.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
            Text(
                showMeta(show),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp,
                color = YingShiColors.Sub,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun Hero(show: ShowRecord, phone: Boolean, onPlay: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().height(if (phone) 230.dp else 390.dp)) {
        val art = show.backdropUrl ?: show.posterUrl
        if (art.isNullOrBlank()) {
            PosterFace(show.title, null, Modifier.fillMaxSize())
        } else {
            AsyncImage(
                model = art,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().optionalBlur(if (phone) 8.dp else 26.dp),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, YingShiColors.Bg))),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = if (phone) 12.dp else 28.dp, vertical = if (phone) 10.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(show.title, color = YingShiColors.Text, fontSize = if (phone) 22.sp else 36.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(showMeta(show), color = YingShiColors.Sub, fontSize = if (phone) 12.sp else 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (show.overview.isNotBlank()) {
                Text(show.overview, color = YingShiColors.Text, fontSize = if (phone) 13.sp else 16.sp, maxLines = if (phone) 2 else 3, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = onPlay) { Text("播放") }
        }
    }
}

private fun showMeta(show: ShowRecord): String {
    return listOfNotNull(
        show.year?.toString(),
        mediaTypeLabel(show.mediaType),
        show.rating?.let { "评分 ${"%.1f".format(it)}" },
        show.genres.take(3).joinToString(" / ").ifBlank { null },
    ).joinToString("  ·  ")
}

private fun Modifier.optionalBlur(radius: Dp): Modifier {
    if (Build.VERSION.SDK_INT < 31) return this
    return blur(radius)
}
