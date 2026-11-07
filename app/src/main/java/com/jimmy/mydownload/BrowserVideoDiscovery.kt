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
    /**
     * Scans the LIVE active player at download-click time. Unlike SCAN_SCRIPT,
     * which lists every visible/preloaded video, this returns one active clip
     * with caption and post identity from its own card. Never reuse stale
     * scan results from a previous swipe.
     */
    val ACTIVE_VIDEO_SCAN_SCRIPT = """
(() => {
  const result = {width:innerWidth, videos:[], pageText:'', pagePostUrl:''};
  const within = rect => rect.width >= 100 && rect.height >= 70 &&
    rect.left < innerWidth && rect.right > 0 &&
    rect.top < innerHeight && rect.bottom > 0;
  const visibleArea = rect =>
    Math.max(0,Math.min(innerWidth,rect.right)-Math.max(0,rect.left)) *
    Math.max(0,Math.min(innerHeight,rect.bottom)-Math.max(0,rect.top));
  const entries = [...document.querySelectorAll('video')].map(video => {
    const rect = video.getBoundingClientRect();
    const area = visibleArea(rect);
    const centerY = (rect.top+rect.bottom)/2;
    const centerX = (rect.left+rect.right)/2;
    const distance = Math.abs(centerY-innerHeight/2)/Math.max(innerHeight,1) +
      Math.abs(centerX-innerWidth/2)/Math.max(innerWidth,1);
    const playing = !video.paused && !video.ended && video.readyState >= 1;
    return {video,rect,area,distance,playing};
  }).filter(entry => within(entry.rect) && entry.area>10000);
  if (!entries.length) return JSON.stringify(result);
  // Favor a playing, prominently visible video over one preloaded above/below.
  entries.sort((a,b) => Number(b.playing)-Number(a.playing) ||
    b.area-a.area || a.distance-b.distance);
  const {video,rect} = entries[0];

  const currentStatus = /\/status\/(\d{6,22})/.exec(location.pathname)?.[1] || '';
  const statusLinks = container => container
    ? [...container.querySelectorAll('a[href*="/status/"]')].map(a=>a.href)
       .filter(url => /\/status\/\d{6,22}/.test(url)) : [];

  let article = video.closest('article');
  const getText = node => {
    const candidate = node?.querySelector(
      '[data-testid="tweetText"],[data-testid="media-caption"],' +
      '[data-testid="video-caption"],[data-testid="videoDescription"]'
    );
    return (candidate?.innerText?.trim() ||
      candidate?.textContent?.trim() || '').slice(0,1200);
  };
  let text = getText(article);
  let url = statusLinks(article)[0] || '';

  // X media-viewer DOM may place video and caption in sibling nodes.
  // Find the smallest ancestor with a caption or matching status link,
  // without accidentally selecting the whole feed with an older post.
  if (!text || !url) {
    let node=video.parentElement;
    for (let level=0;node && level<12;node=node.parentElement,level++) {
      const candidateText=getText(node);
      const links=statusLinks(node);
      const matchingUrl=currentStatus ?
        links.find(link=>link.includes('/status/'+currentStatus)) : '';
      const candidateUrl=matchingUrl || (links.length===1?links[0]:'');
      if ((!text && candidateText) || (!url && candidateUrl)) {
        if (!text && candidateText) text=candidateText;
        if (!url && candidateUrl) url=candidateUrl;
      }
      if (text && url) break;
    }
  }

  // If the viewer is on a specific status, resolve its caption from that
  // post rather than borrowing another article's text.
  if (currentStatus) {
    const exact = [...document.querySelectorAll('article')].find(node =>
      statusLinks(node).some(link => link.includes('/status/'+currentStatus)));
    if (exact && (article === exact || !text || !article)) {
      text=getText(exact)||text;
    }
    if (!url || !url.includes('/status/'+currentStatus)) url=location.href;
  }

  // Final fallback: nearest visible caption to the active video, not the
  // first tweet in the DOM. A distant/hidden caption is not accepted.
  if (!text) {
    const captions=[...document.querySelectorAll('[data-testid="tweetText"]')];
    const scored=captions.map(node => {
      const r=node.getBoundingClientRect();
      const d=Math.abs((r.top+r.bottom)/2-(rect.top+rect.bottom)/2);
      return {node,r,d};
    }).filter(x=>x.r.width>20 && x.r.height>10 &&
      x.r.bottom>0 && x.r.top<innerHeight &&
      x.d<innerHeight*0.82).sort((a,b)=>a.d-b.d);
    text=scored[0]?.node.innerText?.trim() ||
      scored[0]?.node.textContent?.trim() || '';
  }

  const src=video.currentSrc || video.src ||
    video.querySelector('source')?.src || '';
  const thumb=video.poster ||
    article?.querySelector('img[src*="twimg.com"]')?.src || '';
  const mediaId=(
    /(?:ext_tw_video_thumb|amplify_video_thumb|tweet_video_thumb)\/(\d+)/.exec(thumb) ||
    /(?:ext_tw_video|amplify_video|tweet_video|tv_video)\/(\d+)/.exec(src) ||
    [,'']
  )[1];
  result.pageText=text.slice(0,1200);
  result.pagePostUrl=url || (currentStatus?location.href:'');
  result.videos.push({
    // The visible active video may be partially clipped by a carousel edge.
    // Keep coordinates valid for the Android decoder without changing the DOM.
    src,right:Math.max(0,rect.right),top:Math.max(0,rect.top),mediaId,
    postText:result.pageText,postUrl:result.pagePostUrl
  });
  return JSON.stringify(result);
})()
""".trimIndent()

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
}
