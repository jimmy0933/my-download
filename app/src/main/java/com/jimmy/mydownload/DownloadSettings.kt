package com.jimmy.mydownload

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
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

    /**
     * A real provider write probe, not a comparison against persistedUriPermissions.
     * Some vendors return equivalent tree permissions with differently encoded URIs.
     * Fail here while selecting the folder instead of after downloading a large video.
     */
    fun testFolderWrite(context: Context, uri: Uri) {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            uri, DocumentsContract.getTreeDocumentId(uri)
        )
        val probeName = ".mydownload-write-check-" + System.currentTimeMillis() + ".tmp"
        val document = try {
            DocumentsContract.createDocument(
                resolver, parent, "application/octet-stream", probeName
            ) ?: throw IOException("所選資料夾不允許建立檔案，請改選其他資料夾")
        } catch (e: SecurityException) {
            throw IOException("Android 未授予所選資料夾的寫入權限，請重新選擇", e)
        }
        try {
            resolver.openOutputStream(document, "w")?.use {
                it.write(byteArrayOf(0x4d, 0x44))
                it.flush()
            } ?: throw IOException("所選資料夾無法開啟寫入串流")
        } catch (e: SecurityException) {
            throw IOException("所選資料夾拒絕寫入，請改選可寫入的資料夾", e)
        } finally {
            runCatching { DocumentsContract.deleteDocument(resolver, document) }
        }
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
