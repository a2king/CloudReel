package com.cloudreel.tv.core.provider

import com.cloudreel.tv.core.model.MediaCard
import com.cloudreel.tv.core.model.MediaDetail
import com.cloudreel.tv.core.model.SearchHit

interface MediaProvider {
    val id: String
    val name: String
    val priority: Int

    suspend fun home(): List<MediaCard>

    suspend fun search(keyword: String): List<SearchHit>

    suspend fun detail(sourceId: String): MediaDetail
}
