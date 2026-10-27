package com.cloudreel.tv.feature.live

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cloudreel.tv.ui.Button
import com.cloudreel.tv.ui.ListItem
import com.cloudreel.tv.ui.OutlinedButton
import androidx.tv.material3.Text
import com.cloudreel.tv.core.catalog.readableError
import com.cloudreel.tv.core.model.Episode
import com.cloudreel.tv.core.model.PlayLine
import com.cloudreel.tv.core.model.PlaybackRequest
import com.cloudreel.tv.data.LivePage
import com.cloudreel.tv.ui.LocalContainer
import com.cloudreel.tv.ui.TvTextField
import com.cloudreel.tv.ui.startPlayback
import com.cloudreel.tv.ui.theme.YingShiColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun LiveScreen(onPush: () -> Unit) {
    val repository = LocalContainer.current.repository
    val context = LocalContext.current
    val playlists by repository.observePlaylistSummaries().collectAsStateWithLifecycle(emptyList())
    var selectedId by rememberSaveable { mutableStateOf("") }
    var page by remember { mutableStateOf<LivePage?>(null) }
    var group by remember { mutableStateOf("") }
    var adding by rememberSaveable { mutableStateOf(false) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var loadingPage by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        importing = true
        try {
            repository.importLivesFromSavedLines()
            notice = "正在检查直播源，打不开的会直接删掉"
            val removed = repository.removeUnplayableLiveSources()
            notice = if (removed > 0) "已删除 $removed 个不能播放的直播源" else null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        } finally {
            importing = false
        }
    }
    LaunchedEffect(playlists) {
        if (playlists.none { it.id == selectedId }) {
            selectedId = playlists.firstOrNull { it.name.contains("心魔") }?.id ?: playlists.firstOrNull()?.id.orEmpty()
        }
    }
    LaunchedEffect(selectedId) {
        if (selectedId.isBlank()) {
            page = null
            loadingPage = false
            return@LaunchedEffect
        }
        loadingPage = true
        page = try {
            repository.loadPlaylist(selectedId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        loadingPage = false
    }
    val channels = page?.channels.orEmpty()
    val groups = remember(page?.id, channels.size) { channels.map { it.group }.distinct() }
    val selected = group.takeIf { it in groups } ?: groups.firstOrNull().orEmpty()
    val visible = remember(page?.id, selected, channels.size) { channels.filter { it.group == selected } }
    Column(modifier = Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("直播", fontSize = 28.sp, color = YingShiColors.Text)
                Text("打开直播时会检查地址，打不开的源会直接删掉。", color = YingShiColors.Sub, fontSize = 14.sp)
            }
            OutlinedButton(onClick = { adding = !adding }) { Text(if (adding) "取消" else "添加接口") }
            Button(onClick = onPush) { Text("手机提交") }
        }
        if (adding) {
            LiveUrlForm { name, url, epgUrl ->
                notice = null
                notice = repository.addLiveUrl(name, url, epgUrl)
                adding = false
            }
        }
        notice?.let { Text(it, color = YingShiColors.Success) }
        if (playlists.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyRow(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(playlists, key = { it.id }) { playlist ->
                        val label = playlist.name
                        if (playlist.id == selectedId) {
                            Button(onClick = { selectedId = playlist.id }) {
                                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        } else {
                            OutlinedButton(onClick = { selectedId = playlist.id }) {
                                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (selectedId.isNotBlank()) {
                    OutlinedButton(onClick = { scope.launch { repository.deletePlaylist(selectedId) } }) {
                        Text("删除此源")
                    }
                }
            }
        }
        page?.takeIf { it.truncated }?.let {
            Text("这个直播源频道太多，只显示前 ${it.channels.size} 个。", color = YingShiColors.Sub, fontSize = 14.sp)
        }
        if ((importing || loadingPage) && channels.isEmpty()) {
            Text("正在读取当前直播源…", color = YingShiColors.Sub)
        } else if (channels.isEmpty()) {
            Text("还没有直播源。可以添加直播接口，或用手机扫码提交 M3U、JSON 或 XMLTV。", color = YingShiColors.Sub)
        } else {
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LazyColumn(modifier = Modifier.weight(0.28f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(groups) { item ->
                        ListItem(
                            selected = item == selected,
                            onClick = { group = item },
                            headlineContent = { Text(item, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
                LazyColumn(modifier = Modifier.weight(0.72f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(visible, key = { index, channel -> "${channel.group}:${channel.name}:$index" }) { _, channel ->
                        val program = if (page?.programs.isNullOrEmpty()) {
                            null
                        } else {
                            repository.programNow(page?.programs.orEmpty(), channel)
                        }
                        ListItem(
                            selected = false,
                            onClick = { context.playChannel(channel.name, channel.urls) },
                            headlineContent = { Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    program?.title ?: "${channel.urls.size} 个地址",
                                    color = YingShiColors.Sub,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveUrlForm(onSave: suspend (String, String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var epgUrl by rememberSaveable { mutableStateOf("") }
    var saving by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("填写 M3U、JSON 或 XMLTV 地址。节目单可以留空。", color = YingShiColors.Sub, fontSize = 14.sp)
        TvTextField(name, { name = it }, "名称，例如 我的直播", Modifier.fillMaxWidth())
        TvTextField(url, { url = it }, "https://", Modifier.fillMaxWidth())
        TvTextField(epgUrl, { epgUrl = it }, "节目单地址，可留空", Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    if (saving) return@Button
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            onSave(name, url, epgUrl)
                            saving = false
                        } catch (throwable: CancellationException) {
                            throw throwable
                        } catch (throwable: Exception) {
                            error = readableError(throwable)
                            saving = false
                        }
                    }
                },
                enabled = url.isNotBlank() && !saving,
            ) { Text(if (saving) "正在读取" else "保存") }
        }
        error?.let { Text(it, color = YingShiColors.Error) }
    }
}

private fun Context.playChannel(name: String, urls: List<String>) {
    if (urls.isEmpty()) return
    startPlayback(
        PlaybackRequest(
            mediaKey = "live:$name",
            title = name,
            poster = null,
            year = null,
            refs = emptyList(),
            lines = listOf(
                PlayLine(
                    providerId = "live",
                    providerName = "直播",
                    name = name,
                    episodes = urls.mapIndexed { index, url -> Episode(if (index == 0) "主源" else "备用$index", url) },
                ),
            ),
            lineIndex = 0,
            episodeIndex = 0,
            resumePositionMs = 0,
            liveChannel = true,
        ),
    )
}
