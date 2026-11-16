package com.jimmy.mydownload

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small on-device diagnostic log (Settings -> 診斷記錄). Kept private to the app,
 * trimmed to a few hundred KB, and meant to be copied into a bug report.
 */
object AppLog {
    private const val FILE = "app.log"
    private const val MAX_BYTES = 200_000L
    private val lock = Any()
    private val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun log(tag: String, message: String) {
        val context = appContext ?: return
        synchronized(lock) {
            runCatching {
                val file = File(context.filesDir, FILE)
                if (file.length() > MAX_BYTES) {
                    // Keep only the newest half.
                    file.writeText(file.readText().takeLast((MAX_BYTES / 2).toInt()))
                }
                file.appendText(format.format(Date()) + " [" + tag + "] " + message + "\n")
            }
        }
    }

    fun error(tag: String, message: String, error: Throwable) {
        log(
            tag,
            message + "：" + error.javaClass.simpleName + " " + (error.message ?: "") +
                "\n" + error.stackTraceToString().lineSequence().take(14).joinToString("\n")
        )
    }

    fun read(context: Context): String = synchronized(lock) {
        runCatching { File(context.filesDir, FILE).readText() }.getOrDefault("")
    }.ifBlank { "（目前沒有記錄）" }

    fun clear(context: Context) {
        synchronized(lock) { runCatching { File(context.filesDir, FILE).delete() } }
    }
}
