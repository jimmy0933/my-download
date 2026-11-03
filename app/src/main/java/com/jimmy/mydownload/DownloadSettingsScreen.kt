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
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun DownloadSettingsScreen(adsBlocked: Boolean, onBlockChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    var folder by remember { mutableStateOf(DownloadSettings.folderLabel(context)) }
    var customFolder by remember { mutableStateOf(DownloadSettings.folder(context).isNotEmpty()) }
    var error by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                DownloadSettings.setFolder(context, uri)
                folder = DownloadSettings.folderLabel(context)
                customFolder = true
                error = ""
            } catch (e: Exception) {
                error = "無法使用資料夾：" + (e.message ?: "沒有存取權限")
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

        if (error.isNotBlank()) Text(error, color = Color(0xFFFFB1A8))
    }
}
