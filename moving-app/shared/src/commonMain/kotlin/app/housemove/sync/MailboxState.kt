package app.housemove.sync

import app.housemove.data.AppJson
import app.housemove.data.File
import app.housemove.data.Lock
import app.housemove.data.MoveIdentity
import app.housemove.data.isFile
import app.housemove.data.readText
import app.housemove.data.synchronized
import app.housemove.data.writeWhole
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * What it takes to register a mailbox on the move's mailbox program (design/encryption.md, "Mailbox as built"): its [url]
 * and the house [token] the program made at its first start, from the mailbox's code ([MailboxCode]) or from another
 * phone of the move, inside an encrypted sync or a sealed message, never in the move's data. [at]: when it was scanned.
 */
@Serializable
internal data class MailboxAccess(val url: String = "", val token: String = "", val at: Long = 0)

/** A photo's small copy sent to a phone by mailbox: in the message [seq], at [at], not yet acknowledged. */
@Serializable
internal data class PhotoSent(val seq: Long = 0, val at: Long = 0)

/**
 * This phone's mailbox dealings with one other phone of the move: the number of the last message sent to it ([out]), the
 * data's stamp then ([sentStamp]) and when ([sentAt]); the newest of its messages it has acknowledged ([got]); the newest
 * of that phone's messages opened here ([heard], [heardAt]) and acknowledged to it ([acked]), and whether an
 * acknowledgement is owed ([owes]); the photos sent and not yet acknowledged ([pending]), and those it has ([delivered]).
 */
@Serializable
internal data class PeerBox(
    val out: Long = 0,
    val sentStamp: Long = 0,
    val sentAt: Long = 0,
    val got: Long = 0,
    val heard: Long = 0,
    val heardAt: Long = 0,
    val acked: Long = 0,
    val owes: Boolean = false,
    val pending: Map<String, PhotoSent> = emptyMap(),
    val delivered: List<String> = emptyList(),
)

@Serializable
private data class MailboxSaved(
    val move: String = "",
    val access: MailboxAccess? = null,
    /** The mailbox this phone registered: on [url], [id], read with [secret] (which never leaves this phone but to [url]). */
    val url: String = "",
    val id: String = "",
    val secret: String = "",
    val registeredAt: Long = 0,
    val peers: Map<String, PeerBox> = emptyMap(),
)

/**
 * One move's mailbox on this phone (`mailbox.json` in the move's no-backup folder, beside the pins; never in a backup, an
 * update file or a sync): the house token, this phone's own mailbox and the secret it reads it with, and what it has sent
 * to and heard from each other phone. Saved for another move, it counts as nothing.
 */
internal class MailboxState(private val file: File?, private val identity: () -> MoveIdentity) {
    private val lock = Lock()
    private var saved: MailboxSaved = read()

    private fun read(): MailboxSaved = try {
        file?.takeIf { it.isFile }?.let { AppJson.decodeFromString<MailboxSaved>(it.readText()) } ?: MailboxSaved()
    } catch (_: Exception) {
        MailboxSaved()
    }

    private fun current(): MailboxSaved = if (saved.move.isNotEmpty() && identity().matches(saved.move)) saved else MailboxSaved()

    private fun write(next: MailboxSaved) {
        val stamped = next.copy(move = identity().id)
        if (stamped == saved) return
        saved = stamped
        try {
            file?.let { writeWhole(it, AppJson.encodeToString(stamped)) }
        } catch (_: Exception) {
            // Kept in memory; written again at the next change.
        }
    }

    /** The house token this phone has for the mailbox at [url], or null. */
    fun access(url: String): MailboxAccess? = synchronized(lock) { current().access?.takeIf { url.isNotEmpty() && sameUrl(it.url, url) && it.token.isNotEmpty() } }

    /** Any access this phone has, for passing on. */
    fun anyAccess(): MailboxAccess? = synchronized(lock) { current().access }

    /**
     * Takes [a] (scanned here, or from another phone): kept when it is well formed and is for [moveUrl], the mailbox the move
     * has now (any, when the move has none yet: the code was just scanned), and newer than what this phone has for it.
     * Returns whether it was taken.
     */
    fun offer(a: MailboxAccess, moveUrl: String): Boolean = synchronized(lock) {
        if (!MailboxCode.isToken(a.token) || MailboxCode.cleanUrl(a.url) == null) return@synchronized false
        if (moveUrl.isNotEmpty() && !sameUrl(a.url, moveUrl)) return@synchronized false
        val c = current()
        val was = c.access
        if (was != null && sameUrl(was.url, a.url) && (was.token == a.token || was.at >= a.at)) return@synchronized false
        write(c.copy(access = a.copy(url = MailboxCode.cleanUrl(a.url)!!)))
        true
    }

    /** This phone's registered mailbox: (url, id, secret), or null. */
    fun registration(): Triple<String, String, String>? = synchronized(lock) {
        current().takeIf { it.id.isNotEmpty() && it.secret.isNotEmpty() }?.let { Triple(it.url, it.id, it.secret) }
    }

    /** This phone's mailbox is [id] on [url], read with [secret]; what was sent and heard on another mailbox counts no more. */
    fun registered(url: String, id: String, secret: String, at: Long) = synchronized(lock) {
        val c = current()
        write(c.copy(url = url, id = id, secret = secret, registeredAt = at, peers = if (sameUrl(c.url, url)) c.peers else emptyMap()))
    }

    /** This phone has stopped using its mailbox (the switch is off, or it was deleted): it registers a new one next time. */
    fun forgetRegistration() = synchronized(lock) {
        val c = current()
        write(c.copy(url = "", id = "", secret = "", registeredAt = 0, peers = emptyMap()))
    }

    fun peer(phoneId: String): PeerBox = synchronized(lock) { current().peers[phoneId] ?: PeerBox() }

    fun peers(): Map<String, PeerBox> = synchronized(lock) { current().peers }

    fun updatePeer(phoneId: String, f: (PeerBox) -> PeerBox) = synchronized(lock) {
        val c = current()
        val was = c.peers[phoneId] ?: PeerBox()
        val next = f(was)
        if (next != was) write(c.copy(peers = c.peers + (phoneId to next)))
    }

    /** Forgets every phone but [keep] (phones removed or retired, or gone from the move). */
    fun keepOnly(keep: Set<String>) = synchronized(lock) {
        val c = current()
        if (c.peers.keys.all { it in keep }) return@synchronized
        write(c.copy(peers = c.peers.filterKeys { it in keep }))
    }

    private fun sameUrl(a: String, b: String): Boolean = MailboxCode.cleanUrl(a) == MailboxCode.cleanUrl(b)
}
