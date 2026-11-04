package com.jimmy.mydownload

import java.net.URI
import java.net.URLEncoder

/** URL parsing shared by the browser UI and local unit tests. */
object BrowserAddress {
    const val DEFAULT_SITE = "https://www.av01.media/"
    const val BLANK_TAB = "about:blank"

    fun isWebUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
            !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)

    fun navigateInput(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed == BLANK_TAB) return BLANK_TAB
        // Never treat file:, javascript:, intent:, content:, or data: as a URL.
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(trimmed) &&
            !trimmed.startsWith("https://", true) && !trimmed.startsWith("http://", true)
        ) return null
        val candidate = if (trimmed.startsWith("https://", true) ||
            trimmed.startsWith("http://", true)
        ) trimmed else if (trimmed.contains(' ') || !trimmed.contains('.')) {
            "https://www.google.com/search?q=" +
                URLEncoder.encode(trimmed, "UTF-8")
        } else "https://$trimmed"
        return candidate.takeIf(::isWebUrl)
    }

    fun displayName(url: String): String = if (url == BLANK_TAB) "新分頁"
    else runCatching { URI(url).host.removePrefix("www.") }.getOrDefault(url)
}
