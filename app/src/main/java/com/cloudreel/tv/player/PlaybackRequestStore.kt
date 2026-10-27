package com.cloudreel.tv.player

import com.cloudreel.tv.core.model.PlaybackRequest

object PlaybackRequestStore {
    var request: PlaybackRequest? = null
        private set

    fun set(value: PlaybackRequest) {
        request = value
    }

    fun updateCursor(lineIndex: Int, episodeIndex: Int, positionMs: Long) {
        request = request?.copy(
            lineIndex = lineIndex,
            episodeIndex = episodeIndex,
            resumePositionMs = positionMs,
        )
    }

    fun replaceCurrentUrl(url: String, headers: Map<String, String>) {
        val current = request ?: return
        val lines = current.lines.mapIndexed { linePosition, line ->
            if (linePosition != current.lineIndex) {
                line
            } else {
                line.copy(
                    episodes = line.episodes.mapIndexed { episodePosition, episode ->
                        if (episodePosition != current.episodeIndex) episode else episode.copy(url = url, headers = headers)
                    },
                )
            }
        }
        request = current.copy(lines = lines)
    }
}
