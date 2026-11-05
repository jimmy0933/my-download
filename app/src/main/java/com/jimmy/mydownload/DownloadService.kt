package com.jimmy.mydownload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

class DownloadService : Service() {
    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 2026
        private const val ACTION_START = "download"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_REPAIR = "repair"
        private const val EXTRA_ID = "id"

        fun start(context: Context, id: Long) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ID, id)
            })
        }

        fun repair(context: Context, id: Long) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java).apply {
                action = ACTION_REPAIR
                putExtra(EXTRA_ID, id)
            })
        }

        fun pause(context: Context, id: Long) {
            context.startService(Intent(context, DownloadService::class.java).apply {
                action = ACTION_PAUSE
                putExtra(EXTRA_ID, id)
            })
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val restartAfterStop = ConcurrentHashMap<Long, String>()

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "下載中", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
        if (intent?.action == ACTION_PAUSE) {
            restartAfterStop.remove(id)
            jobs[id]?.cancel()
            DownloadStore.patch(this, id) { record ->
                if (record.status == "完成") record.copy(repairStatus = "已暫停修復")
                else record.copy(status = "已暫停")
            }
            if (jobs.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }
        val repairRequest = intent?.action == ACTION_REPAIR
        if (intent?.action != ACTION_START && !repairRequest || id < 0) return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, notification(
            if (repairRequest) "準備修復 MP4" else "準備下載", 0
        ))
        jobs[id]?.let { existing ->
            // A rapid Pause -> Continue can arrive before cancelled work finishes.
            // Queue the restart rather than silently dropping the user's request.
            if (existing.isCancelled || !existing.isActive) {
                restartAfterStop[id] = if (repairRequest) ACTION_REPAIR else ACTION_START
            }
            return START_NOT_STICKY
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val record = DownloadStore.get(this@DownloadService, id) ?: return@launch
                if (repairRequest) {
                    if (record.kind != "hls" || record.status != "完成")
                        throw IOException("只有已完成的 HLS 影片可離線修復 MP4")
                    repairMp4(record)
                } else {
                    when (record.kind) {
                        "mp4" -> downloadMp4(record)
                        "hls" -> downloadHls(record)
                        else -> throw IOException("尚未支援的影片格式")
                    }
                }
            } catch (_: CancellationException) {
                DownloadStore.patch(this@DownloadService, id) {
                    if (repairRequest) it.copy(repairStatus = "已暫停修復", repairProgress = 0)
                    else it.copy(status = "已暫停")
                }
            } catch (e: Exception) {
                DownloadStore.patch(this@DownloadService, id) {
                    if (repairRequest) it.copy(
                        repairStatus = "修復失敗：" + (e.message ?: "未知原因"),
                        repairProgress = 0
                    ) else it.copy(status = "失敗", error = e.message ?: "下載中斷")
                }
            } finally {
                jobs.remove(id)
                when (restartAfterStop.remove(id)) {
                    ACTION_REPAIR -> repair(this@DownloadService, id)
                    ACTION_START -> start(this@DownloadService, id)
                    else -> if (jobs.isEmpty()) stopSelf()
                }
            }
        }
        if (jobs.putIfAbsent(id, job) == null) job.start()
        return START_NOT_STICKY
    }

    private fun notification(title: String, percentage: Int): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("My Download")
            .setContentText(title)
            .setOnlyAlertOnce(true)
            .setProgress(100, percentage.coerceIn(0, 100), percentage <= 0)
            .setOngoing(percentage < 100)
            .build()

    private fun progress(
        item: DownloadRecord, status: String, pct: Int,
        bytes: Long? = null, checkpoint: Int? = null
    ) {
        DownloadStore.patch(this, item.id) {
            it.copy(status = status, progress = pct,
                bytes = bytes ?: it.bytes, checkpoint = checkpoint ?: it.checkpoint, error = "")
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(item.title, pct))
    }

    private fun tempFile(id: Long): File {
        val dir = File(filesDir, "temp_downloads")
        dir.mkdirs()
        return File(dir, id.toString() + ".part")
    }

    private suspend fun downloadMp4(item: DownloadRecord) {
        val partial = tempFile(item.id)
        if ((item.readyToFinalize || item.status == "待儲存") &&
            partial.isFile && partial.length() > 0L) {
            verifyMp4Downloaded(partial)
            finish(item, partial, "mp4", "video/mp4")
            return
        }

        var current = if (partial.isFile) partial.length() else 0L
        val isXMedia = runCatching {
            val host = URL(item.url).host.lowercase()
            host == "video.twimg.com"
        }.getOrDefault(false)
        progress(item, "正在連線影片來源", 0, current)

        // X's CDN may serve 206 chunks even if a request asked for the remaining
        // file. Keep requesting the next range until Content-Range says all
        // bytes have arrived. Other servers may return a complete HTTP 200.
        var lastTotal: Long? = null
        for (requestNumber in 0 until 1024) {
            coroutineContext.ensureActive()
            val request = BrowserMediaRequest.builder(item.url, item.referer, item.userAgent)
                .apply {
                    if (current > 0L || isXMedia) {
                        header("Range", "bytes=" + current + "-")
                    }
                }.build()

            var restart = false
            var finished = false
            StreamResolver.client.newCall(request).execute().use { response ->
                if (response.code == 416) {
                    val total = Mp4Ranges.totalFor416(response.header("Content-Range"))
                    if (total != null && total == current && partial.isFile) {
                        verifyMp4Downloaded(partial)
                        finished = true
                    } else {
                        restart = true
                    }
                } else {
                    if (response.code != 200 && response.code != 206) {
                        val detail = if (response.code == 401 || response.code == 403)
                            "（X 影片可能已過期或站方不允許直接下載，請回到貼文播放後重新偵測）"
                        else ""
                        throw IOException("MP4 來源 HTTP " + response.code + detail)
                    }
                    val mime = response.header("Content-Type").orEmpty().lowercase()
                    if (mime.contains("text/html") || mime.contains("application/json")) {
                        throw IOException("下載網址回傳網頁而不是 MP4 影片")
                    }
                    val range = if (response.code == 206)
                        Mp4Ranges.parse(response.header("Content-Range")) else null
                    if (response.code == 206 && range == null) {
                        throw IOException("影片伺服器沒有提供完整的 Content-Range，無法確保下載完整")
                    }
                    if (range != null && range.start != current) {
                        throw IOException("影片伺服器的續傳位置不一致：" +
                            range.start + " / " + current)
                    }
                    val append = current > 0L && response.code == 206
                    if (!append) current = 0L
                    val body = response.body ?: throw IOException("影片伺服器回傳空白內容")
                    val total = Mp4Ranges.totalForResponse(
                        response.code,
                        response.header("Content-Range"),
                        body.contentLength().takeIf { it > 0L }
                    )
                    lastTotal = total
                    var updated = 0L
                    var fetched = 0L
                    FileOutputStream(partial, append).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                current += count
                                fetched += count
                                val now = SystemClock.elapsedRealtime()
                                if (now - updated > 400L) {
                                    updated = now
                                    val percent = if (total != null && total > 0L)
                                        (current * 93L / total).toInt().coerceIn(0, 93) else 0
                                    progress(
                                        item,
                                        "下載中 " + (current / 1024L) + " KB" +
                                            if (total != null) " / " + (total / 1024L) + " KB"
                                            else "",
                                        percent, current
                                    )
                                }
                            }
                        }
                    }
                    if (fetched <= 0L) throw IOException("來源未回傳任何 MP4 影片資料")
                    if (range != null) {
                        if (fetched != range.length) {
                            throw IOException("MP4 分段資料不完整：" +
                                fetched + " / " + range.length)
                        }
                        if (total != null && current > total) {
                            throw IOException("MP4 接收資料超過影片總大小")
                        }
                        if (total != null && current == total) {
                            finished = true
                        } else if (fetched < 1024L) {
                            throw IOException("伺服器只回傳 " + fetched +
                                " bytes，無法取得完整影片")
                        }
                    } else {
                        if (total != null && current != total) {
                            throw IOException("MP4 下載不完整：" + current + " / " + total)
                        }
                        finished = true
                    }
                }
            }

            if (restart) {
                partial.delete()
                current = 0L
                if (requestNumber > 0) throw IOException("來源不接受續傳，請重新偵測影片")
                continue
            }
            if (finished) {
                verifyMp4Downloaded(partial)
                DownloadStore.patch(this, item.id) {
                    it.copy(readyToFinalize = true, status = "待儲存",
                        bytes = partial.length(), progress = 94)
                }
                finish(item, partial, "mp4", "video/mp4")
                return
            }
        }
        throw IOException("影片分段下載次數過多，已保存 " +
            (partial.length() / 1024L) + " KB" +
            (lastTotal?.let { " / " + (it / 1024L) + " KB" } ?: "") +
            "，可以按「繼續」再下載")
    }

    private fun verifyMp4Downloaded(partial: File) {
        val size = partial.takeIf { it.isFile }?.length() ?: 0L
        if (!Mp4Ranges.isPlausibleVideoSize(size)) {
            throw IOException("MP4 只有 " + Mp4Ranges.label(size) +
                "，很可能只是 X 播放器的初始化片段，非完整影片。請返回 X 選擇 HLS 來源。")
        }
        val header = ByteArray(12)
        partial.inputStream().use { input ->
            var offset = 0
            while (offset < 12) {
                val n = input.read(header, offset, 12 - offset)
                if (n < 0) break
                offset += n
            }
            if (offset != 12) throw IOException("MP4 標頭不完整")
        }
        val atom = String(header, 4, 4, Charsets.US_ASCII)
        if (atom !in setOf("ftyp", "styp")) {
            throw IOException("MP4 回應沒有有效容器標頭")
        }
        verifyMp4(partial)
    }
    private data class Segments(val list: List<String>, val fragmented: Boolean)

    private fun parseSegments(
        text: String, base: String, referer: String, userAgent: String = ""
    ): Segments {
        if (!text.trimStart().startsWith("#EXTM3U")) throw IOException("M3U8 內容格式錯誤")
        if (text.contains("#EXT-X-KEY:", true) || text.contains("#EXT-X-SESSION-KEY:", true)) {
            throw IOException("加密 HLS 不支援")
        }
        if (text.contains("#EXT-X-BYTERANGE:", true) || text.contains("#EXT-X-DISCONTINUITY", true)) {
            throw IOException("此 HLS 使用尚未支援的串流格式")
        }
        if (text.contains("#EXT-X-STREAM-INF:", true)) {
            val best = StreamResolver.parseManifest(text, base, "影片", referer).first()
            val token = runCatching { base.toHttpUrl().queryParameter("access_token") }.getOrNull()
            val next = if (token.isNullOrBlank()) best.url else
                best.url.toHttpUrl().newBuilder().addQueryParameter("access_token", token)
                    .build().toString()
            return parseSegments(StreamResolver.fetchText(next, referer, userAgent),
                next, referer, userAgent)
        }
        val token = runCatching { base.toHttpUrl().queryParameter("access_token") }.getOrNull()
        fun absolute(raw: String): String {
            val address = URL(URL(base), raw).toString()
            if (!address.startsWith("https://")) throw IOException("不支援非 HTTPS 片段")
            if (token.isNullOrBlank()) return address
            val url = address.toHttpUrl()
            return if (url.queryParameter("access_token") != null) address else
                url.newBuilder().addQueryParameter("access_token", token).build().toString()
        }
        val segments = mutableListOf<String>()
        var fragmented = false
        var mediaSegments = 0
        for (line in text.lines().map(String::trim)) {
            if (line.startsWith("#EXT-X-MAP:", true)) {
                val init = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1)
                    ?: throw IOException("缺少 HLS 初始化片段")
                segments += absolute(init)
                fragmented = true
            } else if (line.isNotBlank() && !line.startsWith("#")) {
                mediaSegments++
                segments += absolute(line)
                if (line.substringBefore('?').endsWith(".m4s", true)) fragmented = true
            }
        }
        if (mediaSegments == 0) {
            throw IOException("HLS 只有初始化資料，沒有實際的影片片段")
        }
        if (segments.size > 20000) throw IOException("影片片段數量過多")
        return Segments(segments, fragmented)
    }

    private suspend fun downloadHls(original: DownloadRecord) {
        var item = original
        val partial = tempFile(item.id)

        // Older releases reached 96% after all segments were downloaded, but
        // accidentally reset checkpoint and byte count. Preserve and finalize
        // that local file instead of truncating it and re-downloading everything.
        val legacyCompleted = item.progress >= 96 && item.checkpoint == 0 &&
            partial.isFile && partial.length() > 0L
        if ((item.readyToFinalize || legacyCompleted) && partial.isFile && partial.length() > 0L) {
            DownloadStore.patch(this, item.id) {
                it.copy(readyToFinalize = true, status = "準備整理已下載影片",
                    bytes = partial.length(), error = "")
            }
            finalizeHls(item, partial, isFragmentedMp4(partial))
            return
        }

        progress(item, "解析串流中", item.progress)
        if (Av01Resolver.supports(item.referer)) {
            val refreshed = runCatching { Av01Resolver.inspect(item.referer) }
                .getOrNull()?.firstOrNull { it.label == item.label }
            if (refreshed != null) {
                item = item.copy(url = refreshed.url)
                DownloadStore.save(this, item)
            }
        }
        val manifest = StreamResolver.fetchText(item.url, item.referer, item.userAgent)
        val sources = parseSegments(manifest, item.url, item.referer, item.userAgent)
        val saved = DownloadStore.get(this, item.id) ?: item
        val count = if (partial.exists()) saved.checkpoint.coerceIn(0, sources.list.size) else 0
        val bytesSaved = if (partial.exists()) saved.bytes.coerceAtLeast(0) else 0L
        java.io.RandomAccessFile(partial, "rw").use { file ->
            file.setLength(bytesSaved.coerceAtMost(file.length()))
        }
        for (index in count until sources.list.size) {
            coroutineContext.ensureActive()
            val before = partial.length()
            var error: Exception? = null
            var success = false
            for (attempt in 0 until 3) {
                coroutineContext.ensureActive()
                try {
                    java.io.RandomAccessFile(partial, "rw").use { it.setLength(before) }
                    val request = BrowserMediaRequest.builder(sources.list[index], item.referer, item.userAgent).build()
                    StreamResolver.client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("片段 HTTP " + response.code)
                        FileOutputStream(partial, true).use { output ->
                            response.body?.byteStream()?.use { input ->
                                val buffer = ByteArray(65536)
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    output.write(buffer, 0, n)
                                }
                            } ?: throw IOException("空白影片片段")
                        }
                    }
                    success = true
                    break
                } catch (e: Exception) {
                    coroutineContext.ensureActive()
                    error = e
                }
            }
            if (!success) throw IOException("片段 " + (index + 1) + " 失敗：" + error?.message)
            val pct = ((index + 1) * 93 / sources.list.size).coerceAtMost(93)
            progress(item, "下載中", pct, partial.length(), index + 1)
        }

        coroutineContext.ensureActive()
        // The complete source is on disk. This state survives process death
        // and ensures Pause -> Continue can resume without network access.
        DownloadStore.patch(this, item.id) {
            it.copy(readyToFinalize = true, status = "準備整理已下載影片",
                progress = 94, checkpoint = sources.list.size, bytes = partial.length(), error = "")
        }
        finalizeHls(item, partial, sources.fragmented)
    }

    private fun isFragmentedMp4(file: File): Boolean {
        if (file.length() < 12L) return false
        return runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(12)
                if (input.read(header) < 8) return@use false
                val atom = String(header, 4, 4, Charsets.US_ASCII)
                atom in setOf("ftyp", "styp", "moov", "moof")
            }
        }.getOrDefault(false)
    }

    /**
     * Remux with observable progress and user-controlled cancellation.
     * No time-based abort: long videos may take as long as needed.
     * Unsupported or malformed streams still surface real errors.
     */
    private suspend fun finalizeHls(item: DownloadRecord, partial: File, fragmented: Boolean) {
        coroutineContext.ensureActive()
        if (!item.autoRepairMp4) {
            // Do not call an initialization-only fMP4 or TS a completed movie.
            verifyMp4(partial)
            val ext = if (fragmented) "mp4" else "ts"
            val mime = if (fragmented) "video/mp4" else "video/mp2t"
            finish(
                item, partial, ext, mime,
                "已依設定保留原始串流（未自動整理 MP4）。如需快轉索引，請到設定 → 影片修復手動選擇。"
            )
            return
        }
        progress(item, "重新封裝 MP4（可暫停）", 94)
        val mp4 = File(partial.parentFile, item.id.toString() + ".mp4")
        // A previous interrupted remux may leave a partial MP4.
        mp4.delete()
        val job = coroutineContext[Job]
        var remuxFailure: Throwable? = null
        val indexed = try {
            remuxToMp4(partial, mp4, job) { percentage ->
                progress(item, "正在建立可快轉 MP4", percentage)
            }
            mp4.isFile && mp4.length() > 0L
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            remuxFailure = e
            false
        }
        coroutineContext.ensureActive()
        if (indexed) {
            verifyMp4(mp4)
            finish(item, mp4, "mp4", "video/mp4")
            partial.delete()
            mp4.delete()
        } else {
            mp4.delete()
            verifyMp4(partial)
            val extension = if (fragmented) "mp4" else "ts"
            val kind = if (fragmented) "video/mp4" else "video/mp2t"
            val note = "MP4 整理無法完成，已保留原始串流。" +
                (remuxFailure?.message?.let { "原因：" + it.take(140) } ?: "")
            finish(item, partial, extension, kind, note)
        }
    }

    private suspend fun finish(
        item: DownloadRecord, file: File, ext: String, mime: String, note: String = ""
    ) {
        coroutineContext.ensureActive()
        progress(item, "正在儲存影片", 98)
        val uri = saveVideo(item, file, ext, mime)
        DownloadStore.patch(this, item.id) {
            it.copy(status = "完成", progress = 100, uri = uri,
                error = note, bytes = file.length(), checkpoint = 0,
                readyToFinalize = false, savedExtension = ext)
        }
        file.delete()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification("下載完成", 100))
    }

    private suspend fun saveVideo(
        item: DownloadRecord, file: File, ext: String, mime: String,
        repairing: Boolean = false
    ): String {
        val name = StreamResolver.safeTitle(item.title) + "_" +
            System.currentTimeMillis().toString() + "." + ext
        val tree = item.destinationTree
        if (tree.isNotBlank()) {
            // Let DocumentsProvider enforce the actual SAF grant. Comparing a saved
            // tree Uri to persistedUriPermissions by object equality incorrectly
            // rejects valid grants returned in different URI forms by some providers.
            val uri = try {
                val root = Uri.parse(tree)
                val parent = DocumentsContract.buildDocumentUriUsingTree(
                    root, DocumentsContract.getTreeDocumentId(root)
                )
                DocumentsContract.createDocument(contentResolver, parent, mime, name)
                    ?: throw IOException("無法在所選資料夾新增影片，請確認資料夾允許寫入")
            } catch (e: SecurityException) {
                throw IOException("自訂資料夾的 Android 授權已失效，請在設定重新選擇下載資料夾", e)
            } catch (e: IllegalArgumentException) {
                throw IOException("儲存的資料夾位置已失效，請在設定重新選擇", e)
            }
            try {
                contentResolver.openOutputStream(uri, "w")?.use { target ->
                    copyVideoWithProgress(item, file, target, repairing)
                } ?: throw IOException("Android 無法開啟所選資料夾的檔案")
                return uri.toString()
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
                if (e is CancellationException) throw e
                if (e is SecurityException) {
                    throw IOException("自訂資料夾寫入權限已失效，請到設定重新授權", e)
                }
                throw IOException("儲存影片至自訂資料夾失敗：" + (e.message ?: "儲存裝置拒絕寫入"), e)
            }
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MyDownload")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: throw IOException("無法建立影片檔")
        try {
            contentResolver.openOutputStream(uri)?.use { target ->
                copyVideoWithProgress(item, file, target, repairing)
            } ?: throw IOException("無法寫入影片")
            contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            return uri.toString()
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private suspend fun copyVideoWithProgress(
        item: DownloadRecord, source: File, target: java.io.OutputStream,
        repairing: Boolean = false
    ) {
        val total = source.length().coerceAtLeast(1L)
        var copied = 0L
        var lastReport = 0L
        source.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                coroutineContext.ensureActive()
                val size = input.read(buffer)
                if (size < 0) break
                target.write(buffer, 0, size)
                copied += size
                val now = SystemClock.elapsedRealtime()
                if (now - lastReport > 450L) {
                    lastReport = now
                    val sizeMB = total / (1024 * 1024)
                    val copiedMB = copied / (1024 * 1024)
                    val percent = 98 + (copied * 1L / total).toInt().coerceIn(0, 1)
                    if (repairing) repairProgress(item,
                        "正在儲存修復後的 MP4 $copiedMB / $sizeMB MB",
                        (90 + (copied * 9L / total).toInt()).coerceIn(90, 99))
                    else progress(item, "正在寫入手機 $copiedMB / $sizeMB MB", percent)
                }
            }
        }
        target.flush()
        coroutineContext.ensureActive()
    }

    private fun repairProgress(item: DownloadRecord, stage: String, percent: Int) {
        DownloadStore.patch(this, item.id) {
            it.copy(repairStatus = stage, repairProgress = percent.coerceIn(0, 100))
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(stage, percent))
    }

    /**
     * Repair a previously completed raw TS or fragmented stream in place.
     * Only local content:// URI is read. We never re-fetch the remote video.
     * The original video is left untouched if conversion or saving fails.
     */
    private suspend fun repairMp4(item: DownloadRecord) {
        val original = item.uri.takeIf { it.isNotBlank() }
            ?: throw IOException("沒有可修復的原始影片")
        val originalUri = Uri.parse(original)
        val originalSize = runCatching {
            contentResolver.query(
                originalUri, arrayOf(OpenableColumns.SIZE), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else -1L
            } ?: -1L
        }.getOrDefault(-1L)

        // Copying the source and writing the MP4 consumes temporary storage.
        // Give the user a clear error instead of silently filling app storage.
        if (originalSize > 0L) {
            val space = StatFs(filesDir.absolutePath).availableBytes
            val required = originalSize * 2L + 64L * 1024L * 1024L
            if (space < required) {
                throw IOException("手機暫存空間不足。修復約需 " +
                    (required / (1024L * 1024L)) + " MB，可先釋放空間")
            }
        }

        val folder = tempFile(item.id).parentFile
            ?: throw IOException("無法建立影片暫存目錄")
        val sourceExtension = when {
            item.savedExtension in setOf("ts", "mp4") -> item.savedExtension
            contentResolver.getType(originalUri) == "video/mp2t" -> "ts"
            else -> "mp4"
        }
        val source = File(folder, item.id.toString() + ".repair-source." + sourceExtension)
        val mp4 = File(folder, item.id.toString() + ".repair.mp4")
        source.delete()
        mp4.delete()
        try {
            repairProgress(item, "讀取已下載影片", 2)
            contentResolver.openInputStream(originalUri)?.use { input ->
                FileOutputStream(source).use { output ->
                    val buffer = ByteArray(131072)
                    var readBytes = 0L
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val size = input.read(buffer)
                        if (size < 0) break
                        output.write(buffer, 0, size)
                        readBytes += size
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastReport > 500L) {
                            lastReport = now
                            val pct = if (originalSize > 0) {
                                (3L + readBytes * 20L / originalSize).toInt().coerceIn(3, 23)
                            } else 12
                            repairProgress(item, "複製原始影片 " +
                                (readBytes / (1024L * 1024L)) + " MB", pct)
                        }
                    }
                }
            } ?: throw IOException("無法讀取已保存的原始影片")
            if (source.length() == 0L) throw IOException("原始影片內容是空的")
            coroutineContext.ensureActive()

            repairProgress(item, "正在重建可快轉 MP4", 24)
            remuxToMp4(
                source, mp4, coroutineContext[Job],
                progressMin = 25, progressMax = 88
            ) { percentage ->
                repairProgress(item, "重新封裝 MP4", percentage)
            }

            if (!mp4.isFile || mp4.length() < 1024L)
                throw IOException("無法產生完整 MP4 檔案")
            verifyMp4(mp4)
            coroutineContext.ensureActive()

            repairProgress(item, "儲存修復後的 MP4", 90)
            val newUri = saveVideo(
                item.copy(title = item.title + "_fixed"),
                mp4, "mp4", "video/mp4", repairing = true
            )
            val savedUri = Uri.parse(newUri)
            // Safeguard against deleting the only usable copy: validate the
            // saved output first, then persist the new download history target.
            // If these fail, the original content URI remains untouched.
            try {
                verifySavedMp4(savedUri, mp4.length())
            } catch (e: Exception) {
                runCatching { deleteSavedVideo(savedUri) }
                throw IOException("修復檔儲存驗證失敗，舊影片已保留：" + (e.message ?: "無法讀取"), e)
            }
            coroutineContext.ensureActive()
            DownloadStore.patch(this, item.id) { record ->
                record.copy(
                    uri = newUri,
                    originalUri = original,
                    status = "完成", progress = 100,
                    error = "", savedExtension = "mp4",
                    repairStatus = "MP4 修復完成，正在清理舊影片",
                    repairProgress = 99
                )
            }
            if (DownloadStore.get(this, item.id)?.uri != newUri) {
                throw IOException("無法更新修復紀錄，舊影片保留")
            }

            // The new MP4 is verified and is now the active record. Only now
            // delete the old SAF/MediaStore file; failure is visible and retryable.
            val originalDeleted = newUri != original &&
                runCatching { deleteSavedVideo(originalUri) }.getOrDefault(false)
            DownloadStore.patch(this, item.id) { record ->
                record.copy(
                    originalUri = if (originalDeleted) "" else original,
                    repairStatus = if (originalDeleted)
                        "MP4 修復完成，舊影片已刪除"
                    else "MP4 修復完成，但舊影片無法刪除，請檢查儲存權限",
                    repairProgress = 100
                )
            }
        } finally {
            // Temporary working copies are safe to remove. The old saved video
            // is deleted only after new-MP4 verification and history commit.
            source.delete()
            mp4.delete()
        }
    }

    private fun verifySavedMp4(uri: Uri, expectedSize: Long) {
        val header = ByteArray(12)
        val count = contentResolver.openInputStream(uri)?.use { stream ->
            var total = 0
            while (total < header.size) {
                val n = stream.read(header, total, header.size - total)
                if (n < 0) break
                total += n
            }
            total
        } ?: throw IOException("新影片無法重新開啟")
        if (count < 12 || String(header, 4, 4, Charsets.US_ASCII) != "ftyp") {
            throw IOException("新影片沒有有效的 MP4 標頭")
        }
        if (expectedSize <= 0) throw IOException("修復後影片大小異常")
        val size = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0))
                        cursor.getLong(0) else null
                }
        }.getOrNull()
        if (size != null && size >= 0 && size != expectedSize) {
            throw IOException("儲存檔大小不符 ($size / $expectedSize)")
        }
    }

    private fun deleteSavedVideo(uri: Uri): Boolean {
        return if (DocumentsContract.isDocumentUri(this, uri)) {
            DocumentsContract.deleteDocument(contentResolver, uri)
        } else {
            contentResolver.delete(uri, null, null) > 0
        }
    }

    private fun verifyMp4(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val video = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it)
                    .getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
            } ?: throw IOException("修復檔沒有影像軌")
            extractor.selectTrack(video)
            if (extractor.sampleTrackIndex < 0 || extractor.sampleTime < 0L ||
                extractor.sampleSize <= 0L) {
                throw IOException("MP4 沒有可播放的影格，可能只有影片初始化資料")
            }
            // HLS TS/fMP4 may not carry a container-level duration even when
            // valid frames exist. Media samples, not duration metadata, decide.
        } finally {
            extractor.release()
        }
    }

    private fun remuxToMp4(
        input: File, output: File, job: Job?,
        progressMin: Int = 94, progressMax: Int = 97,
        onProgress: (Int) -> Unit
    ) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var lastUpdate = 0L
        var processedBytes = 0L
        val expectedBytes = input.length().coerceAtLeast(1L)
        // Do not impose a deadline on remuxing, either automatically or when
        // the user explicitly repairs a completed video in Settings.
        try {
            job?.ensureActive()
            extractor.setDataSource(input.absolutePath)
            val writer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer
            val tracks = mutableMapOf<Int, Int>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/") || mime.startsWith("video/")) {
                    tracks[i] = writer.addTrack(format)
                    extractor.selectTrack(i)
                }
            }
            if (tracks.isEmpty()) throw IOException("沒有可封裝的音訊或影像")
            writer.start()
            val buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var total = 0
            while (true) {
                job?.ensureActive()
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val sourceTrack = extractor.sampleTrackIndex
                val ts = extractor.sampleTime
                if (sourceTrack in tracks && ts >= 0) {
                    val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                        MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, size, ts, flags)
                    writer.writeSampleData(tracks.getValue(sourceTrack), buffer, info)
                    total++
                }
                processedBytes += size.toLong()
                val now = SystemClock.elapsedRealtime()
                if (now - lastUpdate > 500L) {
                    lastUpdate = now
                    val fraction = processedBytes.coerceAtMost(expectedBytes)
                    onProgress((progressMin + fraction * (progressMax - progressMin) /
                        expectedBytes).toInt().coerceIn(progressMin, progressMax))
                }
                if (!extractor.advance()) break
            }
            if (total == 0) throw IOException("沒有可輸出的影片影格")
            job?.ensureActive()
            writer.stop()
        } finally {
            // Only the successful path may call stop(); aborting can hang.
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    override fun onDestroy() {
        jobs.values.forEach { it.cancel() }
        scope.cancel()
        super.onDestroy()
    }
}
