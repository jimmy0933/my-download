package com.jimmy.mydownload

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Read-only discovery of video elements on the currently visible web page.
 * No DOM changes, cookie extraction, DRM bypass, or protected X API requests.
 */
data class VisibleVideo(
    val url: String,
    val right: Float,
    val top: Float
)
data class VisibleVideos(
    val viewportWidth: Float = 1f,
    val videos: List<VisibleVideo> = emptyList()
)

object BrowserVideoDiscovery {
    fun isXPage(url: String): Boolean = runCatching {
        val host = URI(url).host.orEmpty().lowercase()
        host == "x.com" || host == "www.x.com" ||
            host == "twitter.com" || host == "www.twitter.com"
    }.getOrDefault(false)

    /** Only complete, directly addressable HTTPS video playlists/files are downloaded.
     * blob:, .m4s fragments, and encrypted streams are not direct download URLs.
     */
    fun kind(url: String): String? = runCatching {
        val uri = URI(url)
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() ||
            uri.userInfo != null) return@runCatching null
        when (uri.path.orEmpty().lowercase().substringAfterLast('/')) {
            else -> when {
                uri.path.orEmpty().lowercase().endsWith(".mp4") -> "mp4"
                uri.path.orEmpty().lowercase().endsWith(".m3u8") -> "hls"
                else -> null
            }
        }
    }.getOrNull()

    fun decode(raw: String): VisibleVideos = runCatching {
        // evaluateJavascript returns a JSON-escaped *string*.
        val text = JSONArray("[" + raw + "]").getString(0)
        val json = JSONObject(text)
        val width = json.optDouble("width", 0.0).toFloat()
        if (width <= 0f) return@runCatching VisibleVideos()
        val array = json.optJSONArray("videos") ?: return@runCatching VisibleVideos(width)
        VisibleVideos(width, buildList {
            for (i in 0 until minOf(30, array.length())) {
                val item = array.optJSONObject(i) ?: continue
                val right = item.optDouble("right", -1.0).toFloat()
                val top = item.optDouble("top", -1.0).toFloat()
                if (right < 0 || top < 0 || !right.isFinite() || !top.isFinite()) continue
                add(VisibleVideo(item.optString("src"), right, top))
            }
        })
    }.getOrDefault(VisibleVideos())

    val SCAN_SCRIPT = """
(() => {
  const found = {width: innerWidth, videos: []};
  for (const video of document.querySelectorAll('video')) {
    const rect = video.getBoundingClientRect();
    if (rect.width < 80 || rect.height < 65) continue;
    if (rect.right <= 0 || rect.left >= innerWidth ||
        rect.bottom <= 0 || rect.top >= innerHeight) continue;
    const raw = video.currentSrc || video.src ||
        (video.querySelector('source') && video.querySelector('source').src) || '';
    found.videos.push({src: raw, right: rect.right, top: rect.top});
    if (found.videos.length >= 30) break;
  }
  return JSON.stringify(found);
})()
""".trimIndent()
}
