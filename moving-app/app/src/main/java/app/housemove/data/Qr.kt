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

/** QR encoding and decoding, kept free of Android classes so it can be tested on its own. */
object Qr {
    fun matrix(text: String, sizePx: Int): BitMatrix {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
        return QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    }

    /** Reusable decoder for a stream of camera frames. Not thread-safe; use from one thread. */
    class Decoder {
        private val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
        }

        /** [luma] is one brightness byte per pixel, row by row. Returns the QR text or null. */
        fun decode(luma: ByteArray, width: Int, height: Int): String? {
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
