package com.cloudreel.tv.ui

import android.content.Context
import android.content.Intent
import com.cloudreel.tv.core.model.PlaybackRequest
import com.cloudreel.tv.feature.player.PlayerActivity
import com.cloudreel.tv.player.PlaybackRequestStore

fun Context.startPlayback(request: PlaybackRequest) {
    PlaybackRequestStore.set(request)
    startActivity(Intent(this, PlayerActivity::class.java))
}
