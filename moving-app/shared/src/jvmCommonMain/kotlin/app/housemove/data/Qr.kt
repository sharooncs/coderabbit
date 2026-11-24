package app.housemove.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Which kinds of code a scanner should accept. Narrow sets avoid misreads of unrelated codes. */
enum class ScanFormats(internal val formats: List<BarcodeFormat>) {
    /** HouseMove's own stickers and sync codes. */
    LABELS(listOf(BarcodeFormat.QR_CODE)),

    /** The barcode already printed on a book or a product. */
    PRODUCTS(listOf(BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E)),

    /** Serial-number labels on the back of devices. */
    SERIALS(
        listOf(
            BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93, BarcodeFormat.DATA_MATRIX,
            BarcodeFormat.QR_CODE, BarcodeFormat.EAN_13, BarcodeFormat.UPC_A,
        )
    ),

    /** Anything: used when the app looks up what a code belongs to. */
    ANY(
        listOf(
            BarcodeFormat.QR_CODE, BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
            BarcodeFormat.CODE_128, BarcodeFormat.DATA_MATRIX,
        )
    );

    internal val hasLinear get() = formats.any { it != BarcodeFormat.QR_CODE && it != BarcodeFormat.DATA_MATRIX }
}

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

/** QR and barcode encoding and decoding, kept free of Android classes so it can be tested on its own. */
object Qr {
    fun matrix(text: String, sizePx: Int): BitMatrix {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
        return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    }

    /** Reusable decoder for a stream of camera frames. Not thread-safe; use from one thread. */
    class Decoder(private val accept: ScanFormats = ScanFormats.LABELS) {
        private val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to accept.formats, DecodeHintType.TRY_HARDER to true))
        }
        private var turned = ByteArray(0)

        /** [luma] is one brightness byte per pixel, row by row. Returns the code's text or null. */
        fun decode(luma: ByteArray, width: Int, height: Int): String? {
            attempt(luma, width, height)?.let { return it }
            if (!accept.hasLinear) return null
            // Striped barcodes are only read along their length, and the camera frame is
            // often a quarter turn from how the phone is held, so try that way round too.
            if (turned.size != luma.size) turned = ByteArray(luma.size)
            for (y in 0 until height) {
                val row = y * width
                val col = height - 1 - y
                for (x in 0 until width) turned[x * height + col] = luma[row + x]
            }
            return attempt(turned, height, width)
        }

        private fun attempt(luma: ByteArray, width: Int, height: Int): String? {
            val source = PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false)
            return try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            } catch (_: NotFoundException) {
                try {
                    reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert()))).text
                } catch (_: NotFoundException) {
                    null
                }
            } finally {
                reader.reset()
            }
        }
    }
}
