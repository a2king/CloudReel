package com.yingshicang.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.Icon
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.Text
import com.yingshicang.tv.feature.detail.DetailScreen
import com.yingshicang.tv.feature.drive.DriveScreen
import com.yingshicang.tv.feature.home.HomeScreen
import com.yingshicang.tv.feature.library.FavoriteScreen
import com.yingshicang.tv.feature.library.HistoryScreen
import com.yingshicang.tv.feature.live.LiveScreen
import com.yingshicang.tv.feature.search.SearchScreen
import com.yingshicang.tv.feature.setting.SettingScreen
import com.yingshicang.tv.feature.source.AddSourceScreen
import com.yingshicang.tv.feature.source.PushScreen
import com.yingshicang.tv.feature.source.SourceScreen
import com.yingshicang.tv.ui.theme.YingShiColors

private data class NavItem(val route: String, val label: String, val icon: ImageVector)

private val navItems = listOf(
    NavItem("home", "首页", Icons.Filled.Home),
    NavItem("search", "搜索", Icons.Filled.Search),
    NavItem("live", "直播", Icons.Filled.PlayArrow),
    NavItem("drive", "网盘", Icons.Filled.AccountBox),
    NavItem("favorite", "收藏", Icons.Filled.Favorite),
    NavItem("history", "历史", Icons.Filled.DateRange),
    NavItem("source", "数据源", Icons.Filled.Menu),
    NavItem("push", "推送", Icons.AutoMirrored.Filled.Send),
    NavItem("setting", "设置", Icons.Filled.Settings),
)

@Composable
fun TvApp() {
    val container = LocalContainer.current
    var welcomed by rememberSaveable { mutableStateOf(container.prefs.hasSeenWelcome()) }
    if (!welcomed) {
        WelcomeScreen {
            container.prefs.setSeenWelcome()
            welcomed = true
        }
        return
    }
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    NavigationDrawer(
        modifier = Modifier.fillMaxSize(),
        drawerContent = {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                for (item in navItems) {
                    NavigationDrawerItem(
                        selected = route == item.route,
                        onClick = { navController.openSection(item.route) },
                        leadingContent = { Icon(item.icon, contentDescription = item.label) },
                    ) {
                        Text(item.label, fontSize = 16.sp)
                    }
                }
            }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(YingShiColors.Bg),
        ) {
            AppNavHost(navController)
        }
    }
}

@Composable
private fun AppNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = "home") {
        composable("home") { HomeScreen(navController) }
        composable("search") { SearchScreen(navController) }
        composable("favorite") { FavoriteScreen(navController) }
        composable("history") { HistoryScreen(navController) }
        composable("source") { SourceScreen(onAdd = { navController.navigate("add-source") }, onPush = { navController.navigate("push") }) }
        composable("live") { LiveScreen(onPush = { navController.navigate("push") }) }
        composable("drive") { DriveScreen(onPush = { navController.navigate("push") }) }
        composable("push") { PushScreen() }
        composable("setting") { SettingScreen() }
        composable("add-source") {
            AddSourceScreen(
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
        composable("detail/{token}") { entry ->
            DetailScreen(
                token = entry.arguments?.getString("token").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
    }
}

private fun NavHostController.openSection(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun WelcomeScreen(onStart: () -> Unit) {
    val focus = FocusRequester()
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(YingShiColors.Bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("映视", fontSize = 36.sp, color = YingShiColors.Text)
            Text(
                "电视上的聚合播放器。先用内置公开测试片走通首页、搜索、详情、播放和续播。",
                modifier = Modifier.padding(top = 16.dp),
                fontSize = 18.sp,
                color = YingShiColors.Text,
            )
            Text(
                "之后可以添加 CMS 接口和直播，也可以在「网盘」里扫码登录 115、夸克、阿里云盘、百度网盘。应用会访问这些地址返回的播放链接。",
                modifier = Modifier.padding(top = 10.dp),
                color = YingShiColors.Sub,
                fontSize = 16.sp,
            )
            Text(
                "不内置第三方影视站，也不处理加密保护的内容。",
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
                color = YingShiColors.Sub,
                fontSize = 16.sp,
            )
            Button(onClick = onStart, modifier = Modifier.focusRequester(focus)) {
                Text("开始使用")
            }
        }
    }
}
