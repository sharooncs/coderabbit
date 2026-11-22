package app.housemove

import app.housemove.data.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Random

/**
 * Update files in the simulation: now and then one phone sends what it has as an update through a messaging app,
 * and another opens it, as phones that cannot meet do. It is merged with the rules of a sync, so the checks at the
 * end hold it to the same invariants. Opened by a phone it was not sent to, it must change nothing; opened twice, the
 * second time must change nothing more; a Helper's phone must take in nothing a Helper is not sent.
 *
 * An update carries small copies of its photos ([Shrink]). On the JVM the app has no way to draw a smaller picture, so a
 * photo would go as it is and its small copy could not be told from the photo. The simulation puts in a way of its own
 * ([install]): a small copy names the length of the photo it was made from and keeps a few of its bytes, so the checks
 * at the end can see which phones hold a photo whole and which only hold the small copy an update brought.
 */
internal object SimUpdates {
    const val UPDATES = "An update file opened only on the phones it was sent to, the same one twice changed nothing more, and a Helper's phone took in nothing hidden"

    private fun mark(length: Long) = "small copy of $length bytes|".toByteArray()

    /** The small copy of a photo file as the simulation makes it. Shorter than any photo the phones take; a small copy is its own small copy. */
    private fun smallCopy(f: File): ByteArray {
        val bytes = f.readBytes()
        if (isSmallCopy(bytes)) return bytes
        return mark(bytes.size.toLong()) + bytes.copyOf(minOf(16, bytes.size))
    }

    private fun isSmallCopy(bytes: ByteArray) = bytes.decodeToString(0, minOf(bytes.size, 14), throwOnInvalidSequence = false) == "small copy of "

    /** Is [f] the small copy of a photo file that was [length] bytes long? */
    fun isSmallCopyOf(f: File, length: Long): Boolean {
        val m = mark(length)
        if (f.length() != m.size + minOf(16L, length)) return false
        return f.readBytes().copyOf(m.size).contentEquals(m)
    }

    /** Small copies as the simulation makes them, for as long as it runs; [uninstall] puts back the JVM's photo as it is. */
    fun install() {
        Shrink.jpeg = { f, _, _ -> smallCopy(f) }
    }

    fun uninstall() {
        Shrink.jpeg = null
    }

    /**
     * [from] sends an update of the move it has open, Maple Road or another of its moves; [to] opens it, once or twice,
     * through its list of moves, which finds the move the update is for by its ID whichever move is in use there
     * ([Moves.openUpdate]). Only that move may change on [to]; one [to] does not hold is not added, and it says so.
     * Called between rounds of work, as syncs are.
     */
    fun pass(sim: Simulation, from: Simulation.Phone, to: Simulation.Phone, rnd: Random) {
        if (from === to) return
        val sending = from.current
        val file = from.onUi {
            try {
                ByteArrayOutputStream().also { Update.write(sending, it) }.toByteArray()
            } catch (e: IOException) {
                null.also { from.say("sent no update: ${e.message}") }
            }
        } ?: return
        val maple = sending === from.store
        val name = if (maple) "Maple Road" else sim.others[sending.moveId] ?: sending.data.value.move.name
        val sent = Update.peek(ByteArrayInputStream(file)).to
        val held = to.moves.cards.value.any { it.matches(sending.moveId) }
        val forIt = held && to.store.phone.id in sent
        from.say("sent an update of $name to ${sent.size} phones, ${to.tag} ${if (forIt) "among them" else if (held) "not among them" else "not in that move"}")
        val twice = rnd.nextInt(4) == 0
        val takeIn = rnd.nextInt(3) == 0
        var first: AppData? = null
        var promoted = false
        repeat(if (twice) 2 else 1) { n ->
            val before = to.data
            val moves = to.onUi { to.snapshot() }
            // The move the update is of, as [to] holds it (null when it holds none).
            val target = to.onUi { to.stores().firstOrNull { it.identity.value.matches(sending.identity.value) } }
            val wasHelper = target != null && target.data.value.hasOrganiser() && target.myRole == Role.HELPER
            val hiddenBefore = target?.let { hiddenOf(it.data.value) } ?: emptySet()
            val targetBefore = target?.data?.value
            val r = to.onUi { to.moves.openUpdate({ ByteArrayInputStream(file) }, takeInRemoved = takeIn) }
            val after = to.data
            val movesAfter = to.onUi { to.snapshot() }
            val targetAfter = target?.data?.value
            sim.wrote(before, after, onlyNewStamps = true)
            to.hold()
            synchronized(sim.stats) {
                val said = r.message.replace(Regex("^.* has been removed from this move, so the update was not taken in\\.$"), "(Its sender) has been removed from this move, so the update was not taken in.")
                sim.stats.notes.merge("update files opened" + (if (maple) "" else " of a move beside Maple Road") + if (r.ok) "" else ": " + SimStats.rule(said), 1, Int::plus)
            }
            to.say("opened an update of $name from ${from.tag}${if (n == 1) " again" else ""}: ${r.message}")
            // Only the move the update is of changes on the phone that opens it, whichever move it has open.
            if (movesAfter.keys != moves.keys) sim.broke(Simulation.CROSS, "${to.tag}'s list of moves changed when it opened an update of $name")
            for ((id, was) in moves) {
                if (target != null && id == target.moveId) continue
                if (movesAfter[id] != was) sim.broke(Simulation.CROSS, "${to.tag}'s ${sim.others[id] ?: "Maple Road"} changed when it opened an update of $name")
            }
            if (!held && (r.ok || r.message != Update.NOT_ON_PHONE)) sim.broke(Simulation.CROSS, "${to.tag} opened an update of $name, which it does not hold, and was told “${r.message}”")
            if (held && !forIt && targetAfter != targetBefore) sim.broke(UPDATES, "${to.tag} took in an update that was not sent to it: ${r.message}")
            if (held && !forIt && r.ok) sim.broke(UPDATES, "${to.tag} opened an update that was not sent to it")
            if (forIt && r.message == Update.DAMAGED) sim.broke(UPDATES, "${to.tag} found an update from ${from.tag} damaged")
            // A Helper's phone that the first opening told its person is no Helper now takes in, the second time, what a Helper is
            // not sent, as it would at its next sync ("Costs and decisions arrive at your next sync"). Nothing else may come.
            val onlyHidden = promoted && targetAfter != null && first != null && targetAfter.copy(
                expenses = first!!.expenses, hidden = first!!.hidden, refused = first!!.refused, collect = first!!.collect,
                photos = targetAfter.photos.filter { it.kind !in HIDDEN_FROM_HELPERS } + first!!.photos.filter { it.kind in HIDDEN_FROM_HELPERS },
                proposals = targetAfter.proposals.filter { it.kind != ProposalKind.COST } + first!!.proposals.filter { it.kind == ProposalKind.COST },
            ).let { d -> d.records().toMap() == first!!.records().toMap() && d.move == first!!.move }
            if (n == 1 && first != null && targetAfter != first && !onlyHidden) {
                val a = first!!.records().toMap()
                val b = targetAfter?.records()?.toMap().orEmpty()
                val k = (a.keys + b.keys).firstOrNull { a[it] != b[it] } ?: if (first!!.move != targetAfter?.move) Simulation.MOVE else "?"
                sim.broke(UPDATES, "opening the same update of $name twice changed ${to.tag}'s data again (“${r.message}”), first at $k:\n      once: ${a[k].toString().take(500)}\n      twice: ${b[k].toString().take(500)}", listOf(k.substringAfter(':')))
            }
            if (wasHelper && target!!.myRole == Role.HELPER && !hiddenOf(targetAfter!!).all { it in hiddenBefore }) {
                sim.broke(UPDATES, "${to.tag}, a Helper's phone, took in something a Helper is not sent from ${from.tag}'s update")
            }
            if (n == 0 && wasHelper && target!!.myRole != Role.HELPER) promoted = true
            first = targetAfter
        }
    }

    /** What a Helper is not sent that [d] holds, by record. */
    private fun hiddenOf(d: AppData): Set<String> =
        (d.expenses.map { "expense:" + it.id + "@" + it.updatedAt } + d.hidden.map { "hidden:" + it.id + "@" + it.updatedAt } +
            d.photos.filter { it.kind in HIDDEN_FROM_HELPERS }.map { "photo:" + it.id } + d.refused.map { "refused:" + it.id }).toSet()
}
