package com.cloudreel.tv.feature.home

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.cloudreel.tv.core.catalog.readableError
import com.cloudreel.tv.core.show.ShowQuery
import com.cloudreel.tv.core.show.ShowRecord
import com.cloudreel.tv.core.show.ShowSort
import com.cloudreel.tv.core.show.axisGenres
import com.cloudreel.tv.core.show.buildShelves
import com.cloudreel.tv.core.show.matchesShow
import com.cloudreel.tv.core.show.mediaTypeLabel
import com.cloudreel.tv.data.ContinueCard
import com.cloudreel.tv.data.MediaRepository
import com.cloudreel.tv.data.playbackFor
import com.cloudreel.tv.ui.Button
import com.cloudreel.tv.ui.LocalContainer
import com.cloudreel.tv.ui.OutlinedButton
import com.cloudreel.tv.ui.PosterFace
import com.cloudreel.tv.ui.focusAmount
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

private enum class FilterAxis { TYPE, YEAR, GENRE }

data class HomeUiState(
    val loading: Boolean = true,
    val syncing: Boolean = false,
    val shows: List<ShowRecord> = emptyList(),
    val continuing: List<ContinueCard> = emptyList(),
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
            repository.observeContinueCards().collect { rows ->
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
    val continuing = remember(state.continuing, query) { state.continuing.filter { matchesShow(it.show, query) } }
    val hero = state.shows.firstOrNull { it.id == state.focusedId }
        ?: shelves.hot.firstOrNull()
        ?: continuing.firstOrNull()?.show
        ?: state.shows.firstOrNull()
    val posterWidth = if (phone) 118.dp else 156.dp
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
        contentPadding = PaddingValues(bottom = if (phone) 16.dp else 28.dp),
        verticalArrangement = Arrangement.spacedBy(if (phone) 10.dp else 16.dp),
    ) {
        item {
            TopBar(
                phone = phone,
                syncing = state.syncing,
                pad = pad,
                onSearch = { navController.navigate("search") { launchSingleTop = true } },
                onAccount = { navController.navigate(if (phone) "mine" else "setting") { launchSingleTop = true } },
            )
        }
        item {
            hero?.let { HeroCard(it, phone, pad, onPlay = { play(it) }) }
        }
        item {
            Column(modifier = Modifier.padding(horizontal = pad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.error?.let { Text(it, color = YingShiColors.Error, fontSize = 13.sp) }
                FilterBar(
                    phone = phone,
                    mediaType = state.mediaType,
                    year = state.year,
                    genre = state.genre,
                    sort = state.sort,
                    years = years,
                    genres = genres,
                    onType = viewModel::setType,
                    onYear = viewModel::setYear,
                    onGenre = viewModel::setGenre,
                    onSort = viewModel::setSort,
                )
            }
        }
        if (continuing.isNotEmpty()) {
            item {
                Shelf(
                    title = "继续观看",
                    pad = pad,
                    onMore = { navController.navigate("history") { launchSingleTop = true } },
                ) {
                    items(continuing, key = { it.show.id }) { card ->
                        ContinuePoster(
                            card = card,
                            phone = phone,
                            onFocus = { viewModel.focus(card.show.id) },
                            onClick = { navController.openShow(card.show.id) },
                        )
                    }
                }
            }
        }
        if (shelves.hot.isNotEmpty()) {
            item {
                PosterShelf("热播榜", shelves.hot, posterWidth, pad, viewModel::focus, { navController.openShow(it.id) }) {
                    viewModel.setSort(ShowSort.RATING)
                }
            }
        }
        if (shelves.movies.isNotEmpty()) {
            item {
                PosterShelf("电影", shelves.movies, posterWidth, pad, viewModel::focus, { navController.openShow(it.id) }) {
                    viewModel.setType("movie")
                }
            }
        }
        if (shelves.series.isNotEmpty()) {
            item {
                PosterShelf("电视剧", shelves.series, posterWidth, pad, viewModel::focus, { navController.openShow(it.id) }) {
                    viewModel.setType("tv")
                }
            }
        }
        if (shelves.variety.isNotEmpty()) {
            item {
                PosterShelf("综艺", shelves.variety, posterWidth, pad, viewModel::focus, { navController.openShow(it.id) }) {
                    viewModel.setType("variety")
                }
            }
        }
        if (shelves.docs.isNotEmpty()) {
            item {
                PosterShelf("纪录", shelves.docs, posterWidth, pad, viewModel::focus, { navController.openShow(it.id) }) {
                    viewModel.setType("doc")
                }
            }
        }
        if (shelves.anime.isNotEmpty()) {
            item {
                PosterShelf("动漫", shelves.anime, posterWidth, pad, viewModel::focus, { navController.openShow(it.id) }) {
                    viewModel.setType("anime")
                }
            }
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
private fun TopBar(phone: Boolean, syncing: Boolean, pad: Dp, onSearch: () -> Unit, onAccount: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = pad, vertical = if (phone) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (phone) "视频" else "映视", color = YingShiColors.Text, fontSize = if (phone) 22.sp else 20.sp)
        if (syncing) Text("更新中", color = YingShiColors.Sub, fontSize = 12.sp)
        Box(Modifier.weight(1f))
        OutlinedButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "搜索") }
        OutlinedButton(onClick = onAccount) { Icon(Icons.Filled.Settings, contentDescription = if (phone) "我的" else "设置") }
    }
}

@Composable
private fun FilterBar(
    phone: Boolean,
    mediaType: String?,
    year: Int?,
    genre: String?,
    sort: ShowSort,
    years: List<Int>,
    genres: List<String>,
    onType: (String?) -> Unit,
    onYear: (Int?) -> Unit,
    onGenre: (String?) -> Unit,
    onSort: (ShowSort) -> Unit,
) {
    var expanded by remember { mutableStateOf<FilterAxis?>(null) }
    if (phone) {
        ChipRow(
            listOf(null to "全部", "movie" to "电影", "tv" to "剧集", "variety" to "综艺", "doc" to "纪录", "anime" to "动漫"),
            selected = mediaType,
            onSelect = onType,
        )
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!phone) {
            item {
                SummaryChip(typeLabel(mediaType), expanded == FilterAxis.TYPE) {
                    expanded = if (expanded == FilterAxis.TYPE) null else FilterAxis.TYPE
                }
            }
        }
        item {
            SummaryChip(year?.toString() ?: "全部年份", expanded == FilterAxis.YEAR) {
                expanded = if (expanded == FilterAxis.YEAR) null else FilterAxis.YEAR
            }
        }
        item {
            SummaryChip(genre ?: "影片类型", expanded == FilterAxis.GENRE) {
                expanded = if (expanded == FilterAxis.GENRE) null else FilterAxis.GENRE
            }
        }
        item {
            SummaryChip(if (sort == ShowSort.RATING) "按评分" else "按发行日", selected = false, caret = false) {
                onSort(if (sort == ShowSort.RATING) ShowSort.RELEASE_DATE else ShowSort.RATING)
            }
        }
    }
    when (expanded) {
        FilterAxis.TYPE -> ChipRow(
            listOf(null to "全部类型", "movie" to "电影", "tv" to "剧集", "variety" to "综艺", "doc" to "纪录", "anime" to "动漫"),
            selected = mediaType,
            onSelect = {
                onType(it)
                expanded = null
            },
        )
        FilterAxis.YEAR -> if (years.isNotEmpty()) {
            ChipRow(
                listOf(null to "全部年份") + years.map { it to it.toString() },
                selected = year,
                onSelect = {
                    onYear(it)
                    expanded = null
                },
            )
        }
        FilterAxis.GENRE -> if (genres.isNotEmpty()) {
            ChipRow(
                listOf(null to "全部类型") + genres.map { it to it },
                selected = genre,
                onSelect = {
                    onGenre(it)
                    expanded = null
                },
            )
        }
        null -> Unit
    }
}

@Composable
private fun SummaryChip(label: String, selected: Boolean, caret: Boolean = true, onClick: () -> Unit) {
    val click = onClick
    if (selected) {
        Button(onClick = click) { ChipLabel(label, caret) }
    } else {
        OutlinedButton(onClick = click) { ChipLabel(label, caret) }
    }
}

@Composable
private fun ChipLabel(label: String, caret: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, maxLines = 1)
        if (caret) Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
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
private fun PosterShelf(
    title: String,
    shows: List<ShowRecord>,
    posterWidth: Dp,
    pad: Dp,
    onFocus: (String) -> Unit,
    onOpen: (ShowRecord) -> Unit,
    onMore: () -> Unit,
) {
    Shelf(title, pad, onMore) {
        items(shows, key = { it.id }) { show ->
            WallPoster(show, posterWidth, onFocus = { onFocus(show.id) }, onClick = { onOpen(show) })
        }
    }
}

@Composable
private fun Shelf(
    title: String,
    pad: Dp,
    onMore: (() -> Unit)?,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column(modifier = Modifier.padding(start = pad)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = pad, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = YingShiColors.Text, fontSize = 18.sp, modifier = Modifier.weight(1f))
            if (onMore != null) {
                OutlinedButton(onClick = onMore) { Text("更多") }
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(end = pad, top = 8.dp, bottom = 8.dp),
            content = content,
        )
    }
}

@Composable
private fun WallPoster(show: ShowRecord, width: Dp, onFocus: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val factor = focusAmount(focused)
    val dim by animateFloatAsState(if (focused) 1f else 0.72f, tween(180), label = "posterDim")
    val height = if (width < 140.dp) 168.dp else 224.dp
    FocusCard(
        focused = focused,
        dim = dim,
        width = width * factor,
        height = height * factor,
        onFocus = {
            focused = it
            if (it) onFocus()
        },
        onClick = onClick,
    ) {
        PosterFace(show.title, show.posterUrl, Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6101014))))
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Column {
                Text(show.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                show.rating?.let {
                    Text("%.1f".format(it), color = Color(0xFFF5D37A), fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ContinuePoster(card: ContinueCard, phone: Boolean, onFocus: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val factor = focusAmount(focused)
    val dim by animateFloatAsState(if (focused) 1f else 0.72f, tween(180), label = "continueDim")
    val width = (if (phone) 132.dp else 248.dp) * factor
    val height = (if (phone) 186.dp else 140.dp) * factor
    FocusCard(
        focused = focused,
        dim = dim,
        width = width,
        height = height,
        onFocus = {
            focused = it
            if (it) onFocus()
        },
        onClick = onClick,
    ) {
        PosterFace(card.show.title, card.show.posterUrl, Modifier.fillMaxSize())
        if (focused) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center).background(Color(0x66000000), RoundedCornerShape(20.dp)).padding(6.dp),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF0101014))))
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(card.show.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val episode = card.episodeKey.trim()
            if (episode.isNotEmpty()) {
                Text(episode, color = Color(0xFFD5DBE3), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x55FFFFFF)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(card.fraction.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(Color.White),
                )
            }
        }
    }
}

@Composable
private fun FocusCard(
    focused: Boolean,
    dim: Float,
    width: Dp,
    height: Dp,
    onFocus: (Boolean) -> Unit,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    com.cloudreel.tv.ui.Surface(
        selected = focused,
        onClick = onClick,
        modifier = Modifier
            .zIndex(if (focused) 1f else 0f)
            .width(width)
            .height(height)
            .onFocusChanged { onFocus(it.isFocused) }
            .graphicsLayer { alpha = dim }
            .shadow(if (focused) 16.dp else 0.dp, shape, ambientColor = Color.White, spotColor = Color.White)
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
            .clip(shape),
    ) {
        Box(Modifier.fillMaxSize(), content = content)
    }
}

@Composable
private fun HeroCard(show: ShowRecord, phone: Boolean, pad: Dp, onPlay: () -> Unit) {
    val shape = RoundedCornerShape(if (phone) 18.dp else 16.dp)
    Box(
        modifier = Modifier
            .padding(horizontal = pad)
            .fillMaxWidth()
            .height(if (phone) 228.dp else 280.dp)
            .clip(shape)
            .background(YingShiColors.Card),
    ) {
        val art = show.backdropUrl ?: show.posterUrl
        if (art.isNullOrBlank()) {
            PosterFace(show.title, null, Modifier.fillMaxSize())
        } else {
            AsyncImage(
                model = art,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().optionalBlur(if (phone) 0.dp else 18.dp),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            modifier = Modifier.fillMaxSize().background(
                if (phone) {
                    Brush.verticalGradient(listOf(Color(0x22000000), Color(0xE6101014)))
                } else {
                    Brush.horizontalGradient(listOf(Color(0xF0101318), Color(0x66101318), Color(0x22101318)))
                },
            ),
        )
        Row(
            modifier = Modifier.align(Alignment.BottomStart).padding(if (phone) 14.dp else 18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (!phone) {
                PosterFace(
                    show.title,
                    show.posterUrl,
                    Modifier.width(118.dp).height(168.dp).clip(RoundedCornerShape(10.dp)),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (phone) {
                    Text("今日推荐", color = Color(0xFFD7C4FF), fontSize = 12.sp)
                }
                Text(
                    show.title,
                    color = Color.White,
                    fontSize = if (phone) 26.sp else 32.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(showMeta(show), color = Color(0xFFD5DBE3), fontSize = if (phone) 12.sp else 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (show.overview.isNotBlank()) {
                    Text(show.overview, color = Color(0xFFE8EDF2), fontSize = if (phone) 13.sp else 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Button(onClick = onPlay) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text(if (phone) "立即观看" else "播放")
                }
            }
        }
    }
}

private fun typeLabel(type: String?): String = when (type) {
    null -> "全部类型"
    "tv" -> "剧集"
    else -> mediaTypeLabel(type)
}

private fun showMeta(show: ShowRecord): String {
    return listOfNotNull(
        show.year?.toString(),
        mediaTypeLabel(show.mediaType),
        show.genres.take(2).joinToString(" / ").ifBlank { null },
        show.rating?.let { "★ ${"%.1f".format(it)}" },
    ).joinToString("  ·  ")
}

private fun Modifier.optionalBlur(radius: Dp): Modifier {
    if (radius <= 0.dp || Build.VERSION.SDK_INT < 31) return this
    return blur(radius)
}
