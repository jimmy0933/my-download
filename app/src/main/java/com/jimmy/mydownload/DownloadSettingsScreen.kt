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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun DownloadSettingsScreen(adsBlocked: Boolean, onBlockChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    var folder by remember { mutableStateOf(DownloadSettings.folderLabel(context)) }
    var customFolder by remember { mutableStateOf(DownloadSettings.folder(context).isNotEmpty()) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var autoRepair by remember { mutableStateOf(DownloadSettings.autoRepairMp4(context)) }
    var repairOptions by remember { mutableStateOf<List<DownloadRecord>>(emptyList()) }
    var selectedRepairId by remember { mutableLongStateOf(-1L) }
    var repairMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            val eligible = DownloadStore.read(context)
                .filter { it.isMp4RepairCandidate() }
                .take(60)
            repairOptions = eligible
            if (eligible.none { it.id == selectedRepairId }) {
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
            }
        }

        if (notice.isNotBlank()) Text(notice, color = Color(0xFF27DBB3))
        if (error.isNotBlank()) Text(error, color = Color(0xFFFFB1A8))
    }
}
