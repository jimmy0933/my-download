package com.jimmy.mydownload

import org.json.JSONArray
import org.json.JSONObject

/** CSS pixels (document/scrollable column) and physical WebView pixels separately. */
data class BrowserScrollPoint(
    val page: Int = 0,
    val panel: Int = 0,
    val native: Int = 0
) {
    fun hasPosition() = page > 0 || panel > 0 || native > 0
}

object BrowserScroll {
    // X's React timeline commonly scrolls the document; a future layout may
    // use a scrollable column. Reading both avoids treating a WebView scrollY=0
    // as evidence that the user was at the top.
    val CAPTURE_SCRIPT = """
(() => {
  const root = document.scrollingElement || document.documentElement;
  const primary = document.querySelector('[data-testid="primaryColumn"]');
  let panel = null;
  for (let e = primary; e && e !== document.body; e = e.parentElement) {
    if (e.scrollHeight > e.clientHeight + 80 &&
        /(auto|scroll)/.test(getComputedStyle(e).overflowY)) {
      panel = e; break;
    }
  }
  return JSON.stringify({
    page: Math.round(Math.max(window.scrollY || 0, root?.scrollTop || 0)),
    panel: Math.round(panel?.scrollTop || 0)
  });
})()
""".trimIndent()

    fun decode(result: String, native: Int): BrowserScrollPoint {
        return runCatching {
            val text = JSONArray("[" + result + "]").getString(0)
            val json = JSONObject(text)
            BrowserScrollPoint(
                json.optInt("page", 0).coerceAtLeast(0),
                json.optInt("panel", 0).coerceAtLeast(0),
                native.coerceAtLeast(0)
            )
        }.getOrDefault(BrowserScrollPoint(native = native.coerceAtLeast(0)))
    }

    fun restoreScript(point: BrowserScrollPoint): String = """
(() => {
  const root = document.scrollingElement || document.documentElement;
  const primary = document.querySelector('[data-testid="primaryColumn"]');
  if (${point.page} > 0) {
    window.scrollTo(0, ${point.page});
    if (root) root.scrollTop = ${point.page};
  }
  if (${point.panel} > 0 && primary) {
    for (let e = primary; e && e !== document.body; e = e.parentElement) {
      if (e.scrollHeight > e.clientHeight + 80 &&
          /(auto|scroll)/.test(getComputedStyle(e).overflowY)) {
        e.scrollTop = ${point.panel}; break;
      }
    }
  }
})()
""".trimIndent()
}
