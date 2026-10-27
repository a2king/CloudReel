package com.cloudreel.tv.data

import com.cloudreel.tv.core.model.DetailRequest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object DetailNavStore {
    private val requests = ConcurrentHashMap<String, DetailRequest>()

    fun put(request: DetailRequest): String {
        val token = UUID.randomUUID().toString().replace("-", "").take(12)
        requests[token] = request
        return token
    }

    fun get(token: String): DetailRequest? = requests[token]
}

object ShowNavStore {
    private val shows = ConcurrentHashMap<String, String>()

    fun put(showId: String): String {
        val token = UUID.randomUUID().toString().replace("-", "").take(12)
        shows[token] = showId
        return token
    }

    fun get(token: String): String? = shows[token]
}
