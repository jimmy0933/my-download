package com.jimmy.mydownload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DownloadTest {
    @Test fun av01VideoUrlsAreRestrictedToExpectedHosts() {
        assertTrue(Av01BrowserLinks.isVideoLink(
            "https://www.av01.media/tw/video/223716/sone-853-ltx"
        ))
        assertEquals(false, Av01BrowserLinks.isVideoLink("https://example.net/video/223716"))
        assertEquals(false, Av01BrowserLinks.isVideoLink(
            "https://av01.media.fake.example/video/223716"
        ))
    }

    @Test fun hlsMasterOffersQualityOptions() {
        val content = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1920x1080
            1080/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1600000,RESOLUTION=1280x720
            720/index.m3u8
        """.trimIndent()
        val options = StreamResolver.parseManifest(
            content, "https://cdn.example.org/master.m3u8", "demo", "https://example.org/"
        )
        assertEquals(2, options.size)
        assertEquals("1080P", options.first().label)
        assertEquals("https://cdn.example.org/1080/index.m3u8", options.first().url)
    }

    @Test fun rejectsEncryptedHls() {
        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://example.org/key"
            #EXTINF:5.0,
            segment.ts
        """.trimIndent()
        assertThrows(IOException::class.java) {
            StreamResolver.parseManifest(playlist, "https://example.org/index.m3u8", "demo", "r")
        }
    }

    @Test fun adFilterDoesNotBlockVideoCdn() {
        assertTrue(AdPageFilter.blocks("https://ads.exoclick.com/pop?x=1"))
        assertEquals(false, AdPageFilter.blocks("https://www.av01.media/tw/video/223716"))
        assertEquals(false, AdPageFilter.blocks("https://customers.iw01.xyz/api/v1/videos/1"))
        assertEquals(false, AdPageFilter.blocks("https://exoclick.com.evil.example/"))
    }
}

class CdnTokenTest {
    @Test fun accessTokenOnlyGoesToIssuingCdn() {
        assertTrue(Av01Resolver.isCdnUrl("https://customers.iw01.xyz/seg/1.ts"))
        assertEquals(false, Av01Resolver.isCdnUrl("https://evil.example/seg/1.ts"))
        assertEquals(false, Av01Resolver.isCdnUrl("https://customers.iw01.xyz.evil.example/x"))
    }
}

class SeparateAudioTest {
    @Test fun masterWithSeparateAudioKeepsAudioPlaylist() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:NAME="Audio",TYPE=AUDIO,GROUP-ID="audio-128",URI="/amplify_video/1/mp4a/128000/a.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=832000,RESOLUTION=480x852,CODECS="avc1.4d001f,mp4a.40.2",AUDIO="audio-128"
            /amplify_video/1/avc1/480x852/v.m3u8
        """.trimIndent()
        val variants = StreamResolver.parseManifest(
            master, "https://video.twimg.com/amplify_video/1/pl/master.m3u8", "t", "https://x.com/"
        )
        assertEquals(1, variants.size)
        assertEquals("https://video.twimg.com/amplify_video/1/mp4a/128000/a.m3u8",
            variants.first().audioUrl)
    }

    @Test fun masterWithoutSeparateAudioHasNoAudioUrl() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=832000,RESOLUTION=480x852
            v.m3u8
        """.trimIndent()
        val variants = StreamResolver.parseManifest(
            master, "https://cdn.example.org/master.m3u8", "t", "r"
        )
        assertEquals("", variants.first().audioUrl)
    }
}
