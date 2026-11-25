package app.housemove.data

/*
 * Files, folders, byte streams and zips, as the shared code uses them (step 1 of design/ios-plan.md).
 *
 * On Android and the JVM, File, InputStream, OutputStream, IOException and EOFException are java.io's own classes
 * (typealiases), and every function here is the java.io, kotlin.io or java.util.zip call the code made before it was
 * shared (Files.jvm.kt): Android runs what it ran. On the iPhone (Files.ios.kt) files and folders are POSIX calls with
 * the same behaviour, and zips are the common writer and reader in Zip.kt, which write what java.util.zip writes and
 * read what it reads.
 */

/** A file or folder on this phone: java.io.File on Android and the JVM. A null [parent] makes [child] a path of its own. */
expect class File(parent: File?, child: String)

internal expect val File.name: String
internal expect val File.isFile: Boolean
internal expect val File.isDirectory: Boolean
internal expect val File.parentFile: File?
internal expect val File.absoluteFile: File
internal expect fun File.exists(): Boolean
internal expect fun File.delete(): Boolean
internal expect fun File.mkdirs(): Boolean
internal expect fun File.renameTo(dest: File): Boolean
internal expect fun File.length(): Long

/** When the file was last changed, in milliseconds since 1970; 0 when there is nothing there (java.io.File.lastModified). */
internal expect fun File.lastModified(): Long
internal expect fun File.listFiles(): Array<File>?

/** The whole file as UTF-8 text (kotlin.io's readText). */
internal expect fun File.readText(): String

/** The whole file written as UTF-8 text (kotlin.io's writeText). */
internal expect fun File.writeText(text: String)
internal expect fun File.readBytes(): ByteArray
internal expect fun File.copyTo(target: File, overwrite: Boolean = false): File
internal expect fun File.deleteRecursively(): Boolean
internal expect fun File.inputStream(): InputStream
internal expect fun File.outputStream(): OutputStream

/** Bytes read in order: java.io.InputStream on Android and the JVM. */
expect abstract class InputStream : AutoCloseable {
    override fun close()
}

/** Bytes written in order: java.io.OutputStream on Android and the JVM. */
expect abstract class OutputStream : AutoCloseable {
    override fun close()
}

internal expect fun OutputStream.write(b: ByteArray, off: Int, len: Int)

/** Up to [len] bytes into [b] from [off]: how many were read (at least one when [len] is not 0), or -1 at the end. */
internal expect fun InputStream.read(b: ByteArray, off: Int, len: Int): Int

/** java.io.IOException on Android and the JVM. */
expect open class IOException : Exception {
    constructor()
    constructor(message: String?)
    constructor(message: String?, cause: Throwable?)
    constructor(cause: Throwable?)
}

/** java.io.EOFException on Android and the JVM: the bytes ended before what was being read. */
expect open class EOFException : IOException {
    constructor()
    constructor(message: String?)
}

/** java.io.UTFDataFormatException on Android and the JVM: text that modified UTF-8 cannot hold, or bytes that are not it. */
expect open class UTFDataFormatException : IOException {
    constructor()
    constructor(message: String?)
}

/** One entry of a zip being read ([ZipReader]). */
internal class ZipItem(val name: String, val isDirectory: Boolean)

/**
 * Writes a zip entry by entry: on Android and the JVM a java.util.zip.ZipOutputStream over [out], buffered, which
 * writes each entry with a data descriptor after it (see golden/README.md, backup.json).
 */
internal expect class ZipWriter(out: OutputStream) : AutoCloseable {
    /** An entry holding the bytes [bytes] makes, which it makes once the entry has begun. */
    fun put(name: String, bytes: () -> ByteArray)

    /** An entry holding the bytes of the file [from]. */
    fun putFile(name: String, from: File)

    override fun close()
}

/** Reads a zip entry by entry: on Android and the JVM a java.util.zip.ZipInputStream over [input], buffered. */
internal expect class ZipReader(input: InputStream) : AutoCloseable {
    /** The next entry, or null after the last. */
    fun nextEntry(): ZipItem?

    /** The rest of the current entry. */
    fun readBytes(): ByteArray

    /** The rest of the current entry, written to [file]. */
    fun copyTo(file: File)

    fun closeEntry()

    override fun close()
}
