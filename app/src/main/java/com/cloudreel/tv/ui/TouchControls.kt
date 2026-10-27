package com.cloudreel.tv.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.ListItem as TvListItem
import androidx.tv.material3.NavigationDrawerItem as TvNavigationDrawerItem
import androidx.tv.material3.NavigationDrawerScope
import androidx.tv.material3.OutlinedButton as TvOutlinedButton
import androidx.tv.material3.Surface as TvSurface
import androidx.tv.material3.Tab as TvTab
import androidx.tv.material3.TabRowScope

/** 电视组件只处理遥控器确认键，这里补上手指点击。 */
@Composable
fun Modifier.touchActivate(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val currentClick = rememberUpdatedState(onClick)
    val currentEnabled = rememberUpdatedState(enabled)
    return pointerInput(Unit) {
        detectTapGestures(onTap = {
            if (currentEnabled.value) currentClick.value.invoke()
        })
    }
}

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    TvButton(
        onClick = onClick,
        modifier = modifier.touchActivate(enabled, onClick),
        enabled = enabled,
        content = content,
    )
}

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    TvOutlinedButton(
        onClick = onClick,
        modifier = modifier.touchActivate(enabled, onClick),
        enabled = enabled,
        content = content,
    )
}

@Composable
fun ListItem(
    selected: Boolean,
    onClick: () -> Unit,
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    TvListItem(
        selected = selected,
        onClick = onClick,
        headlineContent = headlineContent,
        modifier = modifier.touchActivate(enabled, onClick),
        enabled = enabled,
        supportingContent = supportingContent,
        trailingContent = trailingContent,
    )
}

@Composable
fun Surface(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    TvSurface(
        selected = selected,
        onClick = onClick,
        modifier = modifier.touchActivate(enabled, onClick),
        enabled = enabled,
        content = content,
    )
}

@Composable
fun TabRowScope.Tab(
    selected: Boolean,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    TvTab(
        selected = selected,
        onFocus = onFocus,
        modifier = modifier.touchActivate(enabled) {
            onFocus()
            onClick()
        },
        onClick = onClick,
        enabled = enabled,
        content = content,
    )
}

@Composable
fun NavigationDrawerScope.NavigationDrawerItem(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    TvNavigationDrawerItem(
        selected = selected,
        onClick = onClick,
        leadingContent = leadingContent,
        modifier = modifier.touchActivate(enabled, onClick),
        enabled = enabled,
        content = content,
    )
}
