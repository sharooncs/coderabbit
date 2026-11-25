@file:OptIn(ExperimentalForeignApi::class)

package app.housemove.data

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.O_CLOEXEC
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.PATH_MAX
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.closedir
import platform.posix.getcwd
import platform.posix.lstat
import platform.posix.mkdir
import platform.posix.open
import platform.posix.opendir
import platform.posix.read
import platform.posix.readdir
import platform.posix.remove
import platform.posix.rename
import platform.posix.stat
import platform.posix.write

// The iPhone's files, folders and streams (step 1b of design/ios-plan.md): POSIX calls on paths, with the behaviour of
// the java.io.File and kotlin.io functions the shared code was written against (Files.jvm.kt is those functions
// themselves). The app's folders are in Folders.ios.kt; the zip format is the common one in Zip.kt.
//
// Privacy manifest: stat and lstat (NSPrivacyAccessedAPICategoryFileTimestamp) are called on the app's own files,
// for whether a file is there, its kind, its size and its time (reason C617.1).

/**
 * A file or folder: a path, as java.io.File is. [path] is kept as java.io.File keeps it on a Unix system: no doubled
 * slashes and no slash at the end. Two Files are equal when their paths are.
 */
actual class File private constructor(val path: String, @Suppress("UNUSED_PARAMETER") normalized: Unit) {
    actual constructor(parent: File?, child: String) : this(join(parent, child), Unit)

    /** The file or folder at [path], as java.io.File(path). */
    constructor(path: String) : this(normalize(path), Unit)

    override fun equals(other: Any?): Boolean = other is File && other.path == path

    override fun hashCode(): Int = path.hashCode() xor 1234321

    override fun toString(): String = path

    internal companion object {
        /** As java.io.UnixFileSystem.normalize: doubled slashes become one, and a slash at the end goes (except for "/"). */
        fun normalize(p: String): String {
            if (p.isEmpty()) return p
            val out = StringBuilder(p.length)
            var prev = 0.toChar()
            for (c in p) {
                if (c == '/' && prev == '/') continue
                out.append(c)
                prev = c
            }
            if (out.length > 1 && out[out.length - 1] == '/') out.setLength(out.length - 1)
            return out.toString()
        }

        /** As java.io.File(parent, child) on a Unix system. */
        fun join(parent: File?, child: String): String {
            val c = normalize(child)
            if (parent == null) return c
            val p = if (parent.path.isEmpty()) "/" else parent.path
            if (c.isEmpty()) return p
            if (c.startsWith('/')) return if (p == "/") c else p + c
            return if (p == "/") p + c else "$p/$c"
        }
    }
}

/** The file's kind (S_IFDIR, S_IFREG...), or null when there is nothing at [path]. [follow] as stat does, else as lstat. */
private fun kindOf(path: String, follow: Boolean = true): Int? = memScoped {
    val st = alloc<stat>()
    val r = if (follow) stat(path, st.ptr) else lstat(path, st.ptr)
    if (r != 0) null else st.st_mode.toInt() and S_IFMT
}

internal actual val File.name: String get() = path.substring(path.lastIndexOf('/') + 1)
internal actual val File.isFile: Boolean get() = kindOf(path) == S_IFREG
internal actual val File.isDirectory: Boolean get() = kindOf(path) == S_IFDIR

/** As java.io.File.getParentFile: the path before the last slash; null for a name on its own and for "/". */
internal actual val File.parentFile: File?
    get() {
        val i = path.lastIndexOf('/')
        val prefix = if (path.startsWith('/')) 1 else 0
        if (i < prefix) return if (prefix > 0 && path.length > prefix) File(path.substring(0, prefix)) else null
        return File(path.substring(0, i))
    }

internal actual val File.absoluteFile: File
    get() {
        if (path.startsWith('/')) return this
        val cwd = memScoped {
            val buf = allocArray<ByteVar>(PATH_MAX + 1)
            getcwd(buf, (PATH_MAX + 1).convert())?.toKString() ?: "/"
        }
        return File(File(cwd), path)
    }

internal actual fun File.exists(): Boolean = kindOf(path) != null

/** As java.io.File.delete: a file, or a folder that is empty. */
internal actual fun File.delete(): Boolean = remove(path) == 0

/** As java.io.File.mkdirs: true only when the folder was made, along with any folders above it that were missing. */
internal actual fun File.mkdirs(): Boolean {
    if (exists()) return false
    if (mkdir(path, 0x1FF.convert()) == 0) return true
    val parent = absoluteFile.parentFile ?: return false
    return (parent.mkdirs() || parent.exists()) && mkdir(path, 0x1FF.convert()) == 0
}

/** As java.io.File.renameTo on a Unix system (rename(2)): a file already at [dest] is replaced. */
internal actual fun File.renameTo(dest: File): Boolean = rename(path, dest.path) == 0

internal actual fun File.length(): Long = memScoped {
    val st = alloc<stat>()
    if (stat(path, st.ptr) != 0) 0L else st.st_size
}

/** As java.io.File.lastModified: milliseconds since 1970, or 0 when there is nothing there. */
internal actual fun File.lastModified(): Long = memScoped {
    val st = alloc<stat>()
    if (stat(path, st.ptr) != 0) 0L else st.st_mtimespec.tv_sec * 1000L + st.st_mtimespec.tv_nsec / 1_000_000L
}

/** As java.io.File.listFiles: what the folder holds, in the order the system lists it; null when this is not a folder. */
internal actual fun File.listFiles(): Array<File>? {
    val dir = opendir(path) ?: return null
    val names = ArrayList<String>()
    try {
        while (true) {
            val entry = readdir(dir) ?: break
            val name = entry.pointed.d_name.toKString()
            if (name != "." && name != "..") names += name
        }
    } finally {
        closedir(dir)
    }
    return Array(names.size) { File(this, names[it]) }
}

internal actual fun File.readText(): String = JavaText.decodeUtf8(readBytes())

internal actual fun File.writeText(text: String) {
    val bytes = JavaText.utf8(text)
    outputStream().use { it.writeBytes(bytes, 0, bytes.size) }
}

internal actual fun File.readBytes(): ByteArray = inputStream().use { it.readAll() }

/** As kotlin.io's File.copyTo: a file already at [target] is deleted first, when [overwrite] allows it. */
internal actual fun File.copyTo(target: File, overwrite: Boolean): File {
    if (!exists()) throw NoSuchFileException("$path: The source file doesn't exist.")
    if (target.exists()) {
        if (!overwrite) throw FileAlreadyExistsException("$path -> ${target.path}: The destination file already exists.")
        if (!target.delete()) throw FileAlreadyExistsException("$path -> ${target.path}: Tried to overwrite the destination, but failed to delete it.")
    }
    if (isDirectory) {
        if (!target.mkdirs()) throw IOException("$path -> ${target.path}: Failed to create target directory.")
    } else {
        target.parentFile?.mkdirs()
        inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
    }
    return target
}

/**
 * As kotlin.io's File.deleteRecursively: everything inside first, then the folder; true when all of it is gone. A
 * symbolic link is deleted, not followed.
 */
internal actual fun File.deleteRecursively(): Boolean {
    var all = true
    if (kindOf(path, follow = false) == S_IFDIR) {
        listFiles()?.forEach { if (!it.deleteRecursively()) all = false }
    }
    return (delete() || kindOf(path, follow = false) == null) && all
}

internal actual fun File.inputStream(): InputStream = FileInput(this)

internal actual fun File.outputStream(): OutputStream = FileOutput(this)

/** Bytes read in order. Each kind of stream (a file, a socket, bytes in memory) says how in [readBytes]. */
actual abstract class InputStream : AutoCloseable {
    /** Up to [len] bytes into [b] from [off]: how many were read (at least one when [len] is not 0), or -1 at the end. */
    abstract fun readBytes(b: ByteArray, off: Int, len: Int): Int

    /** Everything left, to the end. */
    fun readAll(): ByteArray {
        var buf = ByteArray(8192)
        var n = 0
        while (true) {
            if (n == buf.size) buf = buf.copyOf(buf.size * 2)
            val r = readBytes(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return buf.copyOf(n)
    }

    /** Everything left, written to [out]; how many bytes that was (kotlin.io's InputStream.copyTo). */
    fun copyTo(out: OutputStream): Long {
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val r = readBytes(buf, 0, buf.size)
            if (r < 0) break
            out.writeBytes(buf, 0, r)
            total += r
        }
        return total
    }

    actual override fun close() {}
}

/** Bytes written in order. Each kind of stream (a file, a socket, bytes in memory) says how in [writeBytes]. */
actual abstract class OutputStream : AutoCloseable {
    /** Writes all [len] bytes of [b] from [off]. */
    abstract fun writeBytes(b: ByteArray, off: Int, len: Int)

    open fun flush() {}

    actual override fun close() {}
}

internal actual fun OutputStream.write(b: ByteArray, off: Int, len: Int) = writeBytes(b, off, len)

internal actual fun InputStream.read(b: ByteArray, off: Int, len: Int): Int = if (len == 0) 0 else readBytes(b, off, len)

/** A file read from its start (java.io.FileInputStream). Throws FileNotFoundException when it cannot be opened. */
internal class FileInput(file: File) : InputStream() {
    private val fd = Fd(open(file.path, O_RDONLY or O_CLOEXEC), file.path)

    override fun readBytes(b: ByteArray, off: Int, len: Int): Int {
        checkRange(b, off, len)
        if (len == 0) return 0
        val n = b.usePinned { p -> retryOnInterrupt(-1L) { read(fd.open(), p.addressOf(off), len.convert()) } }
        if (n < 0) throw IOException(errnoText())
        return if (n == 0L) -1 else n.toInt()
    }

    override fun close() = fd.close()
}

/** A file written from its start, emptied first when it was there (java.io.FileOutputStream). */
internal class FileOutput(file: File) : OutputStream() {
    private val fd = Fd(open(file.path, O_WRONLY or O_CREAT or O_TRUNC or O_CLOEXEC, 0x1B6), file.path)

    override fun writeBytes(b: ByteArray, off: Int, len: Int) {
        checkRange(b, off, len)
        var done = 0
        while (done < len) {
            val n = b.usePinned { p -> retryOnInterrupt(-1L) { write(fd.open(), p.addressOf(off + done), (len - done).convert()) } }
            if (n < 0) throw IOException(errnoText())
            done += n.toInt()
        }
    }

    override fun close() = fd.close()
}

/** An open file descriptor, closed once. */
private class Fd(private val fd: Int, path: String) {
    private var closed = false

    init {
        if (fd < 0) throw FileNotFoundException("$path (${errnoText()})")
    }

    fun open(): Int {
        if (closed) throw IOException("Stream Closed")
        return fd
    }

    fun close() {
        if (closed) return
        closed = true
        platform.posix.close(fd)
    }
}

internal fun checkRange(b: ByteArray, off: Int, len: Int) {
    if (off < 0 || len < 0 || len > b.size - off) throw IndexOutOfBoundsException("off $off, len $len, size ${b.size}")
}

/** An input read through a buffer of [size] bytes, as java.io.BufferedInputStream: each read asks [input] for at most what fills it. */
internal class BufferedInput(private val input: InputStream, size: Int = 8192) : InputStream() {
    private val buf = ByteArray(size)
    private var pos = 0
    private var count = 0

    override fun readBytes(b: ByteArray, off: Int, len: Int): Int {
        checkRange(b, off, len)
        if (len == 0) return 0
        if (pos == count) {
            // A read as large as the buffer goes straight through, as BufferedInputStream does.
            if (len >= buf.size) return input.readBytes(b, off, len)
            val n = input.readBytes(buf, 0, buf.size)
            if (n < 0) return -1
            pos = 0
            count = n
        }
        val n = minOf(len, count - pos)
        buf.copyInto(b, off, pos, pos + n)
        pos += n
        return n
    }

    override fun close() = input.close()
}

/** An output written through a buffer of [size] bytes, as java.io.BufferedOutputStream: nothing goes until it is full or flushed. */
internal class BufferedOutput(private val out: OutputStream, size: Int = 8192) : OutputStream() {
    private val buf = ByteArray(size)
    private var count = 0

    private fun flushBuffer() {
        if (count > 0) {
            out.writeBytes(buf, 0, count)
            count = 0
        }
    }

    override fun writeBytes(b: ByteArray, off: Int, len: Int) {
        checkRange(b, off, len)
        if (len >= buf.size) {
            flushBuffer()
            out.writeBytes(b, off, len)
            return
        }
        if (len > buf.size - count) flushBuffer()
        b.copyInto(buf, count, off, off + len)
        count += len
    }

    override fun flush() {
        flushBuffer()
        out.flush()
    }

    /** As FilterOutputStream.close: what is in the buffer is written, then [out] is closed. */
    override fun close() {
        try {
            flush()
        } finally {
            out.close()
        }
    }
}

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

/** java.io.FileNotFoundException: a file that could not be opened. */
open class FileNotFoundException(message: String? = null) : IOException(message)

/** kotlin.io.NoSuchFileException: a file to be copied that is not there. */
open class NoSuchFileException(message: String? = null) : IOException(message)

/** kotlin.io.FileAlreadyExistsException: a file in the way of a copy. */
open class FileAlreadyExistsException(message: String? = null) : IOException(message)

/** Writes a zip entry by entry (the common writer in Zip.kt, through a buffer as the JVM's is). */
internal actual class ZipWriter actual constructor(out: OutputStream) : AutoCloseable {
    private val zip = ZipOut(BufferedOutput(out)) { dosTimeNow() }

    actual fun put(name: String, bytes: () -> ByteArray) {
        zip.putNextEntry(name)
        val b = bytes()
        zip.write(b, 0, b.size)
        zip.closeEntry()
    }

    actual fun putFile(name: String, from: File) {
        zip.putNextEntry(name)
        from.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.readBytes(buf, 0, buf.size)
                if (n < 0) break
                zip.write(buf, 0, n)
            }
        }
        zip.closeEntry()
    }

    actual override fun close() = zip.close()
}

/** Reads a zip entry by entry (the common reader in Zip.kt, through a buffer as the JVM's is). */
internal actual class ZipReader actual constructor(input: InputStream) : AutoCloseable {
    private val zip = ZipIn(BufferedInput(input))

    actual fun nextEntry(): ZipItem? = zip.nextEntry()

    actual fun readBytes(): ByteArray {
        var buf = ByteArray(8192)
        var n = 0
        while (true) {
            if (n == buf.size) buf = buf.copyOf(buf.size * 2)
            val r = zip.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return buf.copyOf(n)
    }

    actual fun copyTo(file: File) {
        file.outputStream().use { out ->
            val buf = ByteArray(8192)
            while (true) {
                val n = zip.read(buf, 0, buf.size)
                if (n < 0) break
                out.writeBytes(buf, 0, n)
            }
        }
    }

    actual fun closeEntry() = zip.closeEntry()

    actual override fun close() = zip.close()
}
