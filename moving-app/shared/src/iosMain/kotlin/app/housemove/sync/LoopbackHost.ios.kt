package app.housemove.sync

import app.housemove.data.Store
import app.housemove.data.currentTimeMillis
import app.housemove.data.secureRandom

/**
 * A hosting phone that only this iPhone itself can reach: it listens on 127.0.0.1 alone, never on the Wi-Fi. The
 * iPhone app's demo mode uses it (started only by the launch argument `-HMDemoHost YES`, which the UI tests pass),
 * so the app can join a move inside itself, through the same [WifiSync] code and real sockets a real join uses.
 *
 * It takes no new phones by the plain code and asks nobody to confirm a join: only an invite made with
 * [WifiSync.Host.inviteFor] lets a phone in. iPhone only (iosMain): Android never has it.
 */
fun WifiSync.loopbackHost(store: Store, onResult: (WifiSync.Result) -> Unit): WifiSync.Host {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val r = secureRandom()
    val token = (1..8).map { chars[r.nextInt(chars.length)] }.joinToString("")
    val host = WifiSync.Host(
        store, ServerSocket(0, loopbackOnly = true), token, ::currentTimeMillis,
        letNewPhonesIn = false, confirmJoins = false, onResult = onResult,
    )
    host.start()
    return host
}
