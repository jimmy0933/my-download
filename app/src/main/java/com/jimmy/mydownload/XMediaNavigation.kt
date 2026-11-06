package com.jimmy.mydownload

import java.net.URI

/**
 * X gallery changes /photo/1 to /photo/2 (or /video/1) within the same post.
 * This is an in-document navigation, not a request to recreate the Android WebView.
 */
internal object XMediaNavigation {
    private val mediaPath = Regex("""^(/[^/]+/status/[0-9]+)/(?:photo|video)/[0-9]+/?$""")
    private val xHosts = setOf("x.com", "www.x.com", "twitter.com", "www.twitter.com")

    private fun postKey(url: String): String? = runCatching {
        val uri = URI(url)
        if (uri.scheme?.lowercase() != "https" || uri.host?.lowercase() !in xHosts)
            return@runCatching null
        mediaPath.matchEntire(uri.path.orEmpty())?.groupValues?.get(1)?.lowercase()
    }.getOrNull()

    fun samePostViewer(before: String, after: String): Boolean {
        if (before == after) return false
        val key = postKey(before) ?: return false
        return key == postKey(after)
    }
}
