package com.jimmy.mydownload

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.view.ViewTreeObserver
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
import androidx.compose.material.icons.filled.CollectionsBookmark
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
    /** Back from a media viewer: X restores its own list scroll; only fix a reset-to-top. */
    var restoreGentle: Boolean = false
    /** Set on a tab opened from an X list: closing it returns to that live list tab. */
    var parentTabId: String? = null
    var autoEnterStarted: Boolean = false
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
    // Never hide an analytics parent. On X mobile that parent may also
    // contain Like and Bookmark in the same compact action group.
    '[data-testid="tweet"] a[href$="/analytics"]'
  );
  if (flags[3]) selectors.push(
    '[data-testid="share"]:not(:has([data-testid="like"],' +
    '[data-testid="unlike"],[data-testid*="bookmark" i]))'
  );
  if (flags[4]) selectors.push(
    // A generic BottomBar can also be X's immersive video action toolbar.
    // Only hide actual HOME/notifications navigation, never Like/Bookmark.
    '#layers nav:has(a[href="/home"]):has(a[href^="/notifications"])',
    '[data-testid="BottomBar"]:has(a[href="/home"],' +
      'a[data-testid="AppTabBar_Home_Link"])',
    'nav[aria-label="Bottom navigation"]:has(a[href="/home"])'
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
    '#layers [role="dialog"]:has([data-testid="tweetPhoto"],video) ' +
    '[data-testid="share"]:not(:has([data-testid="like"],' +
    '[data-testid="unlike"],[data-testid*="bookmark" i]))'
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

  // The full-screen / vertical X viewer uses its own bottom action strip.
  // Keep these two opt-in switches independent of the ordinary tweet controls.
  // Only the individual view-count and share actions are marked, never a
  // player/container node, video, seek bar, or gesture surface.
  const viewerViewAttribute = 'data-mydownload-hide-viewer-views';
  const viewerShareAttribute = 'data-mydownload-hide-viewer-share';
  const nextViewNodes = new Set();
  const nextShareNodes = new Set();
  const viewerActionScope = immersiveRoot || modalRoot;
  const viewerParent = viewerActionScope?.parentElement;
  const videoActionRoot = visibleVideos.length ?
    visibleVideos[0].closest('article[data-testid="tweet"]') : null;
  const actionIsInViewer = el => {
    if (viewerActionScope?.contains(el)) return true;
    if (viewerParent && viewerParent !== document.body &&
        viewerParent !== document.documentElement &&
        viewerParent.contains(el)) return true;
    if (modalRoot && el.closest('#layers')) return true;
    if (mediaRoute && videoActionRoot?.contains(el)) return true;
    // X sometimes renders its action strip in a separate portal. Its
    // fullscreen viewer must still be open and the control near the bottom.
    return !!(immersiveRoot && el.closest('#layers'));
  };
  // Like, Unlike and Bookmark must NEVER be hidden as a side effect of
  // hiding view counts or Share, even when X nests several actions together.
  const preservedActions =
    '[data-testid="like"],[data-testid="unlike"],' +
    '[data-testid*="bookmark" i],[aria-label*="bookmark" i],' +
    '[aria-label*="like" i],[aria-label*="unlike" i],' +
    '[aria-label*="收藏"],[aria-label*="書籤"],' +
    '[aria-label*="喜歡"],[aria-label*="讚"]';
  const markViewerAction = (candidate, attribute, next) => {
    const el = candidate.closest('button,[role="button"],a[href]') || candidate;
    if (el.closest('nav,[role="navigation"]') ||
        el.matches(preservedActions) || el.querySelector(preservedActions) ||
        el.querySelector('video') ||
        el.querySelector('[data-testid="videoPlayer"]')) return;
    const r = el.getBoundingClientRect();
    if (r.width < 12 || r.height < 10 ||
        r.width > Math.max(210,innerWidth*0.58) || r.height > 88 ||
        r.top < innerHeight*0.63 || r.bottom > innerHeight+12 ||
        r.left < -8 || r.right > innerWidth+12) return;
    if (!actionIsInViewer(el)) return;
    el.setAttribute(attribute,'1');
    next.add(el);
  };
  if (playingViewer && (flags[16] || flags[17])) {
    if (flags[16]) {
      document.querySelectorAll(
        '[data-testid="impressions"],[data-testid="analyticsButton"],' +
        '[data-testid="analytics"],[data-testid="viewCount"],' +
        'a[href$="/analytics"],a[href*="/analytics?"],' +
        '[aria-label*="views" i],[aria-label*="觀看次數"],' +
        '[aria-label*="次觀看"],[aria-label*="瀏覽次數"],' +
        '[title*="views" i]'
      ).forEach(el=>markViewerAction(el,viewerViewAttribute,nextViewNodes));
    }
    if (flags[17]) {
      document.querySelectorAll(
        '[data-testid="share"],[data-testid*="shareButton" i],' +
        'button[aria-label*="share" i],button[aria-label*="分享"],' +
        '[role="button"][aria-label*="share" i],' +
        '[role="button"][aria-label*="分享"],' +
        'svg[data-testid*="share" i]'
      ).forEach(el=>markViewerAction(el,viewerShareAttribute,nextShareNodes));
    }
  }
  for (const el of document.querySelectorAll(
    '['+viewerViewAttribute+'],['+viewerShareAttribute+']'
  )) {
    if (!nextViewNodes.has(el)) el.removeAttribute(viewerViewAttribute);
    if (!nextShareNodes.has(el)) el.removeAttribute(viewerShareAttribute);
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
    // X reuses [lang] and [dir=auto] in action groups too. Target only
    // non-interactive text leaves, never parent rows containing actions.
    playbackScope+' [data-testid^="immersive-tweet-ui-content-container"] ' +
      'span[lang]:not(:has(button,a,[role="button"],[role="group"]))',
    playbackScope+' [data-testid^="immersive-tweet-ui-content-container"] ' +
      'span[dir="auto"]:not(:has(button,a,[role="button"],[role="group"]))'
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
      ' { display: none !important; } }' : '') +
    '\n['+viewerViewAttribute+'="1"],['+viewerShareAttribute+'="1"]' +
    ' { visibility: hidden !important; pointer-events: none !important; }';
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
        options.hideVideoBelowProgressDetails,
        options.hideViewerViews, options.hideViewerShare
    ).joinToString(",") { it.toString() }
    // Optional seekbar options are separate from the X cosmetic CSS.
    // Neither switch is enabled by default. No video DOM reparenting.
    return X_DISPLAY_SCRIPT.replace("__FLAGS__", flags) +
        ";\n" + xVideoProgressScript(options) +
        ";\n" + xVideoNativeActionsScript(options)
}

/**
 * Only show/hide the REAL X Like and Bookmark controls. Never create another
 * button or proxy a click: X must own all actions, colors and saved states.
 * Reapplies safe visibility to the active native controls after React swaps
 * video cards without changing X's carousel, footer or video DOM.
 */
private val X_VIDEO_NATIVE_ACTION_VISIBILITY_SCRIPT = """
(() => {
  if(!/^(?:www\.)?(?:x\.com|twitter\.com)$/.test(location.hostname))
    return 'not-x';
  const likeAttr='data-mydownload-native-like';
  const bookmarkAttr='data-mydownload-native-bookmark';
  const keepAttr='data-mydownload-native-actions-keep-visible';
  const keepDisplayAttr='data-mydownload-native-actions-keep-display';
  const styleId='mydownload-x-native-actions-css';
  const state=window.__mydownloadXNativeVisibility ||
    (window.__mydownloadXNativeVisibility={
      savedDisplays:new WeakMap(),orientation:null,rotatedAt:0,
      lastVerified:0,scheduled:false,observer:null,listenerInstalled:false,
      timerIds:[],refresh:null
    });
  state.showLike=__SHOW_LIKE__;
  state.showBookmark=__SHOW_BOOKMARK__;

  // Never create replacement buttons. Remove v244's ineffective proxies.
  const replica=window.__mydownloadXActionButtons;
  if(replica){
    replica.refresh=()=>{};
    replica.root?.remove();
    replica.root=null;
    for(const el of replica.marks||[])
      el?.removeAttribute?.('data-mydownload-x-action-native');
    replica.marks?.clear?.();
  }
  document.getElementById('mydownload-x-video-actions')?.remove();
  document.getElementById('mydownload-x-video-actions-css')?.remove();
  document.getElementById('mydownload-x-action-hint')?.remove();
  for(const el of document.querySelectorAll(
    '[data-mydownload-x-action-native]'))
    el.removeAttribute('data-mydownload-x-action-native');

  let sheet=document.getElementById(styleId);
  if(!sheet){
    sheet=document.createElement('style');
    sheet.id=styleId;
    (document.head||document.documentElement).appendChild(sheet);
  }
  // The earlier layout forced flex on every ancestor. After rotating X may
  // rebuild those rows as grid/contents, so a blanket flex rule breaks them.
  // Only a genuinely display:none parent receives the display override.
  const css='['+likeAttr+'="hide"],['+bookmarkAttr+'="hide"]{'+
    'display:none!important;pointer-events:none!important;}' +
    '['+likeAttr+'="show"],['+bookmarkAttr+'="show"]{'+
    'display:var(--mydownload-x-original-display,inline-flex)!important;'+
    'visibility:visible!important;opacity:1!important;'+
    'pointer-events:auto!important;}' +
    '['+keepAttr+'="1"]{visibility:visible!important;'+
    'opacity:1!important;pointer-events:auto!important;}' +
    '['+keepDisplayAttr+'="1"]{'+
    'display:var(--mydownload-x-original-display,flex)!important;}';
  if(sheet.textContent!==css)sheet.textContent=css;

  const clearAll=()=>{
    document.querySelectorAll('['+likeAttr+'],['+bookmarkAttr+'],'+
      '['+keepAttr+'],['+keepDisplayAttr+']').forEach(el=>{
      el.removeAttribute(likeAttr);
      el.removeAttribute(bookmarkAttr);
      el.removeAttribute(keepAttr);
      el.removeAttribute(keepDisplayAttr);
      el.style.removeProperty('--mydownload-x-original-display');
    });
  };
  const orientation=()=>innerWidth>innerHeight;
  const isVisibleGeometry=(el)=>{
    if(!el || el.closest('[aria-hidden="true"],[inert]'))return false;
    const r=el.getBoundingClientRect();
    if(r.width<120||r.height<65||r.right<=0||r.bottom<=0||
       r.left>=innerWidth||r.top>=innerHeight)return false;
    const css=getComputedStyle(el);
    // Opacity is deliberately NOT checked here. During X's rotation fade,
    // a visible open viewer may temporarily be opacity:0.
    return css.display!=='none'&&css.visibility!=='hidden';
  };
  const viewerRoots=()=>Array.from(document.querySelectorAll(
    '[data-testid="vss-scroll-view"],[data-testid="immersive-viewer"],' +
    '#layers [role="dialog"]:has(video,[data-testid="videoPlayer"])'
  )).filter(el=>{
    if(!isVisibleGeometry(el))return false;
    const r=el.getBoundingClientRect();
    return r.width>=innerWidth*.48&&r.height>=innerHeight*.60&&
      r.top<innerHeight*.30&&r.bottom>innerHeight*.72;
  });
  const videoScore=video=>{
    if(!isVisibleGeometry(video))return -Infinity;
    const r=video.getBoundingClientRect();
    const w=Math.max(0,Math.min(r.right,innerWidth)-Math.max(r.left,0));
    const h=Math.max(0,Math.min(r.bottom,innerHeight)-Math.max(r.top,0));
    return w*h-Math.abs((r.top+r.bottom)/2-innerHeight/2)*400+
      (video.paused?0:100000);
  };
  const activeVideo=scope=>Array.from(scope.querySelectorAll('video'))
    .sort((a,b)=>videoScore(b)-videoScore(a))
    .find(v=>videoScore(v)>0)||null;
  const candidateViewer=()=>{
    for(const root of viewerRoots()){
      const video=activeVideo(root);
      if(!video)continue;
      // X may keep an ordinary feed rooted inside the same DOM container.
      let visiblePosts=0;
      for(const article of root.querySelectorAll('article[data-testid="tweet"]')){
        if(isVisibleGeometry(article) && ++visiblePosts>=2)break;
      }
      if(visiblePosts>=2)continue;
      return {root,video};
    }
    return null;
  };
  const likeSelector=[
    '[data-testid="like"]','[data-testid="unlike"]',
    'button[aria-label="Like" i]','button[aria-label="Unlike" i]',
    '[role="button"][aria-label="Like" i]',
    '[role="button"][aria-label="Unlike" i]',
    '[aria-label="喜歡"]','[aria-label="取消喜歡"]',
    '[aria-label="讚"]','[aria-label="收回讚"]'
  ].join(',');
  const bookmarkSelector=[
    '[data-testid="bookmark"]','[data-testid="removeBookmark"]',
    '[data-testid="unbookmark"]','[data-testid="bookmarkButton"]',
    'button[aria-label="Bookmark" i]',
    'button[aria-label="Remove Bookmark" i]',
    '[role="button"][aria-label="Bookmark" i]',
    '[role="button"][aria-label="Remove Bookmark" i]',
    '[aria-label="加入書籤"]','[aria-label="移除書籤"]',
    '[aria-label="收藏"]','[aria-label="取消收藏"]'
  ].join(',');
  const saveDisplay=(el,fallback)=>{
    if(!el.style)return;
    const display=getComputedStyle(el).display;
    const marked=el.hasAttribute(likeAttr)||el.hasAttribute(bookmarkAttr)||
      el.hasAttribute(keepDisplayAttr);
    if(display!=='none' && !marked)
      state.savedDisplays.set(el,display);
    const original=state.savedDisplays.get(el)||fallback;
    if(el.style.getPropertyValue('--mydownload-x-original-display')!==original)
      el.style.setProperty('--mydownload-x-original-display',original);
  };
  const findButtons=(selector,root,article)=>{
    const found=new Set();
    for(const child of document.querySelectorAll(selector)){
      const el=child.closest('button,[role="button"],a[href]')||
        (child.matches('button,[role="button"]')?child:null);
      if(!el||!el.isConnected||found.has(el)||
         el.closest('#mydownload-optional-x-progress')||
         el.closest('nav:has(a[href="/home"]),' +
           '[role="navigation"]:has(a[href="/home"])')||
         el.querySelector('video,[data-testid="videoPlayer"]')||
         el.querySelectorAll('button,[role="button"],a[href]').length>1||
         el.closest('[role="menu"],[role="menuitem"]'))continue;
      const owner=el.closest('article[data-testid="tweet"]');
      if(article&&owner&&owner!==article)continue;
      if(!root&&(!article||owner!==article))continue;
      if(root&&!root.contains(el)){
        // Allow native X portal actions, but not an adjacent feed post.
        if(owner||!el.closest('#layers'))continue;
        const r=el.getBoundingClientRect();
        if(r.bottom<innerHeight*.30||r.top>innerHeight)continue;
      }
      found.add(el);
    }
    return found;
  };
  const scan=()=>{
    const physicalOrientation=orientation();
    const rotated=state.orientation!==null&&
      state.orientation!==physicalOrientation;
    state.orientation=physicalOrientation;
    if(rotated){
      // Do NOT clear the markers: the CSS keeps the real Like/Bookmark visible
      // through X's rebuild. Wiping them left the buttons hidden until the new
      // layout was rediscovered. Only forget stale per-layout display values.
      state.savedDisplays=new WeakMap();
      state.rotatedAt=Date.now();
    }

    const nativeVerified=__DEDICATED_VIEWER__;
    if(nativeVerified){
      state.lastVerified=Date.now();
      state.verifiedHref=location.href;
    }
    const candidate=candidateViewer();
    // React briefly fades/remounts its immersive root on rotation, and X also
    // fades its whole control layer (opacity:0) when idle, together with the
    // seek bar. The strict viewer check rejects opacity:0, so without a
    // tolerance every marker (and Like/Bookmark) was cleared on each fade.
    // While the geometry-only candidate still exists, stay in the same viewer
    // (same URL) or within a generous grace window.
    const sinceVerified=Date.now()-Math.max(state.rotatedAt,state.lastVerified);
    const rotationGrace=sinceVerified<8000 ||
      (state.verifiedHref===location.href && sinceVerified<10*60*1000);
    if(!nativeVerified && !(candidate&&rotationGrace)){
      clearAll();
      return 'not-viewer';
    }
    if(!candidate){
      // Native X has not mounted the next card yet. Keep current markers;
      // the resize retries / DOM observer will discover the new card.
      return 'waiting-for-native-card';
    }
    const root=candidate.root;
    const video=candidate.video;
    const article=video.closest('article[data-testid="tweet"]');
    const likes=findButtons(likeSelector,root,article);
    const bookmarks=findButtons(bookmarkSelector,root,article);
    for(const el of likes)if(bookmarks.has(el)){
      likes.delete(el);bookmarks.delete(el);
    }
    const previouslyMarked=Array.from(document.querySelectorAll(
      '['+likeAttr+'],['+bookmarkAttr+']'));
    const previouslyKept=Array.from(document.querySelectorAll(
      '['+keepAttr+'],['+keepDisplayAttr+']'));
    for(const el of previouslyMarked){
      if(!likes.has(el))el.removeAttribute(likeAttr);
      if(!bookmarks.has(el))el.removeAttribute(bookmarkAttr);
      if(!likes.has(el)&&!bookmarks.has(el))
        el.style.removeProperty('--mydownload-x-original-display');
    }
    const apply=(elements,attr,enabled)=>{
      for(const el of elements){
        if(enabled)saveDisplay(el,'inline-flex');
        else el.style.removeProperty('--mydownload-x-original-display');
        if(el.getAttribute(attr)!==(enabled?'show':'hide'))
          el.setAttribute(attr,enabled?'show':'hide');
      }
    };
    apply(likes,likeAttr,state.showLike);
    apply(bookmarks,bookmarkAttr,state.showBookmark);

    // An invisible ancestor hides the child even with opacity:1 on that
    // child. Preserve only compact native action wrappers, never the
    // carousel root, video surface or whole tweet.
    const kept=new Set(), keptDisplay=new Set();
    const keepParents=button=>{
      for(let el=button.parentElement,depth=0;
          el&&depth<12;el=el.parentElement,depth++){
        if(el===document.body||el===document.documentElement||
           el===root||el===article||el.querySelector('video,canvas'))break;
        const r=el.getBoundingClientRect();
        if(r.height>innerHeight*.65&&r.width>innerWidth*.65)break;
        kept.add(el);
        const display=getComputedStyle(el).display;
        if(display==='none'||el.hasAttribute(keepDisplayAttr)){
          saveDisplay(el,el.tagName==='SPAN'?'inline-flex':'flex');
          keptDisplay.add(el);
        }else if(!el.hasAttribute(keepAttr)){
          state.savedDisplays.set(el,display);
        }
      }
    };
    if(state.showLike)for(const el of likes)keepParents(el);
    if(state.showBookmark)for(const el of bookmarks)keepParents(el);
    for(const el of previouslyKept){
      if(!kept.has(el)){
        el.removeAttribute(keepAttr);
        el.removeAttribute(keepDisplayAttr);
        el.style.removeProperty('--mydownload-x-original-display');
      }else if(!keptDisplay.has(el)){
        el.removeAttribute(keepDisplayAttr);
        el.style.removeProperty('--mydownload-x-original-display');
      }
    }
    for(const el of kept){
      if(!el.hasAttribute(keepAttr))el.setAttribute(keepAttr,'1');
      if(keptDisplay.has(el) && !el.hasAttribute(keepDisplayAttr))
        el.setAttribute(keepDisplayAttr,'1');
    }
    return 'native-actions-protected:'+likes.size+'/'+bookmarks.size;
  };
  // Every 950ms WebView refresh replaces this function with current
  // preferences. Observers/listeners always call the newest scan closure.
  state.refresh=scan;
  const schedule=()=>{
    if(state.scheduled)return;
    state.scheduled=true;
    // The carousel rewrites style/class on every animation frame while the
    // user swipes between posts. Scanning each frame (querySelectorAll +
    // getComputedStyle) made swiping stutter, so run at most every ~140ms.
    const wait=Math.max(0,140-(performance.now()-(state.lastScanAt||0)));
    const run=()=>requestAnimationFrame(()=>{
      state.scheduled=false;
      state.lastScanAt=performance.now();
      state.refresh?.();
    });
    if(wait>0)setTimeout(run,wait);else run();
  };
  state.schedule=schedule;
  const postRotation=()=>{
    for(const id of state.timerIds)clearTimeout(id);
    state.timerIds=[];
    for(const ms of [80,220,480,900,1600,2800,4500]){
      state.timerIds.push(setTimeout(()=>state.schedule?.(),ms));
    }
  };
  if(!state.listenerInstalled){
    state.listenerInstalled=true;
    const changed=()=>{state.schedule?.();postRotation();};
    window.addEventListener('orientationchange',changed,{passive:true});
    window.addEventListener('resize',changed,{passive:true});
  }
  if(!state.observer){
    state.observer=new MutationObserver(mutations=>{
      // Attribute changes (class/style) drive X's fade; childList changes
      // indicate React replaced a clip or footer after orientation changes.
      if(mutations.some(m=>
        m.type==='childList' ? m.addedNodes.length||m.removedNodes.length :
        ['class','style','aria-hidden','inert'].includes(m.attributeName)))
        state.schedule?.();
    });
    state.observer.observe(document.body||document.documentElement,{
      subtree:true,childList:true,attributes:true,
      attributeFilter:['class','style','aria-hidden','inert']
    });
  }
  return scan();
})()
""".trimIndent()

private fun xVideoNativeActionsScript(options: XDisplayOptions): String =
    X_VIDEO_NATIVE_ACTION_VISIBILITY_SCRIPT
        .replace("__SHOW_LIKE__", options.showViewerLike.toString())
        .replace("__SHOW_BOOKMARK__", options.showViewerBookmark.toString())
        .replace("__DEDICATED_VIEWER__", X_DEDICATED_X_VIDEO_VIEWER_SCRIPT)

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
 * Lightweight landscape fix. X still owns touch handling, video playback and
 * the scroll animation. Only the carousel's outer slide sizing and CSS snap
 * are adjusted, at mount/resize rather than at each swipe.
 */
private val X_LANDSCAPE_CAROUSEL_SCRIPT = """
(() => {
  if (!/^(?:www\.)?(?:x\.com|twitter\.com)$/.test(location.hostname))
    return 'not-x';

  const state=window.__mydownloadLandscapeCarousel ||
    (window.__mydownloadLandscapeCarousel={});
  const trackAttr='data-mydownload-landscape-track';
  const scrollAttr='data-mydownload-landscape-scroll';
  const styleId='mydownload-landscape-track-css';

  // The rotation-only implementation removed the original full-height
  // carousel snap. Its replacement required 2 visible media cards, while X
  // often virtualizes to just one. That is why the next post can remain
  // partially above the current video after a landscape swipe.
  if(state.repairVersion!==2){
    for(const id of ['mydownload-landscape-fit-css',
      'mydownload-landscape-track-css'])document.getElementById(id)?.remove();
    state.fitTrack?.removeAttribute('data-mydownload-landscape-fit');
    document.querySelectorAll('[data-mydownload-landscape-fit],'+
      '[data-mydownload-landscape-track],'+
      '[data-mydownload-landscape-scroll]').forEach(el=>{
        el.removeAttribute('data-mydownload-landscape-fit');
        el.removeAttribute('data-mydownload-landscape-track');
        el.removeAttribute('data-mydownload-landscape-scroll');
      });
    for(const timer of state.restoreTimers||[])clearTimeout(timer);
    clearTimeout(state.rotationTimer);
    state.track=null;
    state.scroll=null;
    state.height=0;
    state.repairVersion=2;
  }
  // Remove pre-native-carousel experimental styles and scroll handlers.
  if(!window.__mydownloadNativeCarouselCleaned){
    const old=window.__mydownloadImmersiveFit;
    if(old){
      old.active=false;
      old.gesture=null;
      old.restoreToken=(old.restoreToken||0)+1;
      clearTimeout(old.timer);
      clearTimeout(old.playbackTimer);
      clearTimeout(old.healthTimer);
      old.scrollObserver?.disconnect?.();
      if(old.prepareRaf)cancelAnimationFrame(old.prepareRaf);
      if(old.settleRaf)cancelAnimationFrame(old.settleRaf);
      if(old.boundScroller){
        old.boundScroller.removeEventListener('scroll',old.scrollHandler);
        old.boundScroller.removeEventListener('scrollend',old.scrollEndHandler);
        old.boundScroller=null;
      }
      old.handleResize=()=>{};
      old.onScroll=()=>{};
      old.onScrollEnd=()=>{};
      old.healthCheck=()=>{};
      old.prepareSlides=()=>{};
      old.attachScroller=()=>{};
      old.nativeStart=()=>{};
      old.nativeFinish=()=>{};
      old.nativeCancel=()=>{};
    }
    document.getElementById('mydownload-immersive-layout-repair')?.remove();
    document.getElementById('mydownload-fullscreen-card-size')?.remove();
    document.querySelectorAll('[data-mydownload-immersive-slide],'+
      '[data-mydownload-fullscreen-card],'+
      '[data-mydownload-fullscreen-scroll]').forEach(el=>{
      el.removeAttribute('data-mydownload-immersive-slide');
      el.removeAttribute('data-mydownload-fullscreen-card');
      el.removeAttribute('data-mydownload-fullscreen-scroll');
    });
    window.__mydownloadNativeCarouselCleaned=true;
  }

  const landscape=()=>innerWidth>innerHeight*1.12;
  const clear=()=>{
    state.track?.removeAttribute(trackAttr);
    state.scroll?.removeAttribute(scrollAttr);
    document.getElementById(styleId)?.remove();
    state.track=null;
    state.scroll=null;
    state.height=0;
  };
  const layout=()=>{
    const scroll=document.querySelector('[data-testid="vss-scroll-view"]');
    if(!scroll||scroll.closest('[aria-hidden="true"],[inert]'))return null;
    const rect=scroll.getBoundingClientRect();
    if(rect.width<120||rect.height<150||rect.right<=0||rect.bottom<=0||
       rect.left>=innerWidth||rect.top>=innerHeight)return null;
    const css=getComputedStyle(scroll);
    if(css.display==='none'||css.visibility==='hidden')return null;
    // Find the parent of FULL carousel slides. This supports X's one-card
    // virtualization as well as multiple visible tweet/video cards.
    let track=scroll.firstElementChild;
    let cards=0;
    for(let depth=0;track&&depth<7;depth++){
      const children=Array.from(track.children).filter(el=>el instanceof HTMLElement);
      if(!children.length)return null;
      const media=children.filter(el=>el.querySelector(
        'video,[data-testid^="immersive-tweet-ui-content-container"]'));
      if(media.length>1 || (media.length===1&&children.length>1)){
        cards=media.length;break;
      }
      if(media.length===1&&children.length===1&&
         media[0].querySelectorAll('video').length<=1){
        // X only mounted the active card. Style its outer container rather
        // than refusing to repair the carousel, as recent versions did.
        cards=1;break;
      }
      if(children.length!==1)return null;
      track=children[0];
    }
    if(!track||!cards)return null;
    const height=Math.round(Math.min(rect.height||scroll.clientHeight,innerHeight));
    return height>=150?{scroll,track,height}:null;
  };
  const activeCard=geometry=>{
    if(!geometry)return null;
    const {scroll,track}=geometry,rr=scroll.getBoundingClientRect();
    let best=null,score=-Infinity;
    for(const video of track.querySelectorAll('video')){
      const r=video.getBoundingClientRect();
      const w=Math.max(0,Math.min(r.right,rr.right,innerWidth)-
        Math.max(r.left,rr.left,0));
      const h=Math.max(0,Math.min(r.bottom,rr.bottom,innerHeight)-
        Math.max(r.top,rr.top,0));
      if(w<100||h<50)continue;
      const rank=w*h-Math.abs((r.top+r.bottom-rr.top-rr.bottom)/2)*500+
        (video.paused?0:20000);
      if(rank>score){
        let card=video;
        while(card&&card.parentElement!==track)card=card.parentElement;
        if(card&&card!==track){
          score=rank;
          // Identity (post id) and position (in viewport heights) let a
          // rotation restore the SAME clip even if X rebuilds its card DOM.
          const link=card.querySelector('a[href*="/status/"]');
          const idMatch=/\/status\/(\d+)/.exec(link?.getAttribute('href')||'');
          best={card,video,source:video.currentSrc||video.src||'',
            id:idMatch?idMatch[1]:'',
            ratio:scroll.scrollTop/Math.max(1,scroll.clientHeight)};
        }
      }
    }
    return best;
  };
  const apply=geometry=>{
    if(!geometry){clear();return false;}
    const {scroll,track,height}=geometry;
    if(state.track!==track||state.scroll!==scroll){
      clear();
      state.track=track;
      state.scroll=scroll;
      track.setAttribute(trackAttr,'1');
      scroll.setAttribute(scrollAttr,'1');
    }
    if(state.height===height&&document.getElementById(styleId))return false;
    // Restore the known-working 2026-11-07 outer-card sizing and snap:
    // no video reparenting, direct playback control or swipe interception.
    // One stylesheet per track/size change, never per 950ms DOM scan.
    const css='['+trackAttr+'="1"] > * {'+
      'min-height:'+height+'px !important;'+
      'scroll-snap-align:start !important;box-sizing:border-box !important;'+
      '} ['+scrollAttr+'="1"] {'+
      'scroll-snap-type:y mandatory !important;'+
      'scroll-padding-top:0 !important;}';
    let sheet=document.getElementById(styleId);
    if(!sheet){
      sheet=document.createElement('style');
      sheet.id=styleId;
      (document.head||document.documentElement).appendChild(sheet);
    }
    sheet.textContent=css;
    state.height=height;
    return true;
  };
  const restoreRotatedCard=anchor=>{
    if(!anchor)return;
    const geometry=layout();
    if(!geometry)return;
    const {scroll}=geometry;
    if(scroll.scrollHeight<=scroll.clientHeight+2)return;
    const rr=scroll.getBoundingClientRect();
    // 1) the same card element, 2) the same post id, 3) the same position
    // measured in viewport heights. Rotation changes the viewport height, and
    // X keeps the old pixel scrollTop, which is how playback skipped ahead
    // to the 2nd/3rd next clip.
    let card=anchor.card?.isConnected&&geometry.track.contains(anchor.card)
      ? anchor.card : null;
    if(!card&&anchor.id){
      for(const child of geometry.track.children){
        if(child.querySelector('a[href*="/status/'+anchor.id+'"]')){card=child;break;}
      }
    }
    let delta;
    if(card)delta=card.getBoundingClientRect().top-rr.top;
    else if(Number.isFinite(anchor.ratio))
      delta=Math.round(anchor.ratio)*scroll.clientHeight-scroll.scrollTop;
    else return;
    // Rotation can leave the list several cards away (e.g. two clips ahead),
    // so allow a large correction; only ignore sub-pixel noise.
    if(Math.abs(delta)<18||Math.abs(delta)>Math.max(rr.height,innerHeight)*12)return;
    // Rotation only. Never snap manually after a native user swipe.
    const oldSnap=scroll.style.scrollSnapType,oldBehavior=scroll.style.scrollBehavior;
    scroll.style.scrollSnapType='none';
    scroll.style.scrollBehavior='auto';
    scroll.scrollTop+=delta;
    requestAnimationFrame(()=>{
      scroll.style.scrollSnapType=oldSnap;
      scroll.style.scrollBehavior=oldBehavior;
    });
  };
  const scheduleRotation=()=>{
    const anchor=state.anchor;
    clearTimeout(state.rotationTimer);
    for(const t of state.restoreTimers||[])clearTimeout(t);
    state.restoreTimers=[];
    state.rotating=true;
    const token=++state.rotationToken;
    // Wait for X/Android to settle the WebView resize before changing card
    // heights; doing so mid-rotation caused the older "skip two clips" bug.
    state.rotationTimer=setTimeout(()=>{
      if(token!==state.rotationToken)return;
      state.rotating=false;
      const geometry=layout();
      if(state.enabled&&landscape())apply(geometry);
      // Return to the clip that was playing, in BOTH directions and whether
      // or not in-app fullscreen is on. X keeps the old pixel scrollTop after
      // the resize, which landed two clips ahead. Retry as X's layout settles.
      if(anchor){
        for(const delay of [0,420,1000,1900,3200]){
          state.restoreTimers.push(setTimeout(()=>{
            if(token!==state.rotationToken||state.touching)return;
            requestAnimationFrame(()=>restoreRotatedCard(anchor));
          },delay));
        }
      }
      state.anchor=anchor||activeCard(geometry);
    },240);
  };
  const refresh=()=>{
    const nowLandscape=landscape();
    const didRotate=state.orientation!==undefined&&
      state.orientation!==nowLandscape;
    state.orientation=nowLandscape;
    if(didRotate)scheduleRotation();
    if(!state.enabled||!nowLandscape){
      if(state.track)clear();
      if(!nowLandscape)state.anchor=activeCard(layout());
      return 'native';
    }
    if(state.rotating)return 'rotation-pending';
    const geometry=layout();
    if(!geometry)return 'no-cards';
    if(!state.touching)apply(geometry);
    if(!state.touching)state.anchor=activeCard(geometry);
    return 'landscape-snapped';
  };

  state.enabled=__ENABLED__;
  if(!state.listenerInstalledV2){
    state.listenerInstalledV2=true;
    state.rotationToken=0;
    state.touching=false;
    // Keep the remembered clip current after momentum/snap scrolling and
    // autoplay-advance, but never while a rotation is changing the layout.
    document.addEventListener('scroll',()=>{
      clearTimeout(state.anchorTimer);
      state.anchorTimer=setTimeout(()=>{
        if(state.rotating||state.touching||
            state.orientation!==landscape())return;
        const geometry=layout();
        const current=geometry&&activeCard(geometry);
        if(current)state.anchor=current;
      },160);
    },{passive:true,capture:true});
    window.addEventListener('resize',()=>refresh(),{passive:true});
    window.addEventListener('orientationchange',()=>refresh(),{passive:true});
    document.addEventListener('touchstart',()=>{
      state.touching=true;
      // A deliberate swipe wins over any pending rotation restore.
      for(const t of state.restoreTimers||[])clearTimeout(t);
      state.restoreTimers=[];
      clearTimeout(state.touchEndTimer);
    },{passive:true,capture:true});
    const finishTouch=()=>{
      clearTimeout(state.touchEndTimer);
      state.touchEndTimer=setTimeout(()=>{
        state.touching=false;
        if(state.enabled&&!state.rotating&&landscape())refresh();
      },180);
    };
    document.addEventListener('touchend',finishTouch,{passive:true,capture:true});
    document.addEventListener('touchcancel',finishTouch,{passive:true,capture:true});
  }
  return refresh();
})()
""".trimIndent()

private fun xImmersiveFitScript(enabled: Boolean): String =
    X_LANDSCAPE_CAROUSEL_SCRIPT.replace("__ENABLED__", enabled.toString())

/**
 * Detect an open X immersive/media viewer without treating every video card
 * on the /home feed as a viewer. Read-only: do not navigate, alter layout or
 * touch video playback.
 */
internal val X_DEDICATED_X_VIDEO_VIEWER_SCRIPT = """
(() => {
  if(!/^(?:www\.)?(?:x\.com|twitter\.com)$/.test(location.hostname))
    return false;
  const visible=(el,w,h)=>{
    if(!el||el.closest('[aria-hidden="true"],[inert]'))return false;
    const r=el.getBoundingClientRect();
    if(r.width<w||r.height<h||r.right<=0||r.bottom<=0||
       r.left>=innerWidth||r.top>=innerHeight)return false;
    const style=getComputedStyle(el);
    return style.display!=='none'&&style.visibility!=='hidden'&&
      style.opacity!=='0';
  };
  const visibleVideo=(root)=>{
    for(const video of root.querySelectorAll('video')){
      if(visible(video,120,75))return true;
    }
    return false;
  };
  const immersiveRoots=document.querySelectorAll(
    '[data-testid="vss-scroll-view"],[data-testid="immersive-viewer"],' +
    '#layers [role="dialog"]:has(video,[data-testid="videoPlayer"])'
  );
  // A real immersive viewer occupies most of the screen. An inline
  // video/card inside the X feed is NOT a dedicated viewer, even if
  // a stale fullscreen flag or media URL remains in browser history.
  for(const root of immersiveRoots){
    if(!visible(root,innerWidth*.54,innerHeight*.65))continue;
    const r=root.getBoundingClientRect();
    if(r.top>innerHeight*.23||r.bottom<innerHeight*.77)continue;
    // Some X builds reuse a large DOM container for the ordinary timeline.
    // More than one visible tweet card means this is a feed, not the
    // dedicated one-video viewer, even if its wrapper resembles a dialog.
    let visibleCards=0;
    for(const article of root.querySelectorAll('article[data-testid="tweet"]')){
      if(visible(article,120,55) && ++visibleCards>=2)break;
    }
    if(visibleCards>=2)continue;
    if(visibleVideo(root))return true;
  }

  // Standalone /status/id/video/1 pages can omit all immersive testids.
  // Check the visible page, not merely the URL: a returning user may still
  // have a /status/... route while scrolling multiple feed/reply cards.
  const videoRoute=/\/status\/\d{6,22}\/(?:video|photo)\/\d+\/?$/.test(
    location.pathname);
  if(!videoRoute)return false;
  let visiblePosts=0;
  for(const article of document.querySelectorAll('article[data-testid="tweet"]')){
    if(!visible(article,120,55))continue;
    if(++visiblePosts>=2)return false;
  }
  // A genuine standalone player dominates the viewport, unlike an
  // embedded 16:9 thumbnail in a scrolling list.
  for(const video of document.querySelectorAll('video')){
    if(!visible(video,innerWidth*.48,innerHeight*.56))continue;
    const r=video.getBoundingClientRect();
    if(r.top<innerHeight*.27&&r.bottom>innerHeight*.71)return true;
  }
  return false;
})()
""".trimIndent()

/**
 * Opening a video from an X list (Home, Bookmarks, Likes) makes X's single-page
 * app tear the list down and rebuild it on Back, which looked like jumping and
 * flashing. A tap on a list video is therefore sent to the app, which opens the
 * video in a NEW tab and keeps this list's WebView untouched. Closing that tab
 * returns to the exact, still-live list. Everything else is left to X.
 */
private val X_VIEW_IN_TAB_SCRIPT = """
(() => {
  if (!/^(?:www\.)?(?:x\.com|twitter\.com)$/.test(location.hostname)) return;
  if (window.__mydownloadViewInTab) return;
  window.__mydownloadViewInTab = true;

  const MAX_TAP_MS=350, MAX_MOVE=14;
  let start=null, swallowClickUntil=0;
  const listPage=()=>/^\/(?:home|i\/bookmarks(?:\/\d+)?|[^\/]+\/likes)\/?$/.test(
    location.pathname);
  const interactive=el=>!!el.closest(
    'button,a,input,textarea,select,[role="button"],[role="link"],'+
    '[role="slider"],[role="menuitem"],[role="menu"],[role="switch"],'+
    '#mydownload-optional-x-progress,[data-testid="User-Name"],'+
    '[data-testid="Tweet-User-Avatar"],[data-testid="socialContext"]');
  const statusId=e=>{
    if(!listPage()||!(e.target instanceof Element)||interactive(e.target))
      return null;
    const article=e.target.closest('article[data-testid="tweet"]');
    if(!article)return null;
    const player=e.target.closest('[data-testid="videoPlayer"],'+
      '[data-testid="videoComponent"]')||
      (e.target.tagName==='VIDEO'?e.target:null);
    if(!player||!article.contains(player))return null;
    // The post's own timestamp link, never a quoted post's.
    for(const time of article.querySelectorAll('a[href*="/status/"] time')){
      const a=time.closest('a');
      if(!a||a.closest('[role="link"][tabindex]:not(a)'))continue;
      // "/handle/status/ID" -> the direct playback page /handle/status/ID/video/1
      const m=/^\/([^\/]+)\/status\/(\d+)/.exec(a.getAttribute('href')||'');
      if(m)return '/'+m[1]+'/status/'+m[2]+'/video/1';
    }
    return null;
  };
  document.addEventListener('touchstart',e=>{
    start=null;
    if(e.touches.length!==1)return;
    const path=statusId(e);
    if(!path)return;
    const t=e.touches[0];
    start={x:t.clientX,y:t.clientY,time:performance.now(),path};
  },{capture:true,passive:true});
  document.addEventListener('touchmove',e=>{
    if(!start)return;
    const t=e.touches[0];
    if(e.touches.length!==1||
        Math.hypot(t.clientX-start.x,t.clientY-start.y)>MAX_MOVE)start=null;
  },{capture:true,passive:true});
  document.addEventListener('touchend',e=>{
    const s=start;start=null;
    if(!s||e.touches.length!==0||performance.now()-s.time>MAX_TAP_MS)return;
    if(e.cancelable)e.preventDefault();
    e.stopImmediatePropagation();
    swallowClickUntil=performance.now()+700;
    // Handled natively (WebViewClient): opens a new tab, leaves this page as is.
    location.href='mydownloadviewer://open?u='+
      encodeURIComponent('https://x.com'+s.path);
  },{capture:true,passive:false});
  document.addEventListener('click',e=>{
    if(performance.now()<swallowClickUntil&&e.target instanceof Element&&
        !interactive(e.target)){
      e.preventDefault();e.stopImmediatePropagation();
    }
  },{capture:true});
})()
""".trimIndent()

/**
 * Viewer tabs only. The post page opened from a list shows the video inline and
 * needs one more real tap to enter X's own playback viewer. X ignores scripted
 * clicks, so this only REPORTS where the player is; the app then sends a genuine
 * touch there (see autoEnterViewer).
 */
private val X_VIEWER_PROBE_SCRIPT = """
(() => {
  const open=!!document.querySelector(
    '[data-testid="vss-scroll-view"],[data-testid="immersive-viewer"],'+
    '#layers [role="dialog"]:has(video)');
  const post=/^\/[^\/]+\/status\/\d+\/?$/.test(location.pathname);
  const player=document.querySelector('article [data-testid="videoPlayer"]')||
    document.querySelector('[data-testid="videoPlayer"]');
  const video=(player&&player.querySelector('video'))||
    document.querySelector('article video');
  const target=video||player;
  if(!target)return JSON.stringify({open:open,ready:false});
  const r=target.getBoundingClientRect();
  const cy=(r.top+r.bottom)/2;
  return JSON.stringify({
    open:open,
    ready:post&&r.width>80&&r.height>60&&cy>0&&cy<innerHeight,
    x:r.left+r.width/2,y:cy,w:r.width,dpr:window.devicePixelRatio||1
  });
})()
""".trimIndent()

private val X_VIDEO_DOUBLE_TAP_SEEK_SCRIPT = """
(() => {
  if (!/^(?:www\.)?(?:x\.com|twitter\.com)$/.test(location.hostname)) return;
  // Exactly one gesture listener set per live X document.
  if (window.__mydownloadVideoSeek) return;
  window.__mydownloadVideoSeek = true;

  const PAIR_MS=390, MAX_TAP_MS=300, MAX_MOVE=24, MAX_PAIR_DISTANCE=78;
  let tapStart=null, lastTap=null, recentSideTap=null;
  let pointerStart=null, lastSeek=null;
  const distance=(a,b)=>Math.hypot(a.x-b.x,a.y-b.y);
  const viewer=()=>__DEDICATED_VIEWER__;
  const validTarget=e=>!(e.target instanceof Element &&
    e.target.closest(
      '#mydownload-optional-x-progress,button,a,input,'+
      '[role="button"],[role="slider"],[data-testid="playButton"]'
    ));
  const sideAt=x=>x<innerWidth*.40?'left':x>innerWidth*.60?'right':'';
  const visibleVideo=video=>{
    const r=video.getBoundingClientRect();
    return r.width>=120&&r.height>=75&&r.right>0&&r.left<innerWidth&&
      r.bottom>0&&r.top<innerHeight&&
      !video.closest('[aria-hidden="true"],[inert]');
  };
  const activeVideo=()=>{
    const root=Array.from(document.querySelectorAll(
      '[data-testid="vss-scroll-view"],[data-testid="immersive-viewer"],'+
      '#layers [role="dialog"]:has(video,[data-testid="videoPlayer"])'
    )).find(el=>{
      const r=el.getBoundingClientRect();
      return r.width>=innerWidth*.40&&r.height>=innerHeight*.40&&
        r.right>0&&r.left<innerWidth&&r.bottom>0&&r.top<innerHeight;
    });
    const candidates=Array.from((root||document).querySelectorAll('video'))
      .filter(video=>visibleVideo(video)&&Number.isFinite(video.duration)&&
        video.duration>0);
    if(!candidates.length)return null;
    // One foreground clip wins, not a previous/next carousel card.
    candidates.sort((a,b)=>{
      const score=video=>{
        const r=video.getBoundingClientRect();
        const w=Math.max(0,Math.min(r.right,innerWidth)-Math.max(0,r.left));
        const h=Math.max(0,Math.min(r.bottom,innerHeight)-Math.max(0,r.top));
        const y=Math.abs((r.top+r.bottom)/2-innerHeight/2);
        return (video.paused?0:100000000)+w*h-y*500;
      };
      return score(b)-score(a);
    });
    return candidates[0];
  };
  const sameRegion=(a,b)=>!!(a&&b&&a.side===b.side&&
    a.video===b.video&&distance(a,b)<=MAX_PAIR_DISTANCE);
  const block=e=>{
    if(e.cancelable)e.preventDefault();
    e.stopImmediatePropagation();
  };
  const feedback=(side)=>{
    const id='mydownload-seek-feedback';
    let el=document.getElementById(id);
    if(!el){
      el=document.createElement('div');
      el.id=id;
      Object.assign(el.style,{
        position:'fixed',top:'36%',zIndex:'2147483647',
        padding:'8px 11px',borderRadius:'22px',pointerEvents:'none',
        color:'white',background:'rgba(0,0,0,0.45)',
        font:'600 15px sans-serif',transition:'opacity 120ms'
      });
      (document.body||document.documentElement).appendChild(el);
    }
    el.style.left=side==='left'?'11%':'auto';
    el.style.right=side==='right'?'11%':'auto';
    el.textContent=side==='left'?'↶ 10':'10 ↷';
    el.style.opacity='1';
    clearTimeout(el.__hideTimer);
    el.__hideTimer=setTimeout(()=>{el.style.opacity='0';},360);
  };
  const seekOnce=(video,side,now)=>{
    const duration=video.duration;
    if(!Number.isFinite(duration)||duration<=0)return;
    const prior=lastSeek;
    // Android WebView/X may report a stale currentTime while a previous
    // asynchronous seek is still settling. Accumulate on the last target.
    // Guard: a stale target (slow buffering, or X moved the playhead itself)
    // must never be extended. If it is far from the real position, start over
    // from the real position so one tap pair can never jump a long way.
    const current=video.currentTime;
    const useTarget=prior?.video===video &&
      (video.seeking||now-prior.at<2500) &&
      Number.isFinite(current) && Math.abs(prior.target-current)<=25;
    const base=useTarget ? prior.target : current;
    if(!Number.isFinite(base))return;
    const next=Math.max(0,Math.min(
      base+(side==='left'?-10:10),Math.max(0,duration-.1)));
    const record={video,target:next,at:now};
    lastSeek=record;
    if(Math.abs(next-base)<.01)return; // Already at start/end.
    try {
      video.currentTime=next; // Exactly one seek for each complete tap pair.
      window.__mydownloadOptionalXSeek?.updateClock?.();
      feedback(side);
      // If X's own double-tap handler ALSO moved the playhead, undo only the
      // overshoot in the seek direction (never touch a stall or a newer tap).
      const dir=side==='left'?-1:1;
      for(const delay of [300,750]){
        setTimeout(()=>{
          if(lastSeek!==record||video.seeking||!video.isConnected)return;
          const expected=next+(video.paused?0:(performance.now()-record.at)/1000);
          if((video.currentTime-expected)*dir>2.5){
            try{video.currentTime=Math.max(0,Math.min(expected,
              Math.max(0,video.duration-.1)));}catch(_){}
          }
        },delay);
      }
    }catch(_) { lastSeek=null; }
  };
  // X's own double-tap tracker can also pair a video tap with a PREVIOUS tap
  // (e.g. on Like/Bookmark) and seek by itself, which looked like random long
  // jumps. After any side tap that we did not turn into a seek, undo a big
  // unexpected playhead move.
  const guardNativeSeek=(video,from,at)=>{
    if(!Number.isFinite(from))return;
    const paused=video.paused;
    for(const delay of [300,800]){
      setTimeout(()=>{
        if(!video.isConnected||video.seeking||activeVideo()!==video)return;
        // We seeked on purpose after this tap: the overshoot guard owns it.
        if(lastSeek&&lastSeek.video===video&&lastSeek.at>=at-5)return;
        const expected=from+(paused||video.paused?0:(performance.now()-at)/1000);
        if(Math.abs(video.currentTime-expected)>3){
          try{video.currentTime=Math.max(0,Math.min(expected,
            Math.max(0,video.duration-.1)));}catch(_){}
        }
      },delay);
    }
  };
  const candidate=(x,y,now)=>{
    if(!viewer())return null;
    const side=sideAt(x),video=side?activeVideo():null;
    return video?{x,y,time:now,side,video}:null;
  };

  // Let X see touch movement (native vertical swipes), but capture the
  // completed short tap so X never applies its own second seek.
  window.addEventListener('touchstart',e=>{
    tapStart=null;
    if(e.touches.length!==1||!validTarget(e)||
        window.__mydownloadMediaZoom?.isZoomed?.()){
      // A Like/Bookmark/button tap must not chain into the next seek: X's own
      // tap tracker can pair it with the following video tap.
      lastTap=null;lastSeek=null;return;
    }
    const t=e.touches[0];
    const tap=candidate(t.clientX,t.clientY,performance.now());
    if(!tap){lastTap=null;return;}
    tapStart=tap;
    // The site must not interpret the second down as another double-tap.
    if(lastTap&&sameRegion(tap,lastTap)&&
       tap.time-lastTap.time<=PAIR_MS)block(e);
  },{capture:true,passive:false});
  window.addEventListener('touchmove',e=>{
    if(!tapStart)return;
    if(e.touches.length!==1||
        distance(tapStart,{x:e.touches[0].clientX,
          y:e.touches[0].clientY})>MAX_MOVE){
      tapStart=null;
      lastTap=null;
      lastSeek=null; // A real swipe is navigation, not another seek pair.
    }
  },{capture:true,passive:true});
  window.addEventListener('touchend',e=>{
    const start=tapStart;
    tapStart=null;
    if(!start||e.touches.length!==0||e.changedTouches.length!==1)return;
    const t=e.changedTouches[0],now=performance.now();
    const tap={...start,x:t.clientX,y:t.clientY,time:now};
    if(now-start.time>MAX_TAP_MS||
       distance(tap,start)>MAX_MOVE||!viewer()||
       !start.video.isConnected){
      lastTap=null;return;
    }
    // Stop X's touchend AND its synthesized click for each video-side tap.
    // No arbitrary 680ms lock: four taps = two deliberate 10s seeks.
    recentSideTap=tap;
    guardNativeSeek(tap.video,start.video.currentTime,now);
    // A single tap must still reveal the optional progress controls even
    // though we swallow X's native touchend to prevent duplicate seeking.
    window.__mydownloadOptionalXSeek?.revealBar?.();
    block(e);
    if(lastTap&&sameRegion(tap,lastTap)&&
       now-lastTap.time<=PAIR_MS){
      lastTap=null;
      seekOnce(tap.video,tap.side,now);
    }else lastTap=tap;
  },{capture:true,passive:false});
  window.addEventListener('touchcancel',()=>{
    tapStart=null;lastTap=null;pointerStart=null;
  },{capture:true,passive:true});

  // X can also listen to PointerEvents rather than TouchEvents. Only block
  // a completed stationary touch; pointermoves remain native for scrolling.
  window.addEventListener('pointerdown',e=>{
    pointerStart=null;
    if(e.pointerType!=='touch'||!validTarget(e))return;
    pointerStart=candidate(e.clientX,e.clientY,performance.now());
  },{capture:true,passive:true});
  window.addEventListener('pointermove',e=>{
    if(pointerStart && (e.pointerType!=='touch'||
       distance(pointerStart,{x:e.clientX,y:e.clientY})>MAX_MOVE))
      pointerStart=null;
  },{capture:true,passive:true});
  window.addEventListener('pointerup',e=>{
    const start=pointerStart;
    pointerStart=null;
    if(!start||e.pointerType!=='touch'||!validTarget(e))return;
    if(performance.now()-start.time<=MAX_TAP_MS&&
       distance(start,{x:e.clientX,y:e.clientY})<=MAX_MOVE)
      block(e);
  },{capture:true,passive:false});
  window.addEventListener('pointercancel',()=>{pointerStart=null;},
    {capture:true,passive:true});

  const blockSynthetic=e=>{
    if(!recentSideTap||performance.now()-recentSideTap.time>750||
       !viewer()||!validTarget(e))return;
    const point={x:e.clientX,y:e.clientY,
      side:sideAt(e.clientX),video:recentSideTap.video};
    if(sameRegion(point,recentSideTap))block(e);
  };
  window.addEventListener('click',blockSynthetic,{capture:true});
  window.addEventListener('dblclick',blockSynthetic,{capture:true});
})()
""".trimIndent().replace(
    "__DEDICATED_VIEWER__", X_DEDICATED_X_VIDEO_VIEWER_SCRIPT
)

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
    // A video in the X home feed does not mean the immersive viewer is open.
    var xImmersiveViewerOpen by remember { mutableStateOf(false) }
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
        // Going back from X's video viewer to the list is a same-document
        // transition: X re-mounts and restores the list itself. Forcing our own
        // scrollTo three times on top of that made the list stutter and flash
        // as if it kept loading. In that case only act once, late, and only if
        // the list really came back at the top.
        val gentle = tab.restoreGentle
        tab.restoreGentle = false
        if (gentle) {
            // The list is hidden (alpha 0) by goBackPreservingScroll while X
            // rebuilds it. Poll briefly: as soon as it is back at the saved
            // position (X restored it, or we scroll it once from the top),
            // reveal it, so the intermediate "jump to top" is never visible.
            var tries = 0
            val step = object : Runnable {
                override fun run() {
                    val stale = tab.restoreSequence != token || tab.url != url
                    var done = stale
                    if (!stale) {
                        if (web.scrollY >= 120) {
                            done = true // X restored its own scroll position
                        } else {
                            if (point.native > 0) web.scrollTo(0, point.native)
                            if (point.native <= 0 || web.scrollY >= point.native - 60) {
                                if (point.page > 0 || point.panel > 0) {
                                    web.evaluateJavascript(BrowserScroll.restoreScript(point), null)
                                }
                                done = true
                            }
                        }
                        if (!done && ++tries >= 20) done = true
                    }
                    if (done) {
                        web.alpha = 1f
                        if (!stale && tab.restoreTarget == url) tab.restoreTarget = null
                    } else web.postDelayed(this, 100L)
                }
            }
            web.postDelayed(step, 120L)
            return
        }
        val delays = listOf(220L, 750L, 1700L)
        delays.forEachIndexed { index, millis ->
            web.postDelayed({
                if (tab.restoreSequence != token || tab.url != url) return@postDelayed
                if (web.url != url && web.url?.substringBefore('#') != url.substringBefore('#'))
                    return@postDelayed
                val last = index == delays.lastIndex
                val skip = false
                // Never fight a position that is already (nearly) right.
                val nativeOff = kotlin.math.abs(web.scrollY - point.native) > 60
                if (!skip && point.native > 0 && nativeOff) web.scrollTo(0, point.native)
                if (!skip && (point.page > 0 || point.panel > 0)) {
                    web.evaluateJavascript(BrowserScroll.restoreScript(point), null)
                }
                if (last && tab.restoreTarget == url) tab.restoreTarget = null
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

    /** Opens an X video from a list in a new tab; the list's WebView stays untouched. */
    fun openViewerTab(parent: TabViewState, parentWeb: WebView?, url: String) {
        if (!BrowserAddress.isWebUrl(url) || !BrowserVideoDiscovery.isXPage(url)) return
        // Silence the list's inline video before the tab is hidden.
        parentWeb?.evaluateJavascript(
            "document.querySelectorAll('video').forEach(function(v){try{v.pause()}catch(e){}})",
            null
        )
        val child = addTab(url, "X 影片") ?: return
        child.parentTabId = parent.id
    }

    /** Back on a viewer tab: close it and return to the untouched list tab. */
    fun closeViewerTab(): Boolean {
        val tab = tabs.firstOrNull { it.id == activeId } ?: return false
        val parentId = tab.parentTabId ?: return false
        val parent = tabs.firstOrNull { it.id == parentId }
        if (parent != null) selectTab(parent)
        closeTab(tab.id)
        return true
    }

    /** A real finger-like tap on the WebView at view-pixel coordinates. */
    fun tapWebView(web: WebView, x: Float, y: Float) {
        val down = android.os.SystemClock.uptimeMillis()
        val press = android.view.MotionEvent.obtain(
            down, down, android.view.MotionEvent.ACTION_DOWN, x, y, 0
        )
        web.dispatchTouchEvent(press)
        press.recycle()
        web.postDelayed({
            val up = android.os.SystemClock.uptimeMillis()
            val release = android.view.MotionEvent.obtain(
                down, up, android.view.MotionEvent.ACTION_UP, x, y, 0
            )
            web.dispatchTouchEvent(release)
            release.recycle()
        }, 70L)
    }

    /**
     * In a viewer tab, wait for the inline player and tap it (up to 3 times, until
     * X's playback viewer is open), doing the "second tap" for the user.
     */
    fun autoEnterViewer(tab: TabViewState, web: WebView, attempt: Int = 0, taps: Int = 0) {
        if (attempt > 45 || taps >= 3) return
        web.postDelayed({
            if (webViews[tab.id] !== web || tab.parentTabId == null || tab.id != activeId) {
                return@postDelayed
            }
            web.evaluateJavascript(X_VIEWER_PROBE_SCRIPT) { raw ->
                val json = runCatching {
                    JSONObject(JSONArray("[" + raw + "]").getString(0))
                }.getOrNull()
                if (json?.optBoolean("open") == true) return@evaluateJavascript
                if (json != null && json.optBoolean("ready")) {
                    val dpr = json.optDouble("dpr", 1.0)
                    tapWebView(
                        web,
                        (json.optDouble("x") * dpr).toFloat(),
                        (json.optDouble("y") * dpr).toFloat()
                    )
                    autoEnterViewer(tab, web, attempt + 1, taps + 1)
                } else autoEnterViewer(tab, web, attempt + 1, taps)
            }
        }, if (taps > 0) 1300L else 450L)
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
                    // Avoid showing the previous viewer's download shortcut
                    // during the next X list page while the DOM scan runs.
                    xImmersiveViewerOpen = false
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
                if (url.startsWith("mydownloadviewer://")) {
                    request.url.getQueryParameter("u")?.let { target ->
                        web.post { openViewerTab(tab, web, target) }
                    }
                    return true
                }
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
                if (tab.parentTabId != null && !tab.autoEnterStarted) {
                    tab.autoEnterStarted = true
                    autoEnterViewer(tab, web)
                }
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
                    view?.evaluateJavascript(X_VIEW_IN_TAB_SCRIPT, null)
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
        web.setOnTouchListener { _, event ->
            if (BrowserVideoDiscovery.isXPage(tab.url)) {
                // Keep the pre-existing two-finger media zoom, but forward
                // single-finger vertical swipes directly to X without any
                // injected Android carousel callbacks.
                mediaScaleDetector.onTouchEvent(event)
            }
            false
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
            // Native-carousel test: only clear legacy injected styling.
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
        if (closeViewerTab()) return
        val web = currentWebView() ?: return
        if (!web.canGoBack()) return
        captureScroll(web, selected)
        val list = web.copyBackForwardList()
        val target = if (list.currentIndex > 0)
            list.getItemAtIndex(list.currentIndex - 1)?.url else null
        selected.restoreTarget = target?.takeIf { BrowserAddress.isWebUrl(it) }
        selected.restoreGentle = XMediaNavigation.isMediaViewer(selected.url)
        if (selected.restoreGentle &&
            target?.let { selected.scrollByUrl[it]?.hasPosition() } == true) {
            // Hide X's momentary jump-to-top while the list is rebuilt; it is
            // revealed again as soon as the position is right (hard cap 2.5s).
            web.alpha = 0f
            web.postDelayed({ web.alpha = 1f }, 2500L)
        } else selected.restoreGentle = false
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
        // Use only the caption belonging to the chosen clip. The WebView's
        // title and a previous scan may belong to a different X post, especially
        // just after swiping. When the caption is unavailable, use the post ID.
        val postText = video?.postText.orEmpty().ifBlank {
            if (video == null && !strictActive && VideoTitle.isXStatus(page))
                videoElements.pageText
            else ""
        }
        val title = if (BrowserVideoDiscovery.isXPage(page)) {
            VideoTitle.preferred(
                postText,
                "",
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
            while (webViews.size > 4) {
                val victimId = webViews.keys.firstOrNull {
                    it != selected.id && it != selected.parentTabId
                } ?: break
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
        xImmersiveViewerOpen = false
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
        // The large X style/progress/action installers are idempotent and keep
        // themselves current through their own MutationObservers. Re-evaluating
        // them every tick re-parses tens of KB of JS and made the page janky,
        // so only refresh them every few ticks. Cheap scans still run each tick.
        var tick = 0
        while (visible) {
            val heavyTick = tick++ % 5 == 0
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
                if (!BrowserVideoDiscovery.isXPage(selected.url)) {
                    xImmersiveViewerOpen = false
                }
                // Dynamic X pages reuse the document while navigating posts.
                // Reinstate CSS if React recreates its head or mobile navigation.
                if (BrowserVideoDiscovery.isXPage(selected.url)) {
                    if (!heavyTick) {
                        // Cheap safety net (after rotation/fade): re-run only the
                        // already-installed Like/Bookmark visibility scan.
                        browser.evaluateJavascript(
                            "window.__mydownloadXNativeVisibility?.schedule?.()", null)
                    }
                    if (heavyTick) {
                        browser.evaluateJavascript(xDisplayScript(currentXOptions), null)
                        browser.evaluateJavascript(X_MEDIA_PINCH_ZOOM_SCRIPT, null)
                        browser.evaluateJavascript(X_VIDEO_DOUBLE_TAP_SEEK_SCRIPT, null)
                        browser.evaluateJavascript(X_VIEW_IN_TAB_SCRIPT, null)
                    }
                    // A React video card on /home must not show download.
                    // Only a visible, open immersive viewer should do so.
                    val checkedUrl = selected.url
                    browser.evaluateJavascript(X_DEDICATED_X_VIDEO_VIEWER_SCRIPT) { result ->
                        if (activeId == checkedId && selected.url == checkedUrl &&
                            webViews[checkedId] === browser) {
                            xImmersiveViewerOpen = result == "true"
                        }
                    }
                    // Native-carousel test: no card alignment or scroll repair.
                    // Cleanup runs only once per document, even with polling.
                    if (heavyTick) {
                        browser.evaluateJavascript(xImmersiveFitScript(browserVideoFullscreen), null)
                    }
                }
                browser.evaluateJavascript(BrowserVideoDiscovery.SCAN_SCRIPT) { result ->
                    if (activeId == checkedId) {
                        videoElements = BrowserVideoDiscovery.decode(result)
                    }
                }
                if (heavyTick) captureScroll(browser, selected)
            } else {
                scanResults = VideoPositions()
                videoElements = VisibleVideos()
            }
            delay(950L)
        }
    }

    // X in-place fullscreen may hide the top status bar, but must ALWAYS
    // retain Android's bottom Back/Home/Recents navigation buttons.
    // Do not alter the video DOM or the swipe carousel.
    val fullscreenMode = visible && (browserVideoFullscreen || fullscreenView != null)
    DisposableEffect(context, fullscreenMode) {
        val window = (context as? Activity)?.window
        if (fullscreenMode && window != null) {
            val decor = window.decorView
            val controller = WindowCompat.getInsetsController(window, decor)
            fun applyFullscreenBars() {
                controller.show(WindowInsetsCompat.Type.navigationBars())
                controller.hide(WindowInsetsCompat.Type.statusBars())
            }
            applyFullscreenBars()
            val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                if (hasFocus) applyFullscreenBars()
            }
            val observer = decor.viewTreeObserver
            observer.addOnWindowFocusChangeListener(focusListener)
            decor.post {
                if (decor.hasWindowFocus() && decor.isAttachedToWindow) applyFullscreenBars()
            }
            onDispose {
                if (observer.isAlive) observer.removeOnWindowFocusChangeListener(focusListener)
                // Restore status on exit; do not conceal bottom navigation.
                WindowCompat.getInsetsController(window, decor)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        } else {
            onDispose { }
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

    BackHandler(visible && (browserVideoFullscreen || fullscreenView != null ||
        selected.canBack || selected.parentTabId != null)) {
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
                enabled = selected.canBack || selected.parentTabId != null,
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
            // One tap back to X's home timeline.
            IconButton(
                onClick = { navigate("https://x.com/home") },
                modifier = Modifier.size(37.dp)
            ) {
                Icon(
                    Icons.Default.Home,
                    contentDescription = "回 X 首頁",
                    tint = BrowserGreen,
                    modifier = Modifier.size(21.dp)
                )
            }
            // One tap to X's own bookmarks timeline (login to X required).
            IconButton(
                onClick = { navigate("https://x.com/i/bookmarks") },
                modifier = Modifier.size(37.dp)
            ) {
                Icon(
                    Icons.Default.CollectionsBookmark,
                    contentDescription = "X 收藏",
                    tint = BrowserGreen,
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
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("我的書籤", style = MaterialTheme.typography.titleMedium)
                        // Opens X's own bookmarks timeline (needs the user to be
                        // logged in to X in this browser).
                        OutlinedButton(onClick = { navigate("https://x.com/i/bookmarks") }) {
                            Icon(Icons.Default.Bookmark, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("X 收藏")
                        }
                    }
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
                !BrowserVideoDiscovery.isXPage(selected.url)) {
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
            // A single movable shortcut is shown for an opened X media
            // viewer/post, AV01 detail page or directly opened media URL.
            // Do not draw downloads over the X home/profile/search feed.
            val direct = selected.url
            val directDownload = Av01BrowserLinks.isVideoLink(direct) ||
                (BrowserAddress.isWebUrl(direct) &&
                    direct.substringBefore('?').let {
                        it.endsWith(".mp4", true) || it.endsWith(".m3u8", true)
                    })
            // Do not infer video-viewer state from URL or fullscreen flags.
            // Those can persist while X shows a normal multi-post list.
            val showXDownload = BrowserVideoDiscovery.isXPage(direct) &&
                xImmersiveViewerOpen
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
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                // The HTML5 dialog must reserve space for the Android nav bar.
                decorFitsSystemWindows = true
            )
        ) {
            // HTML5 fullscreen uses its own window; ensure Android's bottom
            // Back/Home/Recents bar is visible in this window as well.
            var ancestor = LocalView.current.parent
            while (ancestor != null && ancestor !is DialogWindowProvider) {
                ancestor = ancestor.parent
            }
            val dialogWindow = (ancestor as? DialogWindowProvider)?.window
            DisposableEffect(dialogWindow) {
                if (dialogWindow != null) {
                    val decor = dialogWindow.decorView
                    val controller = WindowCompat.getInsetsController(dialogWindow, decor)
                    fun showNavigationAndHideStatus() {
                        controller.show(WindowInsetsCompat.Type.navigationBars())
                        controller.hide(WindowInsetsCompat.Type.statusBars())
                    }
                    showNavigationAndHideStatus()
                    val observer = decor.viewTreeObserver
                    val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                        if (hasFocus) showNavigationAndHideStatus()
                    }
                    observer.addOnWindowFocusChangeListener(focusListener)
                    decor.post {
                        if (decor.hasWindowFocus() && decor.isAttachedToWindow) {
                            showNavigationAndHideStatus()
                        }
                    }
                    onDispose {
                        if (observer.isAlive) {
                            observer.removeOnWindowFocusChangeListener(focusListener)
                        }
                        controller.show(WindowInsetsCompat.Type.systemBars())
                    }
                } else onDispose { }
            }
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
