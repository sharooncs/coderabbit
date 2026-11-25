package app.housemove

import app.housemove.data.AppData
import app.housemove.data.AppJson
import app.housemove.data.Item
import app.housemove.data.Role
import app.housemove.data.Sealing
import app.housemove.data.SealedOut
import app.housemove.data.Update
import app.housemove.data.ZipWriter
import app.housemove.data.base64Decode
import app.housemove.data.decodeUtf8
import app.housemove.data.deleteRecursively
import app.housemove.data.toUtf8
import app.housemove.data.write
import app.housemove.sync.Hpke
import app.housemove.sync.LocalLan
import app.housemove.sync.MemoryOut
import app.housemove.sync.WifiSync
import app.housemove.sync.memberPhone
import app.housemove.sync.noiseCrypto
import app.housemove.sync.noiseKey
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Update files of format 2 (HPKE envelopes in a file, from 2.5; design/encryption.md, "Mailbox as built"), between phones
 * that keep their moves as the app does: written when every phone it is for is known to open them, opened only with the
 * pinned key of the phone it says it came from, which the screen then names; of format 1 when one of them has 2.4 (and the
 * phone writing it says why); format 1 still opening on 2.5, after asking; and what a changed file, a phone it was also
 * for, a stranger, or a 2.4 phone make of one. On the JVM and on the iPhone simulator.
 */
class UpdateFormat2CommonTest {
    private val undoCrypto = useTestCryptoIfNone()
    private val root = newTempFolder()
    private val phones = ArrayList<AutoPhone>()

    private fun phone(name: String) = AutoPhone(root, name, LocalLan(), engineOn = false).also { phones += it }

    @AfterTest
    fun cleanUp() {
        try {
            phones.forEach { it.close() }
            root.deleteRecursively()
        } finally {
            WifiSync.phone24 = { false }
            undoCrypto()
        }
    }

    private fun AutoPhone.update(): Pair<ByteArray, Update.Made> {
        val out = BytesOut()
        val made = Update.write(store, out.stream)
        return out.bytes() to made
    }

    private fun AutoPhone.open(bytes: ByteArray, ask: Boolean = true, takeInRemoved: Boolean = false) = Update.open(store, bytesIn(bytes), takeInRemoved, ask)

    private fun AutoPhone.has(item: String) = store.data.value.liveItems.any { it.name == item }

    private fun formatOf(bytes: ByteArray): Int = ((bytes[8].toInt() and 0xff) shl 24) or ((bytes[9].toInt() and 0xff) shl 16) or ((bytes[10].toInt() and 0xff) shl 8) or (bytes[11].toInt() and 0xff)

    /** Asha (Organiser) with Priya and Omar (Deciders), all met by code, so every phone knows every other opens HPKE. */
    private fun move(): Triple<AutoPhone, AutoPhone, AutoPhone> {
        val asha = phone("Asha")
        asha.found("Maple Road")
        val priya = phone("Priya")
        val omar = phone("Omar")
        asha.invite(priya, Role.DECIDER)
        asha.invite(omar, Role.DECIDER)
        assertTrue(asha.codeSync(priya).ok && asha.codeSync(omar).ok && priya.codeSync(omar).ok)
        return Triple(asha, priya, omar)
    }

    @Test
    fun format2BetweenPhonesWith25NamesItsSender() {
        val (asha, priya, omar) = move()
        assertTrue(asha.store.pins.opensHpke(priya.store.phone.id) && asha.store.pins.opensHpke(omar.store.phone.id))
        asha.store.createItem("Piano", null)
        val (file, made) = asha.update()
        assertTrue(made.sealed, "every phone it is for opens HPKE: format 2")
        assertEquals(2, made.phones)
        assertEquals(Update.FORMAT_HPKE, formatOf(file))
        for (p in listOf(priya, omar)) {
            val o = p.open(file)
            assertTrue(o.ok && o.proven, o.message)
            assertEquals("Asha's phone", o.sender)
            assertEquals("Taken in from Asha's phone: 1 change.", o.message)
            assertTrue(p.has("Piano"))
            // Opening it again changes nothing.
            assertEquals(Update.NOTHING_NEW, p.open(file).message)
        }
        // The file says which phones it is for, and nothing of what is in it.
        val text = file.decodeToString(throwOnInvalidSequence = false)
        assertFalse("Piano" in text)
        assertEquals(Update.FROM_THIS_PHONE, asha.open(file).message)
    }

    /** A phone with 2.4 gets format 1, the phone writing it says so, and a 2.5 phone asks before it opens one. */
    @Test
    fun aPhoneWith24GetsFormat1AndA25PhoneAsksFirst() {
        val (asha, priya, omar) = move()
        // Before a 2.4 phone joins: format 2, which a 2.4 phone does not open.
        asha.store.createItem("Lamp", null)
        val (two, sealed) = asha.update()
        assertTrue(sealed.sealed)
        WifiSync.phone24 = { it == priya.store.phone.id }
        assertEquals(Update.TOO_NEW, priya.open(two, ask = false).message)
        WifiSync.phone24 = { false }

        val dee = phone("Dee")
        val deeId = dee.moves.phone.id
        WifiSync.phone24 = { it == deeId }
        asha.invite(dee, Role.DECIDER)
        assertTrue(asha.codeSync(dee).ok)
        assertFalse(asha.store.pins.opensHpke(dee.store.phone.id), "a 2.4 phone does not say it opens HPKE")
        val (file, made) = asha.update()
        assertFalse(made.sealed)
        assertEquals(Update.FORMAT, formatOf(file))
        assertEquals(listOf("Dee"), made.plainFor)
        assertTrue(Update.madeTheOlderWay(made.plainFor).startsWith("This update was made the older way, because Dee's phone has not shown"))
        // The 2.4 phone opens it as before, and writes format 1 itself.
        val d = dee.open(file, ask = false)
        assertTrue(d.ok && !d.proven, d.message)
        assertTrue(dee.has("Lamp"))
        val (fromDee, deeMade) = dee.update()
        assertEquals(Update.FORMAT, formatOf(fromDee))
        assertTrue(deeMade.plainFor.isEmpty(), "a 2.4 phone says nothing of it")
        // A 2.5 phone asks first: the file cannot show who made it.
        val asked = omar.open(file)
        assertFalse(asked.ok)
        assertTrue(asked.unproven)
        assertEquals("This update was made the older way, so it cannot show it came from Asha's phone. Open it only if Asha sent it to you.", asked.message)
        assertFalse(omar.has("Lamp"), "nothing was taken in before the answer")
        val opened = omar.open(file, ask = false)
        assertTrue(opened.ok && !opened.proven)
        assertTrue(opened.message.startsWith("Taken in from Asha: "), opened.message)
    }

    @Test
    fun aChangedFileOpensNothing() {
        val (asha, priya, _) = move()
        asha.store.createItem("Clock", null)
        val (file, made) = asha.update()
        assertTrue(made.sealed)
        val before = priya.store.data.value
        fun refused(bytes: ByteArray, wanted: String) {
            val o = priya.open(bytes)
            assertFalse(o.ok, wanted)
            assertEquals(wanted, o.message)
            assertEquals(before, priya.store.data.value)
        }
        val n = ((file[12].toInt() and 0xff) shl 24) or ((file[13].toInt() and 0xff) shl 16) or ((file[14].toInt() and 0xff) shl 8) or (file[15].toInt() and 0xff)
        val chunks = 16 + n
        // A byte of the header's time, of a chunk, of the trailer; cut short; something after the trailer.
        val text = file.copyOfRange(16, chunks).decodeUtf8()
        val at = 16 + text.indexOf("\"at\":") + 6
        refused(file.copyOf().also { it[at] = if (it[at] == '1'.code.toByte()) '2'.code.toByte() else '1'.code.toByte() }, Update.DAMAGED)
        refused(file.copyOf().also { it[chunks + 10] = (it[chunks + 10].toInt() xor 1).toByte() }, Update.DAMAGED)
        // Any byte of the trailer, which is sealed under the content key as well.
        for (back in listOf(5, 30, 60)) refused(file.copyOf().also { it[it.size - back] = (it[it.size - back].toInt() xor 1).toByte() }, Update.DAMAGED)
        refused(file.copyOf(file.size - 5), Update.DAMAGED)
        refused(file + byteArrayOf(1), Update.DAMAGED)
        assertTrue(priya.open(file).ok)
    }

    /**
     * Omar's phone, which the file was also for, holds the content key: it seals other contents under it and keeps the
     * header and the trailer. Priya's phone finds the hash in its own envelope is not that of what came.
     */
    @Test
    fun anotherPhoneItWasForCannotChangeItForTheOthers() {
        val (asha, priya, omar) = move()
        asha.store.createItem("Real thing", null)
        val (file, _) = asha.update()
        val n = ((file[12].toInt() and 0xff) shl 24) or ((file[13].toInt() and 0xff) shl 16) or ((file[14].toInt() and 0xff) shl 8) or (file[15].toInt() and 0xff)
        val headerBytes = file.copyOfRange(16, 16 + n)
        val header = AppJson.parseToJsonElement(headerBytes.decodeUtf8()).jsonObject
        val mine = header.getValue("to").jsonArray.map { it.jsonObject }.single { it.getValue("phone").jsonPrimitive.content == omar.store.phone.id }
        val crypto = noiseCrypto()!!
        val prefix = base64Decode(header.getValue("nonce").jsonPrimitive.content)
        val ctx = Hpke.setupAuthR(
            crypto, base64Decode(mine.getValue("enc").jsonPrimitive.content), omar.store.phone.keys.noiseKey()!!,
            Update.hpkeInfo(header.getValue("moveId").jsonPrimitive.content, omar.store.phone.id), asha.store.phone.keys.noiseKey()!!.point,
        )!!
        val contentKey = ctx.open(prefix, base64Decode(mine.getValue("key").jsonPrimitive.content))!!
        // The forged contents, sealed as Asha's phone sealed hers, with the header as it was.
        val forged = asha.store.data.value.copy(items = asha.store.data.value.items + Item(id = "forged", name = "Forged by Omar"))
        val zip = MemoryOut()
        val w = ZipWriter(zip.stream)
        w.put("data.json") { AppJson.encodeToString(AppData.serializer(), forged).toUtf8() }
        w.close()
        val out = BytesOut()
        out.stream.write(file, 0, 16 + n)
        var newHash = Sealing.sha256(headerBytes)
        val sealed = SealedOut(crypto, out.stream, contentKey, prefix, Sealing.sha256(headerBytes), endMark = true) { c -> newHash = Sealing.sha256(newHash + c) }
        sealed.write(zip.bytes(), 0, zip.size)
        sealed.close()
        // The trailer as it was (every phone's sealed hash), opened and sealed again under the content key with the new hash,
        // as Omar's phone can: after the original chunks' end mark.
        val original = file.copyOfRange(16 + n, file.size)
        var at = 0
        var oldHash = Sealing.sha256(headerBytes)
        while (true) {
            val len = ((original[at].toInt() and 0xff) shl 24) or ((original[at + 1].toInt() and 0xff) shl 16) or ((original[at + 2].toInt() and 0xff) shl 8) or (original[at + 3].toInt() and 0xff)
            at += 4
            if (len == 0) break
            oldHash = Sealing.sha256(oldHash + original.copyOfRange(at, at + len))
            at += len
        }
        val trailer = crypto.openGcm(contentKey, Sealing.chunkNonce(prefix, -1), "HouseMove update 2 trailer".toUtf8() + oldHash, original.copyOfRange(at + 4, original.size))!!
        val resealed = crypto.sealGcm(contentKey, Sealing.chunkNonce(prefix, -1), "HouseMove update 2 trailer".toUtf8() + newHash, trailer)
        app.housemove.data.writeInt(out.stream, resealed.size)
        out.stream.write(resealed, 0, resealed.size)
        val o = priya.open(out.bytes())
        assertFalse(o.ok, o.message)
        assertEquals(Update.DAMAGED, o.message)
        assertFalse(priya.has("Forged by Omar"))
    }

    /** Sealed with a key that is not the one pinned here for the phone it says it is from: not opened, and said so. */
    @Test
    fun aKeyThatIsNotThePinAndARemovedSendersLastWork() {
        val (asha, priya, omar) = move()
        asha.store.createItem("Genuine", null)
        val (file, _) = asha.update()
        val ashaId = asha.store.phone.id
        val real = asha.store.phone.keys.publicText
        // Priya's phone pins another key for Asha's phone (as if a phone posing as Asha's had been met).
        val other = app.housemove.data.base64Encode(noiseCrypto()!!.generateKey().publicDer)
        priya.store.pins.met(ashaId, other, priya.store.phone.keys.publicText)
        val o = priya.open(file)
        assertFalse(o.ok)
        assertEquals("This update says it is from Asha's phone, but that phone did not seal it, so nothing was changed.", o.message)
        assertFalse(priya.has("Genuine"))
        priya.store.pins.met(ashaId, real, priya.store.phone.keys.publicText)
        assertTrue(priya.open(file).ok)

        // A removed sender's last work: an Organiser can still take it in alone, its seal checked against its entry's key.
        omar.store.createItem("Omar's last box", null)
        val (last, made) = omar.update()
        assertTrue(made.sealed)
        assertTrue(asha.store.removePerson(asha.store.data.value.memberPhone(omar.store.phone.id)!!.id).ok)
        val refused = asha.open(last)
        assertFalse(refused.ok)
        assertTrue(refused.mayTakeIn, refused.message)
        val taken = asha.open(last, takeInRemoved = true)
        assertTrue(taken.ok && taken.proven, taken.message)
        assertTrue(asha.has("Omar's last box"))
    }
}
