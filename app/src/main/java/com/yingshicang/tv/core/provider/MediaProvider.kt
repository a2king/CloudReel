package com.yingshicang.tv.core.provider

import com.yingshicang.tv.core.model.MediaCard
import com.yingshicang.tv.core.model.MediaDetail
import com.yingshicang.tv.core.model.SearchHit

interface MediaProvider {
    val id: String
    val name: String
    val priority: Int

    suspend fun home(): List<MediaCard>

    suspend fun search(keyword: String): List<SearchHit>

    suspend fun detail(sourceId: String): MediaDetail
}
