package com.cloudreel.tv.feature.live

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.cloudreel.tv.core.catalog.readableError
import com.cloudreel.tv.core.show.ChannelSlot
import com.cloudreel.tv.core.show.LiveCursor
import com.cloudreel.tv.core.show.advanceLiveUrl
import com.cloudreel.tv.core.show.decodeUrlList
import com.cloudreel.tv.core.show.dropDeadHead
import com.cloudreel.tv.core.show.shiftChannel
import com.cloudreel.tv.ui.Button
import com.cloudreel.tv.ui.ListItem
import com.cloudreel.tv.ui.LocalContainer
import com.cloudreel.tv.ui.OutlinedButton
import com.cloudreel.tv.ui.TvTextField
import com.cloudreel.tv.ui.isPhoneLayout
import com.cloudreel.tv.ui.theme.YingShiColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun LiveScreen(onPush: () -> Unit) {
    val repository = LocalContainer.current.repository
    val context = LocalContext.current
    val phone = isPhoneLayout()
    val playlists by repository.observePlaylistSummaries().collectAsStateWithLifecycle(emptyList())
    val categories by repository.observeLiveCategories().collectAsStateWithLifecycle(emptyList())
    val channelRows by repository.observeLiveChannels().collectAsStateWithLifecycle(emptyList())
    var selectedId by rememberSaveable { mutableStateOf("") }
    var overlay by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var cursor by remember { mutableStateOf<LiveCursor?>(null) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val cursorNow = rememberUpdatedState(cursor)
    val rowsNow = rememberUpdatedState(channelRows)
    val player = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val current = cursorNow.value ?: return
                val channel = rowsNow.value.firstOrNull { it.id == current.channelId } ?: return
                val next = advanceLiveUrl(current, decodeUrlList(channel.urls).size)
                if (next == null) {
                    notice = "这个频道的地址都打不开"
                } else {
                    cursor = next
                    notice = "当前地址失败，已切换下一条"
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(Unit) {
        try {
            repository.importLivesFromSavedLines()
            val removed = repository.removeUnplayableLiveSources()
            if (removed > 0) notice = "已去掉 $removed 个打不开的直播源"
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }
    LaunchedEffect(playlists) {
        if (playlists.none { it.id == selectedId }) {
            selectedId = playlists.firstOrNull()?.id.orEmpty()
        }
    }
    LaunchedEffect(selectedId) {
        if (selectedId.isBlank()) return@LaunchedEffect
        val page = try {
            repository.loadPlaylist(selectedId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return@LaunchedEffect
        repository.publishLive(page)
    }
    LaunchedEffect(channelRows) {
        if (channelRows.isEmpty()) {
            cursor = null
            return@LaunchedEffect
        }
        val current = cursor
        if (current == null || channelRows.none { it.id == current.channelId }) {
            val first = channelRows.first()
            cursor = LiveCursor(first.categoryId, first.id, 0)
        }
    }
    LaunchedEffect(cursor?.categoryId, cursor?.channelId, cursor?.urlIndex, channelRows) {
        val current = cursor ?: return@LaunchedEffect
        val row = channelRows.firstOrNull { it.id == current.channelId } ?: return@LaunchedEffect
        var urls = decodeUrlList(row.urls)
        if (current.urlIndex == 0 && urls.isNotEmpty()) {
            val verdict = try {
                repository.probeLiveUrl(urls.first())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                com.cloudreel.tv.core.live.LiveVerdict.UNKNOWN
            }
            urls = dropDeadHead(urls, verdict)
        }
        val url = urls.getOrNull(current.urlIndex) ?: urls.firstOrNull() ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
    }
    val slots = remember(channelRows) {
        channelRows.map { ChannelSlot(it.id, it.categoryId, decodeUrlList(it.urls)) }
    }
    fun move(delta: Int) {
        val current = cursor ?: return
        cursor = shiftChannel(slots, current, delta)
        notice = channelRows.firstOrNull { it.id == cursor?.channelId }?.name
    }
    BackHandler(enabled = overlay || adding) {
        overlay = false
        adding = false
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (overlay || adding) {
                    if (event.key == Key.Menu) {
                        overlay = false
                        true
                    } else {
                        false
                    }
                } else {
                    when (event.key) {
                        Key.DirectionUp -> {
                            move(-1)
                            true
                        }
                        Key.DirectionDown -> {
                            move(1)
                            true
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Menu -> {
                            overlay = true
                            true
                        }
                        else -> false
                    }
                }
            },
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = false
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        val currentName = channelRows.firstOrNull { it.id == cursor?.channelId }
        Column(modifier = Modifier.align(Alignment.TopStart).padding(if (phone) 12.dp else 20.dp)) {
            Text(currentName?.name ?: "直播", color = Color.White, fontSize = if (phone) 18.sp else 22.sp)
            currentName?.epgHint?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = YingShiColors.Sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            notice?.let { Text(it, color = YingShiColors.Warning, fontSize = 13.sp) }
            Text(
                if (phone) "点按画面打开频道列表" else "上下键换台，确认键打开频道列表",
                color = YingShiColors.Sub,
                fontSize = 12.sp,
            )
        }
        if (channelRows.isEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("还没有可播放的直播频道。", color = YingShiColors.Text)
                OutlinedButton(onClick = { adding = true }) { Text("添加直播") }
                OutlinedButton(onClick = onPush) { Text("手机提交") }
            }
        }
        if (!overlay && channelRows.isNotEmpty()) {
            Row(
                modifier = Modifier.align(Alignment.BottomEnd).padding(if (phone) 12.dp else 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (phone) {
                    OutlinedButton(onClick = { move(-1) }) { Text("上一台") }
                    OutlinedButton(onClick = { move(1) }) { Text("下一台") }
                }
                Button(onClick = { overlay = true }) { Text("频道") }
            }
        }
        if (overlay || adding) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(if (phone) 1f else 0.62f)
                    .background(Color(0xEE101318))
                    .padding(if (phone) 12.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("分类", color = YingShiColors.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text("频道", color = YingShiColors.Text, fontSize = 16.sp, modifier = Modifier.weight(1.4f))
                    OutlinedButton(onClick = { adding = !adding }) { Text(if (adding) "取消" else "添加") }
                    OutlinedButton(onClick = { overlay = false }) { Text("关闭") }
                }
                if (adding) {
                    LiveUrlForm { name, url, epgUrl ->
                        notice = repository.addLiveUrl(name, url, epgUrl)
                        adding = false
                    }
                }
                if (playlists.isNotEmpty()) {
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(playlists, key = { it.id }) { playlist ->
                            if (playlist.id == selectedId) {
                                Button(onClick = { selectedId = playlist.id }) { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            } else {
                                OutlinedButton(onClick = { selectedId = playlist.id }) { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyColumn(modifier = Modifier.weight(0.8f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(categories, key = { it.id }) { category ->
                            ListItem(
                                selected = category.id == cursor?.categoryId,
                                onClick = {
                                    val first = channelRows.firstOrNull { it.categoryId == category.id } ?: return@ListItem
                                    cursor = LiveCursor(category.id, first.id, 0)
                                },
                                headlineContent = { Text(category.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                    LazyColumn(modifier = Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val visible = channelRows.filter { it.categoryId == (cursor?.categoryId ?: channelRows.firstOrNull()?.categoryId) }
                        items(visible, key = { it.id }) { channel ->
                            ListItem(
                                selected = channel.id == cursor?.channelId,
                                onClick = { cursor = LiveCursor(channel.categoryId, channel.id, 0) },
                                headlineContent = { Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = channel.epgHint?.let { hint ->
                                    { Text(hint, color = YingShiColors.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                },
                            )
                        }
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
        TvTextField(name, { name = it }, "名称", Modifier.fillMaxWidth())
        TvTextField(url, { url = it }, "https://", Modifier.fillMaxWidth())
        TvTextField(epgUrl, { epgUrl = it }, "节目单，可留空", Modifier.fillMaxWidth())
        Button(
            onClick = {
                if (saving || url.isBlank()) return@Button
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
        error?.let { Text(it, color = YingShiColors.Error) }
    }
}
