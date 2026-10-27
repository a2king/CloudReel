package com.cloudreel.tv.feature.live

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.tv.material3.Icon
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
    fun shiftCategory(delta: Int) {
        if (categories.isEmpty()) return
        val index = categories.indexOfFirst { it.id == cursor?.categoryId }.let { if (it < 0) 0 else it }
        val next = categories[Math.floorMod(index + delta, categories.size)]
        val first = channelRows.firstOrNull { it.categoryId == next.id } ?: return
        cursor = LiveCursor(next.id, first.id, 0)
        notice = first.name
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
                when (event.key) {
                    Key.DirectionUp -> {
                        move(-1)
                        true
                    }
                    Key.DirectionDown -> {
                        move(1)
                        true
                    }
                    Key.DirectionLeft -> if (overlay) {
                        shiftCategory(-1)
                        true
                    } else {
                        false
                    }
                    Key.DirectionRight -> if (overlay) {
                        shiftCategory(1)
                        true
                    } else {
                        false
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Menu -> {
                        overlay = !overlay
                        true
                    }
                    else -> false
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
        if (phone && !overlay && !adding && channelRows.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxSize().clickable { overlay = true })
        }
        val currentName = channelRows.firstOrNull { it.id == cursor?.channelId }
        Column(modifier = Modifier.align(Alignment.TopStart).padding(if (phone) 12.dp else 20.dp)) {
            Text(currentName?.name ?: "直播", color = Color.White, fontSize = if (phone) 18.sp else 22.sp)
            currentName?.epgHint?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = YingShiColors.Sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            notice?.let { Text(it, color = YingShiColors.Warning, fontSize = 13.sp) }
            Text(
                if (overlay) "上下键换台  ·  左右键换分类  ·  OK 关闭列表" else if (phone) "点按画面打开频道列表" else "上下键换台，确认键打开频道列表",
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x66101014))
                    .clickable { overlay = false },
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(if (phone) 0.96f else 0.84f)
                        .fillMaxHeight(if (phone) 0.88f else 0.76f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xE6101318))
                        .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(22.dp))
                        .clickable(onClick = {})
                        .padding(if (phone) 12.dp else 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("直播电视", color = YingShiColors.Text, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { adding = !adding }) { Text(if (adding) "取消" else "添加") }
                        OutlinedButton(onClick = { overlay = false; adding = false }) { Text("关闭") }
                    }
                    if (adding) {
                        LiveUrlForm { name, url, epgUrl ->
                            notice = repository.addLiveUrl(name, url, epgUrl)
                            adding = false
                        }
                    }
                    if (playlists.size > 1) {
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
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LazyColumn(modifier = Modifier.weight(0.78f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(categories, key = { it.id }) { category ->
                                val selected = category.id == (cursor?.categoryId ?: categories.firstOrNull()?.id)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (selected) Color(0x332E6BFF) else Color.Transparent)
                                        .border(if (selected) 1.5.dp else 0.dp, if (selected) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
                                        .clickable {
                                            val first = channelRows.firstOrNull { it.categoryId == category.id } ?: return@clickable
                                            cursor = LiveCursor(category.id, first.id, 0)
                                        }
                                        .padding(horizontal = 10.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(categoryIcon(category.name), contentDescription = null, tint = if (selected) Color.White else YingShiColors.Sub)
                                    Text(category.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        LazyColumn(modifier = Modifier.weight(1.35f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val visible = channelRows.filter { it.categoryId == (cursor?.categoryId ?: channelRows.firstOrNull()?.categoryId) }
                            items(visible.size) { index ->
                                val channel = visible[index]
                                val selected = channel.id == cursor?.channelId
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (selected) Color(0x332E6BFF) else Color.Transparent)
                                        .border(if (selected) 1.5.dp else 0.dp, if (selected) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
                                        .clickable { cursor = LiveCursor(channel.categoryId, channel.id, 0) }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text("%03d".format(index + 1), color = YingShiColors.Sub, fontSize = 12.sp)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(channel.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        channel.epgHint?.takeIf { it.isNotBlank() }?.let { hint ->
                                            Text(hint, color = YingShiColors.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = YingShiColors.Sub)
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = YingShiColors.Sub)
                        Text("上下键换台", color = YingShiColors.Sub, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        Text("OK  打开或关闭列表", color = YingShiColors.Sub, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private fun categoryIcon(name: String): ImageVector {
    return when {
        name.contains("地方") || name.contains("省") -> Icons.Filled.Place
        name.contains("体育") -> Icons.Filled.Star
        name.contains("卫视") -> Icons.Filled.List
        else -> Icons.Filled.PlayArrow
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
