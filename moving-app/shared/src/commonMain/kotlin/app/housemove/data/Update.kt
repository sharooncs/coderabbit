package app.housemove.data

import app.housemove.sync.AutoSession
import app.housemove.sync.AutoSync
import app.housemove.sync.Hpke
import app.housemove.sync.NoiseKey
import app.housemove.sync.WifiSync
import app.housemove.sync.entryKey
import app.housemove.sync.memberPhone
import app.housemove.sync.noiseKey
import app.housemove.sync.pointOfKeyText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

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
 *
 * **Format 2** (from 2.5; design/encryption.md, "Mailbox as built"): the same, but the content key goes to each phone in
 * an HPKE envelope of mode_auth ([Hpke]), sealed with this phone's own key to that phone's pinned key, and after the
 * chunks comes a mark and a trailer in which each phone's envelope seals the hash of the header and every chunk. A
 * phone that opens it knows which phone made it (the sender's pinned key had to take part), and that the contents are
 * the ones that phone sealed (another phone the file was for, which also holds the content key, cannot change them for
 * the others). It is written when every phone it is for is known to open it ([Pins.opensHpke]); otherwise format 1, as
 * before, which says nothing provable about its sender, and the phone that writes it says which phones that was for.
 *
 * Common to Android and the iPhone (design/encryption.md): the crypto is the platform's ([platformCrypto]: the JCA on
 * Android, CryptoKit on the iPhone) and everything else is here, so an iPhone opens what an Android phone sends, and the
 * other way round.
 */
object Update {
    /** The ending of an update file's name. */
    const val EXTENSION = "housemove"

    /** The type an update file is shared as: messaging apps send it as a document, and Android offers this app to open it. */
    const val MIME = "application/vnd.housemove.update"

    internal const val FORMAT = 1

    /** Format 2: HPKE envelopes (from 2.5). */
    internal const val FORMAT_HPKE = 2
    private val MAGIC = "HMUPDATE".toUtf8()
    private const val MAX_HEADER = 1 shl 20
    private const val MAX_TRAILER = 1 shl 20
    private const val MAX_JSON = 64 * 1024 * 1024
    private const val MAX_PHOTO = 40L * 1024 * 1024

    /** What each phone's envelope seals as its second message in a file of format 2: the hash of the header and the chunks. */
    private val SUM_AAD = "HouseMove update 2 sum".toUtf8()

    /** The trailer of a file of format 2 is sealed under the content key too (with the hash), so no byte of it can change. */
    private val TRAILER_AAD = "HouseMove update 2 trailer".toUtf8()

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

    /**
     * Asked before an update of format 1 is opened on a phone with 2.5 (the design's warning for update files from older
     * phones): it cannot show which phone made it. [name] is the person it says it is from.
     */
    fun unproven(name: String): String {
        val who = name.trim()
        return if (who.isEmpty()) "This update was made the older way, so it cannot show which phone it came from. Open it only if you know who sent it."
        else "This update was made the older way, so it cannot show it came from $who's phone. Open it only if $who sent it to you."
    }

    /** An update of format 2 that says it is from [name]'s phone, whose key did not seal it. */
    fun notSealedBy(name: String) = "This update says it is from ${AutoSync.Status.phoneOf(name)}, but that phone did not seal it, so nothing was changed."

    /** An update of format 2 from a phone of the move whose key this phone has no pin for yet. */
    fun keyNotKnown(name: String) =
        "This update is from ${AutoSync.Status.phoneOf(name)}, whose key this phone does not have yet. Sync with that phone once, then open the update again."

    /** Said where an update was written the older way because of the phones of [names] (their HouseMove is older, or not known yet). */
    fun madeTheOlderWay(names: List<String>): String {
        val who = names.map { AutoSync.Status.phoneOf(it) }.distinct()
        val list = when (who.size) {
            0 -> "a phone it is for"
            1 -> who[0]
            else -> who.dropLast(1).joinToString(", ") + " and " + who.last()
        }
        return "This update was made the older way, because $list has not shown it has HouseMove 2.5 yet: it cannot show it came from this phone. " +
            "Once they have synced with this phone on HouseMove 2.5, updates show it."
    }

    @Serializable
    internal data class To(val phone: String = "", val epk: String = "", val salt: String = "", val iv: String = "", val key: String = "", val enc: String = "")

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

    /** A file of format 2 ends with each phone's envelope sealing the hash of the header and the chunks. */
    @Serializable
    internal data class Sum(val phone: String = "", val sum: String = "")

    @Serializable
    internal data class Trailer(val sums: List<Sum> = emptyList())

    /** What an update says about itself before it is opened. */
    data class Peek(val moveId: String, val aliases: List<String>, val from: String, val at: Long, val to: List<String>) {
        val identity: MoveIdentity get() = MoveIdentity(moveId, aliases)
    }

    /**
     * An update that was made: for how many phones, with how many photos. [sealed]: of format 2, so each phone it is for
     * can tell it came from this one. Otherwise [plainFor] names the people whose phones made it the older way.
     */
    data class Made(val phones: Int, val photos: Int, val sealed: Boolean = false, val plainFor: List<String> = emptyList())

    /**
     * What came of opening an update, in words for the screen ([message]). [fromRemoved] is the person it is from
     * when they have been removed from the move: an Organiser may open it again to take in their work alone.
     * [sender] is the phone it is from ("Priya's phone"); [proven] when its seal shows that phone made it (format 2).
     * [unproven]: nothing was opened yet; the person is asked first ([unproven] words), then it is opened again.
     */
    data class Opened(
        val ok: Boolean,
        val message: String,
        val fromName: String = "",
        val changes: Int = 0,
        val photos: Int = 0,
        val fromRemoved: String? = null,
        /** This phone's person may take in the work of [fromRemoved] alone (an Organiser). */
        val mayTakeIn: Boolean = false,
        val sender: String = "",
        val proven: Boolean = false,
        val unproven: Boolean = false,
    )

    // ---- making one ---------------------------------------------------------------------

    /**
     * Write an update from [store] to [out]: sealed for every Organiser's and Decider's phone it knows the key of, or
     * with [organisersOnly] only for Organisers' (a phone taken out of the move sends its last work to an Organiser).
     * Of format 2 when every one of them is known to open it, of format 1 otherwise.
     * Throws an IOException with words for the screen when there is nobody to send it to, or it cannot be written.
     */
    fun write(store: Store, out: OutputStream, organisersOnly: Boolean = store.removedFromMove()): Made {
        val crypto = platformCrypto ?: throw IOException(COULD_NOT_WRITE)
        val to = store.updateRecipients(organisersOnly).mapNotNull { (_, ph) -> publicKeyDer(crypto, ph.key)?.let { ph.id to it } }.distinctBy { it.first }
        if (to.isEmpty()) throw IOException(NO_RECIPIENTS)
        val me = store.phone.id
        val d = store.data.value
        val mine = store.phone.keys.noiseKey()
        // Format 2 needs this phone's key, and for every phone it is for, a pin of the key its entry carries, of a phone known to open it.
        val pins = store.pins.learn(d, me)
        val notReady = to.map { it.first }.filter { id -> pins[id]?.let { !it.hpke || it.key != d.entryKey(id) } ?: true }
        val older = WifiSync.phone24(me)
        if (mine != null && notReady.isEmpty() && !older) {
            val points = to.map { (id, _) -> id to (pointOfKeyText(pins.getValue(id).key) ?: throw IOException(COULD_NOT_WRITE)) }
            return writeHpke(store, crypto, out, mine, points)
        }
        store.flush()
        val data = store.data.value
        val id = store.identity.value
        val contentKey = Sealing.randomBytes(32)
        val prefix = Sealing.randomBytes(8)
        val header = Header(
            schema = SCHEMA, moveId = id.id, aliases = id.aliases, derived = id.derived, from = me,
            at = currentTimeMillis(), nonce = base64Encode(prefix),
            to = to.map { (phoneId, key) -> wrappedFor(crypto, contentKey, key, phoneId, id.id) },
        )
        var photos = 0
        try {
            writeSealed(crypto, out, header, contentKey, prefix) { sealed -> photos = writeContents(store, data, sealed) }
        } catch (e: IOException) {
            throw IOException(COULD_NOT_WRITE, e)
        }
        val plainFor = if (older || mine == null) emptyList() else notReady.map { d.phoneEntry(it)?.first?.name.orEmpty() }
        return Made(to.size, photos, sealed = false, plainFor = plainFor)
    }

    /** The zip of [data] and the photos for it ([photosFor]), as small copies, to [sealed], which it closes. How many photos went in. */
    private fun writeContents(store: Store, data: AppData, sealed: OutputStream): Int {
        var photos = 0
        val zip = ZipWriter(sealed)
        zip.put("data.json") { AppJson.encodeToString(data).toUtf8() }
        for (p in photosFor(store)) {
            val bytes = Shrink.copyOf(File(store.photoDir, p.file)) ?: continue
            zip.put("photos/" + p.file) { bytes }
            photos++
        }
        // Ends the zip and closes the sealed stream under it, which seals the last chunk.
        zip.close()
        return photos
    }

    /** HPKE's info for the envelope of an update to [phoneId] in the move [moveId]: "HouseMove update 2" | move ID | phone ID. */
    internal fun hpkeInfo(moveId: String, phoneId: String): ByteArray = AutoSession.joined("HouseMove update 2".toUtf8(), moveId.toUtf8(), phoneId.toUtf8())

    /** An update of format 2 to the phones [to] (phone ID and pinned point), sealed with this phone's key [mine]. */
    private fun writeHpke(store: Store, crypto: PlatformCrypto, out: OutputStream, mine: NoiseKey, to: List<Pair<String, ByteArray>>): Made {
        store.flush()
        val data = store.data.value
        val id = store.identity.value
        val contentKey = Sealing.randomBytes(32)
        val prefix = Sealing.randomBytes(8)
        val senders = to.map { (phoneId, point) -> phoneId to (Hpke.setupAuthS(crypto, point, hpkeInfo(id.id, phoneId), mine) ?: throw IOException(COULD_NOT_WRITE)) }
        val header = Header(
            format = FORMAT_HPKE, schema = SCHEMA, moveId = id.id, aliases = id.aliases, derived = id.derived, from = store.phone.id,
            at = currentTimeMillis(), nonce = base64Encode(prefix),
            to = senders.map { (phoneId, s) -> To(phone = phoneId, enc = base64Encode(s.enc), key = base64Encode(s.context.seal(prefix, contentKey))) },
        )
        var photos = 0
        try {
            writeSealedHpke(crypto, out, header, contentKey, prefix, { h -> senders.map { (phoneId, s) -> Sum(phoneId, base64Encode(s.context.seal(SUM_AAD, h))) } }) { sealed ->
                photos = writeContents(store, data, sealed)
            }
        } catch (e: IOException) {
            throw IOException(COULD_NOT_WRITE, e)
        }
        return Made(to.size, photos, sealed = true)
    }

    /** [text], a public key as it travels, as X.509 bytes, when it is one [crypto] can agree a key with. */
    private fun publicKeyDer(crypto: PlatformCrypto, text: String): ByteArray? {
        if (text.isBlank()) return null
        val der = try {
            base64Decode(text)
        } catch (_: Exception) {
            return null
        }
        return der.takeIf { crypto.isPublicKey(it) }
    }

    /** [contentKey] wrapped for [phoneId], whose public key is [key] ([Sealing.wrap]), as the header carries it. */
    internal fun wrappedFor(
        crypto: PlatformCrypto,
        contentKey: ByteArray,
        key: ByteArray,
        phoneId: String,
        moveId: String,
        ephemeral: AgreementKey = crypto.generateKey(),
        salt: ByteArray = Sealing.randomBytes(16),
        iv: ByteArray = Sealing.randomBytes(12),
    ): To {
        val w = Sealing.wrap(crypto, contentKey, key, phoneId, moveId, ephemeral, salt, iv)
        return To(phoneId, base64Encode(w.ephemeral), base64Encode(w.salt), base64Encode(w.iv), base64Encode(w.sealed))
    }

    /**
     * Writes an update file to [out]: the magic, the format, [header], then what [body] writes to the stream it is given,
     * sealed under [contentKey] with chunk nonces that start with [prefix]. [body] may close that stream when it is done
     * (a zip writer does), which seals the last chunk. [out] is flushed and left open.
     */
    internal fun writeSealed(crypto: PlatformCrypto, out: OutputStream, header: Header, contentKey: ByteArray, prefix: ByteArray, body: (OutputStream) -> Unit) {
        val headerBytes = AppJson.encodeToString(header).toUtf8()
        val buffered = out.bufferedStream()
        buffered.write(MAGIC, 0, MAGIC.size)
        writeInt(buffered, FORMAT)
        writeInt(buffered, headerBytes.size)
        buffered.write(headerBytes, 0, headerBytes.size)
        val sealed = SealedOut(crypto, buffered, contentKey, prefix, Sealing.sha256(headerBytes))
        body(sealed.stream)
        sealed.close()
        buffered.flush()
    }

    /**
     * An update file of format 2: as [writeSealed], then after the last chunk a four-byte 0, then the trailer: its length and
     * the JSON with what [sums] makes of the hash of the header and every chunk (h = SHA-256(header), then for each sealed
     * chunk h = SHA-256(h | chunk)), sealed with the content key, the nonce prefix | FFFFFFFF (no chunk's) and additional
     * data "HouseMove update 2 trailer" | h.
     */
    internal fun writeSealedHpke(
        crypto: PlatformCrypto, out: OutputStream, header: Header, contentKey: ByteArray, prefix: ByteArray,
        sums: (ByteArray) -> List<Sum>, body: (OutputStream) -> Unit,
    ) {
        val headerBytes = AppJson.encodeToString(header).toUtf8()
        val buffered = out.bufferedStream()
        buffered.write(MAGIC, 0, MAGIC.size)
        writeInt(buffered, FORMAT_HPKE)
        writeInt(buffered, headerBytes.size)
        buffered.write(headerBytes, 0, headerBytes.size)
        val headerHash = Sealing.sha256(headerBytes)
        var h = headerHash.copyOf()
        val sealed = SealedOut(crypto, buffered, contentKey, prefix, headerHash, endMark = true) { c -> h = Sealing.sha256(h + c) }
        body(sealed.stream)
        sealed.close()
        val trailer = crypto.sealGcm(contentKey, Sealing.chunkNonce(prefix, -1), TRAILER_AAD + h, AppJson.encodeToString(Trailer(sums(h))).toUtf8())
        writeInt(buffered, trailer.size)
        buffered.write(trailer, 0, trailer.size)
        buffered.flush()
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

    /** The header, its bytes and the file's format. A 2.4 phone ([WifiSync.phone24], the tests' stand-in) reads format 1 only. */
    private fun readHeader(din: InputStream, newest: Int = FORMAT_HPKE): Triple<Header, ByteArray, Int> {
        val magic = ByteArray(MAGIC.size)
        try {
            readFully(din, magic)
            if (!magic.contentEquals(MAGIC)) throw IllegalArgumentException(NOT_AN_UPDATE)
            val format = readInt(din)
            if (format > newest) throw IllegalArgumentException(TOO_NEW)
            if (format < 1) throw IllegalArgumentException(NOT_AN_UPDATE)
            val n = readInt(din)
            if (n <= 0 || n > MAX_HEADER) throw IllegalArgumentException(NOT_AN_UPDATE)
            val bytes = ByteArray(n).also { readFully(din, it) }
            val header = AppJson.decodeFromString<Header>(bytes.decodeUtf8())
            if (header.moveId.isEmpty()) throw IllegalArgumentException(NOT_AN_UPDATE)
            return Triple(header, bytes, format)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(e.message?.takeIf { it == TOO_NEW } ?: NOT_AN_UPDATE, e)
        } catch (e: Exception) {
            throw IllegalArgumentException(NOT_AN_UPDATE, e)
        }
    }

    /** Read what an update says about itself: which move it is for, and which phones. An IllegalArgumentException in words when it is not one. */
    fun peek(input: InputStream): Peek {
        val (h, _) = readHeader(input.bufferedStream())
        return Peek(h.moveId, h.aliases, h.from, h.at, h.to.map { it.phone })
    }

    /**
     * Open an update into [store]: the move first, then whether it is for this phone, then the contents, merged with
     * the rules of a sync. With [takeInRemoved], an update from someone removed from the move is taken in as their
     * work alone, when this phone's person may restore a backup (an Organiser). Nothing is changed when it is refused,
     * and no photo file is left behind. With [askIfUnproven], an update of format 1 (which cannot show who made it) is
     * not opened: the answer says so ([Opened.unproven]), for the person to be asked, and opened again without it.
     */
    fun open(store: Store, input: InputStream, takeInRemoved: Boolean = false, askIfUnproven: Boolean = false): Opened {
        val din = input.bufferedStream()
        val (header, headerBytes, format) = try {
            readHeader(din, if (WifiSync.phone24(store.phone.id)) FORMAT else FORMAT_HPKE)
        } catch (e: IllegalArgumentException) {
            return Opened(false, e.message ?: NOT_AN_UPDATE)
        }
        // Which move, before anything.
        if (!store.identity.value.matches(header.identity)) return Opened(false, OTHER_MOVE)
        if (header.schema != SCHEMA) return Opened(false, OTHER_VERSION)
        val mine = header.to.firstOrNull { it.phone == store.phone.id }
            ?: return Opened(false, if (header.from == store.phone.id) FROM_THIS_PHONE else NOT_FOR_THIS_PHONE)
        val crypto = platformCrypto ?: return Opened(false, DAMAGED)
        val prefix = try {
            base64Decode(header.nonce).takeIf { it.size == 8 }
        } catch (_: Exception) {
            null
        } ?: return Opened(false, DAMAGED)
        if (format == FORMAT_HPKE) return openHpke(store, crypto, din, header, headerBytes, mine, prefix, takeInRemoved)
        val said = store.data.value.phoneEntry(header.from)?.first?.name.orEmpty()
        if (askIfUnproven && !WifiSync.phone24(store.phone.id)) return Opened(false, unproven(said), said, sender = AutoSync.Status.phoneOf(said), unproven = true)
        val key = try {
            val w = Sealing.Wrapped(base64Decode(mine.epk), base64Decode(mine.salt), base64Decode(mine.iv), base64Decode(mine.key))
            Sealing.unwrap(crypto, w, store.phone.keys, store.phone.id, header.moveId)
        } catch (_: Exception) {
            null
        } ?: return Opened(false, DAMAGED)

        // The contents: the data, and the photo files put aside under a name the app clears at start-up until all of it has opened.
        val staged = LinkedHashMap<String, File>()
        try {
            val json = try {
                val sealed = SealedIn(crypto, din, key, prefix, Sealing.sha256(headerBytes))
                readContents(store, sealed.stream, staged).also {
                    // Every chunk is checked, to the very end, before anything is taken in.
                    sealed.drain()
                }
            } catch (_: Exception) {
                return Opened(false, DAMAGED)
            }
            val remote = decodeData(json) ?: return Opened(false, DAMAGED)
            if (remote.version > SCHEMA) return Opened(false, TOO_NEW)
            return takeIn(store, header.from, header.moveId, header.identity, remote, staged, takeInRemoved, proven = false)
        } finally {
            staged.values.forEach { if (it.exists()) it.delete() }
        }
    }

    /**
     * An update of format 2: the envelope for this phone opens only with the key of the phone it says it is from (its pin;
     * for a phone no longer of the move, the key its entry carries, so an Organiser can still take in its last work), and
     * the hash in the trailer must be the one of what came.
     */
    private fun openHpke(
        store: Store, crypto: PlatformCrypto, din: InputStream, header: Header, headerBytes: ByteArray, mine: To, prefix: ByteArray, takeInRemoved: Boolean,
    ): Opened {
        val d = store.data.value
        val me = store.phone.id
        val from = header.from
        val name = d.phoneEntry(from)?.first?.name.orEmpty()
        val pkS = if (d.memberPhone(from) != null) {
            store.pins.keyOf(from, d, me) ?: return Opened(false, keyNotKnown(name), name)
        } else {
            d.phoneEntry(from)?.second?.key?.let { pointOfKeyText(it) } ?: return Opened(false, STRANGER, name)
        }
        val myKey = store.phone.keys.noiseKey() ?: return Opened(false, DAMAGED)
        val ctx = try {
            Hpke.setupAuthR(crypto, base64Decode(mine.enc), myKey, hpkeInfo(header.moveId, me), pkS)
        } catch (_: Exception) {
            null
        } ?: return Opened(false, DAMAGED)
        val contentKey = try {
            ctx.open(prefix, base64Decode(mine.key))?.takeIf { it.size == 32 }
        } catch (_: Exception) {
            null
        } ?: return Opened(false, notSealedBy(name), name)
        val staged = LinkedHashMap<String, File>()
        try {
            val json = try {
                val headerHash = Sealing.sha256(headerBytes)
                var h = headerHash.copyOf()
                val sealed = SealedIn(crypto, din, contentKey, prefix, headerHash, endMark = true) { c -> h = Sealing.sha256(h + c) }
                val json = readContents(store, sealed.stream, staged)
                sealed.drain()
                // The trailer, and nothing after it: this phone's envelope seals the hash of what came.
                val n = readInt(din)
                if (n <= 0 || n > MAX_TRAILER) throw IOException("bad trailer")
                val sealedTrailer = ByteArray(n).also { readFully(din, it) }
                val trailerJson = crypto.openGcm(contentKey, Sealing.chunkNonce(prefix, -1), TRAILER_AAD + h, sealedTrailer) ?: throw IOException("the trailer does not open")
                val trailer = AppJson.decodeFromString<Trailer>(trailerJson.decodeUtf8())
                if (readByte(din) >= 0) throw IOException("more after the trailer")
                val sum = trailer.sums.firstOrNull { it.phone == me } ?: throw IOException("no sum for this phone")
                val said = ctx.open(SUM_AAD, base64Decode(sum.sum)) ?: throw IOException("the sum does not open")
                if (!sameBytes(said, h)) throw IOException("the contents are not the ones sealed")
                json
            } catch (_: Exception) {
                return Opened(false, DAMAGED, name)
            }
            val remote = decodeData(json) ?: return Opened(false, DAMAGED, name)
            if (remote.version > SCHEMA) return Opened(false, TOO_NEW, name)
            // A phone that seals with HPKE opens it too: updates to it may be of format 2.
            store.pins.markHpke(from)
            return takeIn(store, from, header.moveId, header.identity, remote, staged, takeInRemoved, proven = true)
        } finally {
            staged.values.forEach { if (it.exists()) it.delete() }
        }
    }

    private fun decodeData(json: String?): AppData? = try {
        json?.let { AppJson.decodeFromString<AppData>(it) }
    } catch (_: Exception) {
        null
    }

    /**
     * Reads the zip of an update's contents (or a mailbox message's) from [stream]: the text of data.json, with the photo
     * files put aside in [staged] under a name the app clears at start-up. Throws on anything that is not right.
     */
    internal fun readContents(store: Store, stream: InputStream, staged: MutableMap<String, File>): String? {
        var json: String? = null
        val zip = ZipReader(stream)
        while (true) {
            val e = zip.nextEntry() ?: break
            val name = e.name
            when {
                name == "data.json" -> {
                    val bytes = zip.readBytes()
                    if (bytes.size > MAX_JSON) throw IOException("too large")
                    json = bytes.decodeUtf8()
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
                                val n = zip.read(buf, 0, buf.size)
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
        return json
    }

    /**
     * A mailbox message's contents (design/encryption.md, "Mailbox as built"), from the phone [from] whose seal has already
     * opened here: taken in as an update of format 2 is. [staged] holds its photos, put aside ([readContents]).
     */
    internal fun takeInMessage(store: Store, from: String, moveId: String, remote: AppData, staged: Map<String, File>): Opened {
        if (remote.version > SCHEMA) return Opened(false, TOO_NEW)
        return takeIn(store, from, moveId, store.identity.value, remote, staged, takeInRemoved = false, proven = true)
    }

    private fun takeIn(
        store: Store, from: String, moveId: String, identity: MoveIdentity, remote: AppData, staged: Map<String, File>, takeInRemoved: Boolean, proven: Boolean,
    ): Opened {
        val before = store.data.value
        val device = store.device.value
        // Who sent it: the person whose phone it came from, as their own data has them.
        val sender = remote.phoneEntry(from)?.first ?: before.phoneEntry(from)?.first
        val theirs = sender?.let { remote.person(it.id) } ?: sender
        val name = theirs?.name.orEmpty()
        val phone = AutoSync.Status.phoneOf(name)
        // A retired phone: its work is taken in once, and never again.
        val retired = before.phoneEntry(from)?.second?.takeIf { it.isRetired }
        if (retired?.collected == true) return Opened(false, RETIRED_GONE, name, sender = phone, proven = proven)
        var workAlone = false
        if (retired == null) {
            when (before.admission(device, theirs)) {
                Admission.MEMBER -> Unit
                Admission.REMOVED -> {
                    // What their phone knows about their own removal is kept and passed on, as at a sync.
                    store.hearAbout(theirs)
                    val may = store.data.value.refusal(store.me, Action.RESTORE_BACKUP) == null
                    if (!takeInRemoved || !may) return Opened(false, fromRemoved(name), name, fromRemoved = theirs?.id, mayTakeIn = may, sender = phone, proven = proven)
                    workAlone = true
                }
                Admission.NEWCOMER, Admission.STRANGER -> return Opened(false, STRANGER, name, sender = phone, proven = proven)
            }
        }
        val incoming = if (workAlone && theirs != null) remote.workOf(theirs.id, before) else remote
        // A Helper's phone takes in nothing a Helper is not sent, whatever the update holds.
        val helperHere = before.hasOrganiser() && before.roleOf(device) == Role.HELPER
        val taken = if (helperHere) incoming.sentTo(Role.HELPER, device.personId) else incoming
        store.mergeIn(taken)
        if (moveId != store.moveId) store.adoptAliases(identity)
        if (retired != null) store.markCollected(from)
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
        if (changes == 0 && kept.isEmpty()) return Opened(true, NOTHING_NEW, name, sender = phone, proven = proven)
        // From a sealed update (format 2), the phone that made it; from one of format 1, the name it says it is from.
        val who = if (proven) phone else name.ifBlank { "the other phone" }
        val what = listOfNotNull(
            if (changes > 0) "$changes ${if (changes == 1) "change" else "changes"}" else null,
            if (kept.isNotEmpty()) "${kept.size} ${if (kept.size == 1) "photo" else "photos"}" else null,
        ).joinToString(" and ")
        return Opened(true, "Taken in from $who: $what.", name, changes, kept.size, sender = phone, proven = proven)
    }

    /** How many records differ between [a] and [b], leaving out the log. */
    internal fun changedRecords(a: AppData, b: AppData): Int {
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
        if (a.mailbox != b.mailbox) n++
        return n
    }
}

/**
 * Open an update file ([Update]) into the move it is for, which need not be the one in use: the move is
 * looked for by its ID before anything else. [read] opens the file; it is read twice. With
 * [takeInRemoved], an Organiser takes in the work of someone removed from the move ([Update.open]); with
 * [askIfUnproven], an update that cannot show who made it is not opened yet ([Update.Opened.unproven]).
 */
fun Moves.openUpdate(read: () -> InputStream, takeInRemoved: Boolean = false, askIfUnproven: Boolean = false): Update.Opened {
    val peek = try {
        read().use { Update.peek(it) }
    } catch (e: IllegalArgumentException) {
        return Update.Opened(false, e.message ?: Update.NOT_AN_UPDATE)
    } catch (_: Exception) {
        return Update.Opened(false, Update.NOT_AN_UPDATE)
    }
    val held = card(peek.moveId) ?: peek.aliases.firstNotNullOfOrNull { card(it) } ?: return Update.Opened(false, Update.NOT_ON_PHONE)
    val s = storeIn(held.folder)
    return try {
        read().use { Update.open(s, it, takeInRemoved, askIfUnproven) }
    } catch (_: Exception) {
        Update.Opened(false, Update.NOT_AN_UPDATE)
    }.also { refresh() }
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
        mailbox = here.mailbox,
    )
}
