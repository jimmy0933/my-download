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
import android.provider.DocumentsContract
import android.provider.MediaStore
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
        private const val EXTRA_ID = "id"

        fun start(context: Context, id: Long) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
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
            jobs[id]?.cancel()
            DownloadStore.patch(this, id) { it.copy(status = "已暫停") }
            if (jobs.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || id < 0) return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, notification("準備下載", 0))
        if (jobs.containsKey(id)) return START_NOT_STICKY
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val record = DownloadStore.get(this@DownloadService, id) ?: return@launch
                when (record.kind) {
                    "mp4" -> downloadMp4(record)
                    "hls" -> downloadHls(record)
                    else -> throw IOException("尚未支援的影片格式")
                }
            } catch (_: CancellationException) {
                DownloadStore.patch(this@DownloadService, id) { it.copy(status = "已暫停") }
            } catch (e: Exception) {
                DownloadStore.patch(this@DownloadService, id) {
                    it.copy(status = "失敗", error = e.message ?: "下載中斷")
                }
            } finally {
                jobs.remove(id)
                if (jobs.isEmpty()) stopSelf()
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

    private fun progress(item: DownloadRecord, status: String, pct: Int, bytes: Long = 0L, checkpoint: Int = 0) {
        DownloadStore.patch(this, item.id) {
            it.copy(status = status, progress = pct, bytes = bytes, checkpoint = checkpoint, error = "")
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
        if (item.status == "待儲存" && partial.isFile && partial.length() > 0L) {
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
        val partial = tempFile(item.id)
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
            for (tryNo in 0 until 3) {
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
                                val block = ByteArray(65536)
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val n = input.read(block)
                                    if (n < 0) break
                                    output.write(block, 0, n)
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
        progress(item, "正在整理影片", 96)
        val mp4 = File(partial.parentFile, item.id.toString() + ".mp4")
        val indexed = runCatching { remuxToMp4(partial, mp4) }.isSuccess && mp4.length() > 0L
        if (indexed) {
            // Keep both temporary files if SAF output fails. After reselecting a
            // folder the user can retry instead of losing the downloaded data.
            finish(item, mp4, "mp4", "video/mp4")
            partial.delete()
            mp4.delete()
        } else {
            mp4.delete()
            val ext = if (sources.fragmented) "mp4" else "ts"
            finish(item, partial, ext, if (sources.fragmented) "video/mp4" else "video/mp2t",
                "已保留原始影片，播放器可能無法拖動進度條。")
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
                error = note, bytes = 0L, checkpoint = 0)
        }
        file.delete()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification("下載完成", 100))
    }

    private fun saveVideo(item: DownloadRecord, file: File, ext: String, mime: String): String {
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
                    file.inputStream().use { it.copyTo(target, 65536) }
                } ?: throw IOException("Android 無法開啟所選資料夾的檔案")
                return uri.toString()
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
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
                file.inputStream().use { it.copyTo(target, 65536) }
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

    private fun remuxToMp4(input: File, output: File) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
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
                extractor.advance()
            }
            if (total == 0) throw IOException("沒有可輸出的影片影格")
            writer.stop()
            started = false
        } finally {
            if (started) runCatching { muxer?.stop() }
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
