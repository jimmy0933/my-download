package com.jimmy.mydownload

import java.net.URI

/** Prefer the text of the video post; never use generic "首頁 / X" as filename. */
object VideoTitle {
    private val genericTitles = listOf(
        "首頁 / X", "首頁_X", "home / x", "home_x", "x", "twitter",
        "首頁", "x.com", "影片", "video"
    )
    private val statusId = Regex("""/status/(\d{6,22})""")

    fun cleanText(input: String): String = input
        .replace(Regex("""[\u0000-\u001F\u007F]+"""), " ")
        .replace(Regex("""[\\/:*?"<>|]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '.')
        .take(78)
        .trimEnd()

    fun preferred(postText: String, pageTitle: String, postUrl: String, mediaId: String = ""): String {
        val fromPost = cleanText(postText)
        if (fromPost.isNotEmpty()) return fromPost
        val fallback = cleanText(pageTitle)
        if (fallback.isNotEmpty() &&
            genericTitles.none { cleanText(it).equals(fallback, true) }) {
            return fallback
        }
        val id = statusId.find(postUrl)?.groupValues?.getOrNull(1)
            ?: mediaId.takeIf { it.matches(Regex("""\d{6,22}""")) }
        return if (id == null) "X影片" else "X影片_$id"
    }

    fun isXStatus(url: String): Boolean {
        if (!BrowserVideoDiscovery.isXPage(url)) return false
        return runCatching { statusId.containsMatchIn(URI(url).path.orEmpty()) }.getOrDefault(false)
    }
}
