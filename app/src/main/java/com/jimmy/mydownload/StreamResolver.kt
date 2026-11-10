package com.jimmy.mydownload

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeUnit

data class StreamChoice(
    val title: String,
    val label: String,
    val url: String,
    val kind: String,
    val referer: String,
    val detail: String = "",
    val userAgent: String = ""
)

object StreamResolver {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .addNetworkInterceptor { chain ->
            // BrowserMediaRequest attaches WebView cookies for the original host
            // by hand; OkHttp keeps such headers across redirects, so drop them
            // whenever a hop leaves that host.
            val request = chain.request()
            val origin = request.header(BrowserMediaRequest.COOKIE_HOST)
            val builder = request.newBuilder().removeHeader(BrowserMediaRequest.COOKIE_HOST)
            if (origin != null && !origin.equals(request.url.host, true)) {
                builder.removeHeader("Cookie")
            }
            chain.proceed(builder.build())
        }
        .build()

    suspend fun inspect(input: String): List<StreamChoice> = withContext(Dispatchers.IO) {
        val url = input.trim()
        require(url.startsWith("https://", ignoreCase = true)) { "請輸入 HTTPS 網址" }
        var siteError = ""
        if (Av01Resolver.supports(url)) {
            try {
                return@withContext Av01Resolver.inspect(url)
            } catch (e: Exception) {
                siteError = e.message ?: "影片資訊 API 無法使用"
            }
        }
        try {
            client.newCall(BrowserMediaRequest.builder(url, url).build()).execute().use {
                if (!it.isSuccessful) throw IOException("網址回應 HTTP " + it.code)
                val finalUrl = it.request.url.toString()
                val type = it.header("Content-Type").orEmpty().lowercase()
                val title = safeTitle(videoName(finalUrl))
                when {
                    type.startsWith("video/mp4") || mediaKind(finalUrl) == "mp4" ->
                        listOf(StreamChoice(title, "MP4", finalUrl, "mp4", url))
                    type.contains("mpegurl") || mediaKind(finalUrl) == "hls" ->
                        parseManifest(readText(it, 2_000_000), finalUrl, title, url)
                    else ->
                        inspectHtml(readText(it, 2_000_000), finalUrl, url)
                }
            }
        } catch (e: Exception) {
            if (siteError.isNotBlank()) throw IOException("AV01 API：" + siteError + "；網頁：" + e.message)
            throw e
        }
    }

    private fun inspectHtml(html: String, pageUrl: String, referer: String): List<StreamChoice> {
        val title = Regex("""(?is)<title[^>]*>(.*?)</title>""")
            .find(html)?.groupValues?.get(1)
            ?.replace(Regex("<[^>]+>"), "")
            ?.replace("&amp;", "&")?.trim()?.take(90)
            ?.ifBlank { null } ?: videoName(pageUrl)
        val normalized = html.replace("\\/", "/").replace("&amp;", "&")
            .replace("\\u002F", "/").replace("\\u0026", "&")
        val urls = linkedSetOf<String>()
        Regex("""https://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
            .findAll(normalized).forEach { urls.add(it.value) }
        Regex("""(?i)(?:src|file|source|url)\s*[:=]\s*["']([^"']+)["']""")
            .findAll(normalized).forEach { m ->
                val candidate = m.groupValues[1]
                if (candidate.contains(".m3u8", true) || candidate.contains(".mp4", true)) {
                    runCatching { URL(URL(pageUrl), candidate).toString() }
                        .getOrNull()?.takeIf { it.startsWith("https://") }?.let(urls::add)
                }
            }
        val outputs = mutableListOf<StreamChoice>()
        for (target in urls.take(12)) {
            try {
                if (mediaKind(target) == "mp4") {
                    outputs += StreamChoice(safeTitle(title), "MP4", target, "mp4", referer)
                } else {
                    outputs += parseManifest(fetchText(target, referer), target, safeTitle(title), referer)
                }
            } catch (_: Exception) {
                // Skip expired links in source HTML.
            }
        }
        if (outputs.isEmpty()) throw IOException("找不到可下載的未加密 MP4 或 M3U8 串流")
        return outputs.distinctBy { it.url }
    }

    fun parseManifest(text: String, baseUrl: String, title: String, referer: String): List<StreamChoice> {
        if (!text.trimStart().startsWith("#EXTM3U")) throw IOException("不是有效的 M3U8")
        if (text.contains("#EXT-X-KEY:", true) || text.contains("#EXT-X-SESSION-KEY:", true)) {
            throw IOException("此來源使用加密串流，無法直接下載")
        }
        val lines = text.lines().map(String::trim)
        val variants = mutableListOf<StreamChoice>()
        for (i in lines.indices) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF:", true)) continue
            val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() } ?: continue
            if (next.startsWith("#")) continue
            val height = Regex("""RESOLUTION=\d+x(\d+)""")
                .find(lines[i])?.groupValues?.get(1)?.toIntOrNull()
            val bw = Regex("""BANDWIDTH=(\d+)""")
                .find(lines[i])?.groupValues?.get(1)?.toLongOrNull()
            val label = when {
                height != null -> height.toString() + "P"
                bw != null -> (bw / 1000).toString() + " kbps"
                else -> "自動畫質"
            }
            val resolved = URL(URL(baseUrl), next).toString()
            if (resolved.startsWith("https://")) variants +=
                StreamChoice(title, label, resolved, "hls", referer)
        }
        if (variants.isNotEmpty()) return variants.sortedByDescending {
            Regex("""\d+""").find(it.label)?.value?.toIntOrNull() ?: 0
        }.distinctBy { it.url }
        return listOf(StreamChoice(title, "HLS 原始畫質", baseUrl, "hls", referer))
    }

    fun fetchText(url: String, referer: String, userAgent: String = ""): String {
        val request = BrowserMediaRequest.builder(url, referer, userAgent).build()
        client.newCall(request).execute().use {
            if (!it.isSuccessful) throw IOException("讀取串流失敗 HTTP " + it.code)
            return readText(it, 4_000_000)
        }
    }

    private fun readText(response: okhttp3.Response, maxBytes: Int): String {
        val input = response.body?.byteStream() ?: throw IOException("空白網路回應")
        val output = java.io.ByteArrayOutputStream()
        val bytes = ByteArray(8192)
        while (true) {
            val n = input.read(bytes)
            if (n < 0) break
            if (output.size() + n > maxBytes) throw IOException("資料超過解析上限")
            output.write(bytes, 0, n)
        }
        return output.toString("UTF-8")
    }

    fun mediaKind(url: String): String {
        val path = URL(url).path.lowercase()
        return when {
            path.endsWith(".m3u8") -> "hls"
            path.endsWith(".mp4") || path.endsWith(".m4v") -> "mp4"
            else -> ""
        }
    }

    fun videoName(url: String): String = URL(url).path.substringAfterLast('/')
        .substringBeforeLast('.').ifBlank { "影片" }

    fun safeTitle(title: String): String =
        VideoTitle.cleanText(title).ifBlank { "影片" }
}
