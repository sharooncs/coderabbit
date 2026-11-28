package app.housemove.data

import kotlin.concurrent.Volatile

/**
 * Time to finish a transfer once HouseMove has left the front (design/ios-plan.md, step 5). iOS suspends an app moments
 * after the person switches to another one or locks the phone, and a join, a sync, a mailbox request or a backup being
 * written would break half way. So each of them runs [during] a hold, and the iPhone asks iOS for time to finish while
 * one is held: it sets [holder] to [BackgroundTasks] over UIApplication's beginBackgroundTask and endBackgroundTask
 * (sharedUi iosMain). Android goes on running a transfer by itself; there [holder] stays as it is and does nothing.
 *
 * Nothing here changes what the phones send each other, or what is kept.
 */
object TransferTime {
    /** Lets go of a hold. Called once for each hold, however the transfer ended. */
    fun interface Hold {
        fun release()
    }

    /** Starts a hold named [what] ("sync by code", "mailbox"…), until its [Hold] is let go. */
    fun interface Holder {
        fun hold(what: String): Hold
    }

    /** No hold at all: Android, the JVM, and the iPhone until the app has set its own. */
    val NONE: Holder = Holder { Hold {} }

    @Volatile
    var holder: Holder = NONE

    // What is held, as [during] names it.
    const val JOIN = "join"
    const val SYNC_BY_CODE = "sync by code"
    const val SYNC_BY_ITSELF = "sync by itself"
    const val MAILBOX = "mailbox"
    const val BACKUP = "backup"
    const val UPDATE_FILE = "update file"

    /**
     * Runs [block] while [what] is held, and lets go of it however [block] ends: a result, an error or a return from
     * inside it. A holder that fails to hold never stops the transfer.
     */
    inline fun <T> during(what: String, block: () -> T): T {
        val hold = try {
            holder.hold(what)
        } catch (_: Exception) {
            null
        }
        try {
            return block()
        } finally {
            try {
                hold?.release()
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * A platform's background tasks as a [TransferTime.Holder]: on the iPhone, UIApplication's beginBackgroundTask ([begin],
 * which gives an ID and calls its `expired` when iOS's time is up) and endBackgroundTask ([end]). Each hold begins one
 * task and ends it exactly once: when the transfer ends, or sooner when iOS says the time is up (iOS ends an app whose
 * task is not ended then), whichever comes first, also when the time runs out before [begin] has given its ID. [none] is
 * the ID [begin] gives when iOS gives no time at all (UIBackgroundTaskInvalid): nothing is ended for it.
 */
class BackgroundTasks(
    private val begin: (name: String, expired: () -> Unit) -> Long,
    private val end: (id: Long) -> Unit,
    private val none: Long,
) : TransferTime.Holder {
    override fun hold(what: String): TransferTime.Hold = Held("HouseMove: $what")

    private inner class Held(name: String) : TransferTime.Hold {
        private val lock = Lock()

        /** The task's ID once [begin] has given it; [none] before, and once it has been ended. */
        private var id = none

        /** Let go of: by the transfer or by iOS's time running out. */
        private var done = false

        init {
            val got = begin(name) { release() }
            // The time may have run out already, before the ID was known here: then it is ended now.
            val endNow = synchronized(lock) {
                if (done) true else {
                    id = got
                    false
                }
            }
            if (endNow && got != none) end(got)
        }

        override fun release() {
            val ending = synchronized(lock) {
                if (done) none else {
                    done = true
                    id.also { id = none }
                }
            }
            if (ending != none) end(ending)
        }
    }
}
