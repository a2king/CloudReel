package com.cloudreel.tv.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

@Composable
fun isPhoneLayout(): Boolean {
    val config = LocalConfiguration.current
    val mode = config.uiMode and Configuration.UI_MODE_TYPE_MASK
    if (mode == Configuration.UI_MODE_TYPE_TELEVISION) return false
    return config.smallestScreenWidthDp < 600
}
