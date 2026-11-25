package app.housemove.data

// The iPhone's files, folders, streams and zips come in step 1b of design/ios-plan.md (files and folders through
// NSFileManager under the app's Application Support folder, a zip reader and writer limited to what Android reads).
// Until then every one throws, so nothing on the iPhone quietly behaves differently from Android.

private fun notYet(): Nothing = throw NotImplementedError("step 1b")

actual class File actual constructor(parent: File?, child: String) {
    init {
        notYet()
    }
}

internal actual val File.name: String get() = notYet()
internal actual val File.isFile: Boolean get() = notYet()
internal actual val File.isDirectory: Boolean get() = notYet()
internal actual val File.parentFile: File? get() = notYet()
internal actual val File.absoluteFile: File get() = notYet()
internal actual fun File.exists(): Boolean = notYet()
internal actual fun File.delete(): Boolean = notYet()
internal actual fun File.mkdirs(): Boolean = notYet()
internal actual fun File.renameTo(dest: File): Boolean = notYet()
internal actual fun File.length(): Long = notYet()
internal actual fun File.listFiles(): Array<File>? = notYet()
internal actual fun File.readText(): String = notYet()
internal actual fun File.writeText(text: String): Unit = notYet()
internal actual fun File.readBytes(): ByteArray = notYet()
internal actual fun File.copyTo(target: File, overwrite: Boolean): File = notYet()
internal actual fun File.deleteRecursively(): Boolean = notYet()
internal actual fun File.inputStream(): InputStream = notYet()
internal actual fun File.outputStream(): OutputStream = notYet()

/** Bytes read in order. Step 1b gives it a file and a socket version; [readBytes] is what each reads with. */
actual abstract class InputStream : AutoCloseable {
    /** Up to [len] bytes into [b] from [off]: how many were read, or -1 at the end. */
    abstract fun readBytes(b: ByteArray, off: Int, len: Int): Int

    actual override fun close() {}
}

/** Bytes written in order. Step 1b gives it a file and a socket version; [writeBytes] is what each writes with. */
actual abstract class OutputStream : AutoCloseable {
    abstract fun writeBytes(b: ByteArray, off: Int, len: Int)

    open fun flush() {}

    actual override fun close() {}
}

internal actual fun OutputStream.write(b: ByteArray, off: Int, len: Int) = writeBytes(b, off, len)

actual open class IOException : Exception {
    actual constructor() : super()
    actual constructor(message: String?) : super(message)
    actual constructor(message: String?, cause: Throwable?) : super(message, cause)
    actual constructor(cause: Throwable?) : super(cause)
}

actual open class EOFException : IOException {
    actual constructor() : super()
    actual constructor(message: String?) : super(message)
}

actual open class UTFDataFormatException : IOException {
    actual constructor() : super()
    actual constructor(message: String?) : super(message)
}

internal actual class ZipWriter actual constructor(out: OutputStream) : AutoCloseable {
    init {
        notYet()
    }

    actual fun put(name: String, bytes: () -> ByteArray): Unit = notYet()
    actual fun putFile(name: String, from: File): Unit = notYet()
    actual override fun close(): Unit = notYet()
}

internal actual class ZipReader actual constructor(input: InputStream) : AutoCloseable {
    init {
        notYet()
    }

    actual fun nextEntry(): ZipItem? = notYet()
    actual fun readBytes(): ByteArray = notYet()
    actual fun copyTo(file: File): Unit = notYet()
    actual fun closeEntry(): Unit = notYet()
    actual override fun close(): Unit = notYet()
}
