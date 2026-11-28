package app.housemove

import app.housemove.data.BackgroundTasks
import app.housemove.data.TransferTime
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid

/**
 * Time to finish a transfer once HouseMove leaves the front (design/ios-plan.md, step 5): each join, sync (by code or by
 * itself), mailbox request, backup or update file the shared code runs during a [TransferTime] hold is a background task
 * of iOS's own (UIApplication's beginBackgroundTask and endBackgroundTask), so switching to another app or locking the
 * phone does not cut it off half way. iOS gives about half a minute; when that runs out first, the task is ended then
 * ([BackgroundTasks] ends each task exactly once). Apple's documentation says both calls may be made from any thread,
 * as the sync threads do; the application object itself is taken once, here, on the main thread.
 */
internal object TransferTasks {
    /** Called once, on the main thread, before anything can sync. */
    fun install() {
        if (TransferTime.holder !== TransferTime.NONE) return
        val app = UIApplication.sharedApplication
        TransferTime.holder = BackgroundTasks(
            begin = { name, expired -> app.beginBackgroundTaskWithName(name, expirationHandler = expired).toLong() },
            end = { id -> app.endBackgroundTask(id.toULong()) },
            none = UIBackgroundTaskInvalid.toLong(),
        )
    }
}
