package app.housemove.data

/** The iPhone's QR encoder comes in step 1b (design/ios-plan.md), with the same modules as zxing's (golden/README.md, qr.json). */
actual fun qrPixels(text: String, sizePx: Int): QrPixels = throw NotImplementedError("step 1b")
