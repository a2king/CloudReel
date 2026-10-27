package com.yingshicang.tv.feature.drive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.yingshicang.tv.core.catalog.readableError
import com.yingshicang.tv.core.drive.CloudPhase
import com.yingshicang.tv.core.drive.CloudQrSession
import com.yingshicang.tv.ui.Button
import com.yingshicang.tv.ui.LocalContainer
import com.yingshicang.tv.ui.OutlinedButton
import com.yingshicang.tv.ui.QrImage
import com.yingshicang.tv.ui.TvTextField
import com.yingshicang.tv.ui.theme.YingShiColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private data class CloudCard(
    val kind: String,
    val title: String,
    val manualHint: String,
)

private val cloudCards = listOf(
    CloudCard("115", "115 网盘", "UID=...; CID=...; SEID=...; KID=..."),
    CloudCard("quark", "夸克网盘", "从浏览器复制的夸克 Cookie"),
    CloudCard("ali", "阿里云盘", "refresh_token"),
    CloudCard("baidu", "百度网盘", "包含 BDUSS 的 Cookie"),
)

@Composable
fun DriveScreen(onPush: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("网盘", fontSize = 28.sp, color = YingShiColors.Text)
                    Text(
                        "用对应手机 App 扫码登录。片源里的 115、夸克、阿里云盘、百度网盘链接会用这里的账号换播放地址。夸克分享会先转存到你的夸克网盘。",
                        color = YingShiColors.Sub,
                        fontSize = 14.sp,
                    )
                }
                Button(onClick = onPush) { Text("手机提交") }
            }
        }
        items(cloudCards.size) { index ->
            val card = cloudCards[index]
            CloudLoginCard(card.kind, card.title, card.manualHint)
        }
    }
}

@Composable
private fun CloudLoginCard(kind: String, title: String, manualHint: String) {
    val container = LocalContainer.current
    val repository = container.repository
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(container.cloudSecret(kind).isNotBlank()) }
    var qr by remember { mutableStateOf<CloudQrSession?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var starting by remember { mutableStateOf(false) }
    LaunchedEffect(qr?.content) {
        var session = qr ?: return@LaunchedEffect
        while (isActive && qr != null) {
            try {
                val poll = repository.pollCloudLogin(kind, session)
                session = poll.session
                when (poll.phase) {
                    CloudPhase.WAITING -> status = "请用 $title App 扫码"
                    CloudPhase.SCANNED -> status = "已扫码，请在手机上确认"
                    CloudPhase.CONFIRMED -> {
                        container.saveCloudSecret(kind, repository.finishCloudLogin(kind, session))
                        ready = true
                        qr = null
                        failed = false
                        status = "$title 已登录"
                        return@LaunchedEffect
                    }
                    CloudPhase.EXPIRED -> {
                        qr = null
                        failed = true
                        status = "二维码已过期，请重新扫码"
                        return@LaunchedEffect
                    }
                    CloudPhase.CANCELED -> {
                        qr = null
                        failed = true
                        status = "手机上取消了登录"
                        return@LaunchedEffect
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                qr = null
                failed = true
                status = readableError(error)
                return@LaunchedEffect
            }
            delay(2_000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 20.sp, color = YingShiColors.Text)
        Text(
            if (ready) "$title 已登录。" else "用手机 App 扫二维码登录。",
            color = YingShiColors.Sub,
            fontSize = 14.sp,
        )
        qr?.let { session -> QrImage(session.content, Modifier.size(200.dp)) }
        status?.let { Text(it, color = if (failed) YingShiColors.Error else YingShiColors.Sub, fontSize = 14.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    if (starting) return@Button
                    starting = true
                    failed = false
                    status = "正在获取二维码…"
                    scope.launch {
                        try {
                            qr = repository.startCloudLogin(kind)
                            status = "请用手机 App 扫码"
                            failed = false
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            failed = true
                            status = readableError(error)
                        } finally {
                            starting = false
                        }
                    }
                },
                enabled = !starting,
            ) { Text(if (qr != null) "刷新二维码" else if (ready) "重新扫码" else "扫码登录") }
            if (ready && qr == null) {
                OutlinedButton(onClick = {
                    container.saveCloudSecret(kind, "")
                    ready = false
                    failed = false
                    status = "已退出$title"
                }) { Text("退出") }
            }
            OutlinedButton(onClick = { manual = !manual }) { Text(if (manual) "收起" else "手动填写") }
        }
        if (manual) {
            TvTextField(pasted, { pasted = it }, manualHint, Modifier.fillMaxWidth())
            Button(
                onClick = {
                    container.saveCloudSecret(kind, pasted)
                    pasted = ""
                    ready = container.cloudSecret(kind).isNotBlank()
                    manual = false
                    failed = false
                    status = if (ready) "$title 已登录" else "已清除$title"
                },
                enabled = pasted.isNotBlank(),
            ) { Text("保存") }
        }
    }
}
