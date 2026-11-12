package com.jimmy.mydownload

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

/** Uses AV01's publicly accessible APIs and the access credentials normally granted to the client.
 * Does not bypass DRM, subscriptions, CAPTCHAs, or protected accounts.
 */
object Av01Resolver {
    private const val BASE = "https://www.av01.media"
    private const val CDN = "https://customers.iw01.xyz"

    /** access_token is only ever sent to the CDN that issued it. */
    fun isCdnUrl(url: String): Boolean =
        runCatching { url.toHttpUrl().host == CDN.toHttpUrl().host }.getOrDefault(false)

    fun supports(url: String): Boolean {
        val parsed = runCatching { url.toHttpUrl() }.getOrNull() ?: return false
        return parsed.host in setOf("av01.media", "www.av01.media", "av01.tv", "www.av01.tv") &&
            Regex("""/video/\d+""").containsMatchIn(parsed.encodedPath)
    }

    fun inspect(page: String): List<StreamChoice> {
        val id = Regex("""/video/(\d+)""").find(page)?.groupValues?.get(1)
            ?: throw IOException("網址沒有影片編號")
        val geo = fetchJson("$BASE/edge/geo.js?json", page)
        val token = geo.optString("token_v2").ifBlank { geo.optString("token") }
        val expiry = geo.optString("expires")
        val ip = geo.optString("ip")
        if (token.isBlank() || expiry.isBlank() || ip.isBlank()) {
            throw IOException("網站 Geo API 沒有提供必要資訊")
        }
        val metadata = fetchJson("$BASE/api/v1/videos/$id", page)
        val title = StreamResolver.safeTitle(
            metadata.optString("dvd_id").ifBlank {
                metadata.optString("title").ifBlank { "AV01_" + id }
            }
        )
        val storage = metadata.optString("storage_base")
        val hash = if (storage.isBlank()) "" else MessageDigest.getInstance("SHA-256")
            .digest(storage.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(16)

        val accessUrl = "$CDN/api/v1/videos/$id/cdn-access".toHttpUrl().newBuilder()
            .addQueryParameter("token_v2", token)
            .addQueryParameter("expires", expiry)
            .addQueryParameter("ip", ip)
            .build().toString()
        val accessToken = fetchJson(accessUrl, page).optString("access_token")
        if (accessToken.isBlank()) throw IOException("網站未核發 CDN 存取權杖")

        val playlist = "$BASE/api/v1/videos/$id/manifest/master.m3u8"
        val master = StreamResolver.fetchText(
            if (hash.isBlank()) playlist else "$playlist?hb=$hash", page
        )
        val cdnMaster = "$CDN/api/v1/videos/$id/manifest/master.m3u8"
        return StreamResolver.parseManifest(master, cdnMaster, title, page).map {
            fun withToken(u: String) = u.toHttpUrl().newBuilder()
                .addQueryParameter("access_token", accessToken).build().toString()
            it.copy(url = withToken(it.url),
                audioUrl = if (it.audioUrl.isNotBlank() && isCdnUrl(it.audioUrl))
                    withToken(it.audioUrl) else it.audioUrl)
        }
    }

    private fun fetchJson(url: String, referer: String): JSONObject {
        val request = Request.Builder().url(url)
            .header("User-Agent", StreamResolver.USER_AGENT)
            .header("Referer", referer)
            .header("Accept", "application/json")
            .build()
        StreamResolver.client.newCall(request).execute().use {
            if (!it.isSuccessful) throw IOException("AV01 API HTTP " + it.code)
            return JSONObject(it.body?.string() ?: throw IOException("空白 API 回應"))
        }
    }
}
