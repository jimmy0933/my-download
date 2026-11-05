package com.jimmy.mydownload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Mp4RangesTest {
    @Test fun oneByteProbeUsesFullContentRangeTotalNotOneByteLength() {
        assertEquals(17_000_000L, Mp4Ranges.totalForResponse(
            206, "bytes 0-0/17000000", 1L
        ))
        assertEquals("16.2 MB", Mp4Ranges.label(17_000_000L))
    }

    @Test fun missingContentRangeNeverDisplaysProbeAsZeroKbVideo() {
        assertNull(Mp4Ranges.totalForResponse(206, null, 1L))
        assertEquals("大小未知", Mp4Ranges.label(null))
        assertEquals("1 B", Mp4Ranges.label(1L))
    }

    @Test fun partialChunksPreserveOffsetsAndWholeFileSize() {
        val first = Mp4Ranges.parse("bytes 0-999/3000")!!
        val next = Mp4Ranges.parse("bytes 1000-1999/3000")!!
        val last = Mp4Ranges.parse("bytes 2000-2999/3000")!!
        assertEquals(1000L, first.length)
        assertEquals(1000L, next.start)
        assertEquals(3000L, last.total)
        assertEquals(3000L, last.end + 1)
    }

    @Test fun rejectsInvalidAndUnsatisfiedRanges() {
        assertNull(Mp4Ranges.parse("bytes 1000-0/2000"))
        assertNull(Mp4Ranges.parse("bytes 0-5000/2000"))
        assertEquals(4096L, Mp4Ranges.totalFor416("bytes */4096"))
        assertEquals(4096L, Mp4Ranges.totalForResponse(200, null, 4096L))
    }
}
