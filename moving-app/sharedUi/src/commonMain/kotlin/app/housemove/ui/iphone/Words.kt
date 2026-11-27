package app.housemove.ui.iphone

import app.housemove.data.Role
import app.housemove.data.SYNC_PREFIX
import app.housemove.sync.WifiSync
import app.housemove.ui.PhoneWords
import app.housemove.ui.screens.ANDROID_OLDER
import app.housemove.ui.screens.VERSIONS_ON_IPHONE
import app.housemove.ui.screens.versionText
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/*
 * Everything the first iPhone build says, in short plain sentences. What goes wrong when phones meet comes from
 * WifiSync as one of its messages; here each gets a heading and, where the iPhone has more to say (the local network,
 * where things are on the Android phone), the iPhone's own words.
 */

/** Something to tell the person: a heading, a few sentences, and whether a button to Settings helps. */
data class Report(
    val ok: Boolean,
    val title: String,
    val text: String,
    /** The fix is in Settings (the local network is switched off for HouseMove): offer "Open Settings". */
    val settings: Boolean = false,
)

object Words {
    const val NOT_AN_INVITE =
        "That is not a HouseMove invite. On the Android phone, open People, tap the person this iPhone is for, and show their invite."

    const val SYNC_CODE_NOT_INVITE =
        "That is a sync code, which only works once this iPhone is in the move. To join, use an invite: on the Android phone, open People, tap the person this iPhone is for, and show their invite."

    const val NOTHING_TO_PASTE = "There is nothing to paste. Copy the invite first, then tap Paste."

    /** The join screen's first words: a move is on an Android phone, and only its Organiser can let someone in. */
    const val ASK_TO_BE_ADDED =
        "A move is kept on an Android phone. Ask the move's Organiser to add you under People there, and to show you your invite."

    const val LOCAL_NETWORK_ASK =
        "Your iPhone may ask if HouseMove can find devices on your local network. Tap Allow: that is how it reaches the Android phone."


    /** Where the switch is, said the way the person will see it. */
    const val WHERE_LOCAL_NETWORK = "Tap Open Settings and switch on Local Network. (It is also in Settings, Privacy & Security, Local Network.)"

    val DENIED = Report(
        false, "HouseMove may not use your Wi-Fi",
        "Your iPhone is set to keep HouseMove off the local network, so it cannot reach the Android phone. $WHERE_LOCAL_NETWORK Then come back and try again.",
        settings = true,
    )

    val NO_WIFI = Report(
        false, "This iPhone is not on Wi-Fi",
        "Connect this iPhone to the same Wi-Fi as the Android phone, then try again.",
    )

    /** Could not reach the other phone, and iOS did not say the local network is switched off. */
    fun unreachable(couldBeDenied: Boolean) = Report(
        false, "Cannot reach the Android phone",
        "Check that both phones are on the same Wi-Fi, and that the Android phone still shows its code. Some guest networks keep phones apart: then try the Android phone's hotspot." +
            if (couldBeDenied) " If you tapped Don't Allow when your iPhone asked about the local network: $WHERE_LOCAL_NETWORK" else "",
        settings = couldBeDenied,
    )

    /**
     * What a join or a sync that did not work tells the person, from all [r] says: when the phones have different
     * versions of HouseMove, which phone to update and how ([versionText]: an Android phone as Android phones are, this
     * iPhone or another iPhone from TestFlight).
     */
    fun problem(r: WifiSync.Result): Report =
        if (r.error == WifiSync.VERSION_MISMATCH) Report(false, "Different versions", versionText(r, null, PhoneWords.IPHONE))
        else joinProblem(r.error, r.shareApp)

    /** What a join that did not work tells the person. [error] is WifiSync's message. */
    fun joinProblem(error: String?, shareApp: Boolean = false): Report = when (error) {
        WifiSync.INVITE_EXPIRED -> Report(
            false, "This invite no longer works",
            "An invite works once, and for ${WifiSync.INVITE_MINUTES} minutes. On the Android phone, tap Make a new invite, then use the new one.",
        )
        WifiSync.CODE_NOT_ACCEPTED -> Report(
            false, "The code was not accepted",
            "The Android phone did not take this code. Show the invite again on the Android phone and use the new one.",
        )
        WifiSync.UNDONE -> Report(
            false, "The Android phone said no",
            "Someone tapped Undo on the Android phone, so nothing was sent. The invite still works: try again, and tap Right there.",
        )
        WifiSync.NOT_CONFIRMED -> Report(
            false, "Nobody tapped Right in time",
            "The Android phone asked \"Joined: …, on iPhone\", and nobody answered within a minute and a half. The invite still works: try again, and tap Right on the Android phone.",
        )
        WifiSync.CONNECTION_LOST -> Report(
            false, "The connection dropped",
            "The phones stopped hearing each other before joining finished: the Wi-Fi dropped, or one phone took too long to answer. Keep both phones awake and close together, then try again.",
        )
        WifiSync.NO_NETWORK -> NO_WIFI
        WifiSync.UNREACHABLE -> unreachable(couldBeDenied = true)
        WifiSync.VERSION_MISMATCH -> Report(false, "Different versions", if (shareApp) ANDROID_OLDER else VERSIONS_ON_IPHONE)
        WifiSync.NOT_ADDED, WifiSync.UNKNOWN_PHONE -> Report(
            false, "Not added to this move",
            "This iPhone is not in that move yet. On the Android phone, open People, tap the person this iPhone is for, and show their invite.",
        )
        WifiSync.WRONG_MOVE -> Report(false, "A different move", error)
        WifiSync.REMOVED_SELF -> Report(false, "Removed from this move", error)
        "That person is no longer in the move." -> Report(false, "Removed from this move", "That person has been taken out of the move on the Android phone.")
        WifiSync.RETIRED_PHONE, WifiSync.RETIRED_GONE -> Report(false, "This iPhone was retired", error)
        WifiSync.UNREADABLE -> Report(false, "Could not read the Android phone", error)
        else -> Report(false, "Could not join", error ?: "Something went wrong. Try again.")
    }

    /**
     * After a sync or a join that went through without encryption because the other phone has an older HouseMove: a
     * sentence saying so, to follow what is said. Empty when it was encrypted, as it is with a phone that has 2.4.
     */
    fun plainNote(r: WifiSync.Result): String =
        if (r.plain && (r.ok || r.error == WifiSync.PHOTOS_CUT)) " " + WifiSync.notEncrypted(r.peerName) else ""

    /** What a sync to get fresh data tells the person. */
    fun syncResult(r: WifiSync.Result): Report = when {
        r.ok || r.error == WifiSync.PHOTOS_CUT -> Report(
            true, "Up to date",
            "Synced with ${r.peerName.ifBlank { "the Android phone" }}. What changed there is on this iPhone now." + plainNote(r),
        )
        r.error == WifiSync.CODE_NOT_ACCEPTED -> Report(
            false, "The code was not accepted",
            "Show the sync code again on the Android phone and use the new one.",
        )
        else -> problem(r).let { if (it.title == "Could not join") it.copy(title = "Could not sync") else it }
    }

    /** What each role may do, as the Android app says it. */
    fun roleLine(role: Role): String = when (role) {
        Role.ORGANISER -> "Runs the move: decides, approves, adds people."
        Role.DECIDER -> "Suggests what to keep, sell or buy. An Organiser approves."
        Role.HELPER -> "Photographs, packs, scans on and off the van, unpacks."
    }

    /** "3 items", kept on one line (a no-break space), so large text never leaves a number at the end of a line. */
    fun count(n: Int, one: String, many: String = one + "s") = "$n\u00A0${if (n == 1) one else many}"

    /** "just now", "5 min ago", "2 h ago", "3 days ago". */
    @OptIn(ExperimentalTime::class)
    fun ago(millis: Long, now: Long = Clock.System.now().toEpochMilliseconds()): String {
        val mins = ((now - millis) / 60_000).coerceAtLeast(0)
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 60 * 24 -> "${mins / 60} h ago"
            else -> count((mins / (60 * 24)).toInt(), "day") + " ago"
        }
    }

    @OptIn(ExperimentalTime::class)
    fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

    /**
     * An invite or sync code as the person gave it: pasted, typed, or opened as a link from the Camera. Spaces and
     * line breaks are dropped (a code copied across lines), and the start is put in capitals ("hmsync:" from a link).
     */
    fun cleanCode(text: String): String {
        val t = text.filterNot { it.isWhitespace() }
        return if (t.startsWith(SYNC_PREFIX, ignoreCase = true)) SYNC_PREFIX + t.substring(SYNC_PREFIX.length) else t
    }
}
