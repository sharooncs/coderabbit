package app.housemove.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Update files: working apart (design 2.1, section 4). Any phone of a move can send one through any messaging
 * app; it holds what this phone holds of the move, as an Organiser or a Decider may see it (a Helper's phone
 * holds only a Helper's share, so that is all a Helper's update carries), with small copies of the photos that no
 * Organiser's or Decider's phone has had from it yet. It is sealed for every Organiser's and Decider's phone this
 * phone knows the key of ([Store.updateRecipients], [Sealing]); nobody else can read it.
 *
 * Opening one checks which move it is for before anything else, then whether it is for this phone, then merges it
 * with exactly the rules of a sync: the same check of who sent it ([admission]), the same merge ([Store.mergeIn]),
 * a retired phone's work taken in once, and nothing a Helper is not sent kept on a Helper's phone. Opening the
 * same file twice changes nothing the second time. An update from someone who has been removed is refused, unless
 * an Organiser chooses to take in their work alone ([workOf]), with changes they had no right to refused as usual.
 *
 * The file: "HMUPDATE", the format (an int), the header's length and the header ([Header], JSON: the move, the
 * phone it is from, and the content key wrapped for each phone it is for), then the sealed contents ([SealedOut]):
 * a zip of data.json and photos/ as a backup has them.
 */
object Update {
    /** The ending of an update file's name. */
    const val EXTENSION = "housemove"

    /** The type an update file is shared as: any app takes it, and it opens in this one. */
    const val MIME = "application/octet-stream"

    internal const val FORMAT = 1
    private val MAGIC = "HMUPDATE".toByteArray()
    private const val MAX_HEADER = 1 shl 20
    private const val MAX_JSON = 64 * 1024 * 1024
    private const val MAX_PHOTO = 40L * 1024 * 1024

    const val NOT_AN_UPDATE = "That file is not a HouseMove update, or it is damaged. Ask for it to be sent again."
    const val TOO_NEW = "This update was made by a newer version of HouseMove. Update the app, then open it again."
    const val OTHER_VERSION = "This update comes from a phone with a different version of HouseMove. Install the same version on both, then send it again."
    const val OTHER_MOVE = "This update is for another move, not this one."
    const val NOT_ON_PHONE = "This update is for a move that is not on this phone."
    const val NOT_FOR_THIS_PHONE = "This update was sent to the move's other phones. Once this phone has synced with the sender, their next update comes here too."
    const val FROM_THIS_PHONE = "This update was made on this phone, so there is nothing in it that this phone does not have."
    const val DAMAGED = "That update file is damaged, so nothing was changed. Ask for it to be sent again."
    const val STRANGER = "That update is from a phone that has not been added to this move, so nothing was changed."
    const val RETIRED_GONE = "That update is from a retired phone whose work was already taken in, so nothing was changed."
    const val NO_RECIPIENTS = "No Organiser's or Decider's phone can open an update from this phone yet. Sync with one of them once, then try again."
    const val NOTHING_NEW = "This phone already had everything in that update."
    const val COULD_NOT_WRITE = "The update could not be written. Check there is space on the phone, then try again."

    /** Said on an Organiser's or a Decider's phone when an update comes from someone removed from the move. */
    fun fromRemoved(name: String) = "${name.ifBlank { "Its sender" }} has been removed from this move, so the update was not taken in."

    @Serializable
    internal data class To(val phone: String = "", val epk: String = "", val salt: String = "", val iv: String = "", val key: String = "")

    @Serializable
    internal data class Header(
        val format: Int = FORMAT,
        /** The data-format version of the phone that made it ([SCHEMA]): only the same one is taken in, as for a sync. */
        val schema: Int = 0,
        val moveId: String = "",
        val aliases: List<String> = emptyList(),
        val derived: Boolean = false,
        /** The phone ID of the phone that made it. */
        val from: String = "",
        val at: Long = 0,
        /** The first part of every chunk's nonce. */
        val nonce: String = "",
        val to: List<To> = emptyList(),
    ) {
        val identity: MoveIdentity get() = MoveIdentity(moveId, aliases, derived)
    }

    /** What an update says about itself before it is opened. */
    data class Peek(val moveId: String, val aliases: List<String>, val from: String, val at: Long, val to: List<String>) {
        val identity: MoveIdentity get() = MoveIdentity(moveId, aliases)
    }

    /** An update that was made: for how many phones, with how many photos. */
    data class Made(val phones: Int, val photos: Int)

    /**
     * What came of opening an update, in words for the screen ([message]). [fromRemoved] is the person it is from
     * when they have been removed from the move: an Organiser may open it again to take in their work alone.
     */
    data class Opened(
        val ok: Boolean,
        val message: String,
        val fromName: String = "",
        val changes: Int = 0,
        val photos: Int = 0,
        val fromRemoved: String? = null,
    )

    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    // ---- making one ---------------------------------------------------------------------

    /**
     * Write an update from [store] to [out]: sealed for every Organiser's and Decider's phone it knows the key of, or
     * with [organisersOnly] only for Organisers' (a phone taken out of the move sends its last work to an Organiser).
     * Throws an IOException with words for the screen when there is nobody to send it to, or it cannot be written.
     */
    fun write(store: Store, out: OutputStream, organisersOnly: Boolean = store.removedFromMove()): Made {
        val to = store.updateRecipients(organisersOnly).mapNotNull { (_, ph) -> PhoneKeys.publicFrom(ph.key)?.let { ph.id to it } }.distinctBy { it.first }
        if (to.isEmpty()) throw IOException(NO_RECIPIENTS)
        store.flush()
        val data = store.data.value
        val id = store.identity.value
        val contentKey = Sealing.randomBytes(32)
        val prefix = Sealing.randomBytes(8)
        val header = Header(
            schema = SCHEMA, moveId = id.id, aliases = id.aliases, derived = id.derived, from = store.phone.id,
            at = System.currentTimeMillis(), nonce = b64e.encodeToString(prefix),
            to = to.map { (phoneId, key) ->
                val w = Sealing.wrap(contentKey, key, phoneId, id.id)
                To(phoneId, b64e.encodeToString(w.ephemeral), b64e.encodeToString(w.salt), b64e.encodeToString(w.iv), b64e.encodeToString(w.sealed))
            },
        )
        val headerBytes = AppJson.encodeToString(header).toByteArray()
        var photos = 0
        try {
            val dout = DataOutputStream(out.buffered())
            dout.write(MAGIC)
            dout.writeInt(FORMAT)
            dout.writeInt(headerBytes.size)
            dout.write(headerBytes)
            val sealed = SealedOut(dout, contentKey, prefix, Sealing.sha256(headerBytes))
            val zip = ZipOutputStream(sealed)
            zip.putNextEntry(ZipEntry("data.json"))
            zip.write(AppJson.encodeToString(data).toByteArray())
            zip.closeEntry()
            for (p in photosFor(store)) {
                val bytes = Shrink.copyOf(File(store.photoDir, p.file)) ?: continue
                zip.putNextEntry(ZipEntry("photos/" + p.file))
                zip.write(bytes)
                zip.closeEntry()
                photos++
            }
            zip.finish()
            sealed.close()
            dout.flush()
        } catch (e: IOException) {
            throw IOException(COULD_NOT_WRITE, e)
        }
        return Made(to.size, photos)
    }

    /**
     * The photos an update carries small copies of: those no Organiser's or Decider's phone has had from this one yet
     * (changed since its last full sync with one of them), whose files this phone has.
     */
    internal fun photosFor(store: Store): List<Photo> {
        val since = store.device.value.sharedAllUpTo
        return store.data.value.livePhotos.filter { maxOf(it.updatedAt, it.createdAt) > since && isSafeFileName(it.file) && File(store.photoDir, it.file).isFile }
    }

    // ---- reading one --------------------------------------------------------------------

    private fun readHeader(din: DataInputStream): Pair<Header, ByteArray> {
        val magic = ByteArray(MAGIC.size)
        try {
            din.readFully(magic)
            if (!magic.contentEquals(MAGIC)) throw IllegalArgumentException(NOT_AN_UPDATE)
            val format = din.readInt()
            if (format > FORMAT) throw IllegalArgumentException(TOO_NEW)
            if (format < 1) throw IllegalArgumentException(NOT_AN_UPDATE)
            val n = din.readInt()
            if (n <= 0 || n > MAX_HEADER) throw IllegalArgumentException(NOT_AN_UPDATE)
            val bytes = ByteArray(n).also { din.readFully(it) }
            val header = AppJson.decodeFromString<Header>(bytes.decodeToString())
            if (header.moveId.isEmpty()) throw IllegalArgumentException(NOT_AN_UPDATE)
            return header to bytes
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(e.message?.takeIf { it == TOO_NEW } ?: NOT_AN_UPDATE, e)
        } catch (e: Exception) {
            throw IllegalArgumentException(NOT_AN_UPDATE, e)
        }
    }

    /** Read what an update says about itself: which move it is for, and which phones. An IllegalArgumentException in words when it is not one. */
    fun peek(input: InputStream): Peek {
        val (h, _) = readHeader(DataInputStream(input.buffered()))
        return Peek(h.moveId, h.aliases, h.from, h.at, h.to.map { it.phone })
    }

    /**
     * Open an update into [store]: the move first, then whether it is for this phone, then the contents, merged with
     * the rules of a sync. With [takeInRemoved], an update from someone removed from the move is taken in as their
     * work alone, when this phone's person may restore a backup (an Organiser). Nothing is changed when it is refused,
     * and no photo file is left behind.
     */
    fun open(store: Store, input: InputStream, takeInRemoved: Boolean = false): Opened {
        val din = DataInputStream(input.buffered())
        val (header, headerBytes) = try {
            readHeader(din)
        } catch (e: IllegalArgumentException) {
            return Opened(false, e.message ?: NOT_AN_UPDATE)
        }
        // Which move, before anything.
        if (!store.identity.value.matches(header.identity)) return Opened(false, OTHER_MOVE)
        if (header.schema != SCHEMA) return Opened(false, OTHER_VERSION)
        val mine = header.to.firstOrNull { it.phone == store.phone.id }
            ?: return Opened(false, if (header.from == store.phone.id) FROM_THIS_PHONE else NOT_FOR_THIS_PHONE)
        val key = try {
            Sealing.unwrap(Sealing.Wrapped(b64d.decode(mine.epk), b64d.decode(mine.salt), b64d.decode(mine.iv), b64d.decode(mine.key)), store.phone.keys, store.phone.id, header.moveId)
        } catch (_: Exception) {
            null
        } ?: return Opened(false, DAMAGED)
        val prefix = try {
            b64d.decode(header.nonce).takeIf { it.size == 8 }
        } catch (_: Exception) {
            null
        } ?: return Opened(false, DAMAGED)

        // The contents: the data, and the photo files put aside under a name the app clears at start-up until all of it has opened.
        val staged = LinkedHashMap<String, File>()
        try {
            var json: String? = null
            try {
                val sealed = SealedIn(din, key, prefix, Sealing.sha256(headerBytes))
                val zip = ZipInputStream(sealed)
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name
                    when {
                        name == "data.json" -> {
                            val bytes = zip.readBytes()
                            if (bytes.size > MAX_JSON) throw IOException("too large")
                            json = bytes.decodeToString()
                        }
                        name.startsWith("photos/") && !e.isDirectory -> {
                            val file = name.removePrefix("photos/")
                            if (isSafeFileName(file) && file !in staged) {
                                val tmp = File(store.photoDir, "$file.import")
                                staged[file] = tmp
                                tmp.outputStream().use { out ->
                                    val buf = ByteArray(16 * 1024)
                                    var total = 0L
                                    while (true) {
                                        val n = zip.read(buf)
                                        if (n < 0) break
                                        total += n
                                        if (total > MAX_PHOTO) throw IOException("photo too large")
                                        out.write(buf, 0, n)
                                    }
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                }
                // Every chunk is checked, to the very end, before anything is taken in.
                sealed.drain()
            } catch (_: Exception) {
                return Opened(false, DAMAGED)
            }
            val remote = try {
                json?.let { AppJson.decodeFromString<AppData>(it) }
            } catch (_: Exception) {
                null
            } ?: return Opened(false, DAMAGED)
            if (remote.version > SCHEMA) return Opened(false, TOO_NEW)
            return takeIn(store, header, remote, staged, takeInRemoved)
        } finally {
            staged.values.forEach { if (it.exists()) it.delete() }
        }
    }

    private fun takeIn(store: Store, h: Header, remote: AppData, staged: Map<String, File>, takeInRemoved: Boolean): Opened {
        val before = store.data.value
        val device = store.device.value
        // Who sent it: the person whose phone it came from, as their own data has them.
        val sender = remote.phoneEntry(h.from)?.first ?: before.phoneEntry(h.from)?.first
        val theirs = sender?.let { remote.person(it.id) } ?: sender
        val name = theirs?.name.orEmpty()
        // A retired phone: its work is taken in once, and never again.
        val retired = before.phoneEntry(h.from)?.second?.takeIf { it.isRetired }
        if (retired?.collected == true) return Opened(false, RETIRED_GONE, name)
        var workAlone = false
        if (retired == null) {
            when (before.admission(device, theirs)) {
                Admission.MEMBER -> Unit
                Admission.REMOVED -> {
                    // What their phone knows about their own removal is kept and passed on, as at a sync.
                    store.hearAbout(theirs)
                    if (!takeInRemoved || store.data.value.refusal(store.me, Action.RESTORE_BACKUP) != null) {
                        return Opened(false, fromRemoved(name), name, fromRemoved = theirs?.id)
                    }
                    workAlone = true
                }
                Admission.NEWCOMER, Admission.STRANGER -> return Opened(false, STRANGER, name)
            }
        }
        val incoming = if (workAlone && theirs != null) remote.workOf(theirs.id, before) else remote
        // A Helper's phone takes in nothing a Helper is not sent, whatever the update holds.
        val helperHere = before.hasOrganiser() && before.roleOf(device) == Role.HELPER
        val taken = if (helperHere) incoming.sentTo(Role.HELPER, device.personId) else incoming
        store.mergeIn(taken)
        if (h.moveId != store.moveId) store.adoptAliases(h.identity)
        if (retired != null) store.markCollected(h.from)
        // The files of photos the move now has on record, that this phone has no file for yet, are kept, as small copies.
        val after = store.data.value
        val wanted = after.photos.filter { !it.deleted && (!helperHere || it.kind !in HIDDEN_FROM_HELPERS) }.map { it.file }.toSet()
        val kept = ArrayList<String>()
        for ((file, tmp) in staged) {
            val target = File(store.photoDir, file)
            if (file in wanted && !target.exists() && tmp.isFile && tmp.renameTo(target)) kept += file
        }
        store.noteSmallCopies(kept, small = true)
        store.flush()
        val changes = changedRecords(before, store.data.value)
        if (changes == 0 && kept.isEmpty()) return Opened(true, NOTHING_NEW, name)
        val who = name.ifBlank { "the other phone" }
        val what = listOfNotNull(
            if (changes > 0) "$changes ${if (changes == 1) "change" else "changes"}" else null,
            if (kept.isNotEmpty()) "${kept.size} ${if (kept.size == 1) "photo" else "photos"}" else null,
        ).joinToString(" and ")
        return Opened(true, "Taken in from $who: $what.", name, changes, kept.size)
    }

    /** How many records differ between [a] and [b], leaving out the log. */
    private fun changedRecords(a: AppData, b: AppData): Int {
        var n = 0
        fun <T : Rec> count(x: List<T>, y: List<T>) {
            val was = HashMap<String, T>(x.size * 2)
            for (r in x) was[r.id] = r
            for (r in y) if (was[r.id] != r) n++
        }
        count(a.rooms, b.rooms); count(a.items, b.items); count(a.boxes, b.boxes); count(a.photos, b.photos)
        count(a.tasks, b.tasks); count(a.expenses, b.expenses); count(a.supplies, b.supplies); count(a.trips, b.trips)
        count(a.readings, b.readings); count(a.entries, b.entries); count(a.people, b.people); count(a.proposals, b.proposals)
        count(a.hidden, b.hidden); count(a.collect, b.collect); count(a.damage, b.damage); count(a.lines, b.lines)
        if (a.move != b.move) n++
        return n
    }
}

/**
 * The work of [id] in this data, for an Organiser who takes in the update of someone removed from the move: what
 * they added or had a hand in, what they suggested, asked, reported, ticked or set, and the trips and readings
 * that belongs to. Who is in the move, and the move's settings, stay as [here] has them; the merge then refuses
 * whatever they had no right to do, as it would at a sync.
 */
internal fun AppData.workOf(id: String, here: AppData): AppData {
    fun Owned.theirs() = createdBy == id || id in hands
    val items = items.filter { it.theirs() }
    val boxes = boxes.filter { it.theirs() }
    val photos = photos.filter { it.by == id || id in it.hands }
    val owners = photos.map { it.ownerId }.toSet()
    val trips = (items.mapNotNull { it.tripId } + boxes.mapNotNull { it.tripId }).toSet()
    return AppData(
        version = version,
        move = here.move,
        rooms = rooms.filter { it.theirs() },
        items = items,
        boxes = boxes,
        photos = photos,
        tasks = tasks.filter { it.doneBy.by == id },
        expenses = expenses.filter { it.paidBy.by == id },
        trips = this.trips.filter { it.id in trips },
        readings = readings.filter { it.id in owners },
        entries = entries.filter { id in it.hands },
        people = emptyList(),
        proposals = proposals.filter { it.by == id },
        activity = activity.filter { it.by == id },
        hidden = hidden.filter { it.detailsBy.by == id || it.saleBy.by == id },
        collect = collect.filter { it.by.by == id },
        damage = damage.filter { it.by == id },
        lines = lines.filter { it.by == id },
    )
}
