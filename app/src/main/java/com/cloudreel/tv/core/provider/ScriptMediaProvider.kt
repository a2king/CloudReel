package com.cloudreel.tv.core.provider

import com.cloudreel.tv.core.model.MediaCard
import com.cloudreel.tv.core.model.MediaDetail
import com.cloudreel.tv.core.model.SearchHit
import com.cloudreel.tv.core.script.ScriptRunner
import com.cloudreel.tv.core.script.scriptCards
import com.cloudreel.tv.core.script.scriptDetail
import com.cloudreel.tv.core.script.scriptHits
import com.cloudreel.tv.core.script.scriptPlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScriptMediaProvider(
    private val runner: ScriptRunner,
    private val code: String,
    override val id: String,
    override val name: String,
    override val priority: Int,
) : MediaProvider {
    override suspend fun home(): List<MediaCard> = withContext(Dispatchers.IO) {
        scriptCards(runner.invoke(code, "home", emptyList()), id, name)
    }

    override suspend fun search(keyword: String): List<SearchHit> = withContext(Dispatchers.IO) {
        scriptHits(runner.invoke(code, "search", listOf(keyword, 1)), id, name, priority)
    }

    override suspend fun detail(sourceId: String): MediaDetail = withContext(Dispatchers.IO) {
        val parsed = scriptDetail(runner.invoke(code, "detail", listOf(sourceId)), id, name)
        val lines = parsed.lines.map { line ->
            line.copy(
                episodes = line.episodes.map { episode ->
                    if (episode.url.isNotBlank()) {
                        episode
                    } else {
                        val played = scriptPlay(runner.invoke(code, "play", listOf(line.name, episode.name)))
                        episode.copy(url = played.url, headers = episode.headers + played.headers)
                    }
                },
            )
        }
        parsed.copy(lines = lines)
    }
}
