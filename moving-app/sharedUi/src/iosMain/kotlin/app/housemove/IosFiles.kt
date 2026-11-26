@file:OptIn(ExperimentalForeignApi::class)

package app.housemove

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import app.housemove.data.AppFolders
import app.housemove.data.File
import app.housemove.data.IOException
import app.housemove.data.InputStream
import app.housemove.data.OutputStream
import app.housemove.pdf.DrawnPapers
import app.housemove.pdf.IosPaperWriter
import app.housemove.ui.Papers
import app.housemove.ui.PickedFile
import app.housemove.ui.SaveFile
import app.housemove.ui.iphone.IncomingFile
import app.housemove.ui.iphone.PhoneFiles
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileCoordinatorWritingForReplacing
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeData
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.UniformTypeIdentifiers.UTTypeZIP
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.ferror
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fwrite

/*
 * Files on the iPhone (step 3b of design/ios-plan.md), all of it UIKit and Foundation called from Kotlin:
 *
 * - the share sheet (UIActivityViewController: AirDrop, Messages, Mail, Save to Files, Print), for every file the
 *   screens hand on: backups, update files and the PDFs;
 * - the system's document picker (UIDocumentPickerViewController), to open a file (an update file, a backup to merge) and
 *   to save a backup in Files;
 * - the printouts, drawn with the layout Android's are (pdf/IosDraw.kt).
 *
 * Everything is kept in Library/Caches/HouseMove: out/ (files made to hand on), in/ (files opened, read from there for as
 * long as the screens need them) and save/ (a backup on its way into Files). The three are emptied when the app starts.
 */
internal object IosFiles : PhoneFiles {
    override val outDir: File = File(AppFolders.caches, "out")
    private val inDir = File(AppFolders.caches, "in")
    private val saveDir = File(AppFolders.caches, "save")

    private val fm: NSFileManager get() = NSFileManager.defaultManager

    private fun makeDir(dir: File) {
        fm.createDirectoryAtPath(dir.path, withIntermediateDirectories = true, attributes = null, error = null)
    }

    /** Empties out/, in/ and save/ of what the app's last run left. Called once at start, before any file link is taken in. */
    fun clean() {
        for (dir in listOf(outDir, inDir, saveDir)) {
            fm.removeItemAtPath(dir.path, error = null)
            makeDir(dir)
        }
    }

    override val papers: Papers = DrawnPapers(IosPaperWriter) { name ->
        makeDir(outDir)
        File(outDir, name)
    }

    // ---- the share sheet ---------------------------------------------------------------------------------------

    override fun share(file: File, mime: String) = onMain {
        // A file link, so the sheet offers what suits the file: AirDrop, Save to Files, Print for a PDF, the apps.
        val sheet = UIActivityViewController(activityItems = listOf(NSURL.fileURLWithPath(file.path)), applicationActivities = null)
        present(sheet)
    }

    // ---- the document picker: opening a file ----------------------------------------------------------------

    @Composable
    override fun rememberOpenFile(onPicked: (PickedFile?) -> Unit): (mimes: List<String>) -> Unit {
        val latest by rememberUpdatedState(onPicked)
        return remember { { mimes: List<String> -> pickToOpen(mimes) { latest(it) } } }
    }

    /** The kinds of file the picker offers, from the screens' MIME types; the wildcard offers every file, as on Android. */
    private fun contentTypes(mimes: List<String>): List<UTType> {
        val types = ArrayList<UTType>()
        for (mime in mimes) {
            val type = when (mime) {
                "*/*" -> UTTypeItem
                "application/octet-stream" -> UTTypeData
                "application/zip" -> UTTypeZIP
                // HouseMove's update files: the type Info.plist declares (app.housemove.update).
                else -> UTType.typeWithMIMEType(mime)
            }
            if (type != null && types.none { it.isEqual(type) }) types += type
        }
        if (types.isEmpty()) types += UTTypeItem
        return types
    }

    private fun pickToOpen(mimes: List<String>, done: (PickedFile?) -> Unit) = onMain {
        // A copy: the system fetches the file (from iCloud Drive or another app's storage) and hands HouseMove its own.
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = contentTypes(mimes), asCopy = true)
        picker.allowsMultipleSelection = false
        picker.delegate = Picker(onPicked = { urls -> done(urls.firstOrNull()?.let { keep(it) }) }, onCancelled = { done(null) })
        present(picker)
    }

    /**
     * The file [url] points to, copied into in/ so it can be read for as long as the screens need it (an update made the
     * older way is read again once the person says to open it). The system's own copy is then removed when it is in this
     * app's folders. Read within its security scope, should it have one. Null when it cannot be read.
     */
    private fun keep(url: NSURL): PickedFile? {
        val scoped = url.startAccessingSecurityScopedResource()
        try {
            val from = url.path ?: return null
            makeDir(inDir)
            val folder = File(inDir, NSUUID().UUIDString)
            makeDir(folder)
            val to = File(folder, (url.lastPathComponent ?: "file").replace('/', '-'))
            if (!fm.copyItemAtPath(from, toPath = to.path, error = null)) return null
            if (from.startsWith(NSHomeDirectory() + "/") || from.startsWith("/private" + NSHomeDirectory() + "/")) fm.removeItemAtPath(from, error = null)
            return PathFile(to.path)
        } finally {
            if (scoped) url.stopAccessingSecurityScopedResource()
        }
    }

    // ---- the document picker: saving a file in Files -----------------------------------------------------------

    @Composable
    override fun rememberSaveFile(mime: String, onPicked: (SaveFile?) -> Unit): (name: String) -> Unit {
        val latest by rememberUpdatedState(onPicked)
        return remember { { name: String -> pickToSave(name) { latest(it) } } }
    }

    /**
     * Saving a file named [name] in Files, where the person chooses, as Android's "create a document" does: the picker
     * puts an empty file of that name there, and [done] is given the way to write it (or null if they cancelled). What
     * the screen writes goes to save/ first and then, in one go, to the place chosen ([SavingOutput]).
     */
    private fun pickToSave(name: String, done: (SaveFile?) -> Unit) = onMain {
        val folder = File(saveDir, NSUUID().UUIDString)
        makeDir(folder)
        val placeholder = File(folder, name.replace('/', '-'))
        if (!fm.createFileAtPath(placeholder.path, contents = null, attributes = null)) {
            done(null)
            return@onMain
        }
        val picker = UIDocumentPickerViewController(forExportingURLs = listOf(NSURL.fileURLWithPath(placeholder.path)), asCopy = true)
        picker.delegate = Picker(
            onPicked = { urls ->
                val place = urls.firstOrNull()
                done(place?.let { SaveFile { SavingOutput(it, File(folder, "writing.part")) } })
            },
            onCancelled = { done(null) },
        )
        present(picker)
    }

    // ---- files other apps hand to HouseMove: not yet ---------------------------------------------------------

    override val incoming: IncomingFile? get() = null

    override fun incomingTaken() {}

    // ---- presenting on the screen --------------------------------------------------------------------------

    /** The view controller at the front: the key window's root, and what it has presented, to the last one. */
    private fun front(): UIViewController? {
        val scenes = UIApplication.sharedApplication.connectedScenes.mapNotNull { it as? UIWindowScene }
            .sortedBy { if (it.activationState == UISceneActivationStateForegroundActive) 0 else 1 }
        for (scene in scenes) {
            val window = scene.keyWindow ?: scene.windows.firstOrNull { it is UIWindow } as? UIWindow ?: continue
            var top = window.rootViewController ?: continue
            while (true) {
                val next = top.presentedViewController ?: break
                if (next.isBeingDismissed()) break
                top = next
            }
            return top
        }
        return null
    }

    /**
     * Shows [controller] over the screen. The iPhone shows a sheet; a popover (on an iPad) needs a place to point at, so
     * it is given the bottom middle of the screen, which is never wrong.
     */
    private fun present(controller: UIViewController) {
        val top = front() ?: return
        controller.popoverPresentationController?.let { pop ->
            val view = top.view
            pop.sourceView = view
            pop.sourceRect = view.bounds.useContents { CGRectMake(origin.x + size.width / 2.0, origin.y + size.height - 1.0, 1.0, 1.0) }
            pop.permittedArrowDirections = 0uL
        }
        top.presentViewController(controller, animated = true, completion = null)
    }

    private fun onMain(block: () -> Unit) {
        if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue(), block)
    }
}

/** The document picker holds its delegate weakly: each [Picker] is held here until the picker answers. */
private val showing = HashSet<Any>()

/** The document picker's answer: the files picked, or cancelled. Kept alive while the picker is on screen. */
private class Picker(private val onPicked: (List<NSURL>) -> Unit, private val onCancelled: () -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    init {
        showing += this
    }

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        showing -= this
        onPicked(didPickDocumentsAtURLs.mapNotNull { it as? NSURL })
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        showing -= this
        onCancelled()
    }
}

/** A file kept in HouseMove's own folders, read as often as needed. */
private class PathFile(private val path: String) : PickedFile {
    override fun open(): InputStream? = PosixInput.open(path)
}

/** A file read from its start with POSIX stdio. */
private class PosixInput(private var f: CPointer<FILE>?) : InputStream() {
    override fun readBytes(b: ByteArray, off: Int, len: Int): Int {
        val file = f ?: throw IOException("closed")
        if (len == 0) return 0
        val n = b.usePinned { fread(it.addressOf(off), 1u, len.toULong(), file) }.toInt()
        if (n > 0) return n
        if (ferror(file) != 0) throw IOException("could not read")
        return -1
    }

    override fun close() {
        f?.let { fclose(it) }
        f = null
    }

    companion object {
        fun open(path: String): PosixInput? = fopen(path, "rb")?.let { PosixInput(it) }
    }
}

/**
 * Writing a backup to the place in Files the person chose ([place], from the document picker): written to [part] first,
 * then, when it is closed, put in place in one go, within the place's security scope and through a file coordinator, so
 * iCloud Drive and other apps' storage see a whole file. Throws on close when it could not be put there.
 */
private class SavingOutput(private val place: NSURL, private val part: File) : OutputStream() {
    private var f: CPointer<FILE>? = fopen(part.path, "wb") ?: throw IOException("could not write ${part.path}")

    override fun writeBytes(b: ByteArray, off: Int, len: Int) {
        val file = f ?: throw IOException("closed")
        if (len == 0) return
        val written = b.usePinned { fwrite(it.addressOf(off), 1u, len.toULong(), file) }
        if (written.toLong() != len.toLong()) throw IOException("could not write ${part.path}")
    }

    override fun close() {
        val file = f ?: return
        f = null
        val flushed = fclose(file) == 0
        val placed = flushed && putInPlace()
        NSFileManager.defaultManager.removeItemAtPath(part.path, error = null)
        if (!placed) throw IOException("It could not be saved in the place chosen. Choose another, or use Share backup.")
    }

    private fun putInPlace(): Boolean {
        val scoped = place.startAccessingSecurityScopedResource()
        try {
            var done = false
            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                NSFileCoordinator(filePresenter = null).coordinateWritingItemAtURL(place, options = NSFileCoordinatorWritingForReplacing, error = error.ptr) { url ->
                    val target = url ?: place
                    val fm = NSFileManager.defaultManager
                    fm.removeItemAtURL(target, error = null)
                    done = fm.copyItemAtURL(NSURL.fileURLWithPath(part.path), toURL = target, error = null)
                }
            }
            return done
        } finally {
            if (scoped) place.stopAccessingSecurityScopedResource()
        }
    }
}
