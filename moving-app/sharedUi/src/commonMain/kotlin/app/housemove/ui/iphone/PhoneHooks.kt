package app.housemove.ui.iphone

import app.housemove.data.File
import app.housemove.sync.Discovery

/**
 * What the iPhone's own screens and its [IphonePlatform] ask of the phone they run on. The iPhone's side is in iosMain
 * (IosHooks); the desktop tests give their own. Nothing here changes the move: it is changed by the shared code, as on
 * Android (joining and syncing, [app.housemove.data.Moves] and [app.housemove.sync.WifiSync]; the shared screens, by the
 * Store's rules for this iPhone's person).
 */
interface PhoneHooks {
    /** Where the moves are kept ([app.housemove.data.Moves]' filesDir), and what stays out of backups (noBackupDir). */
    val dataDir: File
    val noBackupDir: File

    /** The name the phone has, as other phones are told it when it joins ("iPhone"). */
    fun deviceName(): String = ""

    /** Opens HouseMove's page in the phone's Settings, where Local Network is switched on and off. */
    fun openSettings() {}

    /**
     * May HouseMove use the local network? On the iPhone this asks iOS, which shows its prompt the first time.
     * [done] is called once, on any thread.
     */
    fun checkLocalNetwork(done: (LocalNetwork) -> Unit) = done(LocalNetwork.UNKNOWN)

    /**
     * Keep the screen from going to sleep while another phone may connect, a sync runs or the camera is open ([on]), or
     * let it sleep again. Each `true` is matched by a `false`: the screen sleeps again once every one has let go.
     */
    fun keepAwake(on: Boolean) {}

    /** The camera, the scanner and choosing photos (step 3a, [PhoneCamera]): null where the phone has none of them. */
    val camera: PhoneCamera? get() = null

    /** The text the person copied last, if any. The iPhone asks first ("Allow Paste"). */
    fun paste(): String? = null

    /** Deletes [folder] and everything in it. Used to take a move off this phone when [app.housemove.data.Moves.remove] may not. */
    fun deleteFolder(folder: File) {}

    /**
     * How this phone finds the other phones of its move on the Wi-Fi, to sync by itself (design/auto-sync.md): Bonjour on
     * the iPhone, an in-process stand-in in demo mode and tests. Null: this phone does not sync by itself.
     */
    fun discovery(): Discovery? = null

    /** Syncing by itself listens on 127.0.0.1 alone (demo mode and tests), not on the Wi-Fi. */
    val loopbackOnly: Boolean get() = false

    /**
     * Does this phone keep up to date through the move's internet mailbox when it has one (design/encryption.md, "Mailbox as
     * built")? The iPhone does; demo mode and the desktop tests do not.
     */
    val mailbox: Boolean get() = false

    /** A set of words the shared screens keep on this phone under [key] (which answers its person has read). */
    fun readSet(key: String): Set<String> = emptySet()

    fun writeSet(key: String, values: Set<String>) {}

    /** The country the phone is set to ("GB"), for the paper size; empty when not known. */
    fun country(): String = ""
}

/** What iOS says about HouseMove and the local network. */
enum class LocalNetwork {
    /** HouseMove may reach other phones on the Wi-Fi. */
    ALLOWED,

    /** Switched off for HouseMove ("Don't Allow", or later in Settings). */
    DENIED,

    /** iOS did not say in time, or this phone does not ask. */
    UNKNOWN,
}
