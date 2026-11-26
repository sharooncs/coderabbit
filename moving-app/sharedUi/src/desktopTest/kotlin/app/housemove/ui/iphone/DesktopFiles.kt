package app.housemove.ui.iphone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import app.housemove.pdf.AwtPaperWriter
import app.housemove.pdf.DrawnPapers
import app.housemove.ui.Papers
import app.housemove.ui.PickedFile
import app.housemove.ui.SaveFile
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The iPhone's files ([PhoneFiles]) on a plain JVM, for the desktop tests: the share sheet keeps what it is handed, the
 * document picker answers with [toOpen] or saves into [outDir], and the printouts are drawn with Java2D (AwtDraw.kt).
 */
internal class DesktopFiles(root: File) : PhoneFiles {
    override val outDir: File = File(root, "out").apply { mkdirs() }

    /** What was handed to the share sheet, with its MIME type, in order. */
    val shared = CopyOnWriteArrayList<Pair<File, String>>()

    /** The file the document picker picks next, or null for Cancel. */
    @Volatile
    var toOpen: File? = null

    /** Where the document picker saved, in order. */
    val saved = CopyOnWriteArrayList<File>()

    override fun share(file: File, mime: String) {
        shared += file to mime
    }

    @Composable
    override fun rememberOpenFile(onPicked: (PickedFile?) -> Unit): (List<String>) -> Unit {
        val latest by rememberUpdatedState(onPicked)
        return { _ -> latest(toOpen?.let { f -> PickedFile { f.inputStream() } }) }
    }

    @Composable
    override fun rememberSaveFile(mime: String, onPicked: (SaveFile?) -> Unit): (String) -> Unit {
        val latest by rememberUpdatedState(onPicked)
        return { name ->
            val f = File(outDir, "saved-$name").also { saved += it }
            latest(SaveFile { f.outputStream() })
        }
    }

    override val papers: Papers = DrawnPapers(AwtPaperWriter) { File(outDir, it) }

    override var incoming: IncomingFile? by mutableStateOf(null)
        private set

    override fun incomingTaken() {
        incoming = null
    }

    /** [file] opened with HouseMove from another app: a backup with [backup], an update file otherwise. */
    fun hand(file: File, backup: Boolean = false) {
        incoming = IncomingFile(PickedFile { file.inputStream() }, backup)
    }
}
