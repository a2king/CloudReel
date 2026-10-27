package com.yingshicang.tv.data

import com.yingshicang.tv.core.model.DetailRequest
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
