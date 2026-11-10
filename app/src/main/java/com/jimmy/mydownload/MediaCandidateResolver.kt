package com.jimmy.mydownload

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** Reuses only cookies matching the media URL host; never forwards x.com cookies elsewhere. */
object BrowserMediaRequest {
    const val COOKIE_HOST = "X-Cookie-Host"
    fun builder(url: String, referer: String, userAgent: String = ""): Request.Builder {
        val request = Request.Builder().url(url)
            .header(
                "User-Agent",
                userAgent.takeIf { it.isNotBlank() && it.length < 1000 }
                    ?: StreamResolver.USER_AGENT
            ).header("Accept", "*/*")
        if (BrowserAddress.isWebUrl(referer)) request.header("Referer", referer)
        val cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        if (!cookies.isNullOrBlank()) {
            request.header("Cookie", cookies)
            runCatching { java.net.URI(url).host }.getOrNull()
                ?.let { request.header(COOKIE_HOST, it) }
        }
        return request
    }
}

object MediaCandidateResolver {
    data class Result(val choices: List<StreamChoice>, val failures: List<String>)
    private val client = StreamResolver.client.newBuilder()
        .callTimeout(10, TimeUnit.SECONDS).build()

    fun resolution(url: String): String? {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault(url)
        val size = Regex("""(?i)(\d{2,4})x(\d{2,4})""").find(path)
        if (size != null) return size.groupValues[1] + "×" + size.groupValues[2]
        return Regex("""(?i)(?:/|_)(360|480|540|720|1080|1440|2160)p(?:/|_|\.|$)""")
            .find(path)?.groupValues?.get(1)?.plus("P")
    }

    fun mediaId(url: String): String? {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        return Regex("""/(?:ext_tw_video|amplify_video|tweet_video|tv_video)/(\d+)""")
            .find(path)?.groupValues?.get(1)
    }

    fun group(url: String): String {
        val id = mediaId(url)
        return if (id == null) runCatching { URI(url).host.orEmpty() }.getOrDefault("影片")
        else "影片 ID …" + id.takeLast(7)
    }

    private fun totalSize(response: Response): Long? =
        Mp4Ranges.totalForResponse(
            response.code,
            response.header("Content-Range"),
            response.body?.contentLength()?.takeIf { it > 0L }
        )

    private fun playlist(response: Response): String {
        val input = response.body?.byteStream() ?: throw IOException("播放清單為空")
        val output = java.io.ByteArrayOutputStream()
        input.use {
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                if (output.size() + count > 2_000_000) throw IOException("播放清單過大")
                output.write(buffer, 0, count)
            }
        }
        return output.toString("UTF-8")
    }

    suspend fun inspect(inputs: List<StreamChoice>, referer: String): Result =
        withContext(Dispatchers.IO) {
            val urls = inputs.map { it.url }
                .filter { BrowserVideoDiscovery.kind(it) != null }
                .distinct().take(10)
            val title = StreamResolver.safeTitle(inputs.firstOrNull()?.title ?: "影片")
            val choices = mutableListOf<StreamChoice>()
            val failures = mutableListOf<String>()
            for (url in urls) {
                currentCoroutineContext().ensureActive()
                val kind = BrowserVideoDiscovery.kind(url) ?: continue
                val group = group(url)
                try {
                    val sourceAgent = inputs.firstOrNull { it.url == url }
                        ?.userAgent.orEmpty()
                    val request = BrowserMediaRequest.builder(url, referer, sourceAgent).apply {
                        if (kind == "mp4") header("Range", "bytes=0-0")
                    }.build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException(
                            "HTTP " + response.code + if (response.code in listOf(401, 403))
                                "（權限不足、來源過期或不允許下載）" else ""
                        )
                        val mime = response.header("Content-Type").orEmpty().lowercase()
                        if (mime.contains("text/html") || mime.contains("application/json"))
                            throw IOException("回傳網頁而非影片")
                        if (kind == "mp4") {
                            // Range probe is only one byte. Never report that
                            // one byte as the complete movie size.
                            if (response.code == 206 &&
                                Mp4Ranges.parse(response.header("Content-Range")) == null) {
                                throw IOException("影片伺服器未回傳可驗證的 Content-Range")
                            }
                            val size = totalSize(response)
                            if (!Mp4Ranges.isPlausibleVideoSize(size)) {
                                throw IOException(
                                    "來源只有 " + Mp4Ranges.label(size) +
                                    "，可能是影片初始化片段而非完整 MP4；請改選 HLS"
                                )
                            }
                            val quality = resolution(url) ?: "畫質未知"
                            choices += StreamChoice(
                                title, "$quality · MP4", url, "mp4", referer,
                                "MP4 · $quality · " + Mp4Ranges.label(totalSize(response)) + " · $group",
                                sourceAgent
                            )
                        } else {
                            val text = playlist(response)
                            if (!text.trimStart().startsWith("#EXTM3U"))
                                throw IOException("無效的 M3U8")
                            if (text.contains("#EXT-X-KEY:", true) ||
                                text.contains("#EXT-X-SESSION-KEY:", true))
                                throw IOException("加密串流不可直接下載")
                            val variants = StreamResolver.parseManifest(
                                text, url, title, referer
                            )
                            val audioSeparate = text.lines().any {
                                it.startsWith("#EXT-X-MEDIA:", true) &&
                                    it.contains("TYPE=AUDIO", true)
                            }
                            for (variant in variants) {
                                if (variant.url.contains("/mp4a/", true) ||
                                    variant.label.contains("audio", true)) continue
                                val quality = resolution(variant.url) ?:
                                    variant.label.takeIf { it != "HLS 原始畫質" } ?: "畫質未知"
                                choices += variant.copy(
                                    label = "$quality · HLS",
                                    detail = "HLS · $quality · 大小未知 · $group" +
                                        if (audioSeparate) " · 音訊可能獨立，建議 MP4" else "",
                                    userAgent = sourceAgent
                                )
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures += "$group · " + kind.uppercase() + "：" +
                        (e.message ?: "來源無法讀取")
                }
            }
            Result(
                choices.distinctBy { it.url }.sortedWith(
                    compareBy<StreamChoice> { if (it.kind == "mp4") 0 else 1 }
                        .thenByDescending {
                            val q = resolution(it.url) ?: it.label
                            Regex("""\d+×(\d+)""").find(q)
                                ?.groupValues?.get(1)?.toIntOrNull()
                                ?: Regex("""(\d+)[Pp]""").find(q)
                                    ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        }
                ),
                failures.distinct()
            )
        }
}
