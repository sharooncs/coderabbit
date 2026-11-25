package app.housemove.sync

import app.housemove.data.Lock
import app.housemove.data.currentTimeMillis
import app.housemove.data.synchronized
import kotlin.concurrent.Volatile

/**
 * Code syncs and invite joins that have just happened (design/auto-sync.md, "Phones that joined before 2.3"): the two
 * phones both saw the code that was shown on one of them and scanned by the other, and each knows the other's phone
 * ID from that sync. For [MINUTES] minutes that code is a key the two can prove themselves with to start syncing by
 * themselves ([AutoSession]), once, without a Wi-Fi key. Kept in memory only, by this phone's ID (tests run several
 * phones in one process), and never written anywhere.
 */
internal object Meetings {
    const val MINUTES = 10

    /** One meeting, as phone [me] saw it: in the move known by [moves], with phone [peer], by [code]. */
    class Meeting(val me: String, val moves: List<String>, val peer: String, val code: String, val at: Long)

    private val lock = Lock()
    private val list = ArrayList<Meeting>()

    /** Counts up at every new meeting, so that the engine can sync at once to swap keys while it lasts. */
    @Volatile
    var version = 0
        private set

    /** The clock meetings are timed by. Tests set their own. */
    @Volatile
    var clock: () -> Long = ::currentTimeMillis

    /** Phone [me], in the move known by [moves], has just synced with phone [peer] by [code]. */
    fun note(me: String, moves: List<String>, peer: String, code: String) {
        if (me.isEmpty() || peer.isEmpty() || code.isEmpty() || peer == me) return
        val now = clock()
        synchronized(lock) {
            list.removeAll { it.at + MINUTES * 60_000L < now || (it.me == me && it.peer == peer) }
            list += Meeting(me, moves.filter { it.isNotEmpty() }, peer, code, now)
            // At most 16 of one phone's, the oldest let go first, and a thousand in all (tests run many phones in one process).
            val mine = list.filter { it.me == me }
            if (mine.size > 16) list.remove(mine.first())
            while (list.size > 1000) list.removeAt(0)
            version++
        }
    }

    /** Phone [me]'s meetings in the move known by any of [moves] that still count. */
    fun recent(me: String, moves: List<String>): List<Meeting> {
        val now = clock()
        return synchronized(lock) { list.filter { it.me == me && it.at + MINUTES * 60_000L >= now && it.moves.any { m -> m in moves } } }
    }

    /** The meeting of [me] with [peer] has been used: it counts no more. */
    fun used(me: String, peer: String) {
        synchronized(lock) { list.removeAll { it.me == me && it.peer == peer } }
    }

    /** Forget every meeting (tests). */
    fun clear() {
        synchronized(lock) { list.clear() }
    }

    /** Forget the meetings of the phones [mine] says are its own (a test that plays the same phones again). */
    fun forget(mine: (String) -> Boolean) {
        synchronized(lock) { list.removeAll { mine(it.me) } }
    }
}
