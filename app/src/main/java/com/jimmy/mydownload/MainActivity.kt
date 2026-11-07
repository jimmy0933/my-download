package com.jimmy.mydownload

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

private val Navy = Color(0xFF091421)
private val Panel = Color(0xFF142333)
private val Green = Color(0xFF27DBB3)
private val Soft = Color(0xFFB4C8D5)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
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
    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    })
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
                result.onSuccess { choices = it }
                    .onFailure { resolveError = it.message ?: "影片解析失敗" }
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

    LaunchedEffect(Unit) {
        while (true) {
            downloads = DownloadStore.read(context)
            delay(900)
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
                        contentPadding = PaddingValues(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            Text("下載管理", style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(7.dp))
                            Text("貼上網址解析影片，或從瀏覽分頁直接選影片下載。", color = Soft)
                        }
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Panel),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedTextField(
                                        value = manualUrl, onValueChange = { manualUrl = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("HTTPS 影片網址") },
                                        maxLines = 3,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        OutlinedButton(
                                            onClick = {
                                                clipboard.getText()?.text?.let { manualUrl = it }
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) { Text("貼上連結") }
                                        Button(
                                            onClick = { showDownload(manualUrl.trim()) },
                                            enabled = manualUrl.trim().startsWith("https://"),
                                            modifier = Modifier.weight(1f)
                                        ) { Text("解析下載") }
                                    }
                                }
                            }
                        }
                        item {
                            Text("下載紀錄（" + downloads.size + "）",
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                        if (downloads.isEmpty()) {
                            item {
                                Text("目前沒有下載任務。", color = Soft,
                                    modifier = Modifier.fillMaxWidth().padding(20.dp))
                            }
                        }
                        items(downloads, key = { it.id }) { item ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Panel),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(9.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            item.title, fontWeight = FontWeight.Bold,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(item.label, color = Green)
                                    }
                                    LinearProgressIndicator(
                                        progress = { item.progress / 100f },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Text(item.status + " · " + item.progress + "%",
                                        color = Soft, style = MaterialTheme.typography.bodySmall)
                                    if (item.status == "完成" && item.bytes > 0L) {
                                        Text("已儲存 " + Mp4Ranges.label(item.bytes),
                                            color = Soft, style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (item.error.isNotBlank()) {
                                        Text(item.error, color = Color(0xFFFFBC92),
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (item.repairStatus.isNotBlank()) {
                                        Text(item.repairStatus, color = if (item.repairStatus.startsWith("修復失敗"))
                                            Color(0xFFFFBC92) else Green,
                                            style = MaterialTheme.typography.bodySmall)
                                        if (item.repairProgress in 1..99) {
                                            LinearProgressIndicator(
                                                progress = { item.repairProgress / 100f },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                    if (item.kind == "hls" && item.progress in 94..99 &&
                                        item.status !in setOf("完成", "失敗", "已暫停")) {
                                        Text("影片片段已下載，正在整理快轉索引或寫入手機。長片可能需要一些時間。",
                                            color = Soft, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Row(horizontalArrangement = Arrangement.End) {
                                        when (item.status) {
                                            "完成" -> {
                                                TextButton(onClick = {
                                                    runCatching { openVideo(context, item) }
                                                        .onFailure { problem ->
                                                            if (problem is IOException) {
                                                                DownloadStore.patch(context, item.id) {
                                                                    it.copy(status = "失敗",
                                                                        error = problem.message ?: "影片檔案無效",
                                                                        progress = 0)
                                                                }
                                                                downloads = DownloadStore.read(context)
                                                            }
                                                            scope.launch {
                                                                snackbar.showSnackbar(
                                                                    problem.message ?: "無法播放影片"
                                                                )
                                                            }
                                                        }
                                                }) { Text("播放") }
                                            }
                                            "已暫停", "失敗", "待儲存" -> TextButton(onClick = {
                                                DownloadService.start(context, item.id)
                                            }) { Text("繼續") }
                                            else -> TextButton(onClick = {
                                                DownloadService.pause(context, item.id)
                                            }) { Text("暫停") }
                                        }
                                        if (item.kind == "hls" && item.progress in 94..99 &&
                                            item.status !in setOf("完成", "失敗", "已暫停")) {
                                            TextButton(onClick = {
                                                DownloadService.pause(context, item.id)
                                                DownloadService.start(context, item.id)
                                            }) { Text("重試整理") }
                                        }
                                        TextButton(onClick = {
                                            deleteVideo(context, item)
                                            downloads = DownloadStore.read(context)
                                        }) { Text("刪除", color = Color(0xFFFFB1A8)) }
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
            }
        }

        if (selectedLink != null) {
            ModalBottomSheet(
                onDismissRequest = { selectedLink = null },
                containerColor = Panel
            ) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 640.dp)
                        .verticalScroll(androidx.compose.foundation.rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, bottom = 30.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Show only actionable, validated download options.
                    // Technical source URLs, media IDs and failed probe logs stay
                    // out of the picker; they are not useful when choosing quality.
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
                                    scope.launch { snackbar.showSnackbar("已加入下載：" + stream.label) }
                                }.onFailure {
                                    scope.launch {
                                        snackbar.showSnackbar(it.message ?: "無法開始下載")
                                    }
                                }
                            },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF23384B))
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stream.label,
                                    color = Green,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
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
