package com.cloudreel.tv.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.tv.material3.TabRow
import com.cloudreel.tv.ui.Button
import com.cloudreel.tv.ui.OutlinedButton
import com.cloudreel.tv.ui.Tab
import androidx.tv.material3.Text
import com.cloudreel.tv.core.catalog.mergeHits
import com.cloudreel.tv.core.catalog.readableError
import com.cloudreel.tv.core.model.MergedHit
import com.cloudreel.tv.core.model.SearchHit
import com.cloudreel.tv.data.MediaRepository
import com.cloudreel.tv.ui.LocalContainer
import com.cloudreel.tv.ui.PosterCard
import com.cloudreel.tv.ui.TvTextField
import com.cloudreel.tv.ui.openHit
import com.cloudreel.tv.ui.openMerged
import com.cloudreel.tv.ui.theme.YingShiColors
import com.cloudreel.tv.ui.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val searching: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val merged: List<MergedHit> = emptyList(),
    val flat: List<SearchHit> = emptyList(),
    val failures: List<String> = emptyList(),
    val history: List<String> = emptyList(),
)

class SearchViewModel(private val repository: MediaRepository) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state = _state.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            repository.observeSearches().collect { records ->
                _state.update { it.copy(history = records.map { record -> record.keyword }) }
            }
        }
    }

    fun search(keyword: String) {
        val query = keyword.trim()
        if (query.isBlank()) return
        job?.cancel()
        job = viewModelScope.launch {
            _state.update {
                it.copy(
                    searching = true,
                    done = 0,
                    total = 0,
                    merged = emptyList(),
                    flat = emptyList(),
                    failures = emptyList(),
                )
            }
            repository.rememberSearch(query)
            try {
                repository.search(query) { snapshot ->
                    _state.update {
                        it.copy(
                            searching = snapshot.searching,
                            done = snapshot.done,
                            total = snapshot.total,
                            merged = mergeHits(snapshot.hits),
                            flat = snapshot.hits,
                            failures = snapshot.failures,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(searching = false, failures = it.failures + readableError(error)) }
            }
        }
    }
}

@Composable
fun SearchScreen(navController: NavHostController) {
    val repository = LocalContainer.current.repository
    val viewModel: SearchViewModel = viewModel(factory = viewModelFactory { SearchViewModel(repository) })
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var mergedView by rememberSaveable { mutableStateOf(true) }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    val genres = state.merged.flatMap { it.genres }.distinct().take(10)
    val shownMerged = state.merged.filter { genre == null || genre in it.genres }
    val shownFlat = state.flat.filter { genre == null || genre in it.genres }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
    ) {
        Text("搜索", fontSize = 28.sp, color = YingShiColors.Text)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TvTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "输入影片名称",
                modifier = Modifier.weight(1f),
                onSearch = { viewModel.search(query) },
            )
            Button(onClick = { viewModel.search(query) }, enabled = query.isNotBlank()) { Text("搜索") }
        }
        if (state.history.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.history) { word ->
                    OutlinedButton(onClick = {
                        query = word
                        viewModel.search(word)
                    }) { Text(word) }
                }
            }
        }
        if (state.total > 0) {
            Text(
                if (state.searching) "已完成 ${state.done}/${state.total} 个源" else "搜索完成 ${state.done}/${state.total} 个源",
                color = YingShiColors.Sub,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        TabRow(selectedTabIndex = if (mergedView) 0 else 1, modifier = Modifier.padding(top = 8.dp)) {
            Tab(selected = mergedView, onFocus = { mergedView = true }, onClick = { mergedView = true }) {
                Text("合并结果", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            Tab(selected = !mergedView, onFocus = { mergedView = false }, onClick = { mergedView = false }) {
                Text("按来源", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        if (genres.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    GenreButton("全部", genre == null) { genre = null }
                }
                items(genres) { name ->
                    GenreButton(name, genre == name) { genre = name }
                }
            }
        }
        if (!state.searching && state.total > 0 && shownMerged.isEmpty() && shownFlat.isEmpty()) {
            Text("没有找到相符的内容", color = YingShiColors.Sub, modifier = Modifier.padding(top = 16.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(180.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            if (mergedView) {
                items(shownMerged, key = { "${it.title}:${it.year}:${it.hits.size}" }) { hit ->
                    PosterCard(
                        title = hit.title,
                        poster = hit.poster,
                        subtitle = listOfNotNull(hit.year, "${hit.hits.size} 个源").joinToString(" · "),
                        onClick = { navController.openMerged(hit) },
                    )
                }
            } else {
                items(shownFlat, key = { "${it.providerId}:${it.sourceId}:${it.title}" }) { hit ->
                    PosterCard(
                        title = hit.title,
                        poster = hit.poster,
                        subtitle = listOfNotNull(hit.providerName, hit.year).joinToString(" · "),
                        onClick = { navController.openHit(hit) },
                    )
                }
            }
            if (state.failures.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(state.failures.joinToString("    "), color = YingShiColors.Error, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun GenreButton(text: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick) { Text(text) }
    }
}
