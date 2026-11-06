package com.jimmy.mydownload

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt

private val BrowserNavy = Color(0xFF091421)
private val BrowserPanel = Color(0xFF142333)
private val BrowserGreen = Color(0xFF27DBB3)


/**
 * A movable overlay, not an element inside the site's HTML. A tap still downloads,
 * while an intentional drag updates the saved normalized position.
 */
@Composable
private fun BoxScope.MovableDownloadButton(
    containerWidth: Int,
    containerHeight: Int,
    opacityPercent: Int,
    position: FloatingDownloadPosition,
    onMove: (FloatingDownloadPosition) -> Unit,
    onMoveFinished: (FloatingDownloadPosition) -> Unit,
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    val edge = with(density) { 10.dp.roundToPx() }
    var buttonWidth by remember { mutableIntStateOf(0) }
    var buttonHeight by remember { mutableIntStateOf(0) }
    val availableX = (containerWidth - buttonWidth - edge * 2).coerceAtLeast(0)
    val availableY = (containerHeight - buttonHeight - edge * 2).coerceAtLeast(0)
    val posX = edge + (position.xFraction.coerceIn(0f, 1f) * availableX).roundToInt()
    val posY = edge + (position.yFraction.coerceIn(0f, 1f) * availableY).roundToInt()
    val latestPosition by rememberUpdatedState(position)
    val latestOnMove by rememberUpdatedState(onMove)
    val latestOnFinished by rememberUpdatedState(onMoveFinished)

    Box(
        modifier = Modifier.align(Alignment.TopStart)
            .offset { IntOffset(posX, posY) }
            .alpha(opacityPercent.coerceIn(20, 100) / 100f)
            .onSizeChanged { size ->
                buttonWidth = size.width
                buttonHeight = size.height
            }
            .pointerInput(availableX, availableY) {
                var dragPosition = latestPosition
                detectDragGestures(
                    onDragStart = { dragPosition = latestPosition },
                    onDragEnd = { latestOnFinished(dragPosition) },
                    onDragCancel = { latestOnFinished(dragPosition) }
                ) { change, amount ->
                    change.consume()
                    dragPosition = dragPosition.movedBy(
                        amount.x, amount.y, availableX, availableY
                    )
                    latestOnMove(dragPosition)
                }
            }
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier.size(49.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = BrowserGreen, contentColor = BrowserNavy
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 7.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            Icon(Icons.Default.Download, contentDescription = "下載或偵測影片",
                modifier = Modifier.size(25.dp))
        }
    }
}

private class TabViewState(record: BrowserTabRecord) {
    val id: String = record.id
    var url by mutableStateOf(record.url)
    var title by mutableStateOf(record.title)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var progress by mutableIntStateOf(100)
    var scrollY: Int = 0
    val scrollByUrl = mutableMapOf<String, BrowserScrollPoint>()
    var restoreTarget: String? = null
    var restoreSequence: Int = 0
    val mediaSources = mutableStateListOf<String>()
    fun record() = BrowserTabRecord(id, url, title)
}

private data class VideoPosition(val url: String, val right: Float, val top: Float)
private data class VideoPositions(val width: Float = 1f, val tiles: List<VideoPosition> = emptyList())

/** Read-only scan: no event handlers or HTML in the site are modified. */
private val X_DISPLAY_SCRIPT = """
(() => {
  // Only restyle X/Twitter. No network hooks or changes to post/media handlers.
  if (!/^(?:www\.)?(?:x\.com|twitter\.com)$/.test(location.hostname)) return;
  const flags = [__FLAGS__];
  const selectors = [
    '[data-testid="SideNav_NewTweet_Button"]',
    '[data-testid="FloatingActionButton"]',
    '[data-testid="FloatingActionButtonContainer"]',
    '[data-testid="FloatingActionButtons_Tweet_Button"]',
    'a[href="/compose/post"]', 'a[href^="/compose/post?"]',
    'a[href="/compose/tweet"]', 'a[href^="/compose/tweet?"]'
  ];
  if (flags[0]) selectors.push('[data-testid="reply"]');
  if (flags[1]) selectors.push('[data-testid="retweet"]', '[data-testid="unretweet"]');
  if (flags[2]) selectors.push(
    '[data-testid="tweet"] [data-testid="impressions"]',
    '[data-testid="tweet"] [data-testid="analyticsButton"]',
    '[data-testid="tweet"] [data-testid="analytics"]',
    '[data-testid="tweet"] [data-testid="viewCount"]',
    '[data-testid="tweet"] [role="group"] > div:has(> a[href$="/analytics"])',
    '[aria-modal="true"] [role="group"] > div:has(> a[href$="/analytics"])',
    '[data-testid="tweet"] a[href$="/analytics"]'
  );
  if (flags[3]) selectors.push('[data-testid="share"]');
  if (flags[4]) selectors.push(
    '#layers nav:has(a[href="/home"]):has(a[href^="/notifications"])',
    '[data-testid="BottomBar"]',
    'nav[aria-label="Bottom navigation"]'
  );
  // Limit caption/title filtering to a media viewer dialog, never the feed.
  // CSS survives React's in-place gallery swipes without rebuilding the WebView.
  const mediaDialog = '#layers [role="dialog"]:has([data-testid="swipe-to-dismiss"],[data-testid="tweetPhoto"],[data-testid="videoPlayer"],video)';
  if (flags[5]) selectors.push(
    mediaDialog + ' [data-testid="tweetText"]'
  );
  if (flags[6]) selectors.push(
    mediaDialog + ' h1',
    mediaDialog + ' h2',
    mediaDialog + ' [role="heading"]'
  );
  // Mobile X uses AppTabBar anchors. Restrict these selectors to mobile
  // navigation containers so post links and the desktop side menu remain.
  const mobileBar = ':is([data-testid="BottomBar"],[data-testid="BottomNav"],' +
    '[data-testid="AppTabBar"],nav[aria-label="Bottom navigation"],' +
    'nav[aria-label="Bottom Navigation"],nav:has([data-testid^="AppTabBar_"]))';
  const mobileButtons = [];
  const hideInMobileBar = (enabled, matches) => {
    if (enabled) matches.forEach(match => mobileButtons.push(mobileBar + ' ' + match));
  };
  hideInMobileBar(flags[7], [
    'a[data-testid="AppTabBar_Home_Link"]', 'a[href="/home"]'
  ]);
  hideInMobileBar(flags[8], [
    'a[data-testid="AppTabBar_Explore_Link"]', 'a[href="/explore"]', 'a[href^="/search"]'
  ]);
  hideInMobileBar(flags[9], [
    'a[data-testid="AppTabBar_Grok_Link"]', 'a[href^="/i/grok"]', 'a[href^="/grok"]'
  ]);
  hideInMobileBar(flags[10], [
    'a[data-testid="AppTabBar_DirectMessage_Link"]',
    'a[data-testid="AppTabBar_Chat_Link"]',
    'a[href^="/messages"]', 'a[href^="/i/chat"]', 'a[href^="/i/dm"]'
  ]);
  hideInMobileBar(flags[11], [
    'a[data-testid="AppTabBar_Notifications_Link"]', 'a[href^="/notifications"]'
  ]);
  hideInMobileBar(flags[12], [
    '[data-testid="share"]', 'button[aria-label="Share"]',
    'button[aria-label="分享"]'
  ]);
  // Photo/video viewer share controls can appear outside AppTabBar.
  if (flags[12]) selectors.push(
    '#layers [role="dialog"]:has([data-testid="tweetPhoto"],video) [data-testid="share"]'
  );
  // X's media page can be a full-screen route, not a role=dialog modal.
  // Detect the *visible seekbar* without touching player event handlers.
  const mediaRoute = /\/status\/\d+\/(?:photo|video)\/\d+\/?$/.test(location.pathname) ||
    /^\/i\/status\/\d+\/?$/.test(location.pathname);
  const modal = !!document.querySelector('#layers [role="dialog"]:has(video,[data-testid="videoPlayer"])');
  const liveVideo = Array.from(document.querySelectorAll('video')).some(node => {
    const rect = node.getBoundingClientRect();
    return rect.width >= 120 && rect.height >= 90 && rect.bottom > 0 &&
      rect.top < innerHeight;
  });
  const seekbarVisible = Array.from(document.querySelectorAll(
    '[role="slider"],input[type="range"],[data-testid="videoPlayer"] [aria-valuenow]'
  )).some(node => {
    const rect = node.getBoundingClientRect();
    const css = getComputedStyle(node);
    return rect.width >= 80 && rect.height > 0 && rect.bottom > 0 &&
      rect.top < innerHeight && css.display !== 'none' && css.visibility !== 'hidden';
  });
  document.documentElement.dataset.mydownloadXSeek =
    (liveVideo && (mediaRoute || modal) && seekbarVisible) ? '1' : '0';
  const seekViewer = 'html[data-mydownload-x-seek="1"]';
  if (flags[13]) selectors.push(
    seekViewer + ' [data-testid="tweetText"]',
    seekViewer + ' [data-testid="media-caption"]',
    seekViewer + ' [data-testid="video-caption"]',
    seekViewer + ' [data-testid="videoDescription"]'
  );
  if (flags[14]) selectors.push(
    seekViewer + ' [data-testid="User-Name"]',
    seekViewer + ' [data-testid="UserName"]',
    seekViewer + ' [data-testid="User-Names"]',
    seekViewer + ' [data-testid^="UserAvatar-Container"]',
    seekViewer + ' [data-testid="Tweet-User-Avatar"]',
    seekViewer + ' [data-testid="videoTitle"]'
  );
  const id = 'mydownload-x-display';
  let style = document.getElementById(id);
  if (!style) {
    style = document.createElement('style');
    style.id = id;
    (document.head || document.documentElement).appendChild(style);
  }
  const css = selectors.join(',\n') + ' { display: none !important; }' +
    (mobileButtons.length ?
      '\n@media (max-width: 900px) { ' + mobileButtons.join(',\n') +
      ' { display: none !important; } }' : '');
  if (style.textContent !== css) style.textContent = css;
})()
""".trimIndent()

private fun xDisplayScript(options: XDisplayOptions): String {
    val flags = listOf(
        options.hideReply, options.hideRepost, options.hideViews,
        options.hideShare, options.hideBottomNavigation,
        options.hideMediaDescription, options.hideMediaTitle,
        options.hideBottomHome, options.hideBottomSearch,
        options.hideBottomGrok, options.hideBottomChat,
        options.hideBottomNotifications, options.hideBottomShare,
        options.hideVideoPlaybackText, options.hideVideoPlaybackTitle
    ).joinToString(",") { it.toString() }
    return X_DISPLAY_SCRIPT.replace("__FLAGS__", flags)
}

private val AV01_TILE_SCAN = """
(() => {
  let result = {width: innerWidth, tiles: []};
  if (/\/video\/\d+(?:\/|$)/.test(location.pathname)) return JSON.stringify(result);
  let seen = new Set();
  for (const anchor of document.querySelectorAll('a[href*="/video/"]')) {
    try {
      const uri = new URL(anchor.href, location.href);
      if (!/^(www\.)?av01\.(media|tv)$/.test(uri.hostname) ||
          uri.protocol !== 'https:' || !/\/video\/\d+(?:\/|$)/.test(uri.pathname)) continue;
      const rect = (anchor.querySelector('img') || anchor).getBoundingClientRect();
      if (rect.width < 65 || rect.height < 55) continue;
      if (rect.right < 0 || rect.left > innerWidth || rect.top > innerHeight || rect.bottom < 0) continue;
      const key = Math.round(rect.left) + ':' + Math.round(rect.top);
      if (seen.has(key)) continue;
      seen.add(key);
      result.tiles.push({url: uri.href, right: rect.right, top: rect.top});
      if (result.tiles.length >= 45) break;
    } catch (ignored) {}
  }
  return JSON.stringify(result);
})()
""".trimIndent()

private fun decodeVideoPositions(raw: String): VideoPositions = runCatching {
    val text = JSONArray("[" + raw + "]").getString(0)
    val json = JSONObject(text)
    val width = json.optDouble("width").toFloat()
    if (width <= 0) return@runCatching VideoPositions()
    val positions = json.optJSONArray("tiles") ?: return@runCatching VideoPositions(width)
    VideoPositions(width, buildList {
        for (i in 0 until positions.length()) {
            val entry = positions.optJSONObject(i) ?: continue
            val url = entry.optString("url")
            if (Av01BrowserLinks.isVideoLink(url)) add(VideoPosition(
                url, entry.optDouble("right").toFloat(), entry.optDouble("top").toFloat()
            ))
        }
    })
}.getOrDefault(VideoPositions())

private enum class BrowserLibrary { BOOKMARKS, HISTORY }

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiTabBrowser(
    visible: Boolean,
    adBlockEnabled: Boolean,
    xDisplayOptions: XDisplayOptions,
    downloadButtonOpacityPercent: Int,
    modifier: Modifier = Modifier,
    onDownload: (String) -> Unit,
    onDetectedVideos: (List<StreamChoice>) -> Unit,
    onDownloads: () -> Unit,
    onSettings: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val currentDownload by rememberUpdatedState(onDownload)
    val currentDetectedVideos by rememberUpdatedState(onDetectedVideos)
    val currentDownloads by rememberUpdatedState(onDownloads)
    val currentSettings by rememberUpdatedState(onSettings)
    val blockAds by rememberUpdatedState(adBlockEnabled)
    val currentXOptions by rememberUpdatedState(xDisplayOptions)
    var floatingDownloadPosition by remember(context) {
        mutableStateOf(DownloadSettings.floatingDownloadPosition(context))
    }

    val session = remember(context) { BrowserStore.loadSession(context) }
    val tabs = remember(context) {
        mutableStateListOf<TabViewState>().apply {
            session.tabs.forEach { add(TabViewState(it)) }
        }
    }
    var activeId by remember(context) { mutableStateOf(session.activeId) }
    val selected = tabs.firstOrNull { it.id == activeId } ?: tabs.first()
    val host = remember(context) { FrameLayout(context) }
    // Access-order LRU. A maximum of four live WebViews prevents memory exhaustion.
    val webViews = remember(context) { LinkedHashMap<String, WebView>(16, 0.75f, true) }
    var addressInput by remember { mutableStateOf("") }
    var selectedSheet by remember { mutableStateOf<BrowserLibrary?>(null) }
    var sheetSearch by remember { mutableStateOf("") }
    var bookmarkCategory by remember { mutableStateOf("全部") }
    var bookmarkSortByName by remember { mutableStateOf(false) }
    var editingBookmark by remember { mutableStateOf<BrowserBookmark?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editCategory by remember { mutableStateOf("") }
    var bookmarks by remember { mutableStateOf(BrowserStore.bookmarks(context)) }
    var history by remember { mutableStateOf(BrowserStore.history(context)) }
    var fullscreenView by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var scanResults by remember { mutableStateOf(VideoPositions()) }
    var viewportWidth by remember { mutableIntStateOf(0) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    var videoElements by remember { mutableStateOf(VisibleVideos()) }
    var showTabs by remember { mutableStateOf(false) }
    var showBrowserMenu by remember { mutableStateOf(false) }
    var discoveryMessage by remember { mutableStateOf("") }

    fun saveSession() {
        BrowserStore.saveSession(context, tabs.map { it.record() }, activeId)
    }

    fun captureScroll(web: WebView, tab: TabViewState) {
        val page = tab.url
        if (!BrowserAddress.isWebUrl(page) || tab.restoreTarget == page) return
        val nativeY = web.scrollY.coerceAtLeast(0)
        web.evaluateJavascript(BrowserScroll.CAPTURE_SCRIPT) { response ->
            if (tab.url != page || tab.restoreTarget == page) return@evaluateJavascript
            val point = BrowserScroll.decode(response, nativeY)
            if (point.hasPosition() || tab.progress == 100) {
                tab.scrollByUrl[page] = point
                tab.scrollY = point.native
            }
        }
    }

    fun restoreScroll(web: WebView, tab: TabViewState, url: String) {
        val point = tab.scrollByUrl[url] ?: return
        val token = ++tab.restoreSequence
        listOf(220L, 750L, 1700L).forEachIndexed { index, millis ->
            web.postDelayed({
                if (tab.restoreSequence != token || tab.url != url) return@postDelayed
                if (web.url != url && web.url?.substringBefore('#') != url.substringBefore('#'))
                    return@postDelayed
                if (point.native > 0) web.scrollTo(0, point.native)
                if (point.page > 0 || point.panel > 0) {
                    web.evaluateJavascript(BrowserScroll.restoreScript(point), null)
                }
                if (index == 2 && tab.restoreTarget == url) tab.restoreTarget = null
            }, millis)
        }
    }

    fun selectTab(tab: TabViewState) {
        val oldTab = tabs.firstOrNull { it.id == activeId }
        val oldWeb = webViews[activeId]
        if (oldTab != null && oldWeb != null && oldTab.id != tab.id) {
            captureScroll(oldWeb, oldTab)
        }
        activeId = tab.id
        scanResults = VideoPositions()
        videoElements = VisibleVideos()
        saveSession()
    }

    fun addTab(url: String = BrowserAddress.BLANK_TAB, title: String = "新分頁"): TabViewState? {
        if (tabs.size >= BrowserStore.MAX_TABS) {
            Toast.makeText(context, "最多可同時開啟 12 個分頁", Toast.LENGTH_SHORT).show()
            return null
        }
        val record = BrowserStore.newTab(url, title)
        return TabViewState(record).also {
            tabs.add(it)
            selectTab(it)
        }
    }

    fun closeTab(id: String) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        val old = webViews.remove(id)
        if (old != null) {
            (old.parent as? ViewGroup)?.removeView(old)
            old.stopLoading()
            old.onPause()
            old.destroy()
        }
        tabs.removeAt(index)
        if (tabs.isEmpty()) {
            addTab()
        } else if (activeId == id) {
            selectTab(tabs[(index - 1).coerceIn(0, tabs.lastIndex)])
        } else saveSession()
    }

    // Every WebView gets its own navigation state, cookies are shared normally
    // by Android WebView, and all navigation stays within the app.
    fun createWebView(tab: TabViewState, initialLoad: Boolean = true): WebView {
        val web = WebView(context)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadsImagesAutomatically = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.setSupportMultipleWindows(true)
        web.settings.javaScriptCanOpenWindowsAutomatically = false
        web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.textZoom = if (BrowserVideoDiscovery.isXPage(tab.url))
            currentXOptions.fontSizePercent.coerceIn(80, 160) else 100

        fun updateUrl(url: String) {
            // X font-size setting follows SPA navigation and never affects other sites.
            val textZoom = if (BrowserVideoDiscovery.isXPage(url))
                currentXOptions.fontSizePercent.coerceIn(80, 160) else 100
            if (web.settings.textZoom != textZoom) web.settings.textZoom = textZoom
            if (!BrowserAddress.isWebUrl(url) && url != BrowserAddress.BLANK_TAB) return
            if (tab.url != url) {
                val previousWasX = BrowserVideoDiscovery.isXPage(tab.url)
                val oldPage = tab.url
                val gallerySwipe = XMediaNavigation.isMediaSwipe(oldPage, url)
                if (BrowserAddress.isWebUrl(oldPage) && tab.scrollY > 0) {
                    val saved = tab.scrollByUrl[oldPage]
                    if (saved == null || saved.native < tab.scrollY) {
                        tab.scrollByUrl[oldPage] = (saved ?: BrowserScrollPoint())
                            .copy(native = tab.scrollY)
                    }
                }
                if (!gallerySwipe) tab.scrollY = 0
                tab.url = url
                // Viewer swipes should never trigger our saved-page scroll restoration.
                if (gallerySwipe) tab.restoreTarget = null
                else if (tab.restoreTarget == url) restoreScroll(web, tab, url)
                if (tab.id == activeId && !gallerySwipe) {
                    // An X gallery swipe changes the URL but not the WebView/DOM.
                    // Keep the current media rendered until the next scan completes.
                    scanResults = VideoPositions()
                    videoElements = VisibleVideos()
                }
                // X uses in-page navigation and may reuse a cached player
                // after changing the post URL. Keep recently observed media
                // requests within the same site so detection still works.
                if (!previousWasX || !BrowserVideoDiscovery.isXPage(url)) {
                    tab.mediaSources.clear()
                }
                saveSession()
            }
            tab.canBack = web.canGoBack()
            tab.canForward = web.canGoForward()
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                if (request == null || !request.isForMainFrame) return false
                val url = request.url.toString()
                if (blockAds && AdPageFilter.blocks(url)) return true
                // Unlike the previous AV01-only browser, allow all regular web
                // navigation, including x.com and OAuth redirects, in this tab.
                return !BrowserAddress.isWebUrl(url) && url != BrowserAddress.BLANK_TAB
            }

            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request == null) return null
                val resource = request.url.toString()
                if (BrowserVideoDiscovery.kind(resource) != null &&
                    !AdPageFilter.blocks(resource)) {
                    // WebView invokes interception off the UI thread. Only store
                    // the URL on the WebView's UI thread, not from this callback.
                    web.post {
                        if (resource !in tab.mediaSources) {
                            tab.mediaSources.add(0, resource)
                            if (tab.mediaSources.size > 24) tab.mediaSources.removeAt(24)
                        }
                    }
                }
                if (!DownloadSettings.adBlockEnabled(context) ||
                    !AdPageFilter.blocks(resource)) return null
                return WebResourceResponse(
                    "text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0))
                )
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val mediaSwipe = url != null &&
                    XMediaNavigation.isMediaSwipe(tab.url, url)
                if (url != null) updateUrl(url)
                if (!mediaSwipe) tab.progress = 0
            }

            override fun doUpdateVisitedHistory(
                view: WebView?, url: String?, isReload: Boolean
            ) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (url != null) updateUrl(url)
                if (url != null && BrowserAddress.isWebUrl(url) && !isReload) {
                    BrowserStore.recordVisit(context, url, tab.title)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null) updateUrl(url)
                val title = view?.title.orEmpty()
                if (title.isNotBlank()) tab.title = title.take(120)
                else if (url != null) tab.title = BrowserAddress.displayName(url)
                tab.progress = 100
                tab.canBack = web.canGoBack()
                tab.canForward = web.canGoForward()
                if (url != null && BrowserAddress.isWebUrl(url)) {
                    BrowserStore.recordVisit(context, url, tab.title)
                }
                if (url != null && tab.restoreTarget == url) {
                    restoreScroll(web, tab, url)
                }
                // X is a dynamic site: persistent CSS also hides compose buttons
                // added later by React, without removing other interactive elements.
                if (url != null && BrowserVideoDiscovery.isXPage(url)) {
                    view?.evaluateJavascript(xDisplayScript(currentXOptions), null)
                }
                saveSession()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                tab.progress = newProgress
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    tab.title = title.take(120)
                    saveSession()
                }
            }

            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean,
                isUserGesture: Boolean, resultMsg: Message?
            ): Boolean {
                // Block unsolicited popup ads, but open user-requested target=_blank
                // links as real in-app tabs. No need to launch an external browser.
                if (!isUserGesture || resultMsg == null) return false
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                val next = addTab() ?: return false
                // WebViewTransport requires a never-navigated WebView.
                val child = createWebView(next, initialLoad = false)
                webViews[next.id] = child
                transport.webView = child
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || fullscreenView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                fullscreenView = view
                fullscreenCallback = callback
            }

            override fun onHideCustomView() {
                fullscreenView = null
                fullscreenCallback?.onCustomViewHidden()
                fullscreenCallback = null
            }
        }

        web.setOnScrollChangeListener { _, _, y, _, _ ->
            if (tab.restoreTarget != tab.url) {
                tab.scrollY = y.coerceAtLeast(0)
                if (y > 0 && BrowserAddress.isWebUrl(tab.url)) {
                    val existing = tab.scrollByUrl[tab.url] ?: BrowserScrollPoint()
                    tab.scrollByUrl[tab.url] = existing.copy(native = y)
                }
            }
        }
        web.setOnLongClickListener {
            val target = web.hitTestResult?.extra.orEmpty()
            if (Av01BrowserLinks.isVideoLink(target)) {
                currentDownload(target)
                true
            } else false
        }
        web.setDownloadListener(DownloadListener { url, _, _, mimeType, _ ->
            if (BrowserAddress.isWebUrl(url) &&
                (mimeType.startsWith("video/", ignoreCase = true) ||
                    url.substringBefore('?').endsWith(".m3u8", true))) {
                currentDownload(url)
            } else {
                Toast.makeText(context, "目前下載管理支援影片檔案", Toast.LENGTH_SHORT).show()
            }
        })
        if (initialLoad) {
            if (tab.scrollByUrl[tab.url]?.hasPosition() == true) tab.restoreTarget = tab.url
            web.loadUrl(tab.url)
        }
        return web
    }

    fun currentWebView(): WebView? = webViews[activeId]

    fun goBackPreservingScroll() {
        val web = currentWebView() ?: return
        if (!web.canGoBack()) return
        captureScroll(web, selected)
        val list = web.copyBackForwardList()
        val target = if (list.currentIndex > 0)
            list.getItemAtIndex(list.currentIndex - 1)?.url else null
        selected.restoreTarget = target?.takeIf { BrowserAddress.isWebUrl(it) }
        web.goBack()  // Reuse WebView's history: never loadUrl() for Back.
    }

    fun navigate(url: String) {
        if (url != BrowserAddress.BLANK_TAB && !BrowserAddress.isWebUrl(url)) {
            Toast.makeText(context, "請輸入有效的網站網址", Toast.LENGTH_SHORT).show()
            return
        }
        currentWebView()?.let { captureScroll(it, selected) }
        selected.restoreTarget = null
        selected.url = url
        selected.title = if (url == BrowserAddress.BLANK_TAB) "新分頁"
        else BrowserAddress.displayName(url)
        selected.scrollY = 0
        scanResults = VideoPositions()
        videoElements = VisibleVideos()
        saveSession()
        val browser = currentWebView()
        if (browser == null) {
            // The active tab will be initialized with its recorded URL.
        } else browser.loadUrl(url)
    }

    fun offerDetectedVideo(video: VisibleVideo? = null) {
        val page = selected.url
        val title = if (BrowserVideoDiscovery.isXPage(page)) {
            VideoTitle.preferred(
                video?.postText.orEmpty().ifBlank { videoElements.pageText },
                selected.title,
                video?.postUrl.orEmpty().ifBlank {
                    videoElements.pagePostUrl.ifBlank { page }
                },
                video?.mediaId.orEmpty()
            )
        } else StreamResolver.safeTitle(
            selected.title.ifBlank { BrowserAddress.displayName(page) }
        )
        val webUserAgent = currentWebView()?.settings?.userAgentString.orEmpty()
        val fromPlayer = video?.url?.takeIf { BrowserVideoDiscovery.kind(it) != null }
        if (fromPlayer != null) {
            val kind = BrowserVideoDiscovery.kind(fromPlayer) ?: "mp4"
            currentDetectedVideos(listOf(StreamChoice(
                title, if (kind == "mp4") "MP4" else "HLS",
                fromPlayer, kind, page,
                MediaCandidateResolver.group(fromPlayer), webUserAgent
            )))
            return
        }

        val active = currentWebView()
        if (active == null) {
            discoveryMessage = "網頁尚未載入，請先開啟影片。"
            return
        }

        val checkedId = selected.id
        active.evaluateJavascript(BrowserVideoDiscovery.RESOURCE_SCAN_SCRIPT) { result ->
            if (checkedId != activeId) return@evaluateJavascript
            val fromPerformance = BrowserVideoDiscovery.decodeResourceList(result)
            val discovered = (selected.mediaSources + fromPerformance)
                .filter { BrowserVideoDiscovery.kind(it) != null }
                .distinct()
            val associated = video?.mediaId?.takeIf { it.isNotBlank() }?.let { id ->
                discovered.filter { MediaCandidateResolver.mediaId(it) == id }
            }.orEmpty()
            // Prefer the clicked player's media ID when X exposes a thumbnail.
            // Never mix candidates from a different video when the ID matches.
            val all = (if (associated.isNotEmpty()) associated else discovered).take(10)
            if (all.isEmpty()) {
                discoveryMessage = if (BrowserVideoDiscovery.isXPage(page))
                    "已找到 X 影片播放器，但目前只看到網站內部串流（例如 blob:），沒有可直接下載的 MP4／M3U8。可以播放影片後再試；受 X 下載權限或保護限制的內容無法直接儲存。"
                else "目前網頁沒有提供可直接下載的 MP4／M3U8。請先播放影片再試。"
            } else {
                currentDetectedVideos(all.map { url ->
                    val kind = BrowserVideoDiscovery.kind(url) ?: "mp4"
                    StreamChoice(
                        title, kind.uppercase(), url, kind, page,
                        MediaCandidateResolver.group(url), webUserAgent
                    )
                })
            }
        }
    }

    fun navigateInput() {
        val normalized = BrowserAddress.navigateInput(addressInput)
        if (normalized == null) {
            Toast.makeText(context, "網址不正確，請輸入 http 或 https 網址", Toast.LENGTH_SHORT).show()
        } else navigate(normalized)
    }

    DisposableEffect(context, host) {
        onDispose {
            webViews.values.toList().forEach { web ->
                (web.parent as? ViewGroup)?.removeView(web)
                web.stopLoading()
                web.destroy()
            }
            webViews.clear()
            host.removeAllViews()
        }
    }

    LaunchedEffect(selected.id, visible) {
        if (visible) {
            val active = webViews[selected.id] ?: createWebView(selected).also {
                webViews[selected.id] = it
            }
            webViews.forEach { (id, view) ->
                if (id != selected.id) {
                    view.onPause()
                    view.pauseTimers()
                }
            }
            if (active.parent !== host) {
                (active.parent as? ViewGroup)?.removeView(active)
                host.removeAllViews()
                host.addView(active, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
            }
            active.resumeTimers()
            active.onResume()
            // Only four expensive WebView instances remain resident. Old tabs
            // persist URL/title and reload lazily when opened again.
            while (webViews.size > 8) {
                val victimId = webViews.keys.firstOrNull { it != selected.id } ?: break
                val victim = webViews.remove(victimId) ?: break
                (victim.parent as? ViewGroup)?.removeView(victim)
                victim.stopLoading()
                victim.onPause()
                victim.destroy()
            }
        } else {
            webViews.values.forEach { it.onPause() }
        }
    }

    LaunchedEffect(selected.id, selected.url) {
        // Keep the address bar in sync without clearing media on each gallery swipe.
        // updateUrl() and selectTab() already reset the scan for real navigation.
        addressInput = if (selected.url == BrowserAddress.BLANK_TAB) "" else selected.url
    }

    // Settings return without reloading X: refresh the stylesheet immediately.
    LaunchedEffect(visible, selected.id, selected.url, xDisplayOptions) {
        if (visible) {
            currentWebView()?.let { browser ->
                val textZoom = if (BrowserVideoDiscovery.isXPage(selected.url))
                    xDisplayOptions.fontSizePercent.coerceIn(80, 160) else 100
                if (browser.settings.textZoom != textZoom) {
                    browser.settings.textZoom = textZoom
                }
                if (BrowserVideoDiscovery.isXPage(selected.url)) {
                    browser.evaluateJavascript(xDisplayScript(xDisplayOptions), null)
                }
            }
        }
    }

    LaunchedEffect(visible, selected.id) {
        while (visible) {
            val browser = currentWebView()
            if (browser != null && selected.url != BrowserAddress.BLANK_TAB &&
                viewportWidth > 0) {
                val checkedId = selected.id
                if (Av01BrowserLinks.isSiteLink(selected.url) &&
                    !Av01BrowserLinks.isVideoLink(selected.url)) {
                    browser.evaluateJavascript(AV01_TILE_SCAN) { result ->
                        if (activeId == checkedId) scanResults = decodeVideoPositions(result)
                    }
                } else scanResults = VideoPositions()
                // Dynamic X pages reuse the document while navigating posts.
                // Reinstate CSS if React recreates its head or mobile navigation.
                if (BrowserVideoDiscovery.isXPage(selected.url)) {
                    browser.evaluateJavascript(xDisplayScript(currentXOptions), null)
                }
                browser.evaluateJavascript(BrowserVideoDiscovery.SCAN_SCRIPT) { result ->
                    if (activeId == checkedId) {
                        videoElements = BrowserVideoDiscovery.decode(result)
                    }
                }
                captureScroll(browser, selected)
            } else {
                scanResults = VideoPositions()
                videoElements = VisibleVideos()
            }
            delay(950L)
        }
    }

    BackHandler(visible && (fullscreenView != null || selected.canBack)) {
        if (fullscreenView != null) {
            currentWebView()?.webChromeClient?.onHideCustomView()
        } else goBackPreservingScroll()
    }

    Column(modifier.background(BrowserNavy)) {
        // Single compact browser toolbar. Tabs, navigation actions, downloads
        // and settings live in sheets/menus rather than covering the website.
        Row(
            Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { goBackPreservingScroll() },
                enabled = selected.canBack,
                modifier = Modifier.size(35.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "上一頁",
                    modifier = Modifier.size(21.dp))
            }
            OutlinedTextField(
                value = addressInput, onValueChange = { addressInput = it },
                modifier = Modifier.weight(1f).height(49.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                placeholder = { Text("網址或搜尋", style = MaterialTheme.typography.bodySmall) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(onGo = { navigateInput() }),
                trailingIcon = {
                    IconButton(onClick = { navigateInput() }, modifier = Modifier.size(35.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, "前往網址",
                            modifier = Modifier.size(19.dp))
                    }
                },
                shape = RoundedCornerShape(13.dp)
            )
            val saved = bookmarks.any { it.url == selected.url }
            IconButton(
                enabled = BrowserAddress.isWebUrl(selected.url),
                onClick = {
                    BrowserStore.toggleBookmark(context, selected.url, selected.title)
                    bookmarks = BrowserStore.bookmarks(context)
                },
                modifier = Modifier.size(37.dp)
            ) {
                Icon(
                    if (saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = if (saved) "取消書籤" else "加入書籤",
                    tint = if (saved) BrowserGreen else Color.White,
                    modifier = Modifier.size(21.dp)
                )
            }
            IconButton(onClick = { showTabs = true }, modifier = Modifier.size(37.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Layers, "切換分頁", modifier = Modifier.size(20.dp))
                    Text(tabs.size.toString(), fontSize = 11.sp, color = BrowserGreen)
                }
            }
            Box {
                IconButton(
                    onClick = { showBrowserMenu = true }, modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.MoreVert, "瀏覽器選單", modifier = Modifier.size(22.dp))
                }
                DropdownMenu(
                    expanded = showBrowserMenu,
                    onDismissRequest = { showBrowserMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("新增分頁") },
                        onClick = { showBrowserMenu = false; addTab() },
                        leadingIcon = { Icon(Icons.Default.Add, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("前進") },
                        enabled = selected.canForward,
                        onClick = {
                            showBrowserMenu = false
                            currentWebView()?.goForward()
                        },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("重新整理") },
                        onClick = {
                            showBrowserMenu = false
                            currentWebView()?.reload()
                        },
                        leadingIcon = { Icon(Icons.Default.Refresh, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("新分頁首頁") },
                        onClick = {
                            showBrowserMenu = false
                            navigate(BrowserAddress.BLANK_TAB)
                        },
                        leadingIcon = { Icon(Icons.Default.Home, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("我的書籤") },
                        onClick = {
                            showBrowserMenu = false
                            bookmarks = BrowserStore.bookmarks(context)
                            sheetSearch = ""
                            selectedSheet = BrowserLibrary.BOOKMARKS
                        },
                        leadingIcon = { Icon(Icons.Default.Bookmark, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("瀏覽紀錄") },
                        onClick = {
                            showBrowserMenu = false
                            history = BrowserStore.history(context)
                            sheetSearch = ""
                            selectedSheet = BrowserLibrary.HISTORY
                        },
                        leadingIcon = { Icon(Icons.Default.History, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("設定") },
                        onClick = { showBrowserMenu = false; currentSettings() },
                        leadingIcon = { Icon(Icons.Default.Settings, null) }
                    )
                }
            }
            // Rightmost shortcut; adding it also gives the URL field more compact width.
            IconButton(
                onClick = { currentDownloads() },
                modifier = Modifier.size(39.dp)
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = "下載管理",
                    tint = BrowserGreen,
                    modifier = Modifier.size(23.dp)
                )
            }
        }
        if (selected.progress in 1..99) {
            LinearProgressIndicator(
                progress = { selected.progress / 100f },
                modifier = Modifier.fillMaxWidth(), color = BrowserGreen
            )
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().onSizeChanged {
                viewportWidth = it.width
                viewportHeight = it.height
            }
        ) {
            AndroidView(factory = { host }, modifier = Modifier.fillMaxSize())
            if (selected.url == BrowserAddress.BLANK_TAB) {
                Column(
                    Modifier.fillMaxSize().background(BrowserNavy)
                        .verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("開啟網站", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold)
                    Text("從常用網站開始，或在上方輸入網址與搜尋文字。",
                        color = Color(0xFFA9BDCB))
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Button(onClick = { navigate("https://x.com/") }) { Text("X") }
                        Button(onClick = { navigate(BrowserAddress.DEFAULT_SITE) }) { Text("AV01") }
                        OutlinedButton(onClick = { navigate("https://www.google.com/") }) {
                            Text("Google")
                        }
                    }
                    Text("我的書籤", style = MaterialTheme.typography.titleMedium)
                    bookmarks.take(12).forEach { bookmark ->
                        Card(
                            onClick = { navigate(bookmark.url) },
                            colors = CardDefaults.cardColors(containerColor = BrowserPanel)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(bookmark.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(bookmark.url, color = Color(0xFFA9BDCB),
                                    maxLines = 1, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            // Existing AV01 direct video badges remain intact, but there is no
            // injection into site DOM and all other websites are untouched.
            if (visible && Av01BrowserLinks.isSiteLink(selected.url) &&
                !Av01BrowserLinks.isVideoLink(selected.url) && viewportWidth > 0
            ) {
                val scale = viewportWidth.toFloat() / scanResults.width
                val width = with(density) { 36.dp.roundToPx() }
                val height = with(density) { 34.dp.roundToPx() }
                val margin = with(density) { 4.dp.roundToPx() }
                scanResults.tiles.forEach { tile ->
                    val x = (tile.right * scale - width - margin).roundToInt()
                        .coerceIn(0, (viewportWidth - width).coerceAtLeast(0))
                    val y = (tile.top * scale + margin).roundToInt()
                    if (y >= 0 && y + height < viewportHeight) {
                        Box(
                            Modifier.offset { IntOffset(x, y) }
                                .size(width = 36.dp, height = 34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xEA27DBB3))
                                .alpha(downloadButtonOpacityPercent.coerceIn(20, 100) / 100f)
                                .clickable { currentDownload(tile.url) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = "下載影片",
                                tint = BrowserNavy, modifier = Modifier.size(21.dp)
                            )
                        }
                    }
                }
            }
            // Native download badges beside each visible HTML5 video. These
            // don't modify the site's DOM or intercept taps outside the badge.
            if (visible && viewportWidth > 0 && videoElements.viewportWidth > 0f &&
                !VideoTitle.isXStatus(selected.url)) {
                val ratio = viewportWidth.toFloat() / videoElements.viewportWidth
                val badgeWidth = with(density) { 37.dp.roundToPx() }
                val badgeHeight = with(density) { 35.dp.roundToPx() }
                val margin = with(density) { 7.dp.roundToPx() }
                videoElements.videos.forEach { video ->
                    val x = (video.right * ratio - badgeWidth - margin).roundToInt()
                        .coerceIn(0, (viewportWidth - badgeWidth).coerceAtLeast(0))
                    val y = (video.top * ratio + margin).roundToInt()
                    if (y >= 0 && y + badgeHeight <= viewportHeight) {
                        Box(
                            Modifier.offset { IntOffset(x, y) }
                                .size(37.dp, 35.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xEA27DBB3))
                                .alpha(downloadButtonOpacityPercent.coerceIn(20, 100) / 100f)
                                .clickable { offerDetectedVideo(video) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = "偵測並下載這部影片",
                                tint = BrowserNavy,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
            // A single movable download shortcut is shared by X posts, X feed
            // video discovery, AV01 detail pages and directly-opened video URLs.
            val direct = selected.url
            val directDownload = Av01BrowserLinks.isVideoLink(direct) ||
                (BrowserAddress.isWebUrl(direct) &&
                    direct.substringBefore('?').let {
                        it.endsWith(".mp4", true) || it.endsWith(".m3u8", true)
                    })
            val showXDownload = BrowserVideoDiscovery.isXPage(direct) &&
                (VideoTitle.isXStatus(direct) || videoElements.videos.isEmpty())
            if (visible && (directDownload || showXDownload)) {
                MovableDownloadButton(
                    containerWidth = viewportWidth,
                    containerHeight = viewportHeight,
                    opacityPercent = downloadButtonOpacityPercent,
                    position = floatingDownloadPosition,
                    onMove = { floatingDownloadPosition = it },
                    onMoveFinished = { position ->
                        floatingDownloadPosition = position
                        DownloadSettings.setFloatingDownloadPosition(context, position)
                    },
                    onClick = {
                        if (directDownload) currentDownload(direct)
                        else offerDetectedVideo(videoElements.videos.firstOrNull())
                    }
                )
            }
        }
    }

    if (showTabs) {
        ModalBottomSheet(
            onDismissRequest = { showTabs = false },
            containerColor = BrowserPanel
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "分頁 (" + tabs.size + ")",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Button(onClick = {
                        addTab()?.let { showTabs = false }
                    }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(5.dp))
                        Text("新增")
                    }
                }
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 490.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    items(tabs, key = { it.id }) { tab ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (activeId == tab.id) Color(0xFF2B4B5C)
                                    else Color(0xFF203548)
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                Modifier.weight(1f).clickable {
                                    selectTab(tab)
                                    showTabs = false
                                }.padding(13.dp)
                            ) {
                                Text(
                                    tab.title.ifBlank { BrowserAddress.displayName(tab.url) },
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    tab.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFB1C2CC),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { closeTab(tab.id) }) {
                                Icon(Icons.Default.Close, "關閉分頁")
                            }
                        }
                    }
                }
            }
        }
    }

    if (discoveryMessage.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { discoveryMessage = "" },
            title = { Text("影片偵測結果") },
            text = { Text(discoveryMessage) },
            confirmButton = {
                TextButton(onClick = { discoveryMessage = "" }) { Text("確定") }
            }
        )
    }

    if (selectedSheet != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedSheet = null },
            containerColor = BrowserPanel
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 17.dp)
                    .padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (selectedSheet == BrowserLibrary.BOOKMARKS) "我的書籤" else "瀏覽紀錄",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (selectedSheet == BrowserLibrary.HISTORY && history.isNotEmpty()) {
                        TextButton(onClick = {
                            BrowserStore.clearHistory(context)
                            history = emptyList()
                        }) { Text("清除全部紀錄") }
                    }
                }
                OutlinedTextField(
                    value = sheetSearch, onValueChange = { sheetSearch = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜尋網站名稱或網址") },
                    singleLine = true
                )
                val term = sheetSearch.trim()
                if (selectedSheet == BrowserLibrary.BOOKMARKS) {
                    val categories = listOf("全部") +
                        bookmarks.map { it.category }.distinct().sorted()
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        categories.forEach { category ->
                            FilterChip(
                                selected = bookmarkCategory == category,
                                onClick = { bookmarkCategory = category },
                                label = { Text(category) }
                            )
                        }
                    }
                    TextButton(
                        onClick = { bookmarkSortByName = !bookmarkSortByName }
                    ) {
                        Text(if (bookmarkSortByName) "排序：名稱 ↑" else "排序：最近加入 ↓")
                    }
                    val filtered = bookmarks.filter {
                        (bookmarkCategory == "全部" || it.category == bookmarkCategory) &&
                            (term.isEmpty() || it.title.contains(term, true) ||
                                it.url.contains(term, true) || it.category.contains(term, true))
                    }
                    val matches = if (bookmarkSortByName)
                        filtered.sortedBy { it.title.lowercase() }
                    else filtered.sortedByDescending { it.createdAt }
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 450.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        items(matches, key = { it.url }) { bookmark ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF213548)),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    Modifier.weight(1f).clickable {
                                        selectedSheet = null
                                        navigate(bookmark.url)
                                    }.padding(13.dp)
                                ) {
                                    Text(bookmark.title, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                    Text(bookmark.category + " · " + bookmark.url,
                                        color = Color(0xFFB0C1CD),
                                        maxLines = 1, style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(onClick = {
                                    editingBookmark = bookmark
                                    editTitle = bookmark.title
                                    editCategory = bookmark.category
                                }) { Icon(Icons.Default.Edit, "編輯書籤名稱及分類") }
                                IconButton(onClick = {
                                    BrowserStore.toggleBookmark(context, bookmark.url, bookmark.title)
                                    bookmarks = BrowserStore.bookmarks(context)
                                }) { Icon(Icons.Default.Delete, "刪除書籤") }
                            }
                        }
                    }
                } else {
                    val matches = history.filter { term.isEmpty() ||
                        it.title.contains(term, true) || it.url.contains(term, true) }
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 450.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        items(matches, key = { it.url }) { visit ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF213548)),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    Modifier.weight(1f).clickable {
                                        selectedSheet = null
                                        navigate(visit.url)
                                    }.padding(13.dp)
                                ) {
                                    Text(visit.title.ifBlank { BrowserAddress.displayName(visit.url) },
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(visit.url, color = Color(0xFFB0C1CD),
                                        maxLines = 1, style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(onClick = {
                                    BrowserStore.removeHistory(context, visit.url)
                                    history = BrowserStore.history(context)
                                }) { Icon(Icons.Default.Delete, "刪除這筆紀錄") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (editingBookmark != null) {
        AlertDialog(
            onDismissRequest = { editingBookmark = null },
            title = { Text("編輯書籤") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editTitle, onValueChange = { editTitle = it },
                        label = { Text("名稱") }, singleLine = true
                    )
                    OutlinedTextField(
                        value = editCategory, onValueChange = { editCategory = it },
                        label = { Text("分類，例如：影片、社群、常用") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editingBookmark?.let { bookmark ->
                        BrowserStore.updateBookmark(
                            context, bookmark.url, editTitle, editCategory
                        )
                    }
                    bookmarks = BrowserStore.bookmarks(context)
                    editingBookmark = null
                }) { Text("儲存") }
            },
            dismissButton = {
                TextButton(onClick = { editingBookmark = null }) { Text("取消") }
            }
        )
    }

    if (fullscreenView != null) {
        Dialog(
            onDismissRequest = { currentWebView()?.webChromeClient?.onHideCustomView() },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            val shown = fullscreenView
            if (shown != null) {
                var fullscreenWidth by remember { mutableIntStateOf(0) }
                var fullscreenHeight by remember { mutableIntStateOf(0) }
                Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged {
                    fullscreenWidth = it.width
                    fullscreenHeight = it.height
                }) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = {
                            FrameLayout(context).apply {
                                (shown.parent as? ViewGroup)?.removeView(shown)
                                addView(shown, FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                                ))
                            }
                        },
                        onRelease = { it.removeAllViews() }
                    )
                    if (BrowserVideoDiscovery.isXPage(selected.url)) {
                        MovableDownloadButton(
                            containerWidth = fullscreenWidth,
                            containerHeight = fullscreenHeight,
                            opacityPercent = downloadButtonOpacityPercent,
                            position = floatingDownloadPosition,
                            onMove = { floatingDownloadPosition = it },
                            onMoveFinished = { position ->
                                floatingDownloadPosition = position
                                DownloadSettings.setFloatingDownloadPosition(context, position)
                            },
                            onClick = {
                                val currentVideo = videoElements.videos.firstOrNull()
                                currentWebView()?.webChromeClient?.onHideCustomView()
                                offerDetectedVideo(currentVideo)
                            }
                        )
                    }
                }
            }
        }
    }
}
