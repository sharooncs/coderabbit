@file:OptIn(ExperimentalForeignApi::class)

package app.housemove.data

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

/**
 * Where HouseMove keeps its files on the iPhone, as Android keeps them in filesDir, noBackupFilesDir and cacheDir.
 *
 * - [data]: Library/Application Support/HouseMove, the moves ([Moves]' filesDir). iCloud backs it up, except its
 *   photos folder, which is marked to be left out (as Android's backup leaves out files/photos).
 * - [noBackup]: Library/Application Support/HouseMove-no-backup ([Moves]' noBackupDir): the phone ID and each move's
 *   letter, counter and label numbers, which stay with the phone that made them, so a phone restored from a backup
 *   gets its own. Marked to be left out of iCloud backups.
 * - [caches]: Library/Caches/HouseMove, for files made only to be handed on (a backup to share), which the system
 *   may clear.
 *
 * Each is made the first time it is asked for, and the marks are set again every time the app starts (a folder made
 * again would lose its mark).
 */
object AppFolders {
    private fun library(kind: ULong): String =
        (NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).firstOrNull() as? String)
            ?: throw IOException("no folder of kind $kind")

    val data: File by lazy { File(File(library(NSApplicationSupportDirectory)), "HouseMove").also { it.mkdirs() } }

    val noBackup: File by lazy { File(File(library(NSApplicationSupportDirectory)), "HouseMove-no-backup").also { it.mkdirs() } }

    val caches: File by lazy { File(File(library(NSCachesDirectory)), "HouseMove").also { it.mkdirs() } }

    /** The folder of the moves' photos inside [data] (Moves keeps them in filesDir/photos), left out of iCloud backups. */
    val photos: File get() = File(data, "photos")

    /**
     * Makes the folders and marks [photos] and [noBackup] to be left out of iCloud backups. Called once when the app
     * starts, before [Moves] is made. Returns false when a mark could not be set.
     */
    fun prepare(): Boolean {
        data.mkdirs()
        photos.mkdirs()
        caches.mkdirs()
        return excludeFromBackup(photos) and excludeFromBackup(noBackup)
    }

    /** Marks [folder] (and so everything in it) to be left out of iCloud and computer backups. */
    fun excludeFromBackup(folder: File): Boolean {
        folder.mkdirs()
        val url = NSURL.fileURLWithPath(folder.path, isDirectory = true)
        return url.setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
    }

    /** Is [folder] marked to be left out of backups? */
    fun isExcludedFromBackup(folder: File): Boolean {
        val url = NSURL.fileURLWithPath(folder.path, isDirectory = true)
        val values = url.resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), error = null) ?: return false
        return (values[NSURLIsExcludedFromBackupKey] as? NSNumber)?.boolValue == true
    }

    /** The moves on this phone, in these folders. */
    fun moves(deviceName: () -> String = { "" }): Moves {
        prepare()
        return Moves(data, noBackup, deviceName)
    }
}
