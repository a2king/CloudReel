package com.cloudreel.tv.core.lan

import org.json.JSONObject

sealed interface Submission {
    data class Cms(val name: String, val url: String) : Submission
    data class Live(
        val name: String,
        val text: String?,
        val url: String?,
        val epg: String?,
        val epgUrl: String?,
    ) : Submission
    data class Drive(
        val provider: String,
        val name: String,
        val endpoint: String,
        val username: String,
        val secret: String,
    ) : Submission
    data class Script(val name: String, val code: String?, val url: String?) : Submission
}

fun parseSubmission(body: String): Submission {
    val json = try {
        JSONObject(body)
    } catch (_: Exception) {
        throw IllegalArgumentException("提交的内容不是 JSON")
    }
    return when (json.optString("type")) {
        "cms" -> Submission.Cms(json.optString("name"), json.optString("url"))
        "live" -> Submission.Live(
            name = json.optString("name"),
            text = json.optString("text").ifBlank { null },
            url = json.optString("url").ifBlank { null },
            epg = json.optString("epg").ifBlank { null },
            epgUrl = json.optString("epgUrl").ifBlank { null },
        )
        "drive" -> Submission.Drive(
            provider = json.optString("provider"),
            name = json.optString("name"),
            endpoint = json.optString("endpoint"),
            username = json.optString("username"),
            secret = json.optString("secret"),
        )
        "script" -> Submission.Script(
            name = json.optString("name"),
            code = json.optString("code").ifBlank { null },
            url = json.optString("url").ifBlank { null },
        )
        else -> throw IllegalArgumentException("不认识的提交类型")
    }
}
