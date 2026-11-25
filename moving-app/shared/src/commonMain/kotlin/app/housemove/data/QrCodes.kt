package app.housemove.data

/** What a label QR printed by 2.1 begins with: then the move's tag ([moveTag]), a colon, and the code. */
const val TAGGED_PREFIX = "HM2:"

/** A scanned label: its code, and the tag of the move it was printed for (null on a label from before 2.1). */
data class ScannedLabel(val code: String, val tag: String?)

/** Read the text of a scanned label QR. Old labels ("HM:L042") still read, without a tag; other text is used as it is, as [labelFromQr] does. */
fun readLabel(text: String): ScannedLabel {
    val t = text.trim()
    if (t.startsWith(TAGGED_PREFIX)) {
        val rest = t.removePrefix(TAGGED_PREFIX)
        val i = rest.indexOf(':')
        if (i > 0) return ScannedLabel(rest.substring(i + 1).trim().take(24), rest.substring(0, i).uppercase())
    }
    return ScannedLabel(labelFromQr(t), null)
}

/** The code in a scanned label's text, with the move tag of a new label left out. The screens use this where they used [labelFromQr]. */
fun labelCodeOf(text: String): String = readLabel(text).code

/** The text of the QR code printed on a label for [code] in the move with [moveTag]; without a tag, as before 2.1. */
fun labelQrText(code: String, moveTag: String): String = if (moveTag.isBlank()) QR_PREFIX + code else "$TAGGED_PREFIX$moveTag:$code"

/** The squares of a QR code drawn [size] pixels wide and high: [get] is true for a dark pixel. */
class QrPixels(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]
}

/**
 * The QR code for [text], [sizePx] pixels square, as label sheets and screens draw it: on Android and the JVM through
 * [Qr.matrix] (zxing 3.5.3, error correction M, a quiet zone of one module, the modules scaled by a whole number and
 * centred). The iPhone's encoder comes in step 1b; it must make the same modules (golden/README.md, qr.json).
 */
expect fun qrPixels(text: String, sizePx: Int): QrPixels
