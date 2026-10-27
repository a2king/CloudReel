package com.cloudreel.tv.core.database

import com.cloudreel.tv.core.show.ShowRecord
import com.cloudreel.tv.core.show.decodeGenres
import com.cloudreel.tv.core.show.encodeGenres

fun ShowRecord.toEntity(): ShowEntity {
    return ShowEntity(
        id = id,
        tmdbId = tmdbId?.ifBlank { null },
        title = title,
        normalizedTitle = normalizedTitle,
        year = year,
        mediaType = mediaType,
        genres = encodeGenres(genres),
        rating = rating,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        overview = overview,
        releaseDate = releaseDate,
        updatedAt = updatedAt,
    )
}

fun ShowEntity.toRecord(): ShowRecord {
    return ShowRecord(
        id = id,
        tmdbId = tmdbId,
        title = title,
        normalizedTitle = normalizedTitle,
        year = year,
        mediaType = mediaType,
        genres = decodeGenres(genres),
        rating = rating,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        overview = overview,
        releaseDate = releaseDate,
        updatedAt = updatedAt,
    )
}
