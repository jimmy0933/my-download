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
    private val restartAfterStop = ConcurrentHashMap.newKeySet<Long>()

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
            if (existing.isCancelled || !existing.isActive) restartAfterStop.add(id)
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
                    if (repairRequest) it.copy(repairStatus = "已暫停修復")
                    else it.copy(status = "已暫停")
                }
            } catch (e: Exception) {
                DownloadStore.patch(this@DownloadService, id) {
                    if (repairRequest) it.copy(
                        repairStatus = "修復失敗：" + (e.message ?: "未知原因")
                    ) else it.copy(status = "失敗", error = e.message ?: "下載中斷")
                }
            } finally {
                jobs.remove(id)
                if (restartAfterStop.remove(id)) {
                    start(this@DownloadService, id)
                } else if (jobs.isEmpty()) stopSelf()
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
        // If bytes were already completely downloaded and only writing into the
        // selected SAF folder failed, do not download the whole MP4 again.
        if ((item.readyToFinalize || item.status == "待儲存") &&
            partial.isFile && partial.length() > 0L) {
            finish(item, partial, "mp4", "video/mp4")
            return
        }
        var current = partial.takeIf { it.exists() }?.length() ?: 0L
        progress(item, "連線中", 0, current)
        for (attempt in 0..1) {
            coroutineContext.ensureActive()
            val request = Request.Builder().url(item.url)
                .header("User-Agent", StreamResolver.USER_AGENT)
                .header("Referer", item.referer)
                .apply { if (current > 0L) header("Range", "bytes=" + current + "-") }
                .build()
            var restart = false
            StreamResolver.client.newCall(request).execute().use { response ->
                if (response.code == 416 && current > 0L) {
                    restart = true
                } else {
                    if (response.code != 200 && response.code != 206) {
                        throw IOException("MP4 HTTP " + response.code)
                    }
                    val append = response.code == 206 && current > 0L
                    if (append) {
                        val header = response.header("Content-Range").orEmpty()
                        val start = Regex("""bytes\s+(\d+)-""")
                            .find(header)?.groupValues?.get(1)?.toLongOrNull()
                        if (start != current) throw IOException("MP4 續傳位置不一致")
                    } else {
                        current = 0L
                    }
                    val body = response.body ?: throw IOException("空白影片回應")
                    val expected = body.contentLength().takeIf { it >= 0 }?.plus(current) ?: -1L
                    var updated = 0L
                    FileOutputStream(partial, append).use { output ->
                        body.byteStream().use { input ->
                            val bytes = ByteArray(65536)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(bytes)
                                if (n < 0) break
                                output.write(bytes, 0, n)
                                current += n
                                val now = SystemClock.elapsedRealtime()
                                if (now - updated > 500) {
                                    updated = now
                                    val percent = if (expected > 0L)
                                        ((current * 94) / expected).toInt().coerceIn(0, 94) else 0
                                    progress(item, "下載中", percent, current)
                                }
                            }
                        }
                    }
                    if (expected > 0 && current != expected) {
                        throw IOException("MP4 下載不完整：" + current + "/" + expected)
                    }
                    if (partial.length() <= 0L) throw IOException("影片檔案為空")
                    DownloadStore.patch(this, item.id) {
                        it.copy(readyToFinalize = true, status = "待儲存",
                            bytes = partial.length())
                    }
                    finish(item, partial, "mp4", "video/mp4")
                    return
                }
            }
            if (restart) {
                partial.delete()
                current = 0L
            }
        }
        throw IOException("來源不接受續傳，請重新加入下載")
    }

    private data class Segments(val list: List<String>, val fragmented: Boolean)

    private fun parseSegments(text: String, base: String, referer: String): Segments {
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
            return parseSegments(StreamResolver.fetchText(next, referer), next, referer)
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
        for (line in text.lines().map(String::trim)) {
            if (line.startsWith("#EXT-X-MAP:", true)) {
                val init = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1)
                    ?: throw IOException("缺少 HLS 初始化片段")
                segments += absolute(init)
                fragmented = true
            } else if (line.isNotBlank() && !line.startsWith("#")) {
                segments += absolute(line)
                if (line.substringBefore('?').endsWith(".m4s", true)) fragmented = true
            }
        }
        if (segments.isEmpty()) throw IOException("播放清單沒有片段")
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
        val manifest = StreamResolver.fetchText(item.url, item.referer)
        val sources = parseSegments(manifest, item.url, item.referer)
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
                    val request = Request.Builder().url(sources.list[index])
                        .header("User-Agent", StreamResolver.USER_AGENT)
                        .header("Referer", item.referer).build()
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
     * Remux with observable progress and a bounded work budget.
     * Never throw away the complete downloaded source if remuxing cannot finish.
     */
    private suspend fun finalizeHls(item: DownloadRecord, partial: File, fragmented: Boolean) {
        coroutineContext.ensureActive()
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
            finish(item, mp4, "mp4", "video/mp4")
            partial.delete()
            mp4.delete()
        } else {
            mp4.delete()
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
                error = note, bytes = 0L, checkpoint = 0,
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
        val source = File(folder, item.id.toString() + ".repair-source")
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
            DownloadStore.patch(this, item.id) { record ->
                record.copy(
                    uri = newUri,
                    originalUri = record.originalUri.ifBlank { original },
                    status = "完成", progress = 100,
                    error = "", savedExtension = "mp4",
                    repairStatus = "MP4 修復完成，可使用新影片播放",
                    repairProgress = 100
                )
            }
        } finally {
            // Original content URI is deliberately never deleted.
            source.delete()
            mp4.delete()
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
            if (extractor.sampleTime < 0L) throw IOException("修復檔找不到可播放的影格")
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
        var started = false
        val startTime = SystemClock.elapsedRealtime()
        var lastUpdate = 0L
        var processedBytes = 0L
        val expectedBytes = input.length().coerceAtLeast(1L)
        // A hard-coded 90 seconds causes large, valid transport streams to
        // always fall back to raw TS. Scale the budget to video size, while
        // keeping a bounded failure path on corrupt streams.
        val mib = expectedBytes / (1024L * 1024L)
        val remuxBudgetMs = (360_000L + mib.coerceAtMost(4096L) * 250L)
            .coerceAtMost(1_200_000L)
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
            started = true
            val buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var total = 0
            while (true) {
                job?.ensureActive()
                if (SystemClock.elapsedRealtime() - startTime > remuxBudgetMs)
                    throw IOException("MP4 整理超過處理時間，改為保留原始影片")
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
            started = false
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
