package com.cloudreel.tv.ui

import androidx.navigation.NavHostController
import com.cloudreel.tv.core.catalog.stableMediaKey
import com.cloudreel.tv.core.database.JsonCodec
import com.cloudreel.tv.core.database.PlayHistoryEntity
import com.cloudreel.tv.core.model.DetailRequest
import com.cloudreel.tv.core.model.MediaCard
import com.cloudreel.tv.core.model.MergedHit
import com.cloudreel.tv.core.model.SearchHit
import com.cloudreel.tv.core.model.SourceRef
import com.cloudreel.tv.data.DetailNavStore

fun NavHostController.openDetail(mediaKey: String, refs: List<SourceRef>) {
    if (refs.isEmpty()) return
    val token = DetailNavStore.put(DetailRequest(mediaKey, refs))
    navigate("detail/$token")
}

fun NavHostController.openCard(card: MediaCard) {
    openDetail(stableMediaKey(card.title, card.year), listOf(SourceRef(card.providerId, card.sourceId)))
}

fun NavHostController.openMerged(hit: MergedHit) {
    openDetail(
        stableMediaKey(hit.title, hit.year),
        hit.hits.map { SourceRef(it.providerId, it.sourceId) },
    )
}

fun NavHostController.openHit(hit: SearchHit) {
    openDetail(stableMediaKey(hit.title, hit.year), listOf(SourceRef(hit.providerId, hit.sourceId)))
}

fun NavHostController.openHistory(item: PlayHistoryEntity) {
    val refs = JsonCodec.decodeRefs(item.refsJson).ifEmpty {
        listOf(SourceRef(item.providerId, item.sourceId))
            .filter { it.providerId.isNotBlank() && it.sourceId.isNotBlank() }
    }
    openDetail(item.mediaKey, refs)
}
