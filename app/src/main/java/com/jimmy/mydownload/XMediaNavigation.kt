package com.jimmy.mydownload

import java.net.URI

/**
 * X switches images horizontally and videos vertically using in-page navigation.
 * Keep the existing Android WebView and its media/scroll state for those transitions.
 * This class only identifies navigation: it never intercepts site links or reloads.
 */
internal object XMediaNavigation {
    private val mediaPath = Regex("""^(/[^/]+/status/[0-9]+)/(?:photo|video)/[0-9]+/?$""")
    private val verticalVideoPath = Regex("""^/i/status/[0-9]+/?$""")
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

    fun isMediaViewer(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme?.lowercase() == "https" && uri.host?.lowercase() in xHosts &&
            (mediaPath.matches(uri.path.orEmpty()) ||
                verticalVideoPath.matches(uri.path.orEmpty()))
    }.getOrDefault(false)

    /**
     * Preserve the browser/media overlay state even when vertically advancing
     * to a different post. Matching media-viewer paths are required on both ends
     * so a normal timeline/post navigation still resets discovery as before.
     */
    fun isMediaSwipe(before: String, after: String): Boolean {
        if (before == after) return false
        if (!isMediaViewer(before) || !isMediaViewer(after)) return false
        return runCatching {
            val previous = URI(before)
            val next = URI(after)
            previous.host.equals(next.host, ignoreCase = true)
        }.getOrDefault(false)
    }
}
