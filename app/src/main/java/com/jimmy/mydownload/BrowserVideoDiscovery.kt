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
    val top: Float,
    val mediaId: String = "",
    val postText: String = "",
    val postUrl: String = ""
)
data class VisibleVideos(
    val viewportWidth: Float = 1f,
    val videos: List<VisibleVideo> = emptyList(),
    val pageText: String = "",
    val pagePostUrl: String = ""
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
        when {
            uri.path.orEmpty().lowercase().endsWith(".mp4") -> "mp4"
            uri.path.orEmpty().lowercase().endsWith(".m3u8") -> "hls"
            else -> null
        }
    }.getOrNull()

    fun decode(raw: String): VisibleVideos = runCatching {
        // evaluateJavascript returns a JSON-escaped *string*.
        val text = JSONArray("[" + raw + "]").getString(0)
        val json = JSONObject(text)
        val width = json.optDouble("width", 0.0).toFloat()
        if (width <= 0f) return@runCatching VisibleVideos()
        val array = json.optJSONArray("videos") ?: return@runCatching VisibleVideos(width)
        val pageText = json.optString("pageText")
        val pagePostUrl = json.optString("pagePostUrl")
        VisibleVideos(width, buildList {
            for (i in 0 until minOf(30, array.length())) {
                val item = array.optJSONObject(i) ?: continue
                val right = item.optDouble("right", -1.0).toFloat()
                val top = item.optDouble("top", -1.0).toFloat()
                if (right < 0 || top < 0 || !right.isFinite() || !top.isFinite()) continue
                add(VisibleVideo(
                    item.optString("src"), right, top,
                    item.optString("mediaId").takeIf { it.matches(Regex("""\d{6,22}""")) }.orEmpty(),
                    item.optString("postText"), item.optString("postUrl")
                ))
            }
        }, pageText, pagePostUrl)
    }.getOrDefault(VisibleVideos())

    fun decodeResourceList(raw: String): List<String> = runCatching {
        val data = JSONArray(JSONArray("[" + raw + "]").getString(0))
        buildList {
            for (i in 0 until minOf(30, data.length())) {
                val address = data.optString(i)
                if (kind(address) != null && address !in this) add(address)
            }
        }
    }.getOrDefault(emptyList())

    // Media players may use blob: URLs for <video>. These entries only expose
    // already-loaded network media resources, without making new API calls.
    val RESOURCE_SCAN_SCRIPT = """
(() => JSON.stringify(performance.getEntriesByType('resource')
  .map(entry => entry.name)
  .filter(url => /^https:\/\//.test(url) && /\.(?:mp4|m3u8)(?:[?#]|$)/i.test(url))
  .slice(-30)))()
""".trimIndent()

    val SCAN_SCRIPT = """
(() => {
  const isStatus = /\/status\/(\d{6,22})/.exec(location.pathname);
  const posts = [...document.querySelectorAll('article')];
  const matchingPost = isStatus
    ? posts.find(article => [...article.querySelectorAll('a[href*="/status/"]')]
        .some(link => link.getAttribute('href')?.includes('/status/' + isStatus[1])))
    : null;
  const detailPost = matchingPost || (isStatus ? posts[0] : null);
  const dataFor = (article) => {
    if (!article) return {text:'', url:''};
    const text = article.querySelector('[data-testid="tweetText"]')?.innerText || '';
    const link = article.querySelector('time')?.closest('a[href*="/status/"]') ||
      article.querySelector('a[href*="/status/"]');
    return {text: text.slice(0, 1000), url: link?.href || ''};
  };
  const detail = dataFor(detailPost);
  const found = {
    width: innerWidth, pageText: detail.text,
    pagePostUrl: detail.url || (isStatus ? location.href : ''),
    videos: []
  };
  for (const video of document.querySelectorAll('video')) {
    const rect = video.getBoundingClientRect();
    if (rect.width < 80 || rect.height < 65) continue;
    if (rect.right <= 0 || rect.left >= innerWidth ||
        rect.bottom <= 0 || rect.top >= innerHeight) continue;
    const raw = video.currentSrc || video.src ||
      video.querySelector('source')?.src || '';
    const article = video.closest('article') || detailPost;
    const post = dataFor(article);
    const thumb = video.poster ||
      (article && [...article.querySelectorAll('img[src*="twimg.com"]')]
        .map(img => img.src)
        .find(src => /(?:ext_tw_video_thumb|amplify_video_thumb|tweet_video_thumb)\/\d+/.test(src))) || '';
    const mediaId = ((/(?:ext_tw_video_thumb|amplify_video_thumb|tweet_video_thumb)\/(\d+)/.exec(thumb)) || [,''])[1];
    found.videos.push({
      src: raw, right: rect.right, top: rect.top, mediaId,
      postText: post.text, postUrl: post.url || (isStatus ? location.href : '')
    });
    if (found.videos.length >= 30) break;
  }
  return JSON.stringify(found);
})()
""".trimIndent()
})()
""".trimIndent()
}
