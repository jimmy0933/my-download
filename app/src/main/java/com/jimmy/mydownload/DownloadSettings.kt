package com.jimmy.mydownload

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.net.URI

/** X-only cosmetic preferences. All optional filters default to off. */
data class XDisplayOptions(
    val hideReply: Boolean = false,
    val hideRepost: Boolean = false,
    val hideViews: Boolean = false,
    val hideShare: Boolean = false,
    val hideBottomNavigation: Boolean = false,
    val hideMediaDescription: Boolean = false,
    val hideMediaTitle: Boolean = false,
    val fontSizePercent: Int = 100,
    val hideBottomHome: Boolean = false,
    val hideBottomSearch: Boolean = false,
    val hideBottomGrok: Boolean = false,
    val hideBottomChat: Boolean = false,
    val hideBottomNotifications: Boolean = false,
    val hideBottomShare: Boolean = false,
    val hideVideoPlaybackText: Boolean = false,
    val hideVideoPlaybackTitle: Boolean = false,
    val hideVideoBelowProgressDetails: Boolean = false
)

/**
 * Normalized viewport position. Fractions keep the floating download button in
 * the same relative place when the device rotates or the window size changes.
 */
data class FloatingDownloadPosition(
    val xFraction: Float = 1f,
    val yFraction: Float = 1f
) {
    fun movedBy(dx: Float, dy: Float, availableWidth: Int, availableHeight: Int) =
        FloatingDownloadPosition(
            (xFraction + if (availableWidth > 0) dx / availableWidth else 0f).coerceIn(0f, 1f),
            (yFraction + if (availableHeight > 0) dy / availableHeight else 0f).coerceIn(0f, 1f)
        )
}

object DownloadSettings {
    private const val PREF = "download_settings"
    private const val FOLDER = "destination_tree"
    private const val BLOCK = "block_ads"
    private const val AUTO_REPAIR = "auto_repair_mp4"
    private const val VIDEO_PLAYER = "preferred_video_player"
    private const val X_HIDE_REPLY = "x_hide_reply"
    private const val X_HIDE_REPOST = "x_hide_repost"
    private const val X_HIDE_VIEWS = "x_hide_views"
    private const val X_HIDE_SHARE = "x_hide_share"
    private const val X_HIDE_BOTTOM = "x_hide_bottom_navigation"
    private const val X_HIDE_MEDIA_DESCRIPTION = "x_hide_media_description"
    private const val X_HIDE_MEDIA_TITLE = "x_hide_media_title"
    private const val X_FONT_PERCENT = "x_font_size_percent"
    private const val X_HIDE_BOTTOM_HOME = "x_hide_bottom_home"
    private const val X_HIDE_BOTTOM_SEARCH = "x_hide_bottom_search"
    private const val X_HIDE_BOTTOM_GROK = "x_hide_bottom_grok"
    private const val X_HIDE_BOTTOM_CHAT = "x_hide_bottom_chat"
    private const val X_HIDE_BOTTOM_NOTIFICATIONS = "x_hide_bottom_notifications"
    private const val X_HIDE_BOTTOM_SHARE = "x_hide_bottom_share"
    private const val X_HIDE_VIDEO_PLAYBACK_TEXT = "x_hide_video_playback_text"
    private const val X_HIDE_VIDEO_PLAYBACK_TITLE = "x_hide_video_playback_title"
    private const val X_HIDE_VIDEO_BELOW_PROGRESS_DETAILS = "x_hide_video_below_progress_details"
    private const val FLOAT_BUTTON_X = "floating_download_x"
    private const val FLOAT_BUTTON_Y = "floating_download_y"
    private const val FLOAT_BUTTON_OPACITY = "floating_download_opacity"

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

    /**
     * Opt-in for automatic MP4 remuxing after HLS download.
     * Disabled by default so downloads finish without lengthy post-processing.
     * The choice is snapshotted when a new download is queued.
     */
    fun autoRepairMp4(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(AUTO_REPAIR, false)

    fun setAutoRepairMp4(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(AUTO_REPAIR, enabled).apply()
    }

    /** Empty string = system default; other IDs are installed app package names. */
    fun videoPlayer(context: Context): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(VIDEO_PLAYER, VideoPlayerApps.SYSTEM).orEmpty()

    fun setVideoPlayer(context: Context, id: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(VIDEO_PLAYER, id)
            .apply()
    }

    fun xDisplayOptions(context: Context): XDisplayOptions {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return XDisplayOptions(
            hideReply = prefs.getBoolean(X_HIDE_REPLY, false),
            hideRepost = prefs.getBoolean(X_HIDE_REPOST, false),
            hideViews = prefs.getBoolean(X_HIDE_VIEWS, false),
            hideShare = prefs.getBoolean(X_HIDE_SHARE, false),
            hideBottomNavigation = prefs.getBoolean(X_HIDE_BOTTOM, false),
            hideMediaDescription = prefs.getBoolean(X_HIDE_MEDIA_DESCRIPTION, false),
            hideMediaTitle = prefs.getBoolean(X_HIDE_MEDIA_TITLE, false),
            fontSizePercent = prefs.getInt(X_FONT_PERCENT, 100).coerceIn(80, 160),
            hideBottomHome = prefs.getBoolean(X_HIDE_BOTTOM_HOME, false),
            hideBottomSearch = prefs.getBoolean(X_HIDE_BOTTOM_SEARCH, false),
            hideBottomGrok = prefs.getBoolean(X_HIDE_BOTTOM_GROK, false),
            hideBottomChat = prefs.getBoolean(X_HIDE_BOTTOM_CHAT, false),
            hideBottomNotifications = prefs.getBoolean(X_HIDE_BOTTOM_NOTIFICATIONS, false),
            hideBottomShare = prefs.getBoolean(X_HIDE_BOTTOM_SHARE, false),
            hideVideoPlaybackText = prefs.getBoolean(X_HIDE_VIDEO_PLAYBACK_TEXT, false),
            hideVideoPlaybackTitle = prefs.getBoolean(X_HIDE_VIDEO_PLAYBACK_TITLE, false),
            hideVideoBelowProgressDetails = prefs.getBoolean(X_HIDE_VIDEO_BELOW_PROGRESS_DETAILS, false)
        )
    }

    fun setXDisplayOptions(context: Context, options: XDisplayOptions) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(X_HIDE_REPLY, options.hideReply)
            .putBoolean(X_HIDE_REPOST, options.hideRepost)
            .putBoolean(X_HIDE_VIEWS, options.hideViews)
            .putBoolean(X_HIDE_SHARE, options.hideShare)
            .putBoolean(X_HIDE_BOTTOM, options.hideBottomNavigation)
            .putBoolean(X_HIDE_MEDIA_DESCRIPTION, options.hideMediaDescription)
            .putBoolean(X_HIDE_MEDIA_TITLE, options.hideMediaTitle)
            .putInt(X_FONT_PERCENT, options.fontSizePercent.coerceIn(80, 160))
            .putBoolean(X_HIDE_BOTTOM_HOME, options.hideBottomHome)
            .putBoolean(X_HIDE_BOTTOM_SEARCH, options.hideBottomSearch)
            .putBoolean(X_HIDE_BOTTOM_GROK, options.hideBottomGrok)
            .putBoolean(X_HIDE_BOTTOM_CHAT, options.hideBottomChat)
            .putBoolean(X_HIDE_BOTTOM_NOTIFICATIONS, options.hideBottomNotifications)
            .putBoolean(X_HIDE_BOTTOM_SHARE, options.hideBottomShare)
            .putBoolean(X_HIDE_VIDEO_PLAYBACK_TEXT, options.hideVideoPlaybackText)
            .putBoolean(X_HIDE_VIDEO_PLAYBACK_TITLE, options.hideVideoPlaybackTitle)
            .putBoolean(X_HIDE_VIDEO_BELOW_PROGRESS_DETAILS, options.hideVideoBelowProgressDetails)
            .apply()
    }

    fun floatingDownloadPosition(context: Context): FloatingDownloadPosition {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        fun normalized(key: String): Float {
            val fraction = prefs.getFloat(key, 1f)
            return if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 1f
        }
        return FloatingDownloadPosition(normalized(FLOAT_BUTTON_X), normalized(FLOAT_BUTTON_Y))
    }

    /** Applies to all video download overlays, not just X. */
    fun floatingButtonOpacityPercent(context: Context): Int =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getInt(FLOAT_BUTTON_OPACITY, 85).coerceIn(20, 100)

    fun setFloatingButtonOpacityPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt(FLOAT_BUTTON_OPACITY, percent.coerceIn(20, 100))
            .apply()
    }

    fun setFloatingDownloadPosition(context: Context, position: FloatingDownloadPosition) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putFloat(FLOAT_BUTTON_X, position.xFraction.coerceIn(0f, 1f))
            .putFloat(FLOAT_BUTTON_Y, position.yFraction.coerceIn(0f, 1f))
            .apply()
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
