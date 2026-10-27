package com.yingshicang.tv.feature.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yingshicang.tv.ui.OutlinedButton
import androidx.tv.material3.Text
import com.yingshicang.tv.BuildConfig
import com.yingshicang.tv.ui.LocalContainer
import com.yingshicang.tv.ui.theme.YingShiColors
import kotlinx.coroutines.launch

@Composable
fun SettingScreen() {
    val repository = LocalContainer.current.repository
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("设置", fontSize = 28.sp, color = YingShiColors.Text)
        Text("映视 ${BuildConfig.VERSION_NAME}", color = YingShiColors.Sub)
        Text(
            "可以多源搜索、播放、续播，也可以看直播。115、夸克、阿里云盘、百度网盘在「网盘」里扫码登录。脚本只能访问网络，不能读写本机文件。",
            color = YingShiColors.Sub,
        )
        Text("播放地址来自你添加的接口，或内置公开测试片。允许 HTTP，是为了兼容常见采集接口。", color = YingShiColors.Sub)
        OutlinedButton(onClick = { scope.launch { repository.clearHistory(); message = "已清空播放历史" } }) {
            Text("清空播放历史")
        }
        OutlinedButton(onClick = { scope.launch { repository.clearFavorites(); message = "已清空收藏" } }) {
            Text("清空收藏")
        }
        OutlinedButton(onClick = { scope.launch { repository.clearSearches(); message = "已清空搜索历史" } }) {
            Text("清空搜索历史")
        }
        message?.let { Text(it, color = YingShiColors.Success) }
    }
}
