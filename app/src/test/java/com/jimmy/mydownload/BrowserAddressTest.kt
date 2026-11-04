package com.jimmy.mydownload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAddressTest {
    @Test fun acceptsDifferentSitesAndNormalizesDomains() {
        assertEquals("https://x.com/", BrowserAddress.navigateInput("x.com/"))
        assertEquals("https://www.av01.media/tw/", BrowserAddress.navigateInput("www.av01.media/tw/"))
        assertEquals("https://example.org/video?id=123", BrowserAddress.navigateInput(
            "https://example.org/video?id=123"
        ))
        assertTrue(BrowserAddress.isWebUrl("https://x.com/user/status/12345"))
        assertTrue(BrowserAddress.isWebUrl("http://example.com/"))
    }

    @Test fun searchesWhenInputIsNotADomain() {
        assertEquals("https://www.google.com/search?q=fun+video",
            BrowserAddress.navigateInput("fun video"))
        assertEquals("https://www.google.com/search?q=hello",
            BrowserAddress.navigateInput("hello"))
        assertEquals("https://www.google.com/search?q=%E5%8F%B0%E7%81%A3",
            BrowserAddress.navigateInput("台灣"))
    }

    @Test fun rejectsDangerousAndInvalidSchemes() {
        assertNull(BrowserAddress.navigateInput("javascript:alert(1)"))
        assertNull(BrowserAddress.navigateInput("file:///sdcard/video.mp4"))
        assertNull(BrowserAddress.navigateInput("intent://evil"))
        assertNull(BrowserAddress.navigateInput("data:text/html,abc"))
        assertNull(BrowserAddress.navigateInput(""))
        assertFalse(BrowserAddress.isWebUrl("https://evil.example@x.com/"))
        assertFalse(BrowserAddress.isWebUrl("ftp://example.com/video.mp4"))
        assertEquals("新分頁", BrowserAddress.displayName("about:blank"))
    }
}
