package com.yingshicang.tv.ui

import android.content.Context
import android.content.Intent
import com.yingshicang.tv.core.model.PlaybackRequest
import com.yingshicang.tv.feature.player.PlayerActivity
import com.yingshicang.tv.player.PlaybackRequestStore

fun Context.startPlayback(request: PlaybackRequest) {
    PlaybackRequestStore.set(request)
    startActivity(Intent(this, PlayerActivity::class.java))
}
