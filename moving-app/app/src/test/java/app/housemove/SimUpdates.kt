package app.housemove

import app.housemove.data.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Random

/**
 * Update files in the simulation: now and then one phone sends what it has as an update through a messaging app,
 * and another opens it, as phones that cannot meet do. It is merged with the rules of a sync, so the checks at the
 * end hold it to the same invariants. Opened by a phone it was not sent to, it must change nothing; opened twice, the
 * second time must change nothing more; a Helper's phone must take in nothing a Helper is not sent.
 */
internal object SimUpdates {
    const val UPDATES = "An update file opened only on the phones it was sent to, the same one twice changed nothing more, and a Helper's phone took in nothing hidden"

    /** [from] sends an update; [to] opens it, once or twice. Called between rounds of work, as syncs are. */
    fun pass(sim: Simulation, from: Simulation.Phone, to: Simulation.Phone, rnd: Random) {
        if (from === to) return
        val file = from.onUi {
            try {
                ByteArrayOutputStream().also { Update.write(from.store, it) }.toByteArray()
            } catch (e: IOException) {
                null.also { from.say("sent no update: ${e.message}") }
            }
        } ?: return
        val forIt = to.store.phone.id in Update.peek(ByteArrayInputStream(file)).to
        from.say("sent an update to ${Update.peek(ByteArrayInputStream(file)).to.size} phones, ${to.tag} ${if (forIt) "among them" else "not among them"}")
        val twice = rnd.nextInt(4) == 0
        val takeIn = rnd.nextInt(3) == 0
        var first: AppData? = null
        repeat(if (twice) 2 else 1) { n ->
            val before = to.data
            val wasHelper = before.hasOrganiser() && to.store.myRole == Role.HELPER
            val hiddenBefore = hiddenOf(before)
            val r = to.onUi { Update.open(to.store, ByteArrayInputStream(file), takeInRemoved = takeIn) }
            val after = to.data
            sim.wrote(before, after, onlyNewStamps = true)
            to.hold()
            synchronized(sim.stats) {
                sim.stats.notes.merge("update files opened" + if (r.ok) "" else ": " + SimStats.rule(r.message), 1, Int::plus)
            }
            to.say("opened an update from ${from.tag}${if (n == 1) " again" else ""}: ${r.message}")
            if (!forIt && after != before) sim.broke(UPDATES, "${to.tag} took in an update that was not sent to it: ${r.message}")
            if (!forIt && r.ok) sim.broke(UPDATES, "${to.tag} opened an update that was not sent to it")
            if (forIt && r.message == Update.DAMAGED) sim.broke(UPDATES, "${to.tag} found an update from ${from.tag} damaged")
            if (n == 1 && first != null && after != first) sim.broke(UPDATES, "opening the same update twice changed ${to.tag}'s data again")
            if (wasHelper && to.store.myRole == Role.HELPER && !hiddenOf(after).all { it in hiddenBefore }) {
                sim.broke(UPDATES, "${to.tag}, a Helper's phone, took in something a Helper is not sent from ${from.tag}'s update")
            }
            first = after
        }
    }

    /** What a Helper is not sent that [d] holds, by record. */
    private fun hiddenOf(d: AppData): Set<String> =
        (d.expenses.map { "expense:" + it.id + "@" + it.updatedAt } + d.hidden.map { "hidden:" + it.id + "@" + it.updatedAt } +
            d.photos.filter { it.kind in HIDDEN_FROM_HELPERS }.map { "photo:" + it.id } + d.refused.map { "refused:" + it.id }).toSet()
}
