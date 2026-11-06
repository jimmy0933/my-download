package com.jimmy.mydownload

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XMediaNavigationTest {
    private val base = "https://x.com/example/status/123456"

    @Test fun samePostPhotoSwipesKeepViewer() {
        assertTrue(XMediaNavigation.samePostViewer("$base/photo/1", "$base/photo/2"))
        assertTrue(XMediaNavigation.samePostViewer("$base/photo/2", "$base/video/1"))
        assertTrue(XMediaNavigation.samePostViewer("$base/photo/1?s=20", "$base/photo/2"))
    }

    @Test fun verticalVideoSwipesKeepExistingWebView() {
        val otherPost = "https://x.com/another/status/789012"
        assertTrue(XMediaNavigation.isMediaSwipe("$base/video/1", "$otherPost/video/1"))
        assertTrue(XMediaNavigation.isMediaSwipe("$base/photo/1", "$otherPost/video/1"))
        assertTrue(XMediaNavigation.isMediaSwipe("https://x.com/i/status/123456",
            "https://x.com/i/status/789012"))
        assertFalse(XMediaNavigation.isMediaSwipe("$base", "$otherPost/video/1"))
        assertFalse(XMediaNavigation.isMediaSwipe("$base/video/1", "https://example.com/video/2"))
    }

    @Test fun unrelatedNavigationDoesNotKeepMedia() {
        assertFalse(XMediaNavigation.samePostViewer("$base/photo/1", "$base"))
        assertFalse(XMediaNavigation.samePostViewer("$base/photo/1", "https://x.com/example/status/321/photo/1"))
        assertFalse(XMediaNavigation.samePostViewer("$base/photo/1", "https://other.example/status/123456/photo/2"))
        assertFalse(XMediaNavigation.samePostViewer("$base/photo/1", "$base/photo/1"))
    }
}
