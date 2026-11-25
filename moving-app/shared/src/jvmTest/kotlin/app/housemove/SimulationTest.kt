package app.housemove

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Seven phones (two Organisers, two Deciders, three Helpers) in one move, each
 * with its own Store, clock and thread, doing long random sequences of things
 * and syncing in random pairs over localhost. Afterwards everyone syncs with
 * everyone until nothing changes, and the invariants in [SimChecks] are checked.
 *
 * The normal test run does a handful of seeds. `tools/simulate.sh 300` does as
 * many as asked and writes build/audit/simulation.md. A failure prints its
 * seed, and the same seed always gives the same run: `tools/simulate.sh 1 <seed>`.
 */
class SimulationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    companion object {
        @JvmStatic
        @BeforeClass
        fun install() {
            SimIds.install()
            SimBytes.install()
            SimUpdates.install()
        }

        @JvmStatic
        @AfterClass
        fun uninstall() {
            SimIds.uninstall()
            SimBytes.uninstall()
            SimUpdates.uninstall()
        }
    }

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private class Outcome(val seed: Long, val stats: SimStats, val failures: List<Simulation.Violation>, val traces: List<List<String>>, val whole: List<String>)

    private fun runSeed(seed: Long, folder: String = "seed$seed", loose: Boolean = false): Outcome {
        val dir = File(tmp.root, folder).apply { mkdirs() }
        val sim = Simulation(seed, dir, loose = loose).run()
        dir.deleteRecursively()
        return Outcome(seed, sim.stats, sim.violations.toList(), sim.violations.map { sim.trace(it.ids, last = 30) }, sim.trace(last = 100_000))
    }

    @Test
    fun sevenPhonesDoRandomThingsAndEndUpAgreeing() {
        val first = env("HM_SIM_FIRST")?.toLong() ?: 1L
        val count = env("HM_SIM_SEEDS")?.toInt() ?: 5
        val jobs = env("HM_SIM_JOBS")?.toInt() ?: 1
        val started = System.currentTimeMillis()
        val pool = Executors.newFixedThreadPool(jobs)
        val outcomes = try {
            (first until first + count).map { seed -> pool.submit(Callable { runSeed(seed) }) }.map { it.get() }
        } finally {
            pool.shutdownNow()
        }
        val seconds = (System.currentTimeMillis() - started) / 1000
        val failed = outcomes.filter { it.failures.isNotEmpty() }

        val text = StringBuilder()
        for (o in failed.take(6)) {
            text.append("\n\nSEED ${o.seed} (run it again with: tools/simulate.sh 1 ${o.seed})")
            for ((n, v) in o.failures.take(4).withIndex()) {
                text.append("\n  BROKEN: ${v.invariant}\n    ${v.message}\n    what led to it:")
                for (line in o.traces[n]) text.append("\n      ").append(line.take(260))
            }
            if (o.failures.size > 4) text.append("\n  and ${o.failures.size - 4} more")
        }
        if (env("HM_SIM_REPORT") != null) report(first, count, seconds, outcomes, text.toString())
        env("HM_SIM_TRACE")?.let { File(it).writeText(outcomes.joinToString("\n\n") { o -> "SEED ${o.seed}\n" + o.whole.joinToString("\n") }) }
        if (failed.isNotEmpty()) fail("${failed.size} of $count seeds broke an invariant: seeds ${failed.joinToString { it.seed.toString() }}$text")
    }

    /**
     * The same, with the phones let off the lead: each keeps doing things on its own thread while its syncs run.
     * Real overlap cannot be repeated exactly, so a failure here names its seed but may not come back on the next run.
     */
    @Test
    fun phonesThatKeepWorkingWhileTheySyncStillEndUpAgreeing() {
        // HM_SIM_LOOSE_SEEDS names the seeds instead ("161,161,181"): a failure seen once is looked for again by repeating its seed.
        val listed = env("HM_SIM_LOOSE_SEEDS")?.split(',')?.mapNotNull { it.trim().toLongOrNull() }
        val first = listed?.firstOrNull() ?: env("HM_SIM_FIRST")?.toLong() ?: 1L
        val seeds = listed ?: (first until first + (env("HM_SIM_LOOSE")?.toInt() ?: 2)).toList()
        val count = seeds.size
        // HM_SIM_LOOSE_JOBS runs that many of these seeds at once (one at a time unless asked), to run many in less time.
        val jobs = env("HM_SIM_LOOSE_JOBS")?.toInt() ?: 1
        val pool = Executors.newFixedThreadPool(jobs)
        val outcomes = try {
            seeds.mapIndexed { n, seed -> pool.submit(Callable { runSeed(seed, "loose$seed-$n", loose = true) }) }.map { it.get() }
        } finally {
            pool.shutdownNow()
        }
        env("HM_SIM_TRACE")?.let { File("$it.loose").writeText(outcomes.filter { it.failures.isNotEmpty() }.joinToString("\n\n") { o -> "SEED ${o.seed}\n" + o.failures.joinToString("") { "BROKEN ${it.invariant}: ${it.message}\n" } + o.whole.joinToString("\n") }) }
        val failed = outcomes.filter { it.failures.isNotEmpty() }
        val text = StringBuilder()
        for (o in failed.take(4)) {
            text.append("\n\nSEED ${o.seed}, phones working while they sync")
            for ((n, v) in o.failures.take(4).withIndex()) {
                text.append("\n  BROKEN: ${v.invariant}\n    ${v.message}\n    what led to it:")
                for (line in o.traces[n]) text.append("\n      ").append(line.take(260))
            }
        }
        if (env("HM_SIM_REPORT") != null) {
            val all = SimStats().also { s -> outcomes.forEach { s.add(it.stats) } }
            File("build/audit").mkdirs()
            File("build/audit/simulation-loose.md").writeText(
                "\n## Phones that keep working while they sync\n\n" +
                    "$count more seeds (${if (listed != null) "seeds " + listed.distinct().joinToString() else "from $first"}) were run with every phone doing things on its own thread while its syncs were under way: " +
                    "${all.actions} actions, ${all.syncs} syncs (${all.syncsOk} completed). The checks made at the end are the same; " +
                    "the check of each single merge is left out, since what went into it is not known exactly. " +
                    (if (failed.isEmpty()) "All invariants held." else "FAILED on seeds ${failed.joinToString { it.seed.toString() }}.") +
                    "\n" + (if (text.isNotBlank()) "\n```\n${text.trim()}\n```\n" else ""),
            )
        }
        if (failed.isNotEmpty()) fail("${failed.size} of $count seeds broke an invariant with phones working while they synced: seeds ${failed.joinToString { it.seed.toString() }}$text")
    }

    /** The promise that a failure can be looked at again: one seed, run twice, does exactly the same things. */
    @Test
    fun theSameSeedGivesTheSameRun() {
        val a = runSeed(7_001, "once")
        val b = runSeed(7_001, "again")
        assertEquals(a.whole.joinToString("\n"), b.whole.joinToString("\n"))
        assertEquals(a.stats.actions, b.stats.actions)
        assertEquals(a.failures.map { it.message }, b.failures.map { it.message })
    }

    private fun report(first: Long, count: Int, seconds: Long, outcomes: List<Outcome>, failures: String) {
        val all = SimStats().also { s -> outcomes.forEach { s.add(it.stats) } }
        val md = StringBuilder()
        md.append("# Seven-phone simulation\n\n")
        md.append("Two Organisers, two Deciders and three Helpers in one move, each phone with its own list of moves, clock and thread, ")
        md.append("syncing in random pairs over localhost sockets, a third of the time by themselves (found on the Wi-Fi, with no code). The phones run different versions (2.2, 2.3 and 2.4): two on 2.4 sync encrypted, every other pair as the older phone speaks. Some phones also keep a move of their own (one tried out alone before it ")
        md.append("joined, or one started mid-run) or join another phone's, switch between their moves, scan labels found in more than one, ")
        md.append("and sync or scan codes from whichever move they have open. Made by `tools/simulate.sh`.\n\n")
        md.append("| | |\n| --- | --- |\n")
        md.append("| Seeds run | $count (seeds $first to ${first + count - 1}), in $seconds s |\n")
        md.append("| Seeds that broke an invariant | ${outcomes.count { it.failures.isNotEmpty() }} |\n")
        md.append("| Actions performed | ${all.actions}: ${all.accepted} went through, ${all.actions - all.accepted} were refused or left unchanged by the rules |\n")
        md.append("| Actions done in the middle of a sync | ${all.midSync} |\n")
        md.append("| Syncs | ${all.syncs}: ${all.syncsOk} completed, ${all.cut} cut off part-way (${all.retried} of those retried at once), ${all.syncs - all.syncsOk - all.cut} refused |\n")
        md.append("| Syncs with another move open on one phone or both | ${all.crossSyncs} |\n")
        md.append("| Syncs by themselves (with no code, besides those above) | ${all.autoSyncs}: ${all.autoOk} went through, ${all.autoCut} cut off part-way, ${all.autoRefused} refused at the handshake (a removed person's phone, or a phone with no key it shares); ${all.autoCross} more tried with another move open on one phone or both |\n")
        md.append("| Versions of HouseMove on the phones | ${all.versions.entries.joinToString(", ") { "${it.value} on ${it.key / 10}.${it.key % 10}" }} |\n")
        md.append("| Encrypted (2.4 with 2.4) | ${all.encrypted} code syncs and ${all.autoSyncs - all.auto23} syncs by themselves; unencrypted, with an older phone: ${all.plain} code syncs, and ${all.auto23} syncs by themselves between two 2.3 phones |\n")
        md.append("\n## Invariants\n\n| Invariant | Result |\n| --- | --- |\n")
        for (name in SimChecks.ALL) {
            val seeds = outcomes.filter { o -> o.failures.any { it.invariant == name } }.map { it.seed }
            md.append("| $name | ${if (seeds.isEmpty()) "pass" else "FAILED on seed${if (seeds.size == 1) "" else "s"} " + seeds.joinToString()} |\n")
        }
        md.append("\n## Refusals by rule\n\nWhat the Store said when it did not do what a phone asked.\n\n| Times | Rule |\n| ---: | --- |\n")
        for ((rule, n) in all.refusals.entries.sortedByDescending { it.value }) md.append("| $n | ${rule.replace("|", "/")} |\n")
        md.append("\n## Syncs that did not complete\n\nWhat either phone showed. One sync can show two messages, one on each phone.\n\n| Times | Message |\n| ---: | --- |\n")
        for ((rule, n) in all.syncErrors.entries.sortedByDescending { it.value }) md.append("| $n | ${rule.replace("|", "/")} |\n")
        if (all.notes.isNotEmpty()) {
            md.append("\n## Seen along the way (not failures)\n\n| Times | What |\n| ---: | --- |\n")
            for ((what, n) in all.notes.entries.sortedByDescending { it.value }) md.append("| $n | $what |\n")
        }
        md.append("\nTime per seed: " + all.millis.entries.joinToString(", ") { "${it.key} ${it.value / count} ms" } + ".\n")
        if (failures.isNotBlank()) md.append("\n## Failures\n\n```\n").append(failures.trim()).append("\n```\n")
        File("build/audit").mkdirs()
        File("build/audit/simulation.md").writeText(md.toString())
    }
}
