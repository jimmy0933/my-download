package com.jimmy.mydownload

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
private fun XVisibilitySetting(
    title: String,
    description: String,
    hidden: Boolean,
    onHiddenChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, color = Color(0xFF9CAFC0),
                style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = hidden, onCheckedChange = onHiddenChange)
    }
}

@Composable
fun DownloadSettingsScreen(
    adsBlocked: Boolean,
    onBlockChange: (Boolean) -> Unit,
    xDisplayOptions: XDisplayOptions,
    onXDisplayChange: (XDisplayOptions) -> Unit,
    downloadButtonOpacityPercent: Int,
    onDownloadButtonOpacityChange: (Int) -> Unit
) {
    val context = LocalContext.current
    var folder by remember { mutableStateOf(DownloadSettings.folderLabel(context)) }
    var customFolder by remember { mutableStateOf(DownloadSettings.folder(context).isNotEmpty()) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var autoRepair by remember { mutableStateOf(DownloadSettings.autoRepairMp4(context)) }
    var repairOptions by remember { mutableStateOf<List<DownloadRecord>>(emptyList()) }
    var selectedRepairId by remember { mutableLongStateOf(-1L) }
    var repairMenuExpanded by remember { mutableStateOf(false) }
    var repairResult by remember { mutableStateOf("") }
    var preferredPlayer by remember { mutableStateOf(DownloadSettings.videoPlayer(context)) }
    var playerMenuExpanded by remember { mutableStateOf(false) }
    val installedPlayers = remember(context) { VideoPlayerApps.options(context) }

    LaunchedEffect(Unit) {
        while (true) {
            val all = DownloadStore.read(context)
            val previous = all.firstOrNull { it.id == selectedRepairId }
            if (previous != null &&
                previous.repairProgress == 100 &&
                previous.repairStatus.contains("修復完成") &&
                !previous.isMp4RepairCandidate()) {
                repairResult = previous.title + "：" + previous.repairStatus
            }
            val eligible = all.filter { it.isMp4RepairCandidate() }.take(60)
            repairOptions = eligible
            val cleaningUp = previous?.repairStatus?.contains("正在清理舊影片") == true &&
                previous.repairProgress < 100
            if (eligible.none { it.id == selectedRepairId } && !cleaningUp) {
                selectedRepairId = eligible.firstOrNull()?.id ?: -1L
            }
            delay(900L)
        }
    }
    val selectedRepair = repairOptions.firstOrNull { it.id == selectedRepairId }
    val repairingNow = selectedRepair != null &&
        selectedRepair.repairProgress in 1..99 &&
        selectedRepair.repairStatus != "已暫停修復" &&
        !selectedRepair.repairStatus.startsWith("修復失敗")

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                // Test real creation and writing instead of comparing URI strings.
                // Do not record a location that the provider refuses to write to.
                DownloadSettings.testFolderWrite(context, uri)
                DownloadSettings.setFolder(context, uri)
                folder = DownloadSettings.folderLabel(context)
                customFolder = true
                val repaired = DownloadStore.repairFailedDestinations(context, uri.toString())
                error = ""
                notice = if (repaired > 0) {
                    "資料夾寫入測試成功，已修復 $repaired 筆失敗任務，可在下載管理按「繼續」。"
                } else "資料夾寫入測試成功，之後的新下載會儲存到這裡。"
            } catch (e: Exception) {
                notice = ""
                error = "資料夾無法寫入：" + (e.message ?: "Android 沒有授予寫入權限") +
                    "。請選擇已建立的子資料夾，並在系統確認允許存取。"
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF091421))
            .verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(17.dp)
    ) {
        Text("設定", style = MaterialTheme.typography.headlineMedium,
            color = Color.White, fontWeight = FontWeight.Bold)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF142333)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, null, tint = Color(0xFF27DBB3))
                    Spacer(Modifier.width(9.dp))
                    Text("下載位置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Text(folder, color = Color(0xFFB4C8D5))
                Text("MP4 與 HLS 都儲存到指定資料夾；更改後僅影響新加入的下載。",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFF9CAFC0))
                Button(
                    onClick = { picker.launch(null) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("選擇資料夾") }
                if (customFolder) {
                    OutlinedButton(
                        onClick = {
                            DownloadSettings.setFolder(context, null)
                            folder = DownloadSettings.folderLabel(context)
                            customFolder = false
                            error = ""
                            notice = "已還原預設下載位置。"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("還原預設位置") }
                }
                Text("Android 11 以上不能直接選根目錄或整個 Downloads，請使用其子資料夾。",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFF9CAFC0))
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF142333)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, null, tint = Color(0xFF27DBB3))
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text("阻擋廣告頁", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Text(if (adsBlocked) "已啟用" else "已停用",
                            style = MaterialTheme.typography.bodySmall, color = Color(0xFF9CAFC0))
                    }
                    Switch(checked = adsBlocked, onCheckedChange = onBlockChange)
                }
                Text("啟用後會阻止常見廣告網域與外站彈出頁，保留 AV01 的內部導覽。",
                    color = Color(0xFFB4C8D5), style = MaterialTheme.typography.bodySmall)
                Text("廣告阻擋是輕量功能，無法保證清除全部廣告。若播放器或網站按鈕異常可關閉。",
                    color = Color(0xFF9CAFC0), style = MaterialTheme.typography.bodySmall)
            }
        }


        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF142333)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("下載圖示", style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF27DBB3), fontWeight = FontWeight.Bold)
                Text("影片下載按鈕只顯示圖示，按一下下載；長按拖曳後會記住位置。",
                    color = Color(0xFFB4C8D5), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("按鈕透明度", modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Medium)
                    Text("${downloadButtonOpacityPercent}%",
                        color = Color(0xFF27DBB3),
                        style = MaterialTheme.typography.titleMedium)
                }
                Slider(
                    value = downloadButtonOpacityPercent.toFloat(),
                    onValueChange = { amount ->
                        onDownloadButtonOpacityChange(
                            ((amount / 5f).roundToInt() * 5).coerceIn(20, 100)
                        )
                    },
                    valueRange = 20f..100f, steps = 15
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("較透明 20%", color = Color(0xFF9CAFC0),
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onDownloadButtonOpacityChange(85) }) {
                        Text("預設 85%")
                    }
                    Text("不透明 100%", color = Color(0xFF9CAFC0),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF142333)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color(0xFF27DBB3))
                    Spacer(Modifier.width(9.dp))
                    Text("影片播放器", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                }
                Text("從手機已安裝的播放器選擇播放方式；下次在下載管理按「播放」就會使用。",
                    color = Color(0xFFB4C8D5), style = MaterialTheme.typography.bodySmall)
                Box {
                    OutlinedButton(
                        onClick = { playerMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(VideoPlayerApps.label(context, preferredPlayer),
                            modifier = Modifier.weight(1f), maxLines = 1)
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = playerMenuExpanded,
                        onDismissRequest = { playerMenuExpanded = false }
                    ) {
                        installedPlayers.forEach { player ->
                            DropdownMenuItem(
                                text = { Text(player.title) },
                                onClick = {
                                    preferredPlayer = player.id
                                    DownloadSettings.setVideoPlayer(context, player.id)
                                    playerMenuExpanded = false
                                    notice = "已儲存播放設定：" + player.title
                                },
                                leadingIcon = {
                                    RadioButton(
                                        selected = preferredPlayer == player.id,
                                        onClick = null
                                    )
                                }
                            )
                        }
                    }
                }
                Text("可選系統預設、每次詢問，或已安裝的播放器。此設定用於下載完成的影片；X 網頁播放仍使用網頁播放器。",
                    color = Color(0xFF9CAFC0), style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF142333)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Text("X 網頁顯示設定", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = Color(0xFF27DBB3))
                Text(
                    "只影響 App 內開啟的 X／Twitter 網頁。開啟開關代表隱藏，關閉即可還原。設定會自動儲存。",
                    color = Color(0xFFB4C8D5), style = MaterialTheme.typography.bodySmall
                )
                HorizontalDivider(color = Color(0xFF304458))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("X 文字大小", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text("${xDisplayOptions.fontSizePercent}%", color = Color(0xFF27DBB3),
                        style = MaterialTheme.typography.titleMedium)
                }
                Slider(
                    value = xDisplayOptions.fontSizePercent.toFloat(),
                    onValueChange = { amount ->
                        onXDisplayChange(
                            xDisplayOptions.copy(
                                fontSizePercent = ((amount / 10f).roundToInt() * 10)
                                    .coerceIn(80, 160)
                            )
                        )
                    },
                    valueRange = 80f..160f, steps = 7
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("較小 80%", color = Color(0xFF9CAFC0),
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        onXDisplayChange(xDisplayOptions.copy(fontSizePercent = 100))
                    }) { Text("恢復 100%") }
                    Text("較大 160%", color = Color(0xFF9CAFC0),
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("僅調整 X 網頁文字，不改變 App 選單大小。變更後返回網頁即可套用。",
                    color = Color(0xFF9CAFC0), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(color = Color(0xFF304458))
                XVisibilitySetting(
                    title = "隱藏回覆按鈕",
                    description = "隱藏貼文底下的回覆圖示及數字，不會刪除回覆內容。",
                    hidden = xDisplayOptions.hideReply,
                    onHiddenChange = { onXDisplayChange(xDisplayOptions.copy(hideReply = it)) }
                )
                XVisibilitySetting(
                    title = "隱藏轉發按鈕",
                    description = "包含轉發和取消轉發圖示。",
                    hidden = xDisplayOptions.hideRepost,
                    onHiddenChange = { onXDisplayChange(xDisplayOptions.copy(hideRepost = it)) }
                )
                XVisibilitySetting(
                    title = "隱藏觀看次數",
                    description = "隱藏貼文的觀看數及分析圖示。",
                    hidden = xDisplayOptions.hideViews,
                    onHiddenChange = { onXDisplayChange(xDisplayOptions.copy(hideViews = it)) }
                )
                XVisibilitySetting(
                    title = "隱藏分享按鈕",
                    description = "隱藏貼文的分享圖示。",
                    hidden = xDisplayOptions.hideShare,
                    onHiddenChange = { onXDisplayChange(xDisplayOptions.copy(hideShare = it)) }
                )
                HorizontalDivider(color = Color(0xFF304458))
                XVisibilitySetting(
                    title = "觀看圖片／影片時隱藏文字說明",
                    description = "只隱藏 X 媒體檢視器裡的貼文內文，其他貼文仍正常顯示。",
                    hidden = xDisplayOptions.hideMediaDescription,
                    onHiddenChange = {
                        onXDisplayChange(xDisplayOptions.copy(hideMediaDescription = it))
                    }
                )
                XVisibilitySetting(
                    title = "觀看圖片／影片時隱藏標題",
                    description = "只隱藏媒體檢視器中的標題，保留圖片、影片與關閉等操作。",
                    hidden = xDisplayOptions.hideMediaTitle,
                    onHiddenChange = {
                        onXDisplayChange(xDisplayOptions.copy(hideMediaTitle = it))
                    }
                )
                HorizontalDivider(color = Color(0xFF304458))
                Text("X 播放進度條", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(
                    "預設保留 X 原本的進度條。兩個選項可獨立控制；若新進度條出現問題，關閉開關即可恢復。",
                    color = Color(0xFF9CAFC0), style = MaterialTheme.typography.bodySmall
                )
                XVisibilitySetting(
                    title = "隱藏 X 原本進度條",
                    description = "僅隱藏影片進度線，保留播放、音量與其他控制按鈕。",
                    hidden = xDisplayOptions.hideNativeXVideoProgress,
                    onHiddenChange = {
                        onXDisplayChange(xDisplayOptions.copy(hideNativeXVideoProgress = it))
                    }
                )
                XVisibilitySetting(
                    title = "顯示新版進度條",
                    description = "顯示可點擊及拖曳快轉的細進度條，不替換影片或調整播放器版面。",
                    hidden = xDisplayOptions.showCustomXVideoProgress,
                    onHiddenChange = {
                        onXDisplayChange(xDisplayOptions.copy(showCustomXVideoProgress = it))
                    }
                )
                if (xDisplayOptions.hideNativeXVideoProgress &&
                    !xDisplayOptions.showCustomXVideoProgress) {
                    Text(
                        "提醒：目前兩種進度條都不顯示。如需拖曳快轉，請開啟新版進度條或恢復 X 原本進度條。",
                        color = Color(0xFFFFC48A),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                HorizontalDivider(color = Color(0xFF304458))
                Text("X 影片底部操作列", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                XVisibilitySetting(
                    title = "影片底部隱藏觀看次數",
                    description = "只隱藏影片播放畫面底下的觀看數及圖表按鈕，不影響播放進度條。",
                    hidden = xDisplayOptions.hideViewerViews,
                    onHiddenChange = {
                        onXDisplayChange(xDisplayOptions.copy(hideViewerViews = it))
                    }
                )
                XVisibilitySetting(
                    title = "影片底部隱藏分享按鈕",
                    description = "只隱藏影片下方的分享圖示，保留其他播放及下載功能。",
                    hidden = xDisplayOptions.hideViewerShare,
                    onHiddenChange = {
                        onXDisplayChange(xDisplayOptions.copy(hideViewerShare = it))
                    }
                )
                HorizontalDivider(color = Color(0xFF304458))
                XVisibilitySetting(
                    title = "播放時隱藏進度條下方資訊",
                    description = "一鍵隱藏 X 影片進度條下方的帳號名稱、頭像及貼文說明；保留影片、進度條、播放控制與下載按鈕。",
                    hidden = xDisplayOptions.hideVideoBelowProgressDetails,
                    onHiddenChange = {
                        onXDisplayChange(xDisplayOptions.copy(hideVideoBelowProgressDetails = it))
                    }
                )
                XVisibilitySetting(
                    title = "只隱藏影片下方內文",
                    description = "僅隱藏貼文說明文字，不隱藏作者資訊；適合想保留帳號名稱的情況。",
                    hidden = xDisplayOptions.hideVideoPlaybackText,
                    onHiddenChange = {
                        onXDisplayChange(xDisplayOptions.copy(hideVideoPlaybackText = it))
                    }
                )
                XVisibilitySetting(
                    title = "只隱藏影片下方作者／標題",
                    description = "僅隱藏帳號名稱、頭像和作者資訊，進度條與貼文內文仍保留。",
                    hidden = xDisplayOptions.hideVideoPlaybackTitle,
                    onHiddenChange = {
                        onXDisplayChange(xDisplayOptions.copy(hideVideoPlaybackTitle = it))
                    }
                )
                HorizontalDivider(color = Color(0xFF304458))
                Text("底部導覽列", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                XVisibilitySetting(
                    title = "隱藏整排底部導覽列",
                    description = "一次隱藏整排；以下個別選項會保留，重新顯示時仍有效。",
                    hidden = xDisplayOptions.hideBottomNavigation,
                    onHiddenChange = {
                        onXDisplayChange(xDisplayOptions.copy(hideBottomNavigation = it))
                    }
                )
                HorizontalDivider(color = Color(0xFF304458))
                XVisibilitySetting(
                    title = "隱藏 Home／首頁",
                    description = "只隱藏底部首頁按鈕。",
                    hidden = xDisplayOptions.hideBottomHome,
                    onHiddenChange = {
                        onXDisplayChange(xDisplayOptions.copy(hideBottomHome = it))
                    }
                )
                XVisibilitySetting(
                    title = "隱藏搜尋",
                    description = "只隱藏底部搜尋／Explore 按鈕。",
                    hidden = xDisplayOptions.hideBottomSearch,
                    onHiddenChange = {
                        onXDisplayChange(xDisplayOptions.copy(hideBottomSearch = it))
                    }
                )
                XVisibilitySetting(
                    title = "隱藏 Grok",
                    description = "只隱藏底部 Grok 按鈕。",
                    hidden = xDisplayOptions.hideBottomGrok,
                    onHiddenChange = {
                        onXDisplayChange(xDisplayOptions.copy(hideBottomGrok = it))
                    }
                )
                XVisibilitySetting(
                    title = "隱藏聊天",
                    description = "只隱藏底部聊天／訊息按鈕。",
                    hidden = xDisplayOptions.hideBottomChat,
                    onHiddenChange = {
                        onXDisplayChange(xDisplayOptions.copy(hideBottomChat = it))
                    }
                )
                XVisibilitySetting(
                    title = "隱藏通知",
                    description = "只隱藏底部通知按鈕。",
                    hidden = xDisplayOptions.hideBottomNotifications,
                    onHiddenChange = {
                        onXDisplayChange(xDisplayOptions.copy(hideBottomNotifications = it))
                    }
                )
                XVisibilitySetting(
                    title = "隱藏底部分享",
                    description = "只隱藏底部工具列／媒體檢視器的分享按鈕，不影響下載鈕。",
                    hidden = xDisplayOptions.hideBottomShare,
                    onHiddenChange = {
                        onXDisplayChange(xDisplayOptions.copy(hideBottomShare = it))
                    }
                )
                Text("部分 X 版本可能調整按鈕位置；此功能僅隱藏畫面元素，不影響帳號設定。",
                    color = Color(0xFF9CAFC0), style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF142333)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Build, null, tint = Color(0xFF27DBB3))
                    Spacer(Modifier.width(9.dp))
                    Text(
                        "影片修復",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("下載完成後自動修復 MP4", fontWeight = FontWeight.Medium)
                        Text(
                            if (autoRepair) "啟用：下載完成後建立 MP4 快轉索引"
                            else "停用：先保留原始影片，之後可手動修復",
                            color = Color(0xFF9CAFC0), style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = autoRepair,
                        onCheckedChange = { enabled ->
                            autoRepair = enabled
                            DownloadSettings.setAutoRepairMp4(context, enabled)
                        }
                    )
                }
                Text(
                    "只影響新加入的 HLS 下載。修復不設時間上限；若影片格式不支援，會顯示處理錯誤。",
                    color = Color(0xFFB4C8D5), style = MaterialTheme.typography.bodySmall
                )
                HorizontalDivider(color = Color(0xFF304458))
                Text("手動修復已下載影片", fontWeight = FontWeight.Bold)
                if (repairOptions.isEmpty()) {
                    Text(
                        "目前沒有需要修復的已完成影片。",
                        color = Color(0xFF9CAFC0), style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Box {
                        OutlinedButton(
                            onClick = { repairMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                selectedRepair?.title ?: "選擇需要修復的影片",
                                maxLines = 1
                            )
                        }
                        DropdownMenu(
                            expanded = repairMenuExpanded,
                            onDismissRequest = { repairMenuExpanded = false }
                        ) {
                            repairOptions.forEach { video ->
                                DropdownMenuItem(
                                    text = { Text(video.title, maxLines = 2) },
                                    onClick = {
                                        selectedRepairId = video.id
                                        repairMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    if (selectedRepair != null) {
                        if (selectedRepair.repairStatus.isNotBlank()) {
                            Text(
                                selectedRepair.repairStatus,
                                color = if (selectedRepair.repairStatus.startsWith("修復失敗"))
                                    Color(0xFFFFB1A8) else Color(0xFF27DBB3),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (selectedRepair.repairProgress in 1..99) {
                            LinearProgressIndicator(
                                progress = { selectedRepair.repairProgress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    Button(
                        onClick = {
                            selectedRepair?.let { video ->
                                if (repairingNow) DownloadService.pause(context, video.id)
                                else DownloadService.repair(context, video.id)
                            }
                        },
                        enabled = selectedRepair != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (repairingNow) "暫停修復" else "開始修復 MP4")
                    }
                }
                Text(
                    "修復使用已下載的影片，不會重新下載。新 MP4 成功驗證後會自動刪除舊影片；修復失敗或暫停時不刪除。",
                    color = Color(0xFF9CAFC0),
                    style = MaterialTheme.typography.bodySmall
                )
                if (repairResult.isNotBlank()) {
                    Text(repairResult, color = Color(0xFF27DBB3),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Button(
            onClick = {
                // Other preferences and destination are already stored when edited.
                // This explicit action re-saves all current display/repair choices.
                DownloadSettings.setXDisplayOptions(context, xDisplayOptions)
                DownloadSettings.setFloatingButtonOpacityPercent(
                    context, downloadButtonOpacityPercent
                )
                DownloadSettings.setAdBlockEnabled(context, adsBlocked)
                DownloadSettings.setAutoRepairMp4(context, autoRepair)
                DownloadSettings.setVideoPlayer(context, preferredPlayer)
                notice = "所有設定已儲存，重開 App 仍會保留。"
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("儲存目前設定") }
        Text("選項變更時也會自動儲存；此按鈕可再次確認寫入。",
            color = Color(0xFF9CAFC0), style = MaterialTheme.typography.bodySmall)
        if (notice.isNotBlank()) Text(notice, color = Color(0xFF27DBB3))
        if (error.isNotBlank()) Text(error, color = Color(0xFFFFB1A8))
    }
}
