package com.jimmy.mydownload

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

data class DownloadRecord(
    val id: Long,
    val title: String,
    val url: String,
    val referer: String,
    val kind: String,
    val label: String,
    val destinationTree: String = "",
    val status: String = "排隊中",
    val progress: Int = 0,
    val uri: String = "",
    val checkpoint: Int = 0,
    val bytes: Long = 0,
    val error: String = ""
)

object DownloadStore {
    private const val PREF = "download_history"
    private const val KEY = "items"
    private val lock = Any()

    private fun fromJson(j: JSONObject) = DownloadRecord(
        id = j.getLong("id"),
        title = j.optString("title"),
        url = j.optString("url"),
        referer = j.optString("referer"),
        kind = j.optString("kind"),
        label = j.optString("label"),
        destinationTree = j.optString("destinationTree"),
        status = j.optString("status", "排隊中"),
        progress = j.optInt("progress"),
        uri = j.optString("uri"),
        checkpoint = j.optInt("checkpoint"),
        bytes = j.optLong("bytes"),
        error = j.optString("error")
    )

    private fun toJson(item: DownloadRecord) = JSONObject().apply {
        put("id", item.id)
        put("title", item.title)
        put("url", item.url)
        put("referer", item.referer)
        put("kind", item.kind)
        put("label", item.label)
        put("destinationTree", item.destinationTree)
        put("status", item.status)
        put("progress", item.progress)
        put("uri", item.uri)
        put("checkpoint", item.checkpoint)
        put("bytes", item.bytes)
        put("error", item.error)
    }

    fun read(context: Context): List<DownloadRecord> = synchronized(lock) {
        val json = runCatching {
            JSONArray(context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY, "[]") ?: "[]")
        }.getOrDefault(JSONArray())
        (0 until json.length()).mapNotNull { index ->
            runCatching { fromJson(json.getJSONObject(index)) }.getOrNull()
        }
    }

    private fun write(context: Context, items: List<DownloadRecord>) {
        val array = JSONArray()
        items.take(200).forEach { array.put(toJson(it)) }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).commit()
    }

    fun save(context: Context, item: DownloadRecord) = synchronized(lock) {
        val all = read(context).toMutableList()
        val index = all.indexOfFirst { it.id == item.id }
        if (index < 0) all.add(0, item) else all[index] = item
        write(context, all)
    }

    fun add(context: Context, stream: StreamChoice): DownloadRecord {
        val item = DownloadRecord(
            id = System.currentTimeMillis() * 1000 + Random.nextInt(1000),
            title = StreamResolver.safeTitle(stream.title),
            url = stream.url,
            referer = stream.referer,
            kind = stream.kind,
            label = stream.label,
            destinationTree = DownloadSettings.folder(context)
        )
        save(context, item)
        return item
    }

    fun get(context: Context, id: Long): DownloadRecord? = read(context).find { it.id == id }

    fun patch(context: Context, id: Long, change: (DownloadRecord) -> DownloadRecord) = synchronized(lock) {
        val all = read(context).toMutableList()
        val index = all.indexOfFirst { it.id == id }
        if (index >= 0) {
            all[index] = change(all[index])
            write(context, all)
        }
    }

    /**
     * Newly granted folder permissions should also repair transfers that failed
     * because their previously saved destination URI became invalid.
     * Do not redirect completed or actively downloading jobs.
     */
    fun repairFailedDestinations(context: Context, newTree: String): Int = synchronized(lock) {
        val records = read(context)
        var repaired = 0
        val updated = records.map { item ->
            val folderError = item.error.contains("資料夾") ||
                item.error.contains("授權") ||
                item.error.contains("寫入權限") ||
                item.error.contains("permission", ignoreCase = true) ||
                item.error.contains("denied", ignoreCase = true)
            if (item.status == "失敗" && item.uri.isBlank() &&
                item.destinationTree.isNotBlank() && folderError) {
                repaired++
                item.copy(destinationTree = newTree, status = "已暫停", error = "")
            } else item
        }
        if (repaired > 0) write(context, updated)
        repaired
    }

    fun remove(context: Context, id: Long) = synchronized(lock) {
        write(context, read(context).filterNot { it.id == id })
    }

    fun contentUri(record: DownloadRecord): Uri? =
        record.uri.takeIf(String::isNotBlank)?.let(Uri::parse)
}
