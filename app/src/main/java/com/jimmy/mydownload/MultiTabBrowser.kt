package com.jimmy.mydownload

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.ScaleGestureDetector
import android.view.MotionEvent
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
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

/**
 * Movable fullscreen button. A tap still toggles fullscreen; dragging the
 * button never pans the video. The onMoveFinished callback persists its place.
 * Unmodified orientations keep the old bottom-right 16dp / 72dp default.
 */
@Composable
private fun BoxScope.MovableFullscreenButton(
    containerWidth: Int,
    containerHeight: Int,
    position: FloatingDownloadPosition?,
    fullscreen: Boolean,
    onMove: (FloatingDownloadPosition) -> Unit,
    onMoveFinished: (FloatingDownloadPosition) -> Unit,
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    val buttonSize = with(density) { 47.dp.roundToPx() }
    val endMargin = with(density) { 16.dp.roundToPx() }
    val bottomMargin = with(density) { 72.dp.roundToPx() }
    val rangeX = (containerWidth - buttonSize).coerceAtLeast(0)
    val rangeY = (containerHeight - buttonSize).coerceAtLeast(0)
    val defaultPosition = FloatingDownloadPosition(
        if (rangeX > 0) ((rangeX - endMargin).coerceAtLeast(0).toFloat() / rangeX)
            .coerceIn(0f, 1f) else 1f,
        if (rangeY > 0) ((rangeY - bottomMargin).coerceAtLeast(0).toFloat() / rangeY)
            .coerceIn(0f, 1f) else 1f
    )
    val current = position ?: defaultPosition
    val posX = (current.xFraction.coerceIn(0f, 1f) * rangeX).roundToInt()
    val posY = (current.yFraction.coerceIn(0f, 1f) * rangeY).roundToInt()
    val currentPosition by rememberUpdatedState(position)
    val currentDefault by rememberUpdatedState(defaultPosition)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnFinished by rememberUpdatedState(onMoveFinished)

    Box(
        modifier = Modifier.align(Alignment.TopStart)
            .offset { IntOffset(posX, posY) }
            .size(47.dp)
            .clip(CircleShape)
            .background(Color(0xB0000000))
            .pointerInput(rangeX, rangeY) {
                var dragPosition = currentPosition ?: currentDefault
                detectDragGestures(
                    onDragStart = { dragPosition = currentPosition ?: currentDefault },
                    onDragEnd = { currentOnFinished(dragPosition) },
                    onDragCancel = { currentOnFinished(dragPosition) }
                ) { change, dragAmount ->
                    change.consume()
                    dragPosition = dragPosition.movedBy(
                        dragAmount.x, dragAmount.y, rangeX, rangeY
                    )
                    currentOnMove(dragPosition)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(
                if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                contentDescription = if (fullscreen) "退出全螢幕，拖曳可移動按鈕"
                    else "切換全螢幕，拖曳可移動按鈕",
                tint = BrowserGreen, modifier = Modifier.size(28.dp)
            )
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
  // X's portrait / immersive video viewer often has neither a dialog nor
  // a native role=slider seekbar. Scope CSS to the active media viewer, rather
  // than requiring both markers (the old rule failed on these layouts).
  const videoNodes = Array.from(document.querySelectorAll('video'));
  const visibleVideos = videoNodes.filter(node => {
    const r = node.getBoundingClientRect();
    return r.width >= 120 && r.height >= 90 &&
      r.right > 0 && r.left < innerWidth &&
      r.bottom > 0 && r.top < innerHeight;
  });
  const immersiveRoot = document.querySelector(
    '[data-testid="vss-scroll-view"],[data-testid="immersive-viewer"]'
  );
  const modalRoot = document.querySelector(
    '#layers [role="dialog"]:has(video,[data-testid="videoPlayer"])'
  );
  const mediaRoute = /\/status\/\d+\/(?:photo|video)\/\d+\/?$/.test(location.pathname) ||
    /^\/i\/status\/\d+\/?$/.test(location.pathname);
  const seekbarVisible = Array.from(document.querySelectorAll(
    '[role="slider"],input[type="range"],[data-testid="videoPlayer"] [aria-valuenow]'
  )).some(node => {
    const rect = node.getBoundingClientRect();
    const css = getComputedStyle(node);
    return rect.width >= 80 && rect.height > 0 && rect.bottom > 0 &&
      rect.top < innerHeight && css.display !== 'none' &&
      css.visibility !== 'hidden';
  });
  const playingViewer = visibleVideos.length > 0 && !!(
    immersiveRoot || modalRoot ||
    (mediaRoute && seekbarVisible) ||
    (window.__mydownloadVideoFullscreen?.enabled && seekbarVisible)
  );
  document.documentElement.dataset.mydownloadXSeek =
    playingViewer ? '1' : '0';

  const scopeAttr = 'data-mydownload-video-details-root';
  document.querySelectorAll('[' + scopeAttr + '="1"]').forEach(el =>
    el.removeAttribute(scopeAttr));
  const markScope = el => {
    if (el && el.nodeType === 1) el.setAttribute(scopeAttr,'1');
  };
  if (playingViewer) {
    markScope(immersiveRoot);
    markScope(modalRoot);
    if (!immersiveRoot && !modalRoot) {
      // For X's full-page media route, find the smallest parent containing
      // both the visible player and its post details.
      const activeVideo = visibleVideos.sort((a,b) => {
        const ar=a.getBoundingClientRect(),br=b.getBoundingClientRect();
        return br.width*br.height-ar.width*ar.height;
      })[0];
      let container=activeVideo;
      for (let depth=0;container && depth<14;depth++) {
        if (container===document.body || container===document.documentElement)
          break;
        const caption=container.querySelector?.(
          '[data-testid="tweetText"],[data-testid="videoDescription"],' +
          '[data-testid^="immersive-tweet-ui-content-container"]'
        );
        if (caption) { markScope(container); break; }
        container=container.parentElement;
      }
      if (!document.querySelector('['+scopeAttr+'="1"]')) {
        markScope(activeVideo.closest('article[data-testid="tweet"]'));
      }
    }
  }

  const playbackScope = '['+scopeAttr+'="1"]';
  const detailSelectors = [
    '[data-testid="tweetText"]',
    '[data-testid="media-caption"]',
    '[data-testid="video-caption"]',
    '[data-testid="videoDescription"]',
    '[data-testid="videoTitle"]'
  ];
  const authorSelectors = [
    '[data-testid="User-Name"]',
    '[data-testid="UserName"]',
    '[data-testid="User-Names"]',
    '[data-testid^="UserAvatar-Container"]',
    '[data-testid="Tweet-User-Avatar"]'
  ];
  // The combined switch hides caption + author with a single action.
  // Independent switches below it still work when the combined switch is off.
  if (flags[13] || flags[15]) detailSelectors.forEach(sel =>
    selectors.push(playbackScope+' '+sel));
  if (flags[14] || flags[15]) authorSelectors.forEach(sel =>
    selectors.push(playbackScope+' '+sel));
  if (flags[13] || flags[15]) selectors.push(
    playbackScope+' [data-testid^="immersive-tweet-ui-content-container"] [lang]',
    playbackScope+' [data-testid^="immersive-tweet-ui-content-container"] [dir="auto"]'
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
        options.hideVideoPlaybackText, options.hideVideoPlaybackTitle,
        options.hideVideoBelowProgressDetails
    ).joinToString(",") { it.toString() }
    return X_DISPLAY_SCRIPT.replace("__FLAGS__", flags)
}

/**
 * Two-finger media zoom is implemented inside the existing X document. This
 * leaves one-finger gallery/video swipes to X, so the page never has to reload
 * and the native browser toolbar and download overlay stay at their size.
 */
private val X_MEDIA_PINCH_ZOOM_SCRIPT = """
(() => {
  if (!/^(?:www\.)?(?:x\.com|twitter\.com)$/.test(location.hostname)) return;
  const existing = window.__mydownloadMediaZoom;
  if (existing) { existing.ensure(); return; }

  const state = {
    path: location.pathname, media: null, scale: 1, x: 0, y: 0,
    startDistance: 0, startScale: 1, startX: 0, startY: 0,
    startPanX: 0, startPanY: 0, mode: '', nativePinch: false, singleStart: null,
    originalScale: '', originalTranslate: '', originalOrigin: '',
    originalScalePriority: '', originalTranslatePriority: ''
  };
  const clamp = (n,lo,hi) => Math.min(hi,Math.max(lo,n));
  const dist = (a,b) => Math.hypot(a.clientX-b.clientX,a.clientY-b.clientY);
  const visible = rect => rect.width>90 && rect.height>65 &&
    rect.right>0 && rect.left<innerWidth &&
    rect.bottom>0 && rect.top<innerHeight;
  const videoViewer = () => !!window.__mydownloadVideoFullscreen?.enabled ||
    !!document.querySelector('[data-testid="vss-scroll-view"]') ||
    !!document.querySelector('#layers [role="dialog"]:has(video,[data-testid="videoPlayer"])');
  const viewer = () => {
    if (/\/status\/\d+/.test(location.pathname) ||
        /^\/i\/status\/\d+/.test(location.pathname) ||
        window.__mydownloadVideoFullscreen?.enabled) return true;
    if (document.querySelector(
        '#layers [role="dialog"]:has(video,[data-testid="tweetPhoto"])'
      )) return true;
    // X may open videos in an inline immersive viewer with no /video/1 path.
    // A large visible video is a safe signal when the user pinches on it.
    return [...document.querySelectorAll('video')].some(v => {
      const r=v.getBoundingClientRect();
      return visible(r) && r.width>=innerWidth*0.44 &&
        r.height>=innerHeight*0.30;
    });
  };
  const reset = () => {
    if (state.media) {
      const media=state.media;
      media.style.setProperty('scale',state.originalScale,
        state.originalScalePriority);
      media.style.setProperty('translate',state.originalTranslate,
        state.originalTranslatePriority);
      media.style.transformOrigin=state.originalOrigin;
    }
    state.media=null;
    state.scale=1;state.x=0;state.y=0;state.mode='';
    state.startDistance=0;state.nativePinch=false;state.singleStart=null;
  };
  const ensure = () => {
    if (state.path!==location.pathname ||
        (state.media && (!state.media.isConnected ||
          (document.querySelector('[data-testid="vss-scroll-view"]') &&
           !visible(state.media.getBoundingClientRect()))))) {
      reset();
      state.path=location.pathname;
    }
  };
  const chooseMedia = (x,y) => {
    const candidates=[...document.querySelectorAll('video,img')]
      .map(media=>({media,rect:media.getBoundingClientRect()}))
      .filter(({rect})=>visible(rect) && rect.width>=120 && rect.height>=80)
      .map(item=>{
        const r=item.rect;
        const inside=x>=r.left && x<=r.right && y>=r.top && y<=r.bottom;
        const overlap=Math.max(0,Math.min(r.right,innerWidth)-Math.max(0,r.left))*
          Math.max(0,Math.min(r.bottom,innerHeight)-Math.max(0,r.top));
        const dx=(r.left+r.right)/2-x,dy=(r.top+r.bottom)/2-y;
        return {...item,inside,overlap,distance:Math.hypot(dx,dy)};
      });
    if (!candidates.length) return null;
    // Video first, never a poster/image overlay that happens to cover it.
    // A second video above or below the swipe must not be selected.
    candidates.sort((a,b)=>
      Number(b.inside)-Number(a.inside) ||
      Number(b.media.tagName==='VIDEO')-Number(a.media.tagName==='VIDEO') ||
      a.distance-b.distance || b.overlap-a.overlap);
    const chosen=candidates[0];
    if (!chosen.inside && chosen.distance>Math.max(innerWidth,innerHeight)*0.48)
      return null;
    return chosen.media;
  };
  const capture = media => {
    if (!media) return false;
    if (state.media!==media) {
      reset();
      state.media=media;
      state.originalScale=media.style.getPropertyValue('scale');
      state.originalTranslate=media.style.getPropertyValue('translate');
      state.originalOrigin=media.style.transformOrigin;
      state.originalScalePriority=media.style.getPropertyPriority('scale');
      state.originalTranslatePriority=media.style.getPropertyPriority('translate');
    }
    return true;
  };
  const render = () => {
    if (!state.media) return;
    state.media.style.transformOrigin='center center';
    // Keep X's original transform/scroll logic. Scale and translate are
    // individual properties, so we never reparent or force the video fixed.
    state.media.style.setProperty('scale',String(state.scale),'important');
    state.media.style.setProperty('translate',
      state.x+'px '+state.y+'px','important');
  };
  const applyScale = factor => {
    if (!state.media || !Number.isFinite(factor) || factor<=0) return;
    state.scale=clamp(state.scale*factor,1,5);
    if (state.scale<=1.005) {state.scale=1;state.x=0;state.y=0;}
    render();
  };
  const movePan = p => {
    if (!state.media) return;
    const rect=state.media.getBoundingClientRect();
    const maxX=Math.max(0,rect.width*(state.scale-1)/(2*state.scale));
    const maxY=Math.max(0,rect.height*(state.scale-1)/(2*state.scale));
    state.x=clamp(state.startPanX+p.clientX-state.startX,-maxX,maxX);
    state.y=clamp(state.startPanY+p.clientY-state.startY,-maxY,maxY);
    render();
  };
  const nativeStart = (xFraction,yFraction) => {
    ensure();
    if (!viewer()) return false;
    const x=innerWidth*clamp(xFraction,0,1);
    const y=innerHeight*clamp(yFraction,0,1);
    if (!capture(chooseMedia(x,y))) return false;
    state.startX=x;state.startY=y;
    state.startPanX=state.x;state.startPanY=state.y;
    state.mode='native';
    state.nativePinch=true;
    return true;
  };
  const nativeScale = factor => {
    if (!state.nativePinch || !state.media) return false;
    applyScale(factor);
    return true;
  };
  const nativeEnd = () => {
    state.nativePinch=false;
    if (state.mode==='native') state.mode='';
  };
  document.addEventListener('touchstart',e=>{
    ensure();
    if (!viewer()) return;
    if (e.touches.length===2) {
      const x=(e.touches[0].clientX+e.touches[1].clientX)/2;
      const y=(e.touches[0].clientY+e.touches[1].clientY)/2;
      const media=chooseMedia(x,y);
      if (!capture(media)) return;
      state.startDistance=Math.max(1,dist(e.touches[0],e.touches[1]));
      state.startScale=state.scale;
      state.startX=x;state.startY=y;
      state.startPanX=state.x;state.startPanY=state.y;
      state.singleStart=null;
      if (!state.nativePinch) state.mode='pinch';
      if (e.cancelable) e.preventDefault();
      e.stopImmediatePropagation();
    } else if (e.touches.length===1 && videoViewer()) {
      // One finger may select the next/previous clip, never pan the zoomed media.
      state.singleStart={x:e.touches[0].clientX,y:e.touches[0].clientY};
    }
  },{capture:true,passive:false});
  document.addEventListener('touchmove',e=>{
    // This must run BEFORE the zoomed-media check: a video is usually not
    // captured until the first pinch, but sideways one-finger drags are
    // forbidden during normal playback too.
    if(e.touches.length===1 && state.singleStart && videoViewer()) {
      const dx=e.touches[0].clientX-state.singleStart.x;
      const dy=e.touches[0].clientY-state.singleStart.y;
      const slider=e.target?.closest?.(
        '[role="slider"],input[type="range"],[data-testid*="seek"],[data-testid*="progress"]');
      if(!slider && Math.abs(dx)>12 && Math.abs(dx)>Math.abs(dy)*1.25) {
        if(e.cancelable)e.preventDefault();
        e.stopImmediatePropagation();
        return;
      }
    }
    if (!state.media || !viewer()) return;
    if (e.touches.length===2 &&
        (state.mode==='pinch' || state.nativePinch)) {
      if (!state.nativePinch && state.startDistance>0) {
        const ratio=dist(e.touches[0],e.touches[1])/state.startDistance;
        state.scale=clamp(state.startScale*ratio,1,5);
        if(state.scale<=1.005){state.scale=1;state.x=0;state.y=0;}
        render();
      }
      // Panning and zooming both require two active fingers.
      if(state.scale>1.01) movePan({
        clientX:(e.touches[0].clientX+e.touches[1].clientX)/2,
        clientY:(e.touches[0].clientY+e.touches[1].clientY)/2
      });
      if(e.cancelable)e.preventDefault();
      e.stopImmediatePropagation();
    }
  },{capture:true,passive:false});
  const end=e=>{
    state.singleStart=null;
    // Lifting one finger after a pinch must not enable one-finger dragging.
    if(e.touches.length<2 && !state.nativePinch) state.mode='';
  };
  document.addEventListener('touchend',end,{capture:true,passive:true});
  document.addEventListener('touchcancel',end,{capture:true,passive:true});
  document.addEventListener('dblclick',e=>{
    if (state.media && state.scale>1.01 && viewer()) {
      e.preventDefault();e.stopImmediatePropagation();reset();
    }
  },{capture:true});
  window.__mydownloadMediaZoom={
    ensure, isZoomed:()=>state.scale>1.01,
    nativeStart,nativeScale,nativeEnd,reset
  };
})()
""".trimIndent()

/**
 * X fullscreen is the same inline player as portrait: only Android chrome and
 * system bars are hidden. Never force a fixed-position video overlay because
 * it can black out Android's hardware-accelerated video surface in landscape.
 */
private val X_VIDEO_FULLSCREEN_SCRIPT = """
(() => {
  if (!/^(?:www\.)?(?:x\.com|twitter\.com)$/.test(location.hostname)) return 'not-x';
  const enabled = __ENABLED__;
  const state = window.__mydownloadVideoFullscreen ||
    (window.__mydownloadVideoFullscreen = { enabled: false });

  // Restore styles from older fullscreen implementations, if any. X owns its
  // original player DOM, video surface, swipe gestures and playback controls.
  // Do not force the video container to position:fixed: this can produce an
  // entirely black compositor layer on Android landscape playback.
  if (state.player) {
    if (state.playerStyle === null) state.player.removeAttribute('style');
    else if (state.playerStyle !== undefined)
      state.player.setAttribute('style', state.playerStyle);
    state.player.removeAttribute('data-mydownload-fullscreen');
  }
  if (state.video) {
    if (state.videoStyle === null) state.video.removeAttribute('style');
    else if (state.videoStyle !== undefined)
      state.video.setAttribute('style', state.videoStyle);
  }
  state.player = null;
  state.video = null;
  if (!enabled) {
    state.enabled = false;
    return 'exited';
  }

  const playingVideo = Array.from(document.querySelectorAll('video')).some(video => {
    const rect = video.getBoundingClientRect();
    return rect.width >= 80 && rect.height >= 60 &&
      rect.right > 0 && rect.left < innerWidth &&
      rect.bottom > 0 && rect.top < innerHeight;
  });
  if (!playingVideo) {
    // On a swipe X may temporarily have no visible video while the next frame
    // mounts. Preserve an already-enabled fullscreen session in that gap.
    return state.enabled ? 'fullscreen' : 'no-video';
  }
  // Only a UI/immersive-mode flag. No video.pause(), play(), seek(), reload()
  // or DOM re-parenting, so landscape behaves like portrait.
  state.enabled = true;
  return 'fullscreen';
})()
""".trimIndent()

private fun xVideoFullscreenScript(enabled: Boolean): String =
    X_VIDEO_FULLSCREEN_SCRIPT.replace("__ENABLED__", enabled.toString())

/**
 * Double tap the left/right side of the *playing video* to seek -/+10 seconds.
 * Uses touch capture only for completed double taps. Single taps, vertical
 * carousel swipes and two-finger zoom remain owned by X.
 */
/**
 * Resize X's mobile immersive carousel (not the video layer) to the *actual*
 * landscape WebView viewport. X retains ownership of vertical swipe snapping.
 */
private val X_IMMERSIVE_FIT_SCRIPT = """
(() => {
  if (!/^(?:www\.)?(?:x\.com|twitter\.com)$/.test(location.hostname)) return;
  const enabled=__ENABLED__ && innerWidth>innerHeight*1.12;
  const stateKey='__mydownloadImmersiveFit';
  const state=window[stateKey]||(window[stateKey]={
    active:false, listenerInstalled:false, gesture:null, timer:0,
    scroll:null, cards:[], lastHeight:0,
    lastViewportWidth:0,lastViewportHeight:0,
    anchorCard:null,anchorIndex:-1,anchorKey:'',restoreToken:0,
    healthTimer:0,lastHealthTop:null,stillSince:0,lastRepairAt:0,
    layoutRepair:false,layoutHeight:0,
    boundScroller:null,scrollObserver:null,prepareRaf:0,settleRaf:0
  });
  const cardAttr='data-mydownload-fullscreen-card';
  const scrollAttr='data-mydownload-fullscreen-scroll';
  const styleId='mydownload-fullscreen-card-size';
  const repairStyleId='mydownload-immersive-layout-repair';
  const repairAttr='data-mydownload-immersive-slide';

  const clear=()=>{
    clearTimeout(state.timer);
    clearTimeout(state.playbackTimer);
    clearTimeout(state.healthTimer);
    state.scrollObserver?.disconnect();
    state.scrollObserver=null;
    if(state.boundScroller){
      state.boundScroller.removeEventListener('scroll',state.scrollHandler);
      state.boundScroller.removeEventListener('scrollend',state.scrollEndHandler);
      state.boundScroller=null;
    }
    if(state.prepareRaf)cancelAnimationFrame(state.prepareRaf);
    if(state.settleRaf)cancelAnimationFrame(state.settleRaf);
    state.prepareRaf=0;state.settleRaf=0;
    state.timer=0;state.gesture=null;
    document.getElementById(repairStyleId)?.remove();
    document.querySelectorAll('['+repairAttr+']').forEach(el=>
      el.removeAttribute(repairAttr));
    state.layoutRepair=false;state.layoutHeight=0;
    document.getElementById(styleId)?.remove();
    document.querySelectorAll('['+cardAttr+']').forEach(el=>
      el.removeAttribute(cardAttr));
    document.querySelectorAll('['+scrollAttr+']').forEach(el=>
      el.removeAttribute(scrollAttr));
    state.scroll=null;state.cards=[];state.lastHeight=0;
  };
  const wasActive=state.active;

  const findCards=scroller=>{
    let list=scroller.firstElementChild;
    for(let depth=0;list&&depth<5;depth++){
      const children=[...list.children].filter(el=>el instanceof HTMLElement);
      if(!children.length)return [];
      const videos=children.filter(el=>el.querySelector(
        'video,[data-testid^="immersive-tweet-ui-content-container"]'));
      if(videos.length>1)return videos;
      if(videos.length===1 && children.length===1 &&
          videos[0].querySelectorAll('video').length>1){
        list=children[0];continue;
      }
      if(videos.length===1 && children.length>1)return videos;
      if(videos.length===1 && children.length===1){
        // A single post may be the only mounted card; keep that card unless
        // its child is clearly another wrapper around multiple media posts.
        return videos;
      }
      // No media at this level, descend through the sole wrapper.
      if(children.length!==1)return [];
      list=children[0];
    }
    return [];
  };
  const measure=()=>{
    const scroller=document.querySelector('[data-testid="vss-scroll-view"]');
    if(!scroller)return null;
    const cards=findCards(scroller);
    if(!cards.length)return null;
    return {scroller,cards,box:scroller.getBoundingClientRect()};
  };
  const apply=()=>{
    // X owns card heights, scroll snapping and video surfaces.
    // Overriding these on every scan could unmount the playing card mid-swipe.
    const found=measure();
    if(found){
      state.scroll=found.scroller;
      state.cards=found.cards;
      state.attachScroller?.(found);
      state.prepareSlides?.(found);
    }
    // Also clean up CSS from an older injected runtime if any.
    document.getElementById(styleId)?.remove();
    document.querySelectorAll('['+cardAttr+']').forEach(el=>
      el.removeAttribute(cardAttr));
    document.querySelectorAll('['+scrollAttr+']').forEach(el=>
      el.removeAttribute(scrollAttr));
    return found;
  };

  // Choose the card which owns the center of the visible screen. A thin
  // remnant of the preceding card at the top cannot win this comparison.
  const centered=({scroller,cards,box})=>{
    const cy=Math.max(box.top,0)+
      Math.min(box.height,innerHeight)/2;
    let chosen=null,best=-Infinity;
    cards.forEach((card,index)=>{
      const r=card.getBoundingClientRect();
      const overlap=Math.max(0,Math.min(r.bottom,box.bottom,innerHeight)-
        Math.max(r.top,box.top,0));
      const contains=cy>=r.top && cy<=r.bottom;
      const score=(contains?100000:0)+overlap-
        Math.abs((r.top+r.bottom)/2-cy)*0.12;
      if(score>best){best=score;chosen={card,index,rect:r,overlap};}
    });
    return chosen && chosen.overlap>Math.min(innerHeight,box.height)*0.22
      ? chosen : null;
  };

  /**
   * Some X WebView carousel sessions retain portrait-size slide wrappers or
   * an off-grid scroll offset after a landscape swipe. Once this happens,
   * every next clip inherits the same split viewport. Repair the CAROUSEL
   * wrapper only, never scale/reparent/reload video or change its playback.
   * The health check runs while idle too, so an earlier bad swipe can recover.
   */
  const viewportHeight=found=>
    Math.min(found.box.height,found.scroller.clientHeight,innerHeight);
  const inspected=found=>{
    const h=viewportHeight(found);
    return found.cards.map((card,index)=>{
      const rect=card.getBoundingClientRect();
      const overlap=Math.max(0,Math.min(rect.bottom,found.box.bottom,innerHeight)-
        Math.max(rect.top,found.box.top,0));
      return {card,index,rect,overlap,h,video:card.querySelector('video')};
    }).filter(item=>item.overlap>0);
  };
  const repairSlideHeight=(found,h)=>{
    const previousHeight=state.layoutHeight;
    state.layoutHeight=Math.round(h);
    if(state.layoutHeight<140)return;
    state.layoutRepair=true;
    let style=document.getElementById(repairStyleId);
    if(!style){
      style=document.createElement('style');
      style.id=repairStyleId;
      (document.head||document.documentElement).appendChild(style);
    }
    // Only min-height on the OUTER card. In contrast to the old fix this
    // never forces a max-height, flex basis, video dimensions or transforms.
    const css='['+repairAttr+'="1"] {'+
      'min-height:'+state.layoutHeight+'px !important;'+
      'box-sizing:border-box !important;'+
      'scroll-snap-align:start !important;}';
    if(style.textContent!==css)style.textContent=css;
    found.cards.forEach(card=>{
      if(!card.hasAttribute(repairAttr))card.setAttribute(repairAttr,'1');
    });
    return previousHeight!==state.layoutHeight;
  };
  // Ensure new landscape cards occupy the full viewport before swiping.
  // The outer slide gets only min-height and snap alignment, never the
  // video layer's dimensions or transforms.
  const prepareSlides=found=>{
    if(!state.active || !enabled || !found)return;
    const h=viewportHeight(found);
    if(h<140 || !found.cards.length)return;
    if(state.layoutRepair){
      repairSlideHeight(found,h);
      return;
    }
    const short=found.cards.some(card=>{
      const rh=card.getBoundingClientRect().height;
      return rh>0 && rh<h*0.97;
    });
    if(short)repairSlideHeight(found,h);
  };
  state.prepareSlides=prepareSlides;
  const prepareNextFrame=()=>{
    if(state.prepareRaf || !state.active)return;
    state.prepareRaf=requestAnimationFrame(()=>{
      state.prepareRaf=0;
      const found=measure();
      if(found)prepareSlides(found);
    });
  };

  const repairOffset=(found,card)=>{
    if(!card?.isConnected || !found.cards.includes(card))return false;
    const scroll=found.scroller;
    const range=scroll.scrollHeight-scroll.clientHeight;
    if(range<15)return false;
    const delta=card.getBoundingClientRect().top-found.box.top;
    if(Math.abs(delta)<9 || Math.abs(delta)>viewportHeight(found)*1.5)
      return false;
    const next=Math.max(0,Math.min(range,scroll.scrollTop+delta));
    if(Math.abs(next-scroll.scrollTop)<5)return false;
    // Disable only the scroller's snap for this single synchronous correction.
    // Re-enable immediately after layout, leaving X's video DOM untouched.
    const originalSnap=scroll.style.scrollSnapType;
    scroll.style.scrollSnapType='none';
    scroll.scrollTop=next;
    requestAnimationFrame(()=>{
      if(scroll.isConnected && scroll.style.scrollSnapType==='none')
        scroll.style.scrollSnapType=originalSnap;
    });
    state.lastRepairAt=Date.now();
    return true;
  };
  const healthCheck=(fromSwipe=false)=>{
    if(!state.active || state.gesture || !enabled)return;
    const now=Date.now();
    if(now-(state.lastResizeAt||0)<650 || now-(state.lastUserTouch||0)<500)
      return;
    const found=measure();
    if(!found || found.cards.length<2 || viewportHeight(found)<140)return;
    const currentTop=found.scroller.scrollTop;
    if(state.lastHealthTop===null || Math.abs(state.lastHealthTop-currentTop)>2){
      state.lastHealthTop=currentTop;
      state.stillSince=now;
      return;
    }
    if(!fromSwipe && now-state.stillSince<300)return;
    if(now-state.lastRepairAt<700)return;
    if(state.layoutRepair)repairSlideHeight(found,viewportHeight(found));
    const entries=inspected(found);
    const h=viewportHeight(found);
    const visible=entries.filter(e=>e.overlap>h*0.06);
    if(visible.length<2)return;
    // The playing video is the best anchor after a slide transition.
    // Otherwise use the card at viewport center, rather than a cached
    // array index which X can recycle for a different post.
    const playing=visible.filter(e=>e.video && !e.video.paused &&
      !e.video.ended && e.video.readyState>=2);
    const centeredCard=centered(found)?.card;
    // During a transition the previous clip may keep playing briefly.
    // Prefer where the viewer is actually centered; do not snap backwards
    // merely because the departing video has not paused yet.
    const recentSwipe=now-(state.lastSwipeAt||0)<3000;
    const directed=recentSwipe && found.cards.includes(state.lastSwipeCard)
      ? visible.filter(e=>e.card!==state.lastSwipeCard &&
          (state.lastSwipeDirection>0
            ? e.index>state.lastSwipeIndex : e.index<state.lastSwipeIndex))
          .filter(e=>e.overlap>=h*0.28)
          .sort((a,b)=>b.overlap-a.overlap)[0]
      : null;
    const chosen=directed ||
      visible.find(e=>e.card===centeredCard) ||
      (playing.length===1 && playing[0].overlap>=h*0.40 ? playing[0] : null) ||
      visible.slice().sort((a,b)=>b.overlap-a.overlap)[0];
    if(!chosen)return;
    const shortCards=visible.some(e=>e.rect.height<h*0.87);
    if(shortCards){
      // The source of the sticky split-screen state is frequently a
      // portrait-sized slide retained in landscape. Repair layout once,
      // then align after the browser has performed that reflow.
      repairSlideHeight(found,h);
      state.lastRepairAt=now;
      const token=++state.restoreToken;
      const card=chosen.card;
      requestAnimationFrame(()=>{
        if(token===state.restoreToken && state.active && !state.gesture){
          const fresh=measure();
          if(fresh)repairOffset(fresh,card);
        }
      });
    }else{
      // Two full-height cards are visible: this is an inherited partial
      // scroll offset rather than the wrong height. Correct it at rest.
      repairOffset(found,chosen.card);
    }
    state.anchorCard=chosen.card;
    state.anchorIndex=chosen.index;
    state.anchorKey=cardKey(chosen.card);
  };
  state.healthCheck=healthCheck;

  const settleOnScrollEnd=()=>{
    if(!enabled || !state.active || state.gesture)return;
    const sinceResize=Date.now()-(state.lastResizeAt||0);
    if(sinceResize<180){
      // A scrollend may arrive while landscape is still measuring its
      // viewport. Retry once after the rotation settles instead of losing it.
      clearTimeout(state.healthTimer);
      state.healthTimer=setTimeout(()=>state.onScrollEnd?.(),180-sinceResize);
      return;
    }
    const found=measure();
    if(!found || found.cards.length<2)return;
    prepareSlides(found);
    const h=viewportHeight(found);
    const visible=inspected(found).filter(e=>e.overlap>h*0.07);
    if(visible.length<2)return;
    const current=centered(found);
    if(!current)return;
    const recent=Date.now()-(state.lastSwipeAt||0)<2000 &&
      found.cards.includes(state.lastSwipeCard);
    const target=recent ? visible.filter(e=>e.card!==state.lastSwipeCard &&
      (state.lastSwipeDirection>0 ? e.index>state.lastSwipeIndex :
        e.index<state.lastSwipeIndex) && e.overlap>=h*0.27)
      .sort((a,b)=>b.overlap-a.overlap)[0] : null;
    const selected=target?.card || current.card;
    if(!found.cards.includes(selected))return;
    if(repairOffset(found,selected)){
      state.anchorCard=selected;
      state.anchorIndex=found.cards.indexOf(selected);
      state.anchorKey=cardKey(selected);
    }
  };
  state.onScrollEnd=()=>{
    if(state.settleRaf)cancelAnimationFrame(state.settleRaf);
    state.settleRaf=requestAnimationFrame(()=>{
      state.settleRaf=0;
      settleOnScrollEnd();
    });
  };
  state.onScroll=prepareNextFrame;
  const attachScroller=found=>{
    const scroll=found.scroller;
    if(state.boundScroller===scroll)return;
    state.scrollObserver?.disconnect();
    if(state.boundScroller){
      state.boundScroller.removeEventListener('scroll',state.scrollHandler);
      state.boundScroller.removeEventListener('scrollend',state.scrollEndHandler);
    }
    state.boundScroller=scroll;
    state.scrollHandler=()=>state.onScroll?.();
    state.scrollEndHandler=()=>state.onScrollEnd?.();
    scroll.addEventListener('scroll',state.scrollHandler,{passive:true});
    scroll.addEventListener('scrollend',state.scrollEndHandler,{passive:true});
    if(typeof MutationObserver!=='undefined'){
      state.scrollObserver=new MutationObserver(()=>prepareNextFrame());
      state.scrollObserver.observe(scroll,{childList:true,subtree:true});
    }
  };
  state.attachScroller=attachScroller;

  // Keep the visible video card, not its old portrait scrollTop, when changing
  // orientation. Otherwise a landscape card height selects another clip.
  const cardKey=card=>card?.querySelector('a[href*="/status/"]')?.getAttribute('href') ||
    card?.querySelector('video')?.currentSrc || '';
  const rememberVisible=()=>{
    const found=measure(), selected=found&&centered(found);
    if(!selected)return;
    state.anchorCard=selected.card;state.anchorIndex=selected.index;
    state.anchorKey=cardKey(selected.card);
  };
  const restoreVisible=(token,attempt=0)=>{
    if(token!==state.restoreToken || state.gesture)return;
    const found=measure();
    if(!found)return;
    let card=found.cards.includes(state.anchorCard)?state.anchorCard:null;
    if(!card && state.anchorKey)
      card=found.cards.find(c=>cardKey(c)===state.anchorKey);
    // Do not fall back to numeric position: X virtualizes and reorders cards,
    // so the same index can point at an unrelated video after a swipe.
    if(!card)return;
    const delta=card.getBoundingClientRect().top-found.box.top;
    // Keep a resize correction narrowly scoped to the original video.
    // X itself remains responsible for normal next/previous swipes.
    if(Math.abs(delta)>8 && Math.abs(delta)<found.box.height*1.5){
      const scroll=found.scroller;
      scroll.scrollTo({top:scroll.scrollTop+delta,behavior:'instant'});
    }
    if(attempt<2)requestAnimationFrame(()=>restoreVisible(token,attempt+1));
  };
  // Orientation events can arrive before the next 950 ms polling cycle.
  // Use the already-saved card immediately, without changing the playing
  // video element, source URL or playback position.
  state.handleResize=()=>{
    state.lastResizeAt=Date.now();
    state.lastHealthTop=null;
    clearTimeout(state.healthTimer);
    clearTimeout(state.timer);
    prepareNextFrame();
    const found=measure();
    if(!found || state.gesture)return;
    // React can replace the card node on rotation, so match the video's
    // stable post/media key as well. Never guess based on array index.
    const present=found.cards.includes(state.anchorCard) ||
      (state.anchorKey && found.cards.some(c=>cardKey(c)===state.anchorKey));
    if(!present)return;
    const token=++state.restoreToken;
    requestAnimationFrame(()=>restoreVisible(token));
  };
  if(!state.resizeInstalled){
    state.resizeInstalled=true;
    window.addEventListener('resize',()=>state.handleResize?.(),{passive:true});
    window.visualViewport?.addEventListener('resize',
      ()=>state.handleResize?.(),{passive:true});
  }
  const width=innerWidth, height=Math.round(window.visualViewport?.height||innerHeight);
  const resized=state.lastViewportWidth>0 &&
    (Math.abs(width-state.lastViewportWidth)>20 ||
     Math.abs(height-state.lastViewportHeight)>20);
  const stamp=()=>{state.lastViewportWidth=width;state.lastViewportHeight=height;};
  if(!enabled){
    state.active=false;
    if(wasActive || resized){
      const token=++state.restoreToken;
      clear();stamp();
      requestAnimationFrame(()=>restoreVisible(token));
    }else{
      if(!state.gesture)rememberVisible();
      stamp();
    }
    return 'disabled';
  }
  state.active=true;
  if(!wasActive){
    state.lastResizeAt=Date.now();
    state.lastHealthTop=null;
  }
  if(!state.anchorCard || !state.anchorCard.isConnected)rememberVisible();
  const token=(resized || !wasActive)?++state.restoreToken:0;
  apply();stamp();
  prepareNextFrame();
  if(token)requestAnimationFrame(()=>restoreVisible(token));
  else if(!state.gesture && Date.now()-(state.lastUserTouch||0)>900)
    rememberVisible();
  if(!state.gesture)healthCheck(false);

  // In landscape X sometimes stops between two immersive cards. Wait for
  // its own momentum to settle before aligning the card that the swipe
  // actually selected. Never resize/reparent a video or modify card heights.
  const overlapHeight=(r,box)=>
    Math.max(0,Math.min(r.bottom,box.bottom,innerHeight)-
      Math.max(r.top,box.top,0));

  const finishSwipe=(gesture,attempt=0)=>{
    if(!state.active || state.gesture || Date.now()-(state.lastResizeAt||0)<400)
      return;
    // Even if the normal snap guard rejects the swipe, a persistent
    // split-screen state must still be detected and repaired at rest.
    clearTimeout(state.healthTimer);
    state.healthTimer=setTimeout(()=>state.healthCheck?.(true),750);
    const found=measure();
    if(!found || gesture.scroller!==found.scroller)return;
    const {scroller,cards,box}=found;
    const viewportHeight=Math.min(box.height,innerHeight);
    if(viewportHeight<140)return;
    const top=scroller.scrollTop;
    // X uses inertia. Only perform one correction after its scroll position
    // has become stable, to avoid a second competing scroll/black video frame.
    if(attempt<5 &&
       (gesture.lastTop===undefined || Math.abs(gesture.lastTop-top)>2)){
      gesture.lastTop=top;
      clearTimeout(state.timer);
      state.timer=setTimeout(()=>finishSwipe(gesture,attempt+1),55);
      return;
    }
    const selected=centered(found);
    if(!selected)return;
    let target=selected;
    const selectedChanged=selected.card!==gesture.fromCard;
    const selectedInDirection=gesture.direction>0
      ? selected.index>gesture.fromIndex
      : selected.index<gesture.fromIndex;
    if(!selectedChanged || !selectedInDirection){
      // X sometimes stops short: the next card is already on screen, but
      // the old one still crosses the center. Only finish a deliberate swipe
      // when the new card occupies at least 30% of the viewport.
      const candidates=cards.map((card,index)=>{
        const rect=card.getBoundingClientRect();
        return {card,index,rect,overlap:overlapHeight(rect,box)};
      }).filter(item=>item.card!==gesture.fromCard &&
        item.overlap>=viewportHeight*0.30 &&
        (gesture.direction>0
          ? item.index>gesture.fromIndex
          : item.index<gesture.fromIndex));
      candidates.sort((a,b)=>b.overlap-a.overlap);
      if(candidates.length &&
         Math.abs(gesture.distance)>=Math.max(55,viewportHeight*0.20))
        target=candidates[0];
      else
        target=selected;
    }
    const targetMoved=target.card!==gesture.fromCard;
    const targetInDirection=gesture.direction>0
      ? target.index>gesture.fromIndex : target.index<gesture.fromIndex;
    if(!targetMoved || !targetInDirection)return;

    const delta=target.rect.top-box.top;
    const visibleNeighbors=cards.some(card=>card!==target.card &&
      overlapHeight(card.getBoundingClientRect(),box)>viewportHeight*0.08);
    // Do not force a scroll when X has already snapped correctly. The range,
    // distance and swipe direction guards prevent jumping to another video.
    const scrollRange=scroller.scrollHeight-scroller.clientHeight;
    const destination=top+delta;
    const correctable=visibleNeighbors &&
      Math.abs(delta)>10 && Math.abs(delta)<viewportHeight*0.72 &&
      (gesture.direction>0 ? delta>0 : delta<0) &&
      scrollRange>viewportHeight*0.35 &&
      destination>=0 && destination<=scrollRange+1;
    if(correctable){
      // Instant, single small adjustment only. No CSS transforms, scroll-snap
      // rewrites, scrolling animation or repeated correction while playing.
      scroller.scrollTo({top:destination,behavior:'instant'});
    }

    // The user-swiped video, not a previous portrait card, is now the anchor.
    state.anchorCard=target.card;
    state.anchorIndex=target.index;
    state.anchorKey=cardKey(target.card);
    window.__mydownloadMediaZoom?.reset?.();
    const newlyActiveCard=target.card;
    clearTimeout(state.playbackTimer);
    state.playbackTimer=setTimeout(()=>{
      if(!state.active || state.gesture)return;
      const still=measure();
      if(!still || centered(still)?.card!==newlyActiveCard)return;
      const video=newlyActiveCard.querySelector('video');
      if(video?.autoplay && video.paused && !video.ended && !video.error &&
          video.readyState>=1 && Date.now()-state.lastUserTouch>500){
        video.play().catch(()=>{});
      }
    },650);
  };

  const startGesture=(x,y)=>{
    if(!state.active || state.gesture)return;
    const found=apply();
    if(!found)return;
    const current=centered(found);
    if(!current)return;
    state.gesture={
      x,y,fromIndex:current.index,fromCard:current.card,
      scroller:found.scroller
    };
    state.lastUserTouch=Date.now();
    state.lastHealthTop=null;
    const beforeSwipe=measure();
    if(beforeSwipe)prepareSlides(beforeSwipe);
    clearTimeout(state.healthTimer);
    clearTimeout(state.timer);
    clearTimeout(state.playbackTimer);
  };
  const completeGesture=(dx,dy)=>{
    const gesture=state.gesture;
    if(!gesture)return;
    state.gesture=null;
    if(Math.abs(dy)<55 || Math.abs(dy)<Math.abs(dx)*1.3)return;
    gesture.direction=dy<0?1:-1;
    gesture.distance=Math.abs(dy);
    state.lastSwipeAt=Date.now();
    state.lastSwipeDirection=gesture.direction;
    state.lastSwipeCard=gesture.fromCard;
    state.lastSwipeIndex=gesture.fromIndex;
    clearTimeout(state.timer);
    // No artificial wait: let scrollend handle normal native snapping.
    state.timer=setTimeout(()=>finishSwipe(gesture),70);
  };
  const cancelGesture=()=>{
    state.gesture=null;
    clearTimeout(state.timer);
  };
  // Android's WebView also forwards a swipe here when X swallows DOM touch
  // events. Both paths share one gesture record and cannot snap twice.
  state.nativeStart=startGesture;
  state.nativeFinish=completeGesture;
  state.nativeCancel=cancelGesture;
  if(!state.listenerInstalled){
    state.listenerInstalled=true;
    document.addEventListener('touchstart',event=>{
      if(!state.active || event.touches.length!==1)return;
      const t=event.touches[0];
      startGesture(t.clientX,t.clientY);
    },{capture:true,passive:true});
    document.addEventListener('touchend',event=>{
      if(event.touches.length!==0 || event.changedTouches.length!==1){
        cancelGesture();return;
      }
      const gesture=state.gesture;
      if(!gesture)return;
      const t=event.changedTouches[0];
      completeGesture(t.clientX-gesture.x,t.clientY-gesture.y);
    },{capture:true,passive:true});
    document.addEventListener('touchcancel',cancelGesture,
      {capture:true,passive:true});
  }
  return 'applied';
})()
""".trimIndent()

private fun xImmersiveFitScript(enabled: Boolean): String =
    X_IMMERSIVE_FIT_SCRIPT.replace("__ENABLED__", enabled.toString())

private val X_VIDEO_DOUBLE_TAP_SEEK_SCRIPT = """
(() => {
  if (!/^(?:www\.)?(?:x\.com|twitter\.com)$/.test(location.hostname)) return;
  if (window.__mydownloadVideoSeek) return;
  window.__mydownloadVideoSeek = true;

  let tapStart = null;
  let lastTap = null;
  const viewer = () => /\/status\/\d+/.test(location.pathname) ||
    !!window.__mydownloadVideoFullscreen?.enabled ||
    !!document.querySelector('#layers [role="dialog"]:has(video,[data-testid="videoPlayer"])');
  const distance = (a,b) => Math.hypot(a.x-b.x,a.y-b.y);
  const validTarget = e => !(e.target instanceof Element &&
    e.target.closest('button,a,input,[role="slider"],[data-testid="playButton"]'));
  const activeVideo = (x,y) => {
    const all = Array.from(document.querySelectorAll('video')).map(video => ({
      video, r: video.getBoundingClientRect()
    })).filter(({r}) => r.width >= 120 && r.height >= 75 &&
      r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth);
    const underTap = all.filter(({r}) => x >= r.left && x <= r.right &&
      y >= r.top && y <= r.bottom);
    const videos = underTap.length ? underTap : all;
    videos.sort((a,b) =>
      Number(!b.video.paused) - Number(!a.video.paused) ||
      b.r.width*b.r.height - a.r.width*a.r.height
    );
    return videos[0]?.video || null;
  };
  const feedback = (side) => {
    const id = 'mydownload-seek-feedback';
    let element = document.getElementById(id);
    if (!element) {
      element = document.createElement('div');
      element.id = id;
      Object.assign(element.style, {
        position:'fixed', top:'36%', zIndex:'2147483647',
        padding:'12px 16px', borderRadius:'28px', pointerEvents:'none',
        color:'white', background:'rgba(0,0,0,0.65)',
        font:'600 19px sans-serif', transition:'opacity 150ms'
      });
      (document.body || document.documentElement).appendChild(element);
    }
    element.style.left = side === 'left' ? '11%' : 'auto';
    element.style.right = side === 'right' ? '11%' : 'auto';
    element.textContent = side === 'left' ? '↶ 10 秒' : '10 秒 ↷';
    element.style.opacity = '1';
    clearTimeout(element.__hideTimer);
    element.__hideTimer = setTimeout(() => {element.style.opacity = '0';}, 650);
  };
  document.addEventListener('touchstart', e => {
    if (e.touches.length !== 1 || !viewer() ||
        !validTarget(e) || window.__mydownloadMediaZoom?.isZoomed?.()) {
      tapStart = null;
      if (e.touches.length > 1) lastTap = null;
      return;
    }
    const t=e.touches[0];
    tapStart={x:t.clientX,y:t.clientY,time:performance.now()};
  }, {capture:true,passive:true});
  document.addEventListener('touchmove', e => {
    if (tapStart && e.touches.length === 1) {
      const t=e.touches[0];
      if (distance(tapStart,{x:t.clientX,y:t.clientY}) > 22) {
        tapStart=null;
        lastTap=null;
      }
    } else tapStart=null;
  }, {capture:true,passive:true});
  document.addEventListener('touchend', e => {
    if (!tapStart || e.touches.length > 0 || e.changedTouches.length !== 1) return;
    const t=e.changedTouches[0], now=performance.now();
    const tap={x:t.clientX,y:t.clientY,time:now};
    const start=tapStart; tapStart=null;
    if (now-start.time>280 || distance(tap,start)>25 || !viewer()) {
      lastTap=null;
      return;
    }
    const side=tap.x < innerWidth*0.4 ? 'left' :
      tap.x > innerWidth*0.6 ? 'right' : '';
    if (!side) {lastTap=null;return;}
    const video=activeVideo(tap.x,tap.y);
    if (!video || !Number.isFinite(video.duration) || video.duration <= 0) {
      lastTap=null;return;
    }
    if (lastTap && lastTap.video===video && lastTap.side===side &&
        now-lastTap.time<350 && distance(tap,lastTap)<75) {
      lastTap=null;
      try {
        const duration=video.duration;
        video.currentTime=Math.min(Math.max(0,video.currentTime+
          (side==='left'?-10:10)),Math.max(0,duration-0.1));
        feedback(side);
        if (e.cancelable) e.preventDefault();
        e.stopImmediatePropagation();
      } catch (ignored) {}
    } else lastTap={...tap,side,video};
  }, {capture:true,passive:false});
  document.addEventListener('touchcancel', () => {
    tapStart=null;lastTap=null;
  }, {capture:true,passive:true});
})()
""".trimIndent()

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
    var browserVideoFullscreen by remember { mutableStateOf(false) }
    val currentDownload by rememberUpdatedState(onDownload)
    val currentDetectedVideos by rememberUpdatedState(onDetectedVideos)
    val currentDownloads by rememberUpdatedState(onDownloads)
    val currentSettings by rememberUpdatedState(onSettings)
    val blockAds by rememberUpdatedState(adBlockEnabled)
    val currentXOptions by rememberUpdatedState(xDisplayOptions)
    var floatingDownloadPosition by remember(context) {
        mutableStateOf(DownloadSettings.floatingDownloadPosition(context))
    }
    // Separate saved locations: rotation must not move the control offscreen.
    var fullscreenPortraitPosition by remember(context) {
        mutableStateOf(DownloadSettings.floatingFullscreenPosition(context, false))
    }
    var fullscreenLandscapePosition by remember(context) {
        mutableStateOf(DownloadSettings.floatingFullscreenPosition(context, true))
    }

    val session = remember(context) { BrowserStore.loadSession(context) }
    val tabs = remember(context) {
        mutableStateListOf<TabViewState>().apply {
            session.tabs.forEach { add(TabViewState(it)) }
        }
    }
    var activeId by remember(context) { mutableStateOf(session.activeId) }
    val selected = tabs.firstOrNull { it.id == activeId } ?: tabs.first()
    // Explicit fullscreen state is per active browser page, not a new tab.
    LaunchedEffect(selected.id) { browserVideoFullscreen = false }
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
        // Avoid WebView's page-level pinch zoom competing with the X media
        // pinch detector. Other websites retain their usual browser zoom.
        web.settings.setSupportZoom(!BrowserVideoDiscovery.isXPage(tab.url))
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
            web.settings.setSupportZoom(!BrowserVideoDiscovery.isXPage(url))
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
                    view?.evaluateJavascript(X_MEDIA_PINCH_ZOOM_SCRIPT, null)
                    view?.evaluateJavascript(X_VIDEO_DOUBLE_TAP_SEEK_SCRIPT, null)
                    if (browserVideoFullscreen) {
                        view?.evaluateJavascript(xVideoFullscreenScript(true), null)
                        view?.evaluateJavascript(xImmersiveFitScript(true), null)
                    }
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

        // Android sees two-finger gestures even when X swallows DOM touch
        // events. Forward incremental scale to the existing in-page media,
        // without reloading the WebView or scaling the whole page.
        val mediaScaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    if (!BrowserVideoDiscovery.isXPage(tab.url)) return false
                    val focusX = (detector.focusX / web.width.coerceAtLeast(1))
                        .coerceIn(0f, 1f)
                    val focusY = (detector.focusY / web.height.coerceAtLeast(1))
                        .coerceIn(0f, 1f)
                    web.evaluateJavascript(X_MEDIA_PINCH_ZOOM_SCRIPT, null)
                    web.evaluateJavascript(
                        "window.__mydownloadMediaZoom?.nativeStart($focusX,$focusY)", null
                    )
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (!BrowserVideoDiscovery.isXPage(tab.url)) return false
                    val factor = detector.scaleFactor.coerceIn(0.5f, 2f)
                    web.evaluateJavascript(
                        "window.__mydownloadMediaZoom?.nativeScale($factor)", null
                    )
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    if (BrowserVideoDiscovery.isXPage(tab.url)) {
                        web.evaluateJavascript(
                            "window.__mydownloadMediaZoom?.nativeEnd()", null
                        )
                    }
                }
            }
        )
        var swipeStartX = 0f
        var swipeStartY = 0f
        var wasMultiTouch = false
        web.setOnTouchListener { _, event ->
            if (BrowserVideoDiscovery.isXPage(tab.url)) {
                mediaScaleDetector.onTouchEvent(event)
                if (browserVideoFullscreen) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            swipeStartX = event.x
                            swipeStartY = event.y
                            wasMultiTouch = false
                            web.evaluateJavascript(
                                "window.__mydownloadImmersiveFit?.nativeStart(" +
                                    "${event.x},${event.y})", null
                            )
                        }
                        MotionEvent.ACTION_POINTER_DOWN -> {
                            wasMultiTouch = true
                            web.evaluateJavascript(
                                "window.__mydownloadImmersiveFit?.nativeCancel()", null
                            )
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!wasMultiTouch) {
                                val dx = event.x - swipeStartX
                                val dy = event.y - swipeStartY
                                if (kotlin.math.abs(dy) > 55f &&
                                    kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.3f
                                ) {
                                    web.evaluateJavascript(
                                        "window.__mydownloadImmersiveFit?.nativeFinish(" +
                                            dx.toString() + "," + dy.toString() + ")", null
                                    )
                                } else {
                                    web.evaluateJavascript(
                                        "window.__mydownloadImmersiveFit?.nativeCancel()", null
                                    )
                                }
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> web.evaluateJavascript(
                            "window.__mydownloadImmersiveFit?.nativeCancel()", null
                        )
                    }
                }
            }
            false  // Existing X single-finger swipes, taps and seek remain native.
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

    fun setVideoFullscreen(enabled: Boolean) {
        if (browserVideoFullscreen == enabled) return
        val web = currentWebView() ?: return
        if (!BrowserVideoDiscovery.isXPage(selected.url)) return
        if (enabled) {
            // Snapshot the current carousel card before changing the viewport.
            web.evaluateJavascript(xImmersiveFitScript(false), null)
            web.evaluateJavascript(xVideoFullscreenScript(true)) { result ->
                if (result == "\"fullscreen\"") browserVideoFullscreen = true
                else Toast.makeText(context, "未偵測到可全螢幕播放的影片", Toast.LENGTH_SHORT).show()
            }
        } else {
            browserVideoFullscreen = false
            web.evaluateJavascript(xImmersiveFitScript(false), null)
            web.evaluateJavascript(xVideoFullscreenScript(false), null)
        }
    }

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

    fun offerDetectedVideo(video: VisibleVideo? = null, strictActive: Boolean = false) {
        val page = selected.url
        if (strictActive && video == null) {
            discoveryMessage = "尚未找到目前正在播放的影片，請播放後再點下載。"
            return
        }
        val videoPostUrl = video?.postUrl.orEmpty()
            .takeIf { BrowserVideoDiscovery.isXPage(it) }
            ?: page
        val title = if (BrowserVideoDiscovery.isXPage(page)) {
            VideoTitle.preferred(
                video?.postText.orEmpty().ifBlank {
                    if (strictActive) "" else videoElements.pageText
                },
                if (strictActive) "" else selected.title,
                videoPostUrl,
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
                fromPlayer, kind, videoPostUrl,
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
            val mediaId = video?.mediaId.orEmpty()
            val associated = if (mediaId.isNotBlank()) discovered.filter {
                MediaCandidateResolver.mediaId(it) == mediaId
            } else emptyList()
            // Strict mode is for the current X video after a swipe. Never use
            // historic resource entries belonging to earlier feed videos.
            val all = when {
                strictActive -> associated
                associated.isNotEmpty() -> associated
                else -> discovered
            }.take(10)
            if (all.isEmpty()) {
                discoveryMessage = if (strictActive)
                    "目前無法確認這部影片的下載來源，為避免下載上一部，請播放幾秒再試。"
                else if (BrowserVideoDiscovery.isXPage(page))
                    "已找到 X 影片播放器，但目前只看到網站內部串流（例如 blob:），沒有可直接下載的 MP4／M3U8。可以播放影片後再試；受 X 下載權限或保護限制的內容無法直接儲存。"
                else "目前網頁沒有提供可直接下載的 MP4／M3U8。請先播放影片再試。"
            } else {
                currentDetectedVideos(all.map { url ->
                    val kind = BrowserVideoDiscovery.kind(url) ?: "mp4"
                    StreamChoice(
                        title, kind.uppercase(), url, kind, videoPostUrl,
                        MediaCandidateResolver.group(url), webUserAgent
                    )
                })
            }
        }
    }

    /**
     * The live DOM is authoritative after a vertical swipe. Do not use
     * videoElements.videos.firstOrNull(): it may still refer to the previous clip.
     */
    fun downloadCurrentXVideo(onResolved: (() -> Unit)? = null) {
        val browser = currentWebView()
        if (browser == null) {
            discoveryMessage = "尚未開啟瀏覽器。"
            return
        }
        val tabId = selected.id
        browser.evaluateJavascript(BrowserVideoDiscovery.ACTIVE_VIDEO_SCAN_SCRIPT) { response ->
            if (tabId != activeId || webViews[tabId] !== browser) return@evaluateJavascript
            val activeVideo = BrowserVideoDiscovery.decode(response).videos.singleOrNull()
            if (activeVideo == null) {
                discoveryMessage = "目前畫面找不到影片，請播放影片後重試。"
                return@evaluateJavascript
            }
            // If the active clip source is still a blob, the media-ID filter
            // inside offerDetectedVideo prevents mixing resources between posts.
            offerDetectedVideo(activeVideo, strictActive = true)
            onResolved?.invoke()
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
                    browser.evaluateJavascript(X_MEDIA_PINCH_ZOOM_SCRIPT, null)
                    browser.evaluateJavascript(X_VIDEO_DOUBLE_TAP_SEEK_SCRIPT, null)
                    // Snapshot the current video in portrait too, before a rotation.
                    browser.evaluateJavascript(xImmersiveFitScript(browserVideoFullscreen), null)
                    // X's own carousel owns the vertical swipe and snap.
                    // Do not call scrollTo/scrollIntoView after the gesture.
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

    // Hide system bars only while playing in fullscreen, restore them on exit.
    DisposableEffect(context, browserVideoFullscreen, fullscreenView) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (browserVideoFullscreen || fullscreenView != null) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else controller?.show(WindowInsetsCompat.Type.systemBars())
        onDispose {
            if (browserVideoFullscreen || fullscreenView != null) {
                controller?.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Keep fullscreen bound to the same WebView across X's SPA route changes.
    // No player CSS/DOM changes or WebView reloads are performed.
    LaunchedEffect(visible, browserVideoFullscreen, selected.id, selected.url) {
        if (visible && BrowserVideoDiscovery.isXPage(selected.url)) {
            currentWebView()?.let { browser ->
                browser.evaluateJavascript(xVideoFullscreenScript(browserVideoFullscreen), null)
                browser.evaluateJavascript(xImmersiveFitScript(browserVideoFullscreen), null)
            }
        }
    }

    BackHandler(visible && (browserVideoFullscreen || fullscreenView != null || selected.canBack)) {
        when {
            fullscreenView != null -> currentWebView()?.webChromeClient?.onHideCustomView()
            browserVideoFullscreen -> setVideoFullscreen(false)
            else -> goBackPreservingScroll()
        }
    }

    Column(modifier.background(BrowserNavy)) {
        // Switching fullscreen only changes layout; AndroidView still owns the
        // same live host/WebView instance and retains playback state.
        if (!browserVideoFullscreen) Row(
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
        if (!browserVideoFullscreen && selected.progress in 1..99) {
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
            // X's native page may omit a fullscreen control, especially in
            // landscape. Keep one available on the video itself in both layouts.
            val xVideoVisible = BrowserVideoDiscovery.isXPage(selected.url) &&
                videoElements.videos.isNotEmpty()
            if (visible && (xVideoVisible || browserVideoFullscreen)) {
                val landscape = viewportWidth > viewportHeight
                MovableFullscreenButton(
                    containerWidth = viewportWidth,
                    containerHeight = viewportHeight,
                    position = if (landscape) fullscreenLandscapePosition
                        else fullscreenPortraitPosition,
                    fullscreen = browserVideoFullscreen,
                    onMove = { moved ->
                        if (landscape) fullscreenLandscapePosition = moved
                        else fullscreenPortraitPosition = moved
                    },
                    onMoveFinished = { saved ->
                        if (landscape) fullscreenLandscapePosition = saved
                        else fullscreenPortraitPosition = saved
                        DownloadSettings.setFloatingFullscreenPosition(context, landscape, saved)
                    },
                    onClick = { setVideoFullscreen(!browserVideoFullscreen) }
                )
            }
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
                        else if (BrowserVideoDiscovery.isXPage(direct)) {
                            downloadCurrentXVideo()
                        } else offerDetectedVideo(videoElements.videos.firstOrNull())
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
                    // X/HTML5 native fullscreen keeps an explicit exit action;
                    // the device back button remains available as well.
                    val landscape = fullscreenWidth > fullscreenHeight
                    MovableFullscreenButton(
                        containerWidth = fullscreenWidth,
                        containerHeight = fullscreenHeight,
                        position = if (landscape) fullscreenLandscapePosition
                            else fullscreenPortraitPosition,
                        fullscreen = true,
                        onMove = { moved ->
                            if (landscape) fullscreenLandscapePosition = moved
                            else fullscreenPortraitPosition = moved
                        },
                        onMoveFinished = { saved ->
                            if (landscape) fullscreenLandscapePosition = saved
                            else fullscreenPortraitPosition = saved
                            DownloadSettings.setFloatingFullscreenPosition(context, landscape, saved)
                        },
                        onClick = { currentWebView()?.webChromeClient?.onHideCustomView() }
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
                                downloadCurrentXVideo {
                                    currentWebView()?.webChromeClient?.onHideCustomView()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
