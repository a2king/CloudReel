package com.cloudreel.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import com.cloudreel.tv.ui.LocalContainer
import com.cloudreel.tv.ui.TvApp
import com.cloudreel.tv.ui.theme.YingShiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as YingShiApp).container
        setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                YingShiTheme {
                    TvApp()
                }
            }
        }
    }
}
