@file:OptIn(ExperimentalForeignApi::class)

package app.housemove.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.uikit.LocalUIViewController
import app.housemove.data.File
import app.housemove.ui.delete
import kotlinx.cinterop.ExperimentalForeignApi
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerConfigurationAssetRepresentationModeCurrent
import platform.PhotosUI.PHPickerConfigurationSelectionOrdered
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.darwin.NSObject

/**
 * Choosing photos already on the iPhone, as Android's photo picker does: Apple's own picker (PHPickerViewController),
 * which runs outside the app and hands over only the photos chosen, so HouseMove needs no permission for the photo
 * library and never sees the rest of it. Up to ten, in the order chosen; each copied into a file from [newFile] as a
 * JPEG of at most 1600 pixels (Jpeg.kt) and given to [onPhoto]; then [onDone] is told how many were chosen. All on the
 * main thread.
 */
@Composable
internal fun rememberPhotoPicker(newFile: () -> File, onPhoto: (File) -> Unit, onDone: (Int) -> Unit): () -> Unit {
    val screen = LocalUIViewController.current
    val file by rememberUpdatedState(newFile)
    val photo by rememberUpdatedState(onPhoto)
    val done by rememberUpdatedState(onDone)
    val picking = remember { Picking() }
    return remember(screen, picking) { { picking.open(screen, { file() }, { photo(it) }, { done(it) }) } }
}

/** One picker at a time, and its delegate kept while it is up and its photos are copied (UIKit keeps only a weak one). */
private class Picking {
    private var running: PickerDelegate? = null

    fun open(from: UIViewController, newFile: () -> File, onPhoto: (File) -> Unit, onDone: (Int) -> Unit) {
        if (running != null) return
        val config = PHPickerConfiguration()
        config.selectionLimit = 10
        config.filter = PHPickerFilter.imagesFilter
        config.selection = PHPickerConfigurationSelectionOrdered
        // As the photo is kept (often HEIC): it is made a JPEG here anyway, so Photos need not convert it first.
        config.preferredAssetRepresentationMode = PHPickerConfigurationAssetRepresentationModeCurrent
        val picker = PHPickerViewController(configuration = config)
        val delegate = PickerDelegate(newFile, onPhoto) { chosen ->
            running = null
            onDone(chosen)
        }
        running = delegate
        picker.delegate = delegate
        // Over whatever is showing now.
        var top = from
        while (true) top = top.presentedViewController ?: break
        top.presentViewController(picker, animated = true, completion = null)
    }
}

private class PickerDelegate(
    private val newFile: () -> File,
    private val onPhoto: (File) -> Unit,
    private val finished: (Int) -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {
    /** Main thread: the photos chosen (none for Cancel). */
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        copy(didFinishPicking.filterIsInstance<PHPickerResult>(), 0)
    }

    /** Copies the [i]th photo chosen, then the next: one at a time, so a few large photos never fill the memory. */
    private fun copy(chosen: List<PHPickerResult>, i: Int) {
        if (i >= chosen.size) {
            finished(chosen.size)
            return
        }
        val file = newFile()
        chosen[i].itemProvider.loadFileRepresentationForTypeIdentifier("public.image") { url, _ ->
            // A copy of the photo that iOS takes away again when this returns: it is read now, on iOS's thread.
            val ok = url != null && saveJpeg(url, file)
            onMain {
                if (ok) onPhoto(file) else file.delete()
                copy(chosen, i + 1)
            }
        }
    }
}
