package com.jimmy.mydownload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTitleTest {
    @Test fun usesPostTextInsteadOfXHomeTitle() {
        assertEquals(
            "今天的小貓真可愛，第一次學會跳躍！",
            VideoTitle.preferred(
                "今天的小貓真可愛，第一次學會跳躍！",
                "首頁 / X",
                "https://x.com/example/status/1234567890123456789"
            )
        )
    }

    @Test fun removesFilenameForbiddenCharactersAndNewlines() {
        assertEquals("小貓 喵喵 今天 測試",
            VideoTitle.cleanText("小貓:喵喵\n今天/測試?"))
        assertTrue(VideoTitle.cleanText("很長的標題".repeat(25)).length <= 78)
    }

    @Test fun statusIdUsedWhenNoPostTextAvailable() {
        assertEquals(
            "X影片_123456789012345",
            VideoTitle.preferred("", "首頁 / X", "https://x.com/user/status/123456789012345/video/1")
        )
        assertEquals("X影片_9876543210",
            VideoTitle.preferred("", "X", "https://x.com/home", "9876543210"))
    }

    @Test fun recognizesVideoDetailUrlsOnlyOnX() {
        assertTrue(VideoTitle.isXStatus("https://x.com/demo/status/123456789012345/video/1"))
        assertTrue(VideoTitle.isXStatus("https://twitter.com/demo/status/123456789012345"))
        assertFalse(VideoTitle.isXStatus("https://x.com/home"))
        assertFalse(VideoTitle.isXStatus("https://example.org/demo/status/123456789012345"))
    }
}
