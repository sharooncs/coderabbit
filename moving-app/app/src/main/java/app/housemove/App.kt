package app.housemove

import android.app.Application
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.housemove.data.Moves
import app.housemove.data.Shrink
import app.housemove.data.Store
import app.housemove.sync.AutoSync
import app.housemove.sync.MailboxSync
import app.housemove.ui.backupFolder
import app.housemove.ui.shrinkJpeg
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The app's one place for its moves. [moves] lists, opens, creates, archives, removes and deletes
 * them, joins and restores them, and gives the Store of the move in use. [store] is that Store for
 * the screens that work on one move; a phone with no move yet gets a new one, as 2.0's first run
 * did, until the "Your moves" screens are built.
 */
class App : Application() {
    val moves: Moves by lazy {
        Moves(filesDir, noBackupFilesDir, deviceName = { deviceName() }).also { m ->
            // The daily small backup goes to the folder picked for it, while the app may still write there.
            m.backupPlace = { backupFolder(this, m.phone.settings.value.backupFolder) }
        }
    }

    val store: Store get() = moves.currentOrNew()

    /**
     * Syncing by itself on the same Wi-Fi (design/auto-sync.md), for the move in use, while the setting is on. Started
     * and stopped with the app's screen (MainActivity): Android gives an app in the background no reliable time.
     */
    val autoSync: AutoSync by lazy {
        AutoSync(NsdDiscovery(this), { moves.current.value }, { moves.phone.settings.value.autoSync })
    }

    /**
     * Syncing through the move's internet mailbox (design/encryption.md, "Mailbox as built"), for the move in use, while the
     * switch is on and the move has one; beside syncing by itself, not instead. Started and stopped with the app's screen.
     */
    val mailbox: MailboxSync by lazy {
        MailboxSync({ moves.current.value }, { moves.phone.settings.value.mailbox })
    }

    /** An update file another app asked this one to open ("Open with HouseMove"), until the screens have opened it. */
    val incomingUpdate = MutableStateFlow<Uri?>(null)

    override fun onCreate() {
        super.onCreate()
        // Small copies of photos, for update files and the daily backup, are made the phone's own way.
        Shrink.jpeg = ::shrinkJpeg
    }

    /** The name the phone has in its own settings ("Priya's phone"), or its model when it has none. */
    private fun deviceName(): String {
        val set = try {
            Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
        } catch (_: Exception) {
            null
        }
        return set?.trim()?.takeIf { it.isNotEmpty() } ?: listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ").trim()
    }
}
