package app.housemove.ui

import app.housemove.data.File
import app.housemove.data.OutputStream

/*
 * The few things the screens do with a file themselves (the shared module's own file calls are its own): where it is,
 * deleting it, and writing it. On Android and the desktop JVM these are java.io's (jvmCommonMain); on the iPhone,
 * POSIX (iosMain).
 */

/** The file's path, as java.io.File.getPath gives it. */
expect val File.path: String

/** Deletes the file; false when it could not. */
expect fun File.delete(): Boolean

/** A stream writing the file from its start (java.io.FileOutputStream). */
expect fun File.outputStream(): OutputStream
