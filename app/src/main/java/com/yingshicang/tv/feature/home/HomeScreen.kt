package com.yingshicang.tv.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.tv.material3.Text
import com.yingshicang.tv.AppPrefs
import com.yingshicang.tv.core.catalog.HomeShelf
import com.yingshicang.tv.core.catalog.readableError
import com.yingshicang.tv.core.database.PlayHistoryEntity
import com.yingshicang.tv.core.database.SourceConfigEntity
import com.yingshicang.tv.core.model.MediaCard
import com.yingshicang.tv.core.model.SourceKinds
import com.yingshicang.tv.data.MediaRepository
import com.yingshicang.tv.ui.Button
import com.yingshicang.tv.ui.LocalContainer
import com.yingshicang.tv.ui.OutlinedButton
import com.yingshicang.tv.ui.PosterCard
import com.yingshicang.tv.ui.SectionTitle
import com.yingshicang.tv.ui.openCard
import com.yingshicang.tv.ui.openHistory
import com.yingshicang.tv.ui.theme.YingShiColors
import com.yingshicang.tv.ui.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class HomeSourceOption(
    val id: String,
    val name: String,
)

data class HomeUiState(
    val loading: Boolean = true,
    val recent: List<PlayHistoryEntity> = emptyList(),
    val cards: List<MediaCard> = emptyList(),
    val notice: String? = null,
    val error: String? = null,
    val shelf: HomeShelf = HomeShelf.HOT_DRAMA,
    val sources: List<HomeSourceOption> = emptyList(),
    val sourceId: String? = null,
    val sourceName: String = "",
    val pickingSource: Boolean = false,
    val filteringLines: Boolean = false,
)

class HomeViewModel(
    private val repository: MediaRepository,
    private val prefs: AppPrefs,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state = _state.asStateFlow()
    private var loadJob: Job? = null
    private var loadGeneration = 0
    private var classifyJob: Job? = null
    private var classifyGeneration = 0
    private var latestSources: List<SourceConfigEntity> = emptyList()
    private val scrapeableLineIds = mutableSetOf<String>()
    private val blockedLineIds = mutableSetOf<String>()
    private var filteringLines = false

    init {
        viewModelScope.launch {
            repository.observeHistory().collect { recent ->
                _state.update { it.copy(recent = recent) }
            }
        }
        viewModelScope.launch {
            repository.observeSources().collect { sources -> applySources(sources) }
        }
    }

    fun refresh() = load(force = true)

    private fun load(force: Boolean) {
        val sourceId = _state.value.sourceId
        val shelf = _state.value.shelf
        if (sourceId.isNullOrBlank()) {
            loadJob?.cancel()
            _state.update { it.copy(loading = false, cards = emptyList(), error = null, notice = null) }
            return
        }
        val generation = ++loadGeneration
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, notice = null, cards = emptyList()) }
            try {
                val page = repository.loadHomeShelf(sourceId, shelf, force)
                if (generation != loadGeneration) return@launch
                _state.update { it.copy(loading = false, cards = page.cards, notice = page.notice) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation != loadGeneration) return@launch
                _state.update { it.copy(loading = false, error = readableError(error)) }
            }
        }
    }

    fun selectShelf(shelf: HomeShelf) {
        if (_state.value.shelf == shelf) return
        _state.update { it.copy(shelf = shelf) }
        load(force = false)
    }

    fun toggleSourcePicker() {
        _state.update { it.copy(pickingSource = !it.pickingSource) }
    }

    fun selectSource(id: String) {
        val source = _state.value.sources.firstOrNull { it.id == id } ?: return
        prefs.setHomeSourceId(id)
        _state.update { it.copy(sourceId = id, sourceName = source.name, pickingSource = false) }
        load(force = false)
    }

    private fun applySources(sources: List<SourceConfigEntity>) {
        latestSources = sources
        classifyLines(sources)
    }

    private fun classifyLines(sources: List<SourceConfigEntity>) {
        val generation = ++classifyGeneration
        classifyJob?.cancel()
        val lines = sources.filter { it.enabled && it.kind == SourceKinds.TVBOX }
        classifyJob = viewModelScope.launch {
            val pending = mutableListOf<SourceConfigEntity>()
            lines.forEach { line ->
                when (repository.cachedLineRole(line.id)) {
                    "cms" -> scrapeableLineIds += line.id
                    "skip" -> blockedLineIds += line.id
                    else -> pending += line
                }
            }
            filteringLines = pending.isNotEmpty()
            publishSources()
            if (pending.isEmpty() || generation != classifyGeneration) return@launch
            val gate = Semaphore(4)
            try {
                coroutineScope {
                    pending.map { line ->
                        async {
                            val role = try {
                                gate.withPermit { repository.classifyLine(line.id, line.location) }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                null
                            }
                            if (generation != classifyGeneration) return@async
                            when (role) {
                                "cms" -> scrapeableLineIds += line.id
                                "skip" -> blockedLineIds += line.id
                            }
                            if (role != null) publishSources()
                        }
                    }.awaitAll()
                }
            } finally {
                if (generation == classifyGeneration) {
                    filteringLines = false
                    publishSources()
                }
            }
        }
    }

    private fun publishSources() {
        val enabled = latestSources.filter { it.enabled }
        val saved = prefs.homeSourceId()
        val holdId = _state.value.sourceId ?: saved
        val visible = enabled.filter { source ->
            when (source.kind) {
                SourceKinds.TVBOX -> source.id in scrapeableLineIds || (source.id == holdId && source.id !in blockedLineIds)
                SourceKinds.SCRIPT, SourceKinds.DRIVE -> false
                else -> true
            }
        }.sortedWith(compareBy<SourceConfigEntity> { sourceRank(it.kind) }.thenBy { it.priority })
        val pending = enabled.any { it.kind == SourceKinds.TVBOX && it.id !in scrapeableLineIds && it.id !in blockedLineIds }
        if (visible.isEmpty()) {
            if (pending) {
                _state.update { it.copy(filteringLines = true) }
                return
            }
            loadJob?.cancel()
            _state.update {
                it.copy(
                    loading = false,
                    sources = emptyList(),
                    sourceId = null,
                    sourceName = "",
                    cards = emptyList(),
                    notice = null,
                    error = null,
                    pickingSource = false,
                    filteringLines = false,
                )
            }
            return
        }
        val options = visible.map { HomeSourceOption(it.id, it.name) }
        val previousId = _state.value.sourceId
        val chosen = visible.firstOrNull { it.id == previousId }
            ?: visible.firstOrNull { it.id == saved && it.id !in blockedLineIds }
            ?: visible.firstOrNull { it.kind == SourceKinds.TVBOX }
            ?: visible.firstOrNull { it.kind == SourceKinds.CMS }
            ?: visible.first()
        val changed = chosen.id != previousId
        val cardsEmpty = _state.value.cards.isEmpty() && !_state.value.loading
        _state.update {
            it.copy(
                sources = options,
                sourceId = chosen.id,
                sourceName = chosen.name,
                filteringLines = filteringLines,
            )
        }
        if (changed) prefs.setHomeSourceId(chosen.id)
        if (changed || cardsEmpty) load(force = false)
    }
}

private fun sourceRank(kind: String): Int = when (kind) {
    SourceKinds.TVBOX -> 0
    SourceKinds.CMS -> 1
    else -> 2
}

@Composable
fun HomeScreen(navController: NavHostController) {
    val container = LocalContainer.current
    val viewModel: HomeViewModel = viewModel(factory = viewModelFactory {
        HomeViewModel(container.repository, container.prefs)
    })
    val state by viewModel.state.collectAsStateWithLifecycle()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(176.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(28.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(state.shelf.label, fontSize = 28.sp, color = YingShiColors.Text)
                    Text(
                        state.sourceName.ifBlank { "还没有启用的线路" },
                        color = YingShiColors.Sub,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                OutlinedButton(
                    onClick = viewModel::toggleSourcePicker,
                    modifier = Modifier.widthIn(max = 360.dp),
                    enabled = state.sources.isNotEmpty() || state.filteringLines,
                ) {
                    Text(
                        if (state.pickingSource) "收起线路" else "线路 · ${state.sourceName.ifBlank { "未选择" }}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(onClick = viewModel::refresh, enabled = !state.loading && state.sourceId != null) {
                    Text(if (state.loading) "刷新中" else "刷新")
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(HomeShelf.entries, key = { it.name }) { shelf ->
                    if (shelf == state.shelf) {
                        Button(onClick = { viewModel.selectShelf(shelf) }) { Text(shelf.label) }
                    } else {
                        OutlinedButton(onClick = { viewModel.selectShelf(shelf) }) { Text(shelf.label) }
                    }
                }
            }
        }
        if (state.pickingSource) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.filteringLines) {
                        Text("正在排除脚本仓，只保留能刮削的线路", color = YingShiColors.Sub, fontSize = 14.sp)
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.sources, key = { it.id }) { source ->
                            if (source.id == state.sourceId) {
                                Button(onClick = { viewModel.selectSource(source.id) }) { Text(source.name) }
                            } else {
                                OutlinedButton(onClick = { viewModel.selectSource(source.id) }) { Text(source.name) }
                            }
                        }
                    }
                }
            }
        }
        if (state.recent.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("最近播放") }
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(state.recent, key = { it.mediaKey }) { item ->
                        PosterCard(
                            title = item.title,
                            poster = item.poster,
                            subtitle = historySubtitle(item),
                            onClick = { navController.openHistory(item) },
                            modifier = Modifier.width(176.dp),
                        )
                    }
                }
            }
        }
        if (state.loading && state.cards.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("正在加载当前线路…", color = YingShiColors.Sub)
            }
        }
        state.error?.let { message ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(message, color = YingShiColors.Error)
            }
        }
        state.notice?.let { message ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(message, color = YingShiColors.Sub)
            }
        }
        if (!state.loading && state.sources.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("没有启用的数据源。到左侧「数据源」打开测试片库，或添加 CMS 接口。", color = YingShiColors.Sub)
            }
        } else if (!state.loading && state.error == null && state.cards.isEmpty() && state.sourceId != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("这个分类没有内容", color = YingShiColors.Sub)
            }
        }
        itemsIndexed(state.cards, key = { index, card -> "${card.providerId}:${card.sourceId}:$index" }) { _, card ->
            PosterCard(
                title = card.title,
                poster = card.poster,
                subtitle = cardSubtitle(card),
                onClick = { navController.openCard(card) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun cardSubtitle(card: MediaCard): String {
    return listOfNotNull(card.year, card.area, card.remarks).joinToString(" · ").ifBlank { card.providerName }
}

private fun historySubtitle(item: PlayHistoryEntity): String {
    val progress = if (item.durationMs > 0) "${item.positionMs * 100 / item.durationMs}%" else ""
    return listOf(item.episodeName, progress).filter { it.isNotBlank() }.joinToString(" · ")
}
