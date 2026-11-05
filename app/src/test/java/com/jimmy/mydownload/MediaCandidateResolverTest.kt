package com.jimmy.mydownload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCandidateResolverTest {
    @Test fun xProgressiveRenditionsShowActualDimensions() {
        val base = "https://video.twimg.com/ext_tw_video/1234567891234/pu/vid/avc1/"
        assertEquals("1280×720", MediaCandidateResolver.resolution(base + "1280x720/clip.mp4?tag=12"))
        assertEquals("480×270", MediaCandidateResolver.resolution(base + "480x270/clip.mp4?tag=12"))
        assertEquals("1080P", MediaCandidateResolver.resolution("https://example.com/video/1080p/movie.m3u8"))
        assertNull(MediaCandidateResolver.resolution("https://example.com/master.m3u8"))
    }

    @Test fun xMediaIdDisambiguatesDifferentVideosOnOneTimeline() {
        val a = MediaCandidateResolver.group(
            "https://video.twimg.com/ext_tw_video/1234567891234/pu/vid/avc1/1280x720/a.mp4")
        val b = MediaCandidateResolver.group(
            "https://video.twimg.com/ext_tw_video/9876543212345/pu/vid/avc1/1280x720/b.mp4")
        assertTrue(a.contains("影片 ID"))
        assertTrue(a != b)
    }
}
