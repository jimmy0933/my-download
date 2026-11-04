package com.jimmy.mydownload

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class BrowserTabRecord(val id: String, val url: String, val title: String)
data class BrowserBookmark(val url: String, val title: String, val createdAt: Long)
data class BrowserHistoryEntry(val url: String, val title: String, val visitedAt: Long)
data class BrowserSession(val tabs: List<BrowserTabRecord>, val activeId: String)

/** Persists only HTTP(S) pages and user's saved data; no cookies or passwords are copied. */
object BrowserStore {
    const val MAX_TABS = 12
    private const val MAX_BOOKMARKS = 300
    private const val MAX_HISTORY = 250
    private const val PREF = "browser_store_v1"
    private const val TABS = "tabs"
    private const val ACTIVE = "active_tab"
    private const val BOOKMARKS = "bookmarks"
    private const val HISTORY = "history"

    fun newTab(url: String = BrowserAddress.BLANK_TAB, title: String = "新分頁") =
        BrowserTabRecord(UUID.randomUUID().toString(), url, title)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun readArray(context: Context, key: String): JSONArray = runCatching {
        JSONArray(prefs(context).getString(key, "[]") ?: "[]")
    }.getOrDefault(JSONArray())

    @Synchronized
    fun loadSession(context: Context): BrowserSession {
        val entries = readArray(context, TABS)
        val tabs = buildList {
            for (i in 0 until minOf(entries.length(), MAX_TABS)) {
                val data = entries.optJSONObject(i) ?: continue
                val url = data.optString("url")
                val id = data.optString("id")
                if (id.isBlank() ||
                    !(BrowserAddress.isWebUrl(url) || url == BrowserAddress.BLANK_TAB)) continue
                add(BrowserTabRecord(id, url, data.optString("title", "分頁").take(160)))
            }
        }.distinctBy { it.id }
        if (tabs.isEmpty()) {
            val first = newTab(BrowserAddress.DEFAULT_SITE, "AV01")
            return BrowserSession(listOf(first), first.id)
        }
        val active = prefs(context).getString(ACTIVE, "").orEmpty()
        return BrowserSession(tabs, active.takeIf { it in tabs.map(BrowserTabRecord::id) }
            ?: tabs.first().id)
    }

    @Synchronized
    fun saveSession(context: Context, tabs: List<BrowserTabRecord>, activeId: String) {
        val array = JSONArray()
        tabs.take(MAX_TABS).forEach { tab ->
            if (tab.url == BrowserAddress.BLANK_TAB || BrowserAddress.isWebUrl(tab.url)) {
                array.put(JSONObject().apply {
                    put("id", tab.id)
                    put("url", tab.url)
                    put("title", tab.title.take(160))
                })
            }
        }
        prefs(context).edit()
            .putString(TABS, array.toString())
            .putString(ACTIVE, activeId)
            .apply()
    }

    @Synchronized
    fun bookmarks(context: Context): List<BrowserBookmark> {
        val array = readArray(context, BOOKMARKS)
        return buildList {
            for (i in 0 until array.length()) {
                val value = array.optJSONObject(i) ?: continue
                val url = value.optString("url")
                if (BrowserAddress.isWebUrl(url)) add(
                    BrowserBookmark(url, value.optString("title").ifBlank {
                        BrowserAddress.displayName(url)
                    }, value.optLong("date"))
                )
            }
        }.distinctBy { it.url }
    }

    @Synchronized
    fun toggleBookmark(context: Context, url: String, title: String): Boolean {
        if (!BrowserAddress.isWebUrl(url)) return false
        val old = bookmarks(context)
        val already = old.any { it.url == url }
        val updated = if (already) old.filter { it.url != url }
        else listOf(BrowserBookmark(url, title.ifBlank { BrowserAddress.displayName(url) },
            System.currentTimeMillis())) + old
        val json = JSONArray()
        updated.take(MAX_BOOKMARKS).forEach {
            json.put(JSONObject().put("url", it.url).put("title", it.title).put("date", it.createdAt))
        }
        prefs(context).edit().putString(BOOKMARKS, json.toString()).apply()
        return !already
    }

    @Synchronized
    fun history(context: Context): List<BrowserHistoryEntry> {
        val array = readArray(context, HISTORY)
        return buildList {
            for (i in 0 until array.length()) {
                val data = array.optJSONObject(i) ?: continue
                val url = data.optString("url")
                if (BrowserAddress.isWebUrl(url)) add(
                    BrowserHistoryEntry(url, data.optString("title"), data.optLong("date"))
                )
            }
        }
    }

    @Synchronized
    fun recordVisit(context: Context, url: String, title: String) {
        if (!BrowserAddress.isWebUrl(url)) return
        val now = System.currentTimeMillis()
        val old = history(context)
        // Most recent visits first; don't create endless duplicate entries on SPA reloads.
        if (old.firstOrNull()?.url == url && now - old.first().visitedAt < 15_000) return
        val entries = listOf(
            BrowserHistoryEntry(url, title.ifBlank { BrowserAddress.displayName(url) }, now)
        ) + old.filterNot { it.url == url }
        val array = JSONArray()
        entries.take(MAX_HISTORY).forEach {
            array.put(JSONObject().put("url", it.url).put("title", it.title).put("date", it.visitedAt))
        }
        prefs(context).edit().putString(HISTORY, array.toString()).apply()
    }

    @Synchronized
    fun clearHistory(context: Context) {
        prefs(context).edit().remove(HISTORY).apply()
    }
}
