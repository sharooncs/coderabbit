// Member calls of java.io.File inside these extensions are meant: each is the call the code made before it was shared.
@file:Suppress("EXTENSION_SHADOWED_BY_MEMBER")

package app.housemove.data

import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.copyTo as ioCopyTo
import kotlin.io.deleteRecursively as ioDeleteRecursively
import kotlin.io.inputStream as ioInputStream
import kotlin.io.outputStream as ioOutputStream
import kotlin.io.readBytes as ioReadBytes
import kotlin.io.readText as ioReadText
import kotlin.io.writeText as ioWriteText

// Android and the JVM: java.io itself, so the shared code calls exactly what it called before it was shared.

actual typealias File = java.io.File

internal actual val File.name: String get() = getName()
internal actual val File.isFile: Boolean get() = isFile()
internal actual val File.isDirectory: Boolean get() = isDirectory()
internal actual val File.parentFile: File? get() = getParentFile()
internal actual val File.absoluteFile: File get() = getAbsoluteFile()
internal actual fun File.exists(): Boolean = exists()
internal actual fun File.delete(): Boolean = delete()
internal actual fun File.mkdirs(): Boolean = mkdirs()
internal actual fun File.renameTo(dest: File): Boolean = renameTo(dest)
internal actual fun File.length(): Long = length()
internal actual fun File.lastModified(): Long = lastModified()
internal actual fun File.listFiles(): Array<File>? = listFiles()
internal actual fun File.readText(): String = ioReadText()
internal actual fun File.writeText(text: String) = ioWriteText(text)
internal actual fun File.readBytes(): ByteArray = ioReadBytes()
internal actual fun File.copyTo(target: File, overwrite: Boolean): File = ioCopyTo(target, overwrite)
internal actual fun File.deleteRecursively(): Boolean = ioDeleteRecursively()
internal actual fun File.inputStream(): InputStream = ioInputStream()
internal actual fun File.outputStream(): OutputStream = ioOutputStream()

actual typealias InputStream = java.io.InputStream
actual typealias OutputStream = java.io.OutputStream

internal actual fun OutputStream.write(b: ByteArray, off: Int, len: Int) = write(b, off, len)
internal actual fun OutputStream.flush() = flush()
internal actual fun InputStream.read(b: ByteArray, off: Int, len: Int): Int = read(b, off, len)
internal actual fun InputStream.bufferedStream(): InputStream = buffered()
internal actual fun OutputStream.bufferedStream(): OutputStream = buffered()

internal actual fun outputStreamOf(onWrite: (ByteArray, Int, Int) -> Unit, onFlush: () -> Unit, onClose: () -> Unit): OutputStream =
    object : OutputStream() {
        override fun write(b: Int) = onWrite(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) = onWrite(b, off, len)

        override fun flush() = onFlush()

        override fun close() = onClose()
    }

internal actual fun inputStreamOf(onRead: (ByteArray, Int, Int) -> Int): InputStream =
    object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            while (true) {
                val n = onRead(one, 0, 1)
                if (n < 0) return -1
                if (n == 1) return one[0].toInt() and 0xff
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = onRead(b, off, len)
    }

actual typealias IOException = java.io.IOException
actual typealias EOFException = java.io.EOFException
actual typealias UTFDataFormatException = java.io.UTFDataFormatException

internal actual class ZipWriter actual constructor(out: OutputStream) : AutoCloseable {
    private val zip = ZipOutputStream(out.buffered())

    actual fun put(name: String, bytes: () -> ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes())
        zip.closeEntry()
    }

    actual fun putFile(name: String, from: File) {
        zip.putNextEntry(ZipEntry(name))
        from.ioInputStream().use { it.ioCopyTo(zip) }
        zip.closeEntry()
    }

    actual override fun close() = zip.close()
}

internal actual class ZipReader actual constructor(input: InputStream) : AutoCloseable {
    private val zip = ZipInputStream(input.buffered())

    actual fun nextEntry(): ZipItem? = zip.nextEntry?.let { ZipItem(it.name, it.isDirectory) }

    actual fun readBytes(): ByteArray = zip.ioReadBytes()

    actual fun copyTo(file: File) {
        file.ioOutputStream().use { zip.ioCopyTo(it) }
    }

    actual fun read(b: ByteArray, off: Int, len: Int): Int = zip.read(b, off, len)

    actual fun closeEntry() = zip.closeEntry()

    actual override fun close() = zip.close()
}
