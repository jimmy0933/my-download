package com.jimmy.mydownload

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private val Navy = Color(0xFF091421)
private val Panel = Color(0xFF142333)
private val Green = Color(0xFF27DBB3)
private val Soft = Color(0xFFB4C8D5)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        val shared = if (intent?.action == Intent.ACTION_SEND) {
            intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        } else ""
        setContent { RootScreen(shared) }
    }
}

private fun requestNotificationPermission(context: android.content.Context) {
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        != PackageManager.PERMISSION_GRANTED
    ) {
        (context as? ComponentActivity)?.requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8
        )
    }
}

private fun startDownload(context: android.content.Context, stream: StreamChoice) {
    val item = DownloadStore.add(context, stream)
    try {
        requestNotificationPermission(context)
        DownloadService.start(context, item.id)
    } catch (e: Exception) {
        DownloadStore.patch(context, item.id) {
            it.copy(status = "失敗", error = e.message ?: "無法啟動下載")
        }
        throw e
    }
}

private fun openVideo(context: android.content.Context, item: DownloadRecord) {
    val uri = DownloadStore.contentUri(item)
        ?: throw IOException("影片沒有儲存位置，請重新下載")
    // Check old downloads too. Earlier versions saved 907-byte X MP4
    // initialization fragments with a misleading 100% status.
    if (item.kind == "mp4") {
        val size = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull()?.takeIf { it >= 0L }
        if (!Mp4Ranges.isPlausibleVideoSize(size)) {
            throw IOException(
                "影片只有 " + Mp4Ranges.label(size) +
                    "，不是完整 MP4。請回到 X 重新偵測並選擇 HLS 來源。"
            )
        }
    }
    val mime = context.contentResolver.getType(uri)
        ?: if (item.savedExtension == "ts") "video/mp2t" else "video/*"
    VideoPlayerApps.open(context, uri, mime)
}

private fun deleteVideo(context: android.content.Context, item: DownloadRecord) {
    if (item.status != "完成") {
        runCatching { DownloadService.pause(context, item.id) }
    }
    listOf(item.uri, item.originalUri).filter { it.isNotBlank() }.distinct().forEach { path ->
        runCatching {
            val uri = Uri.parse(path)
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            } else {
                context.contentResolver.delete(uri, null, null)
            }
        }
    }
    DownloadStore.remove(context, item.id)
    File(context.filesDir, "temp_downloads/" + item.id + ".part").delete()
    File(context.filesDir, "temp_downloads/" + item.id + ".mp4").delete()
    File(context.filesDir, "temp_downloads/" + item.id + ".audio.part").delete()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootScreen(shared: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(if (shared.isNotBlank()) 1 else 0) }
    var adsBlocked by remember { mutableStateOf(DownloadSettings.adBlockEnabled(context)) }
    var xDisplayOptions by remember { mutableStateOf(DownloadSettings.xDisplayOptions(context)) }
    var downloadButtonOpacityPercent by remember {
        mutableIntStateOf(DownloadSettings.floatingButtonOpacityPercent(context))
    }
    var manualUrl by rememberSaveable { mutableStateOf(shared) }

    var selectedLink by remember { mutableStateOf<String?>(null) }
    var resolving by remember { mutableStateOf(false) }
    var resolveError by remember { mutableStateOf("") }
    var choices by remember { mutableStateOf<List<StreamChoice>>(emptyList()) }
    var candidateSources by remember { mutableStateOf<List<StreamChoice>>(emptyList()) }
    var sourceFailures by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentRequest by remember { mutableIntStateOf(0) }
    var downloads by remember { mutableStateOf<List<DownloadRecord>>(emptyList()) }

    fun showDownload(link: String) {
        if (!link.startsWith("https://", true)) return
        val seq = ++currentRequest
        selectedLink = link
        resolving = true
        resolveError = ""
        choices = emptyList()
        candidateSources = emptyList()
        sourceFailures = emptyList()
        scope.launch {
            val result = runCatching { StreamResolver.inspect(link) }
            if (currentRequest == seq && selectedLink == link) {
                resolving = false
                result.onSuccess { found ->
                    choices = found
                    // Add estimated sizes a moment later, if this request is still current.
                    scope.launch {
                        val sized = runCatching { StreamResolver.withSizeEstimates(found) }
                            .getOrNull()
                        if (sized != null && currentRequest == seq && selectedLink == link) {
                            choices = sized
                        }
                    }
                }.onFailure { resolveError = it.message ?: "影片解析失敗" }
            }
        }
    }

    fun showDetectedChoices(found: List<StreamChoice>) {
        if (found.isEmpty()) return
        val seq = ++currentRequest
        val pageUrl = found.first().referer
        selectedLink = pageUrl
        resolving = true
        resolveError = ""
        choices = emptyList()
        candidateSources = found
        sourceFailures = emptyList()
        scope.launch {
            val result = runCatching {
                MediaCandidateResolver.inspect(found, pageUrl)
            }
            if (currentRequest == seq && selectedLink == pageUrl) {
                resolving = false
                result.onSuccess { info ->
                    choices = info.choices
                    sourceFailures = info.failures
                    scope.launch {
                        val sized = runCatching {
                            StreamResolver.withSizeEstimates(info.choices)
                        }.getOrNull()
                        if (sized != null && currentRequest == seq && selectedLink == pageUrl) {
                            choices = sized
                        }
                    }
                    if (info.choices.isEmpty()) {
                        resolveError = "沒有可直接下載的影片來源。" +
                            if (info.failures.isNotEmpty())
                                "\n" + info.failures.take(4).joinToString("\n")
                            else " 請先播放影片再試。"
                    }
                }.onFailure { error ->
                    resolveError = error.message ?: "來源驗證失敗"
                }
            }
        }
    }

    // The whole completed download card uses the same playback and error
    // handling as the existing Play button.
    fun playDownloaded(item: DownloadRecord) {
        if (item.status != "完成") return
        runCatching { openVideo(context, item) }
            .onFailure { problem ->
                if (problem is IOException) {
                    DownloadStore.patch(context, item.id) {
                        it.copy(
                            status = "失敗",
                            error = problem.message ?: "影片檔案無效",
                            progress = 0
                        )
                    }
                    downloads = DownloadStore.read(context)
                }
                scope.launch {
                    snackbar.showSnackbar(problem.message ?: "無法播放影片")
                }
            }
    }

    LaunchedEffect(Unit) {
        while (true) {
            downloads = DownloadStore.read(context)
            delay(900)
        }
    }

    // Opening the download manager drops finished entries whose video file was
    // deleted outside the app, so the list never shows dead items.
    // A link shared into the app is parsed straight away (the paste box is gone).
    LaunchedEffect(Unit) {
        if (shared.trim().startsWith("https://")) showDownload(shared.trim())
    }

    LaunchedEffect(tab) {
        if (tab != 1) return@LaunchedEffect
        val missing = withContext(Dispatchers.IO) {
            DownloadStore.read(context)
                .filter { it.status == "完成" && it.uri.isNotBlank() }
                .filter { record ->
                    val uri = DownloadStore.contentUri(record) ?: return@filter false
                    val gone = runCatching {
                        context.contentResolver.openAssetFileDescriptor(uri, "r")
                            ?.use { false } ?: true
                    }.getOrElse { it is java.io.FileNotFoundException ||
                        it is IllegalArgumentException }
                    gone
                }
                .map { it.id }
        }
        if (missing.isNotEmpty()) {
            missing.forEach { DownloadStore.remove(context, it) }
            downloads = DownloadStore.read(context)
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Green, background = Navy, surface = Panel,
            onBackground = Color.White, onSurface = Color.White
        )
    ) {
        Scaffold(
            containerColor = Navy,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                // Browsing uses a single compact toolbar; keep download/settings
                // navigation in the overflow menu rather than shrinking the web page.
                if (tab != 0) NavigationBar(containerColor = Panel) {
                    NavigationBarItem(
                        selected = tab == 0, onClick = { tab = 0 },
                        icon = { Icon(Icons.Default.Language, null) }, label = { Text("瀏覽網站") }
                    )
                    NavigationBarItem(
                        selected = tab == 1, onClick = { tab = 1 },
                        icon = { Icon(Icons.Default.Download, null) }, label = { Text("下載管理") }
                    )
                    NavigationBarItem(
                        selected = tab == 2, onClick = { tab = 2 },
                        icon = { Icon(Icons.Default.Settings, null) }, label = { Text("設定") }
                    )
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                MultiTabBrowser(
                    visible = tab == 0,
                    adBlockEnabled = adsBlocked,
                    xDisplayOptions = xDisplayOptions,
                    downloadButtonOpacityPercent = downloadButtonOpacityPercent,
                    modifier = if (tab == 0) Modifier.fillMaxSize() else Modifier.size(0.dp),
                    onDownload = { showDownload(it) },
                    onDetectedVideos = { showDetectedChoices(it) },
                    onDownloads = { tab = 1 },
                    onSettings = { tab = 2 }
                )
                if (tab == 1) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().background(Navy),
                        contentPadding = PaddingValues(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Text("下載管理（" + downloads.size + "）",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold)
                        }
                        if (downloads.isEmpty()) {
                            item {
                                Text("目前沒有下載任務。", color = Soft,
                                    modifier = Modifier.fillMaxWidth().padding(20.dp))
                            }
                        }
                        items(downloads, key = { it.id }) { item ->
                            val running = item.status !in setOf("完成", "失敗", "已暫停", "待儲存")
                            Card(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable(enabled = item.status == "完成") {
                                        playDownloaded(item)
                                    },
                                colors = CardDefaults.cardColors(containerColor = Panel),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    DownloadThumb(item)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            item.title, fontWeight = FontWeight.Medium,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        val sizeText = if (item.bytes > 0L)
                                            " · " + Mp4Ranges.label(item.bytes) else ""
                                        Text(
                                            (if (item.status == "完成") "完成" else
                                                item.status + " " + item.progress + "%") +
                                                sizeText + " · " + item.label,
                                            color = Soft, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        if (running || item.repairProgress in 1..99) {
                                            Spacer(Modifier.height(4.dp))
                                            LinearProgressIndicator(
                                                progress = {
                                                    (if (item.repairProgress in 1..99)
                                                        item.repairProgress else item.progress) / 100f
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                        if (item.error.isNotBlank() && item.status != "完成") {
                                            Text(item.error, color = Color(0xFFFFBC92),
                                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    when (item.status) {
                                        "完成" -> IconButton(onClick = { playDownloaded(item) }) {
                                            Icon(Icons.Default.PlayArrow, "播放", tint = Green)
                                        }
                                        "已暫停", "失敗", "待儲存" -> IconButton(onClick = {
                                            DownloadService.start(context, item.id)
                                        }) { Icon(Icons.Default.PlayArrow, "繼續", tint = Green) }
                                        else -> IconButton(onClick = {
                                            DownloadService.pause(context, item.id)
                                        }) { Icon(Icons.Default.Pause, "暫停", tint = Green) }
                                    }
                                    IconButton(onClick = {
                                        deleteVideo(context, item)
                                        downloads = DownloadStore.read(context)
                                    }) {
                                        Icon(Icons.Default.Delete, "刪除",
                                            tint = Color(0xFFFFB1A8))
                                    }
                                }
                            }
                        }
                    }
                }
                if (tab == 2) {
                    DownloadSettingsScreen(
                        adsBlocked = adsBlocked,
                        onBlockChange = {
                            adsBlocked = it
                            DownloadSettings.setAdBlockEnabled(context, it)
                        },
                        xDisplayOptions = xDisplayOptions,
                        onXDisplayChange = { options ->
                            xDisplayOptions = options
                            DownloadSettings.setXDisplayOptions(context, options)
                        },
                        downloadButtonOpacityPercent = downloadButtonOpacityPercent,
                        onDownloadButtonOpacityChange = { percent ->
                            downloadButtonOpacityPercent = percent
                            DownloadSettings.setFloatingButtonOpacityPercent(context, percent)
                        }
                    )
                }

                // Keep the picker in the SAME Compose window and OVER the
                // existing browser box. ModalBottomSheet creates a separate
                // window and can change focus/insets while X's vertically
                // snapped video carousel is playing, briefly showing another
                // clip until the sheet closes. No WebView is resized or reloaded.
                if (selectedLink != null) {
                    BackHandler { selectedLink = null }

                    // A sibling overlay preserves the browser's exact
                    // constraints and blocks touches from reaching X.
                    Box(
                        Modifier.fillMaxSize()
                            .background(Color(0xA6000000))
                            .clickable { selectedLink = null }
                    )

                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { /* Consume taps on empty sheet space. */ },
                        color = Panel,
                        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                        shadowElevation = 18.dp
                    ) {
                        Column(
                            Modifier.fillMaxWidth().heightIn(max = 640.dp)
                                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 30.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "選擇下載畫質",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                TextButton(onClick = { selectedLink = null }) {
                                    Text("關閉")
                                }
                            }

                            // Keep the original download-resolution logic and
                            // validation; change only how its picker is shown.
                            if (resolving) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            } else if (choices.isEmpty()) {
                                Text(
                                    if (resolveError.isNotBlank()) "目前沒有可下載項目"
                                    else "沒有找到可下載項目",
                                    color = Soft, style = MaterialTheme.typography.bodyMedium
                                )
                                OutlinedButton(onClick = {
                                    if (candidateSources.isNotEmpty())
                                        showDetectedChoices(candidateSources)
                                    else selectedLink?.let { showDownload(it) }
                                }) { Text("重新偵測") }
                            }

                            choices.forEach { stream ->
                                Card(
                                    onClick = {
                                        runCatching {
                                            startDownload(context, stream)
                                            selectedLink = null
                                        }.onSuccess {
                                            scope.launch {
                                                snackbar.showSnackbar("已加入下載：" + stream.label)
                                            }
                                        }.onFailure {
                                            scope.launch {
                                                snackbar.showSnackbar(it.message ?: "無法開始下載")
                                            }
                                        }
                                    },
                                    colors = CardDefaults.cardColors(
                                        containerColor = Color(0xFF23384B)
                                    )
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                stream.label, color = Green,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis
                                            )
                                            if (stream.detail.isNotBlank()) {
                                                Text(
                                                    stream.detail, color = Soft,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    maxLines = 2, overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Icon(Icons.Default.Download, "下載此畫質", tint = Green)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


/** Small preview frame of a finished download, read from the saved video. */
@Composable
private fun DownloadThumb(item: DownloadRecord) {
    val context = LocalContext.current
    val frame by produceState<android.graphics.Bitmap?>(null, item.id, item.uri, item.status) {
        value = if (item.status != "完成" || item.uri.isBlank()) null else
            withContext(Dispatchers.IO) {
                runCatching {
                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, Uri.parse(item.uri))
                        retriever.getFrameAtTime(1_000_000L,
                            android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?.let { android.graphics.Bitmap.createScaledBitmap(
                                it, 160, (160f * it.height / it.width).toInt()
                                    .coerceAtLeast(1), true) }
                    } finally {
                        runCatching { retriever.release() }
                    }
                }.getOrNull()
            }
    }
    Box(
        Modifier.size(width = 80.dp, height = 56.dp).clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0B1826)),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = frame
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Default.Movie, null, tint = Soft)
        }
    }
}
