package app.housemove.ui

import app.housemove.data.File
import app.housemove.data.OutputStream
import java.io.FileOutputStream

// Android and the desktop JVM: File is java.io.File, and these are its own calls.

@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
actual val File.path: String get() = getPath()

@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
actual fun File.delete(): Boolean = (this as java.io.File).delete()

actual fun File.outputStream(): OutputStream = FileOutputStream(this)
