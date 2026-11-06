package com.jimmy.mydownload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class BrowserScrollTest {
    @Test fun decodesJsAndNativeScrollIndependently() {
        val json = """{"page":1385,"panel":280}"""
        val quoted = JSONObject.quote(json)
        val point = BrowserScroll.decode(quoted, 2600)
        assertEquals(1385, point.page)
        assertEquals(280, point.panel)
        assertEquals(2600, point.native)
        assertTrue(point.hasPosition())
    }

    @Test fun restoresTopLevelAndNestedScrollPositions() {
        val js = BrowserScroll.restoreScript(BrowserScrollPoint(1200, 450, 2400))
        assertTrue(js.contains("window.scrollTo(0, 1200)"))
        assertTrue(js.contains("e.scrollTop = 450"))
    }

    @Test fun invalidJsCapturePreservesNativeScroll() {
        val point = BrowserScroll.decode("null", 700)
        assertEquals(700, point.native)
        assertTrue(point.hasPosition())
    }
}
