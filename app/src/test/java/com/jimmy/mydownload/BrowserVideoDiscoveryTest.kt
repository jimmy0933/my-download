package com.jimmy.mydownload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserVideoDiscoveryTest {
    @Test fun recognizesPublicXAndTwitterPages() {
        assertTrue(BrowserVideoDiscovery.isXPage("https://x.com/home"))
        assertTrue(BrowserVideoDiscovery.isXPage("https://twitter.com/user/status/123"))
        assertFalse(BrowserVideoDiscovery.isXPage("https://x.com.attacker.example/video"))
        assertFalse(BrowserVideoDiscovery.isXPage("https://example.org/"))
    }

    @Test fun hiddenXCaptionsRemainAvailableToBothDiscoveryPaths() {
        // CSS display:none makes innerText empty but leaves textContent intact.
        assertTrue(BrowserVideoDiscovery.ACTIVE_VIDEO_SCAN_SCRIPT
            .contains("candidate?.textContent?.trim()"))
        assertTrue(BrowserVideoDiscovery.SCAN_SCRIPT
            .contains("candidate?.textContent?.trim()"))
    }

    @Test fun XScanNeverBorrowsAnUnrelatedFirstFeedPost() {
        assertFalse(BrowserVideoDiscovery.SCAN_SCRIPT.contains("posts[0]"))
        assertTrue(BrowserVideoDiscovery.ACTIVE_VIDEO_SCAN_SCRIPT
            .contains("node.querySelectorAll('video').length>1"))
    }

    @Test fun onlyDirectVideoFilesOrHlsPlaylistsAreDownloadCandidates() {
        assertEquals("mp4", BrowserVideoDiscovery.kind(
            "https://video.twimg.com/ext_tw_video/123/pu/vid/avc1/1280x720/abc.mp4?tag=10"))
        assertEquals("hls", BrowserVideoDiscovery.kind(
            "https://video.twimg.com/ext_tw_video/123/pu/pl/master.m3u8?tag=10"))
        assertEquals("mp4", BrowserVideoDiscovery.kind(
            "https://example.org/video.mp4?download=1"))
        assertEquals(null, BrowserVideoDiscovery.kind("blob:https://x.com/123"))
        assertEquals(null, BrowserVideoDiscovery.kind("https://video.twimg.com/a/segment.m4s"))
        assertEquals(null, BrowserVideoDiscovery.kind("javascript:alert(1)"))
        assertEquals(null, BrowserVideoDiscovery.kind("https://user:pass@server.example/video.mp4"))
        assertEquals(null, BrowserVideoDiscovery.kind("https://video.twimg.com/preview.jpg"))
    }
}
