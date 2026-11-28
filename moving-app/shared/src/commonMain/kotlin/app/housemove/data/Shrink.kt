package app.housemove.data

import kotlin.concurrent.Volatile

/**
 * Small copies of photos, for update files and the daily small backup: about [SIDE] pixels on the longer
 * side, as a JPEG of quality [QUALITY]. The data layer does not draw pictures itself; the app puts in the
 * phone's own way of doing it when it starts ([jpeg]). Without one (on the JVM, in tests) a photo goes as
 * it is.
 */
object Shrink {
    const val SIDE = 1024
    const val QUALITY = 70

    /** Makes the small copy of a photo file, or null when it cannot. Set by the app at start; tests put in their own. */
    @Volatile
    var jpeg: ((file: File, side: Int, quality: Int) -> ByteArray?)? = null

    /** The small copy of [file], or the file as it is when no small copy can be made. Null when the file cannot be read. */
    fun copyOf(file: File): ByteArray? {
        val small = try {
            jpeg?.invoke(file, SIDE, QUALITY)
        } catch (_: Throwable) {
            null
        }
        // A photo already smaller than its small copy would be goes as it is.
        if (small != null && small.isNotEmpty() && small.size < file.length()) return small
        return try {
            file.readBytes()
        } catch (_: Exception) {
            null
        }
    }
}
