package app.housemove

import app.housemove.data.*
import java.io.File

/**
 * What version 2.1 adds to the sample move of [AuditSampleData.phones], for the tests of the screens shaped by
 * role: hidden details (the fridge's make, model and serial number, the wardrobe's value, the sofa's price), the
 * sofa's hand-over given to Tom with £80 to collect, the bookcase to donate, a box at the storage unit, a damage
 * report from Tom's phone, and a change Tom's phone tried and was refused ("Tom's phone tried to mark Old sofa
 * sold; Tom is a Helper.").
 */
internal object Sample21 {
    const val SERIAL = "FD9907-000123"
    const val MAKE = "Bosch"
    const val MODEL = "KGN36"

    /** A sync: each phone is sent what its person is sent ([sentTo]), and the photos go across. */
    fun sync(a: Store, b: Store) {
        val merged = a.mergeIn(b.data.value.sentTo(a.myRole, a.me?.id))
        b.mergeIn(merged.sentTo(b.myRole, b.me?.id))
        for ((from, to) in listOf(a to b, b to a)) {
            from.photoDir.listFiles()?.forEach { f -> File(to.photoDir, f.name).takeIf { !it.exists() }?.let { f.copyTo(it) } }
        }
    }

    /** A sync that went all the way through, as the Wi-Fi sync says when it ends. */
    fun fullSync(a: Store, b: Store) {
        sync(a, b)
        a.fullSyncDone(b.me?.id)
        b.fullSyncDone(a.me?.id)
    }

    fun enrich(p: AuditSampleData.Phones): AuditSampleData.Phones {
        val sam = p.organiser
        val tom = p.helper
        val tomId = tom.me!!.id
        check(sam.setDetails("fridge") { it.copy(make = MAKE, model = MODEL, serial = SERIAL, warrantyDay = today() + 400) }.ok)
        check(sam.setDetails("wardrobe") { it.copy(value = 450.0) }.ok)
        check(sam.decide("sofa", Disposition.SELL, 80.0).ok)
        check(sam.arrangeHandover("sofa", to = tomId, paid = false, amount = 80.0).ok)
        check(sam.arrangeHandover("books", to = tomId).ok)

        // The storage unit: the box of cables goes there on a trip of its own. This phone stays on Trip 1.
        val current = sam.device.value.currentTripId
        val toStorage = sam.addTrip(Place.OLD_HOME, Place.STORAGE).valueOrNull()!!
        check(sam.loadOnto(toStorage.id, listOf("box7")).ok)
        check(sam.unload(listOf("box7")).ok)
        sam.setDevice { it.copy(currentTripId = current) }

        for (phone in listOf(p.decider, tom)) sync(sam, phone)

        // Tom reports damage to a box that has come in.
        check(tom.reportDamage("box5", "Corner crushed").ok)

        // A copy of the app on Tom's phone that marks the sofa sold, which a Helper may not do.
        val at = System.currentTimeMillis() + 60_000
        tom.update { d ->
            d.copy(items = d.items.map { i -> if (i.id == "sofa") i.copy(gone = true, updatedAt = at).let { it.copy(outcomeBy = Stamp(tomId, at, it.outcomeSum())) } else i })
        }
        // The sync turns it away on every phone, and the Organisers are told.
        repeat(2) { for (phone in listOf(p.decider, tom)) sync(sam, phone) }
        check(!sam.data.value.item("sofa")!!.gone && !tom.data.value.item("sofa")!!.gone)
        check(sam.data.value.refusedChanges().isNotEmpty())
        tom.fullSyncDone(sam.me?.id)
        for (s in listOf(sam, p.decider, tom)) s.flush()
        return p
    }
}
