package com.jimmy.mydownload

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

data class VideoPlayerOption(val id: String, val title: String)

/**
 * Only external Android players for downloaded, locally stored videos.
 * X's live web player cannot be swapped for an Android app without losing its
 * authenticated streams and its feed navigation controls.
 */
object VideoPlayerApps {
    const val SYSTEM = ""
    const val ASK_EVERY_TIME = "__ask_each_time__"

    fun installed(context: Context): List<VideoPlayerOption> {
        val manager = context.packageManager
        val formats = listOf("video/mp4", "video/*", "video/mp2t")
        return formats.flatMap { format ->
            val probe = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse("content://com.jimmy.mydownload.playerprobe/test.mp4"), format)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            manager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
        }.filter { info ->
            info.activityInfo?.exported == true &&
                info.activityInfo.packageName != context.packageName
        }.distinctBy { it.activityInfo.packageName }
            .map { info ->
                VideoPlayerOption(
                    info.activityInfo.packageName,
                    info.loadLabel(manager)?.toString()?.takeIf(String::isNotBlank)
                        ?: info.activityInfo.packageName
                )
            }.sortedBy { it.title.lowercase() }
    }

    fun options(context: Context): List<VideoPlayerOption> =
        listOf(
            VideoPlayerOption(SYSTEM, "系統預設播放器"),
            VideoPlayerOption(ASK_EVERY_TIME, "每次詢問使用哪個播放器")
        ) + installed(context)

    fun label(context: Context, id: String): String =
        options(context).firstOrNull { it.id == id }?.title
            ?: "原播放器未安裝，請重新選擇"

    fun open(context: Context, videoUri: Uri, mime: String) {
        val selected = DownloadSettings.videoPlayer(context)
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(videoUri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, "影片", videoUri)
        }
        when (selected) {
            SYSTEM -> context.startActivity(viewIntent)
            ASK_EVERY_TIME -> context.startActivity(
                Intent.createChooser(viewIntent, "選擇影片播放器")
            )
            else -> {
                // Never silently choose a different app. On uninstall or MIME
                // incompatibility the user can update the preference in Settings.
                val target = viewIntent.apply { setPackage(selected) }
                if (context.packageManager.queryIntentActivities(
                        target, PackageManager.MATCH_DEFAULT_ONLY
                    ).isEmpty()
                ) {
                    throw IllegalStateException(
                        "選擇的播放器無法開啟此影片格式，請到設定更換播放器。"
                    )
                }
                context.startActivity(target)
            }
        }
    }
}
