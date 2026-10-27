package com.yingshicang.tv.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.tv.material3.Text
import com.yingshicang.tv.core.catalog.readableError
import com.yingshicang.tv.core.catalog.stableMediaKey
import com.yingshicang.tv.core.database.JsonCodec
import com.yingshicang.tv.core.model.DriveRefresh
import com.yingshicang.tv.core.model.PlayLine
import com.yingshicang.tv.core.model.PlaybackRequest
import com.yingshicang.tv.core.model.SourceKinds
import com.yingshicang.tv.core.model.SourceRef
import com.yingshicang.tv.ui.LocalContainer
import com.yingshicang.tv.ui.PosterCard
import com.yingshicang.tv.ui.openDetail
import com.yingshicang.tv.ui.openHistory
import com.yingshicang.tv.ui.startPlayback
import kotlinx.coroutines.launch
import com.yingshicang.tv.ui.theme.YingShiColors

@Composable
fun HistoryScreen(navController: NavHostController) {
    val repository = LocalContainer.current.repository
    val history by repository.observeHistory().collectAsStateWithLifecycle(emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(180.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(28.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("历史", fontSize = 28.sp, color = YingShiColors.Text)
            error?.let { Text(it, color = YingShiColors.Error) }
        }
        if (history.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("还没有播放记录。播放超过 5 秒后会记在这里。", color = YingShiColors.Sub)
            }
        }
        items(history, key = { it.mediaKey }) { item ->
            val progress = if (item.durationMs > 0) "${item.positionMs * 100 / item.durationMs}%" else ""
            PosterCard(
                title = item.title,
                poster = item.poster,
                subtitle = listOf(item.episodeName, progress).filter { it.isNotBlank() }.joinToString(" · "),
                onClick = {
                    if (item.providerId == SourceKinds.DRIVE) {
                        scope.launch {
                            try {
                                val episode = repository.refreshDrive(DriveRefresh(item.sourceId, item.lineName))
                                context.startPlayback(
                                    PlaybackRequest(
                                        mediaKey = item.mediaKey,
                                        title = item.title,
                                        poster = item.poster,
                                        year = item.year,
                                        refs = listOf(SourceRef(SourceKinds.DRIVE, item.sourceId)),
                                        lines = listOf(PlayLine(SourceKinds.DRIVE, "网盘", "网盘", listOf(episode))),
                                        lineIndex = 0,
                                        episodeIndex = 0,
                                        resumePositionMs = 0,
                                        driveRefresh = DriveRefresh(item.sourceId, item.lineName),
                                    ),
                                )
                            } catch (throwable: Exception) {
                                error = readableError(throwable)
                            }
                        }
                    } else {
                        navController.openHistory(item)
                    }
                },
            )
        }
    }
}

@Composable
fun FavoriteScreen(navController: NavHostController) {
    val favorites by LocalContainer.current.repository.observeFavorites().collectAsStateWithLifecycle(emptyList())
    LazyVerticalGrid(
        columns = GridCells.Adaptive(180.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(28.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("收藏", fontSize = 28.sp, color = YingShiColors.Text, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (favorites.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("还没有收藏。在详情页按「收藏」。", color = YingShiColors.Sub)
            }
        }
        items(favorites, key = { it.mediaKey }) { item ->
            PosterCard(
                title = item.title,
                poster = item.poster,
                subtitle = item.year.orEmpty(),
                onClick = {
                    val refs = JsonCodec.decodeRefs(item.refsJson).ifEmpty {
                        listOf(SourceRef(item.providerId, item.sourceId))
                    }
                    navController.openDetail(item.mediaKey.ifBlank { stableMediaKey(item.title, item.year) }, refs)
                },
            )
        }
    }
}
