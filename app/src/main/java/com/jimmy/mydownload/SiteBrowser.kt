package com.jimmy.mydownload

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Download
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
import java.io.ByteArrayInputStream
import java.net.URI
import kotlin.math.roundToInt

private const val HOME = "https://www.av01.media/"

object Av01BrowserLinks {
    private val sites = setOf("av01.media", "www.av01.media", "av01.tv", "www.av01.tv")

    fun isSiteLink(address: String): Boolean = runCatching {
        val uri = URI(address)
        uri.scheme.equals("https", ignoreCase = true) && uri.host?.lowercase() in sites
    }.getOrDefault(false)

    fun isVideoLink(address: String): Boolean = runCatching {
        isSiteLink(address) &&
            Regex("""/video/\d+(?:/|$)""").containsMatchIn(URI(address).path.orEmpty())
    }.getOrDefault(false)
}

private data class VideoTile(val url: String, val right: Float, val top: Float)
private data class VideoTiles(val viewportWidth: Float = 1f, val items: List<VideoTile> = emptyList())

// Scan video anchors without editing React's DOM or replacing site click handlers.
private val SCAN_THUMBNAILS = """
(() => {
  let output = {width: window.innerWidth, tiles: []};
  if (/\/video\/\d+(?:\/|$)/.test(location.pathname)) return JSON.stringify(output);
  let seen = new Set();
  let anchors = document.querySelectorAll('a[href*="/video/"]');
  for (const anchor of anchors) {
    try {
      let uri = new URL(anchor.href, location.href);
      if (uri.protocol !== 'https:' || !/^(www\.)?av01\.(media|tv)$/.test(uri.hostname)) continue;
      if (!/\/video\/\d+(?:\/|$)/.test(uri.pathname)) continue;
      const image = anchor.querySelector('img') || anchor;
      const rect = image.getBoundingClientRect();
      if (rect.width < 65 || rect.height < 55) continue;
      if (rect.right < 0 || rect.left > innerWidth || rect.bottom < 0 || rect.top > innerHeight) continue;
      let key = Math.round(rect.left) + ':' + Math.round(rect.top) + ':' + Math.round(rect.right);
      if (seen.has(key)) continue;
      seen.add(key);
      output.tiles.push({url: uri.href, right: rect.right, top: rect.top});
      if (output.tiles.length >= 50) break;
    } catch (ignored) {}
  }
  return JSON.stringify(output);
})()
""".trimIndent()

private fun decodeThumbnails(response: String): VideoTiles = runCatching {
    val json = JSONObject(JSONArray("[" + response + "]").getString(0))
    val width = json.optDouble("width", 0.0).toFloat()
    if (width <= 0f) return@runCatching VideoTiles()
    val tiles = json.optJSONArray("tiles") ?: return@runCatching VideoTiles(width)
    val records = buildList {
        for (i in 0 until minOf(tiles.length(), 50)) {
            val tile = tiles.optJSONObject(i) ?: continue
            val url = tile.optString("url")
            if (!Av01BrowserLinks.isVideoLink(url)) continue
            add(VideoTile(
                url, tile.optDouble("right").toFloat(), tile.optDouble("top").toFloat()
            ))
        }
    }
    VideoTiles(width, records)
}.getOrDefault(VideoTiles())

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SiteBrowser(
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
    val currentAdBlock by rememberUpdatedState(adBlockEnabled)
    var address by remember { mutableStateOf(HOME) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var fullScreenView by remember { mutableStateOf<View?>(null) }
    var fullScreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var viewportWidth by remember { mutableIntStateOf(0) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    var thumbnails by remember { mutableStateOf(VideoTiles()) }

    val browser = remember(context) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            val main = this
            fun openPopup(url: String) {
                if (Av01BrowserLinks.isSiteLink(url)) main.loadUrl(url)
                else if (!currentAdBlock && (url.startsWith("https://") || url.startsWith("http://"))) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress = newProgress
                }

                override fun onCreateWindow(
                    view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
                ): Boolean {
                    if (!isUserGesture || resultMsg == null) return false
                    val parent = main.parent as? ViewGroup ?: return false
                    val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                    val popup = WebView(context)
                    var handled = false
                    fun handleOnce(link: String) {
                        if (handled || link == "about:blank") return
                        handled = true
                        openPopup(link)
                        popup.post {
                            (popup.parent as? ViewGroup)?.removeView(popup)
                            popup.destroy()
                        }
                    }
                    popup.settings.javaScriptEnabled = false
                    popup.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            if (request != null && request.isForMainFrame) handleOnce(request.url.toString())
                            return true
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            if (url != null) handleOnce(url)
                        }
                    }
                    parent.addView(popup, ViewGroup.LayoutParams(1, 1))
                    transport.webView = popup
                    resultMsg.sendToTarget()
                    return true
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (view == null || fullScreenView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    fullScreenView = view
                    fullScreenCallback = callback
                }

                override fun onHideCustomView() {
                    fullScreenView = null
                    fullScreenCallback?.onCustomViewHidden()
                    fullScreenCallback = null
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?, request: WebResourceRequest?
                ): WebResourceResponse? {
                    if (!DownloadSettings.adBlockEnabled(context) || request == null) return null
                    if (!AdPageFilter.blocks(request.url.toString())) return null
                    return WebResourceResponse(
                        "text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0))
                    )
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    if (request == null || !request.isForMainFrame) return false
                    val url = request.url.toString()
                    if (Av01BrowserLinks.isSiteLink(url)) return false
                    if (!currentAdBlock && request.hasGesture() &&
                        request.url.scheme in setOf("http", "https")) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    }
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    thumbnails = VideoTiles()
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    if (url != null && Av01BrowserLinks.isSiteLink(url)) address = url
                    canBack = view?.canGoBack() == true
                    canForward = view?.canGoForward() == true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url != null && Av01BrowserLinks.isSiteLink(url)) address = url
                    canBack = view?.canGoBack() == true
                    canForward = view?.canGoForward() == true
                }
            }
            setOnLongClickListener {
                val link = hitTestResult?.extra.orEmpty()
                if (Av01BrowserLinks.isVideoLink(link)) {
                    currentDownload(link)
                    true
                } else false
            }
            loadUrl(HOME)
        }
    }

    val holder = remember(browser) {
        FrameLayout(context).apply {
            addView(browser, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
    }

    DisposableEffect(browser) {
        onDispose {
            browser.stopLoading()
            holder.removeAllViews()
            browser.destroy()
        }
    }

    var previousAdBlock by remember { mutableStateOf(adBlockEnabled) }
    LaunchedEffect(adBlockEnabled) {
        if (previousAdBlock != adBlockEnabled) {
            previousAdBlock = adBlockEnabled
            browser.reload()
        }
    }

    LaunchedEffect(visible) {
        if (visible) browser.onResume() else browser.onPause()
    }

    LaunchedEffect(browser, visible) {
        while (visible) {
            if (viewportWidth > 0 && Av01BrowserLinks.isSiteLink(browser.url.orEmpty())) {
                browser.evaluateJavascript(SCAN_THUMBNAILS) {
                    thumbnails = decodeThumbnails(it)
                }
            } else thumbnails = VideoTiles()
            delay(750)
        }
    }

    BackHandler(visible && (canBack || fullScreenView != null)) {
        if (fullScreenView != null) browser.webChromeClient?.onHideCustomView()
        else browser.goBack()
    }

    Column(modifier.background(Color(0xFF091421))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { browser.goBack() }, enabled = canBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "上一頁")
            }
            IconButton(onClick = { browser.goForward() }, enabled = canForward) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "下一頁")
            }
            Text(
                Uri.parse(address).host.orEmpty(),
                modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = { browser.reload() }) {
                Icon(Icons.Default.Refresh, "重新整理")
            }
            IconButton(onClick = { browser.loadUrl(HOME) }) {
                Icon(Icons.Default.Home, "網站首頁")
            }
            IconButton(onClick = { currentSettings() }) {
                Icon(Icons.Default.Settings, "設定")
            }
        }
        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .onSizeChanged { viewportWidth = it.width; viewportHeight = it.height }
        ) {
            AndroidView(factory = { holder }, modifier = Modifier.fillMaxSize())
            if (visible && !Av01BrowserLinks.isVideoLink(address) && viewportWidth > 0) {
                val scale = viewportWidth / thumbnails.viewportWidth
                val width = with(density) { 70.dp.roundToPx() }
                val height = with(density) { 31.dp.roundToPx() }
                val padding = with(density) { 5.dp.roundToPx() }
                thumbnails.items.forEach { tile ->
                    val x = (tile.right * scale - width - padding).roundToInt()
                        .coerceIn(0, (viewportWidth - width).coerceAtLeast(0))
                    val y = (tile.top * scale + padding).roundToInt()
                    if (y >= 0 && y + height < viewportHeight) {
                        Box(
                            modifier = Modifier.offset { IntOffset(x, y) }
                                .size(width = 70.dp, height = 31.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(Color(0xE527DBB3))
                                .clickable { currentDownload(tile.url) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("↓ 下載", color = Color(0xFF091421),
                                fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
            if (Av01BrowserLinks.isVideoLink(address)) {
                Button(
                    onClick = { currentDownload(address) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF27DBB3),
                        contentColor = Color(0xFF091421)
                    )
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("下載此影片")
                }
            }
        }
    }

    if (fullScreenView != null) {
        Dialog(
            onDismissRequest = { browser.webChromeClient?.onHideCustomView() },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            val view = fullScreenView
            if (view != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    factory = {
                        FrameLayout(context).apply {
                            (view.parent as? ViewGroup)?.removeView(view)
                            addView(view, FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            ))
                        }
                    },
                    onRelease = { frame -> frame.removeAllViews() }
                )
            }
        }
    }
}
