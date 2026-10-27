package com.cloudreel.tv.feature.source

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cloudreel.tv.ui.Button
import com.cloudreel.tv.ui.ListItem
import com.cloudreel.tv.ui.OutlinedButton
import androidx.tv.material3.Text
import com.cloudreel.tv.core.catalog.readableError
import com.cloudreel.tv.core.model.SourceKinds
import com.cloudreel.tv.ui.LocalContainer
import com.cloudreel.tv.ui.TvTextField
import com.cloudreel.tv.ui.theme.YingShiColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SourceScreen(onAdd: () -> Unit, onPush: () -> Unit) {
    val repository = LocalContainer.current.repository
    val sources by repository.observeSources().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("数据源", fontSize = 28.sp, color = YingShiColors.Text)
                    Text(
                        "多仓地址会按线路保存。首页线路只显示能刮削的，脚本仓不会出现在线路里。",
                        color = YingShiColors.Sub,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                OutlinedButton(onClick = onPush) { Text("手机提交") }
                Button(onClick = onAdd) { Text("添加接口") }
            }
        }
        items(sources, key = { it.id }) { source ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ListItem(
                    selected = source.enabled,
                    onClick = { scope.launch { repository.setSourceEnabled(source.id, !source.enabled) } },
                    modifier = Modifier.weight(1f),
                    headlineContent = { Text(source.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            sourceLabel(source),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = YingShiColors.Sub,
                        )
                    },
                    trailingContent = {
                        Text(
                            if (source.enabled) "已启用" else "已停用",
                            color = if (source.enabled) YingShiColors.Success else YingShiColors.Sub,
                        )
                    },
                )
                if (!source.builtin) {
                    OutlinedButton(onClick = { scope.launch { repository.deleteSource(source.id) } }) { Text("删除") }
                }
            }
        }
    }
}

private fun sourceLabel(source: com.cloudreel.tv.core.database.SourceConfigEntity): String {
    return when (source.kind) {
        SourceKinds.CMS, SourceKinds.TVBOX -> source.location
        SourceKinds.SCRIPT -> if (source.builtin) "内置脚本样例" else "脚本源"
        else -> "内置 JSON 测试片库"
    }
}

@Composable
fun AddSourceScreen(onDone: () -> Unit, onBack: () -> Unit) {
    val repository = LocalContainer.current.repository
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var progress by rememberSaveable { mutableStateOf<String?>(null) }
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("添加数据源", fontSize = 28.sp, color = YingShiColors.Text)
        Text(
            "多仓地址会保存成线路。已经拆开的旧采集站不会自动合并，删掉后重新添加这份地址即可。",
            color = YingShiColors.Sub,
        )
        TvTextField(value = name, onValueChange = { name = it }, placeholder = "名称，例如 我的片库", modifier = Modifier.fillMaxWidth())
        TvTextField(value = url, onValueChange = { url = it }, placeholder = "https://", modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    if (result != null) {
                        onDone()
                        return@Button
                    }
                    if (saving) return@Button
                    saving = true
                    error = null
                    progress = null
                    scope.launch {
                        try {
                            val message = repository.addCms(name, url) { update ->
                                withContext(Dispatchers.Main) { progress = update }
                            }
                            progress = null
                            result = message
                            saving = false
                        } catch (throwable: CancellationException) {
                            throw throwable
                        } catch (throwable: Exception) {
                            error = readableError(throwable)
                            progress = null
                            saving = false
                        }
                    }
                },
                enabled = url.isNotBlank() && !saving,
            ) { Text(if (result != null) "完成" else if (saving) "正在检查" else "保存") }
            OutlinedButton(onClick = onBack, enabled = !saving) { Text(if (result != null) "返回" else "取消") }
        }
        progress?.let { Text(it, color = YingShiColors.Sub) }
        result?.let { Text(it, color = YingShiColors.Success) }
        error?.let { Text(it, color = YingShiColors.Error) }
    }
}
