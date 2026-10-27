package com.yingshicang.tv.feature.source

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yingshicang.tv.ui.Button
import com.yingshicang.tv.ui.OutlinedButton
import androidx.tv.material3.Text
import com.yingshicang.tv.ui.LocalContainer
import com.yingshicang.tv.ui.QrImage
import com.yingshicang.tv.ui.theme.YingShiColors

@Composable
fun PushScreen() {
    val portal = LocalContainer.current.portal
    val status by portal.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("手机提交", fontSize = 28.sp, color = YingShiColors.Text)
        Text(
            "开启后，手机会看到这个内网地址。扫码可以提交 CMS 接口、直播、WebDAV、Alist 和脚本。提交时请留在映视里。",
            color = YingShiColors.Sub,
        )
        if (status.running && status.url != null) {
            Text(status.url.orEmpty(), color = YingShiColors.Text, fontSize = 18.sp)
            QrImage(status.url.orEmpty(), Modifier.size(280.dp))
            OutlinedButton(onClick = portal::stop) { Text("关闭") }
        } else {
            Button(onClick = portal::start) { Text("开启局域网提交") }
        }
        status.error?.let { Text(it, color = YingShiColors.Error) }
        status.message?.let { Text(it, color = YingShiColors.Success) }
    }
}
