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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

private class TabViewState(record: BrowserTabRecord) {
    val id: String = record.id
    var url by mutableStateOf(record.url)
    var title by mutableStateOf(record.title)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var progress by mutableIntStateOf(100)
    var scrollY: Int = 0
    fun record() = BrowserTabRecord(id, url, title)
}

private data class VideoPosition(val url: String, val right: Float, val top: Float)
private data class VideoPositions(val width: Float = 1f, val tiles: List<VideoPosition> = emptyList())

/** Read-only scan: no event handlers or HTML in the site are modified. */
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
    modifier: Modifier = Modifier,
    onDownload: (String) -> Unit,
    onSettings: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val currentDownload by rememberUpdatedState(onDownload)
    val currentSettings by rememberUpdatedState(onSettings)
    val blockAds by rememberUpdatedState(adBlockEnabled)

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

    fun saveSession() {
        BrowserStore.saveSession(context, tabs.map { it.record() }, activeId)
    }

    fun selectTab(tab: TabViewState) {
        activeId = tab.id
        scanResults = VideoPositions()
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

        fun updateUrl(url: String) {
            if (!BrowserAddress.isWebUrl(url) && url != BrowserAddress.BLANK_TAB) return
            if (tab.url != url) {
                tab.url = url
                if (tab.id == activeId) scanResults = VideoPositions()
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
                if (request == null || !DownloadSettings.adBlockEnabled(context)) return null
                if (!AdPageFilter.blocks(request.url.toString())) return null
                return WebResourceResponse(
                    "text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0))
                )
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url != null) updateUrl(url)
                tab.progress = 0
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
                if (tab.id == activeId && tab.scrollY > 0 && url != BrowserAddress.BLANK_TAB) {
                    val previous = tab.scrollY
                    web.postDelayed({ if (web.scrollY == 0) web.scrollTo(0, previous) }, 250)
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

        web.setOnScrollChangeListener { _, _, y, _, _ -> tab.scrollY = y }
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
        if (initialLoad) web.loadUrl(tab.url)
        return web
    }

    fun currentWebView(): WebView? = webViews[activeId]

    fun navigate(url: String) {
        if (url != BrowserAddress.BLANK_TAB && !BrowserAddress.isWebUrl(url)) {
            Toast.makeText(context, "請輸入有效的網站網址", Toast.LENGTH_SHORT).show()
            return
        }
        selected.url = url
        selected.title = if (url == BrowserAddress.BLANK_TAB) "新分頁"
        else BrowserAddress.displayName(url)
        selected.scrollY = 0
        scanResults = VideoPositions()
        saveSession()
        val browser = currentWebView()
        if (browser == null) {
            // The active tab will be initialized with its recorded URL.
        } else browser.loadUrl(url)
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
            while (webViews.size > 4) {
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
        addressInput = if (selected.url == BrowserAddress.BLANK_TAB) "" else selected.url
        scanResults = VideoPositions()
    }

    LaunchedEffect(visible, selected.id) {
        while (visible) {
            val browser = currentWebView()
            if (browser != null && Av01BrowserLinks.isSiteLink(selected.url) &&
                !Av01BrowserLinks.isVideoLink(selected.url) && viewportWidth > 0
            ) {
                val checkedId = selected.id
                browser.evaluateJavascript(AV01_TILE_SCAN) { result ->
                    if (activeId == checkedId) scanResults = decodeVideoPositions(result)
                }
            } else scanResults = VideoPositions()
            delay(800L)
        }
    }

    BackHandler(visible && (fullscreenView != null || selected.canBack)) {
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
            currentWebView()?.webChromeClient?.onHideCustomView()
        } else currentWebView()?.goBack()
    }

    Column(modifier.background(BrowserNavy)) {
        // Address bar + bookmark action, compact enough to leave most of the screen
        // to the website and not cover the page's own playback buttons.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = addressInput, onValueChange = { addressInput = it },
                modifier = Modifier.weight(1f).height(56.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                placeholder = { Text("搜尋或輸入網址", style = MaterialTheme.typography.bodySmall) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { navigateInput() }),
                trailingIcon = {
                    IconButton(onClick = { navigateInput() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, "開啟網址")
                    }
                },
                shape = RoundedCornerShape(15.dp)
            )
            val saved = bookmarks.any { it.url == selected.url }
            IconButton(
                enabled = BrowserAddress.isWebUrl(selected.url),
                onClick = {
                    BrowserStore.toggleBookmark(context, selected.url, selected.title)
                    bookmarks = BrowserStore.bookmarks(context)
                }
            ) {
                Icon(
                    if (saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = if (saved) "移除書籤" else "加入書籤",
                    tint = if (saved) BrowserGreen else Color.White
                )
            }
            IconButton(onClick = {
                bookmarks = BrowserStore.bookmarks(context)
                sheetSearch = ""
                selectedSheet = BrowserLibrary.BOOKMARKS
            }) {
                Icon(Icons.Default.Bookmark, contentDescription = "書籤")
            }
        }
        Row(
            Modifier.fillMaxWidth().height(43.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(tabs, key = { it.id }) { tab ->
                    Row(
                        Modifier.widthIn(min = 90.dp, max = 144.dp)
                            .height(35.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (tab.id == activeId) Color(0xFF294357) else BrowserPanel)
                            .clickable { selectTab(tab) }
                            .padding(start = 9.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            tab.title.ifBlank { BrowserAddress.displayName(tab.url) },
                            Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        IconButton(onClick = { closeTab(tab.id) }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Default.Close, "關閉分頁", modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }
            IconButton(onClick = { addTab() }, modifier = Modifier.size(37.dp)) {
                Icon(Icons.Default.Add, "新增分頁")
            }
        }
        Row(
            Modifier.fillMaxWidth().height(41.dp).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { currentWebView()?.goBack() }, enabled = selected.canBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "上一頁")
            }
            IconButton(onClick = { currentWebView()?.goForward() }, enabled = selected.canForward) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "下一頁")
            }
            IconButton(onClick = { navigate(BrowserAddress.BLANK_TAB) }) {
                Icon(Icons.Default.Home, "新分頁首頁")
            }
            IconButton(onClick = { currentWebView()?.reload() },
                enabled = selected.url != BrowserAddress.BLANK_TAB) {
                Icon(Icons.Default.Refresh, "重新整理")
            }
            IconButton(onClick = {
                history = BrowserStore.history(context)
                sheetSearch = ""
                selectedSheet = BrowserLibrary.HISTORY
            }) {
                Icon(Icons.Default.History, "歷史紀錄")
            }
            IconButton(onClick = { currentSettings() }) {
                Icon(Icons.Default.Settings, "設定")
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
                val width = with(density) { 72.dp.roundToPx() }
                val height = with(density) { 31.dp.roundToPx() }
                val margin = with(density) { 4.dp.roundToPx() }
                scanResults.tiles.forEach { tile ->
                    val x = (tile.right * scale - width - margin).roundToInt()
                        .coerceIn(0, (viewportWidth - width).coerceAtLeast(0))
                    val y = (tile.top * scale + margin).roundToInt()
                    if (y >= 0 && y + height < viewportHeight) {
                        Box(
                            Modifier.offset { IntOffset(x, y) }
                                .size(width = 72.dp, height = 31.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xEA27DBB3))
                                .clickable { currentDownload(tile.url) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("↓ 下載", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                color = BrowserNavy)
                        }
                    }
                }
            }
            val direct = selected.url
            if (Av01BrowserLinks.isVideoLink(direct) ||
                (BrowserAddress.isWebUrl(direct) &&
                    direct.substringBefore('?').let {
                        it.endsWith(".mp4", true) || it.endsWith(".m3u8", true)
                    })
            ) {
                Button(
                    onClick = { currentDownload(direct) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrowserGreen, contentColor = BrowserNavy
                    )
                ) {
                    Icon(Icons.Default.Download, null)
                    Spacer(Modifier.width(5.dp))
                    Text("下載影片")
                }
            }
        }
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

    if (fullscreenView != null) {
        Dialog(
            onDismissRequest = { currentWebView()?.webChromeClient?.onHideCustomView() },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            val shown = fullscreenView
            if (shown != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
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
            }
        }
    }
}
