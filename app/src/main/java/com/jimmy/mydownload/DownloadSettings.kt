package com.jimmy.mydownload

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.net.URI

object DownloadSettings {
    private const val PREF = "download_settings"
    private const val FOLDER = "destination_tree"
    private const val BLOCK = "block_ads"

    fun folder(context: Context): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(FOLDER, "").orEmpty()

    fun setFolder(context: Context, uri: Uri?) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(FOLDER, uri?.toString().orEmpty()).apply()
    }

    fun adBlockEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(BLOCK, false)

    fun setAdBlockEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(BLOCK, enabled).apply()
    }

    fun folderLabel(context: Context): String {
        val saved = folder(context).takeIf(String::isNotEmpty) ?: return "預設：影片 / MyDownload"
        return runCatching {
            val uri = Uri.parse(saved)
            val document = DocumentsContract.buildDocumentUriUsingTree(
                uri, DocumentsContract.getTreeDocumentId(uri)
            )
            context.contentResolver.query(
                document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()?.takeIf(String::isNotBlank) ?: "自訂儲存位置"
    }
}

object AdPageFilter {
    private val domains = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "googletagservices.com", "adnxs.com", "exoclick.com", "exosrv.com",
        "popads.net", "popcash.net", "propellerads.com", "onclickads.net",
        "trafficjunky.net", "juicyads.com", "hilltopads.net", "adsterra.com",
        "adskeeper.com", "ad-maven.com", "galaksion.com"
    )

    fun blocks(address: String): Boolean = runCatching {
        val parsed = URI(address)
        val host = parsed.host?.lowercase() ?: return@runCatching false
        (parsed.scheme == "http" || parsed.scheme == "https") &&
            domains.any { host == it || host.endsWith("." + it) }
    }.getOrDefault(false)
}
