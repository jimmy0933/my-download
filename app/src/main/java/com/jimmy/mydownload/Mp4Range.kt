package com.jimmy.mydownload

/**
 * HTTP byte range information. A 206 response's Content-Length describes only
 * the returned chunk, not the original video size.
 */
data class Mp4Range(val start: Long, val end: Long, val total: Long?) {
    val length: Long get() = end - start + 1
}

object Mp4Ranges {
    // Tiny X MP4 responses can contain only ftyp/moov initialization data
    // without any video frames. Never present these as complete movies.
    const val MIN_PLAUSIBLE_MP4_BYTES = 16L * 1024L

    fun isPlausibleVideoSize(size: Long?): Boolean =
        size == null || size >= MIN_PLAUSIBLE_MP4_BYTES

    private val RANGE = Regex("""(?i)bytes\s+(\d+)-(\d+)/(\d+|\*)""")
    private val UNSATISFIABLE = Regex("""(?i)bytes\s+\*/(\d+)""")

    fun parse(value: String?): Mp4Range? {
        val m = RANGE.matchEntire(value?.trim().orEmpty()) ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        val end = m.groupValues[2].toLongOrNull() ?: return null
        val total = m.groupValues[3].takeIf { it != "*" }?.toLongOrNull()
        if (end < start || (total != null && (total <= 0L || end >= total))) return null
        return Mp4Range(start, end, total)
    }

    fun totalForResponse(status: Int, contentRange: String?, contentLength: Long?): Long? {
        return when (status) {
            206 -> parse(contentRange)?.total
            200 -> contentLength?.takeIf { it > 0L }
            else -> null
        }
    }

    fun totalFor416(contentRange: String?): Long? =
        UNSATISFIABLE.matchEntire(contentRange?.trim().orEmpty())
            ?.groupValues?.get(1)?.toLongOrNull()

    fun label(totalBytes: Long?): String {
        if (totalBytes == null || totalBytes <= 0L) return "大小未知"
        if (totalBytes < 1024L) return totalBytes.toString() + " B"
        if (totalBytes < 1024L * 1024L) return (totalBytes / 1024L).toString() + " KB"
        return String.format(java.util.Locale.US, "%.1f MB",
            totalBytes.toDouble() / (1024L * 1024L))
    }
}
