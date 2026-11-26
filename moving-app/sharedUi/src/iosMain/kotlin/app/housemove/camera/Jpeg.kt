@file:OptIn(ExperimentalForeignApi::class)

package app.housemove.camera

import app.housemove.data.AppFolders
import app.housemove.data.File
import app.housemove.toData
import app.housemove.ui.iphone.PhoneCamera
import app.housemove.ui.iphone.ScanBeep
import cnames.structs.CGImageSource
import cnames.structs.__CFData
import cnames.structs.__CFURL
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.AudioToolbox.AudioServicesCreateSystemSoundID
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGImageRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.writeToFile
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceCreateWithURL
import platform.ImageIO.CGImageSourceGetCount
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation

/*
 * Photos saved as Android saves them, and the scanner's beep.
 */

/**
 * A photo as the camera or Photos gives it (JPEG or HEIC, of any size, turned as the phone was held), saved into [file]
 * as Android saves one: a JPEG at most 1600 pixels on its long side ([PhoneCamera.PHOTO_LONG_SIDE]), quality 82,
 * upright. ImageIO decodes only as much of the picture as that size needs, so a 48-megapixel photo is never held whole
 * in memory. Nothing else of the original goes with it (no place, no time, no camera details). False when it could not
 * be read or written. Any thread.
 */
internal fun saveJpeg(photo: NSData, file: File): Boolean {
    val data = CFBridgingRetain(photo)?.reinterpret<__CFData>() ?: return false
    try {
        val source = CGImageSourceCreateWithData(data, null) ?: return false
        try {
            return writeScaled(source, file)
        } finally {
            CFRelease(source)
        }
    } finally {
        CFRelease(data)
    }
}

/** The same, for a photo in the file at [photo] (Photos hands a chosen photo over as a file). */
internal fun saveJpeg(photo: NSURL, file: File): Boolean {
    val url = CFBridgingRetain(photo)?.reinterpret<__CFURL>() ?: return false
    try {
        val source = CGImageSourceCreateWithURL(url, null) ?: return false
        try {
            return writeScaled(source, file)
        } finally {
            CFRelease(source)
        }
    } finally {
        CFRelease(url)
    }
}

private fun writeScaled(source: CPointer<CGImageSource>, file: File): Boolean {
    if (CGImageSourceGetCount(source) == 0uL) return false
    val options = CFDictionaryCreateMutable(null, 3, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr) ?: return false
    val image = try {
        // From the picture itself (not the small preview a photo may carry), turned upright, no larger than the long side.
        CFDictionarySetValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
        CFDictionarySetValue(options, kCGImageSourceCreateThumbnailWithTransform, kCFBooleanTrue)
        val side = memScoped {
            val n = alloc<IntVar>()
            n.value = PhoneCamera.PHOTO_LONG_SIDE
            CFNumberCreate(null, kCFNumberIntType, n.ptr)
        } ?: return false
        CFDictionarySetValue(options, kCGImageSourceThumbnailMaxPixelSize, side)
        CFRelease(side)
        CGImageSourceCreateThumbnailAtIndex(source, 0uL, options)
    } finally {
        CFRelease(options)
    } ?: return false
    try {
        val jpeg = UIImageJPEGRepresentation(UIImage.imageWithCGImage(image), PhoneCamera.PHOTO_QUALITY) ?: return false
        return jpeg.writeToFile(file.path, atomically = true)
    } finally {
        CGImageRelease(image)
    }
}

/**
 * The beep of a code read ([ScanBeep], Android's tone), as a system sound: it follows the ring switch and the volume,
 * and plays alongside whatever else is playing without stopping it. Made once, from a WAV file in the caches folder.
 * Main thread.
 */
internal object Beep {
    /** The sound once made; 0u when it could not be. */
    private var sound: UInt? = null

    fun play() {
        val id = sound ?: make().also { sound = it }
        if (id != 0u) AudioServicesPlaySystemSound(id)
    }

    private fun make(): UInt {
        val path = AppFolders.caches.path + "/scan-beep.wav"
        if (!ScanBeep.wav().toData().writeToFile(path, atomically = true)) return 0u
        val url = CFBridgingRetain(NSURL.fileURLWithPath(path))?.reinterpret<__CFURL>() ?: return 0u
        try {
            return memScoped {
                val id = alloc<UIntVar>()
                if (AudioServicesCreateSystemSoundID(url, id.ptr) == 0) id.value else 0u
            }
        } finally {
            CFRelease(url)
        }
    }
}
