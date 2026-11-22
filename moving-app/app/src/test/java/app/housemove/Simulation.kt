package app.housemove

import app.housemove.data.*
import app.housemove.sync.WifiSync
import java.io.File
import java.util.Random
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Seven phones in one move, each with its own Store in its own folder, doing a
 * long random sequence of things at the same time and syncing in random pairs
 * over localhost sockets. See SimulationTest for what is checked afterwards.
 *
 * A run is decided entirely by its seed. Each phone works on its own thread
 * with its own seeded choices, ids and clock; a round lets some phones work and
 * some pairs sync at once, but never lets two things touch the same Store
 * except at the fixed points of a sync that [Wire] offers. So the same seed
 * gives the same run, down to the bytes on the wire, and a failure can be
 * looked at again.
 *
 * With [loose] set the phones are let off that lead: each goes on doing things
 * on its own thread while its syncs run on others, as a person does who keeps
 * packing while the phone syncs. That finds what only real overlap finds, at
 * the price that such a run cannot be repeated exactly; the checks of each
 * single merge, which need to know exactly what went in, are left out.
 */
internal class Simulation(val seed: Long, private val root: File, private val rounds: Int = 0, val loose: Boolean = false) {

    /** Something that should never happen. [ids] are the records it is about, to pick the trace by. */
    class Violation(val invariant: String, val message: String, val ids: Set<String>)

    class Phone(val sim: Simulation, val index: Int, val tag: String, val name: String, val role: Role, val offset: Long) {
        val rnd = Random(sim.seed * 1_000_003 + index * 7919L + 17)
        private val ids = SimIds(sim.seed * 104_729 + index)
        val ui: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread({ SimIds.local.set(ids); r.run() }, "ui-$tag").apply { isDaemon = true } }
        val net: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread({ SimIds.local.set(ids); r.run() }, "net-$tag").apply { isDaemon = true } }

        /** Has this phone ever been more than a Helper's phone, under whichever person? Then it may hold what Helpers are not sent. */
        @Volatile
        var sawMore = false

        /** Every person this phone has been. It gets a new one when the move turns out never to have had the one it joined as. */
        val selves = HashSet<String>()
        val dir = File(sim.root, tag).apply { mkdirs() }
        val clock: () -> Long = { sim.time + offset }
        lateinit var store: Store
        lateinit var host: WifiSync.Host
        val heard = LinkedBlockingQueue<WifiSync.Result>()
        lateinit var hands: Hands

        /** Codes [Store.nextHandCode] gave this phone. */
        val handCodes = ArrayList<String>()

        /** Every version (by its stamp) of each record this phone has ever held. */
        val held = HashMap<String, HashSet<Long>>()
        var labelsSeen = 0
        var backup: ByteArray? = null

        /** An edit opened on a screen earlier and not saved yet. */
        var pending: (() -> String)? = null
        private var said = 0
        private val lock = Any()

        val data: AppData get() = store.data.value
        val personId: String get() = store.device.value.personId

        fun <T> onUi(f: () -> T): T = ui.submit(Callable { f() }).get(120, TimeUnit.SECONDS)

        fun say(text: String) = synchronized(lock) {
            sim.trace(this, said++, text)
        }

        fun hold(d: AppData = data) = synchronized(lock) {
            for ((k, r) in d.records()) held.getOrPut(k) { HashSet() }.add(r.updatedAt)
            held.getOrPut(MOVE) { HashSet() }.add(d.move.updatedAt)
            if (d.move.labelsPrinted > labelsSeen) labelsSeen = d.move.labelsPrinted
            if (d.person(personId)?.role.let { it != null && it != Role.HELPER }) sawMore = true
            selves += personId
        }

        /** One random action, with everything it wrote noted down. Runs on this phone's own thread. */
        fun act(note: String = "") {
            val before = data
            val text = try {
                hands.one()
            } catch (e: Exception) {
                sim.broke(RUN, "$tag threw ${e.javaClass.simpleName}: ${e.message}")
                "threw $e"
            }
            sim.wrote(before, data)
            hold()
            sim.count(text)
            say(text + note)
        }
    }

    val phones = ArrayList<Phone>()

    /** The time on a correct clock. Each phone adds its own error. Only moved between rounds. */
    @Volatile
    var time = 1_760_000_000_000L
        private set
    var round = 0
        private set

    val stats = SimStats()
    val violations = ArrayList<Violation>()
    private val lines = ArrayList<Line>()

    private class Line(val round: Int, val phone: Int, val n: Int, val text: String)

    /** Every version of every record that a phone wrote, by record. */
    val versions = HashMap<String, ArrayList<Any>>()

    /** Photo files the phones made, with their length. */
    val born = HashMap<String, Long>()

    /** Phones that could still sync with each other when everything had gone quiet. */
    var community: List<Phone> = emptyList()
        private set

    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "sim-sync").apply { isDaemon = true } }
    private val rnd = Random(seed)
    private val local = listOf("127.0.0.1")

    /**
     * A phone that is not in the move: someone who photographed a code or an invite, or a phone
     * still on 1.3. It tries what it should not be able to use, and must never get anything.
     */
    private val outsiderIds = SimIds(seed * 7_777 + 3)
    private val outsiderUi: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread({ SimIds.local.set(outsiderIds); r.run() }, "ui-X1").apply { isDaemon = true } }
    lateinit var outsider: Store
        private set

    /** How the outsider's tries went, for the report. */
    val outsiderTries = ArrayList<String>()

    // ---- notes -------------------------------------------------------------------------

    fun broke(invariant: String, message: String, ids: Collection<String?> = emptyList()) {
        synchronized(violations) { if (violations.size < 40) violations += Violation(invariant, message, ids.filterNotNull().filter { it.isNotEmpty() }.toSet()) }
    }

    private fun trace(p: Phone, n: Int, text: String) {
        synchronized(lines) { lines += Line(round, p.index, n, text) }
    }

    fun count(text: String) = synchronized(stats) {
        stats.actions++
        val why = text.substringAfter(REFUSED, "")
        if (why.isEmpty()) stats.accepted++ else stats.refusals.merge(SimStats.rule(why), 1, Int::plus)
    }

    /** Note every record that differs between [before] and [after] as a version somebody wrote. */
    fun wrote(before: AppData, after: AppData, onlyNewStamps: Boolean = false) {
        if (before === after) return
        val old = HashMap<String, Rec>()
        for ((k, r) in before.records()) old[k] = r
        synchronized(versions) {
            for ((k, r) in after.records()) {
                val o = old[k]
                if (o === r || o == r) continue
                val known = versions.getOrPut(k) { ArrayList() }
                if (onlyNewStamps && known.any { (it as Rec).updatedAt == r.updatedAt }) continue
                known += r
            }
            if (before.move != after.move) {
                val known = versions.getOrPut(MOVE) { ArrayList() }
                if (!onlyNewStamps || known.none { (it as MoveInfo).updatedAt == after.move.updatedAt }) known += after.move
            }
        }
    }

    /** The trace, in an order that is the same every time: by round, then phone, then what that phone did. */
    fun trace(about: Set<String> = emptySet(), last: Int = 60): List<String> {
        val all = synchronized(lines) { lines.sortedWith(compareBy({ it.round }, { it.phone }, { it.n })) }
        val short = about.map { it.take(8) }
        // Who joined, and every change to who is who that went through, is part of any story.
        val picked = if (short.isEmpty()) all else all.filter { l -> short.any { it in l.text } || (l.text.startsWith("!") && REFUSED !in l.text) }
        return picked.takeLast(last).map { "r%02d %s %s".format(it.round, phones[it.phone].tag, it.text.removePrefix("!")) }
    }

    /** The last things one phone did or was told, for a failure that is about that phone. */
    fun lately(p: Phone, last: Int = 8): String =
        synchronized(lines) { lines.filter { it.phone == p.index }.sortedWith(compareBy({ it.round }, { it.n })) }.takeLast(last)
            .joinToString("") { "\n      r%02d %s %s".format(it.round, p.tag, it.text.removePrefix("!").take(230)) }

    // ---- the run -----------------------------------------------------------------------

    fun run(): Simulation {
        try {
            var t0 = System.nanoTime()
            fun lap(what: String) {
                val t = System.nanoTime()
                stats.millis.merge(what, (t - t0) / 1_000_000, Long::plus)
                t0 = t
            }
            setUp(); lap("setting up")
            val n = if (rounds > 0) rounds else 36 + rnd.nextInt(20)
            repeat(n) { oneRound() }
            lap("the rounds")
            settle(); lap("syncing until quiet")
            SimChecks(this).all(); lap("checking")
        } catch (e: Throwable) {
            broke(RUN, "the run stopped: $e\n" + e.stackTrace.take(8).joinToString("\n") { "    at $it" })
        } finally {
            close()
        }
        return this
    }

    private fun close() {
        for (p in phones) {
            try {
                p.host.stop()
            } catch (_: Exception) {
            }
            try {
                p.onUi { p.store.close() }
            } catch (_: Exception) {
            }
            p.ui.shutdownNow()
            p.net.shutdownNow()
        }
        try {
            if (::outsider.isInitialized) outsiderUi.submit { outsider.close() }.get(30, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        outsiderUi.shutdownNow()
        pool.shutdownNow()
    }

    private fun <T> asOutsider(f: () -> T): T = outsiderUi.submit(Callable { f() }).get(120, TimeUnit.SECONDS)

    /**
     * The outsider tries one thing on [m]'s phone: its plain code as the screens show it on a shared
     * move (new phones kept out), a hello in the words of 1.3, or a code that is not the one shown.
     * Each must be turned away, and [m]'s data must not change.
     */
    private fun outsiderTriesOn(m: Phone) {
        val before = m.data
        val how = rnd.nextInt(3)
        val said: String = when (how) {
            0 -> {
                val heard = LinkedBlockingQueue<WifiSync.Result>()
                val h = m.onUi { WifiSync.host(m.store, m.clock, letNewPhonesIn = false) { heard.add(it) } }
                try {
                    val r = asOutsider { WifiSync.join(outsider, h.invite(local)) }
                    val hosted = heard.poll(30, TimeUnit.SECONDS)
                    if (r.ok || hosted?.ok != false) broke(OUTSIDERS, "a phone outside the move synced with ${m.tag} by its plain code: ${r.error} / ${hosted?.error}")
                    "the plain code: ${r.error}"
                } finally {
                    h.stop()
                }
            }
            else -> {
                val token = if (how == 1) m.host.token else "WRONG" + rnd.nextInt(999)
                val answer = try {
                    java.net.Socket("127.0.0.1", m.host.port).use { s ->
                        s.soTimeout = 10_000
                        val out = java.io.DataOutputStream(s.getOutputStream().buffered())
                        out.writeUTF(if (how == 1) "HMSYNC2" else "HMSYNC3"); out.writeUTF(token); out.writeUTF("Visitor"); out.writeUTF("V")
                        out.writeInt(if (how == 1) 3 else SCHEMA)
                        if (how == 2) { out.writeUTF(""); out.writeUTF("") }
                        out.flush()
                        java.io.DataInputStream(s.getInputStream()).readUTF()
                    }
                } catch (e: Exception) {
                    "nothing ($e)"
                }
                // The phone's own host noted the attempt; it is not one of the syncs the run waits for.
                m.heard.poll(30, TimeUnit.SECONDS)
                val wanted = if (how == 1) "VERSION" else "NO"
                if (answer != wanted) broke(OUTSIDERS, "${m.tag} answered a ${if (how == 1) "1.3 hello" else "wrong code"} with $answer, not $wanted")
                if (how == 1) "a 1.3 hello: $answer" else "a wrong code: $answer"
            }
        }
        if (m.data != before) broke(OUTSIDERS, "${m.tag}'s data changed when a phone outside the move tried ${said.substringBefore(':')}")
        synchronized(outsiderTries) { outsiderTries += said }
        m.say("a phone outside the move tried $said")
    }

    private fun setUp() {
        // Clocks: one phone is right, one runs minutes behind, one is a minute or two ahead, one agrees with the first to the millisecond.
        val errors = listOf(0L, 41_000L + rnd.nextInt(900), -(300_000L + rnd.nextInt(240_000)), 2_300L, -1_900L, 60_000L + rnd.nextInt(120_000), 0L)
        val who = listOf(
            Triple("O1", "Asha", Role.ORGANISER), Triple("O2", "Omar", Role.ORGANISER), Triple("D1", "Dee", Role.DECIDER), Triple("D2", "Dan", Role.DECIDER),
            Triple("H1", "Hal", Role.HELPER), Triple("H2", "Hana", Role.HELPER), Triple("H3", "Hugo", Role.HELPER),
        )
        for ((i, w) in who.withIndex()) {
            val p = Phone(this, i, w.first, w.second, w.third, errors[i])
            // A phone picks its first letter at random; here it is fixed so that the run repeats.
            File(p.dir, "device.json").writeText("""{"name":"${w.second}","letter":"${"AZZYZXZ"[i]}"}""")
            p.onUi {
                p.store = Store(p.dir, p.clock)
                p.hands = Hands(p)
            }
            // Started from the phone's own thread, so that the threads that serve its syncs take their ids from the same seeded source.
            p.host = p.onUi { WifiSync.host(p.store, p.clock) { p.heard.add(it) } }
            phones += p
        }
        val xdir = File(root, "X1").apply { mkdirs() }
        File(xdir, "device.json").writeText("""{"name":"Visitor","letter":"V"}""")
        outsider = asOutsider { Store(xdir) { time + 7_000 } }
        asOutsider { outsider.createBox("V1", null) }
        val o1 = phones[0]
        o1.onUi {
            val s = o1.store
            val before = s.data.value
            s.setDevice { it.copy(name = "Asha", letter = "A") }
            s.setMove { it.copy(name = "Maple Road", moveDay = 20_600, currency = "£", setupDone = true) }
            for (name in listOf("Kitchen", "Living room", "Main bedroom", "Garage")) {
                val to = s.createRoom(Home.DEST, name)
                s.put(s.createRoom(Home.ORIGIN, name).copy(mapsTo = to.id))
            }
            val rooms = s.data.value.roomsOf(Home.ORIGIN)
            for ((n, name) in listOf("Sofa", "Fridge", "TV", "Bed", "Bike", "Bookcase").withIndex()) s.createItem(name, rooms[n % rooms.size].id)
            s.put(Trip(id = newId(), name = "Trip 1", sort = 1))
            s.put(Task(id = newId(), title = "Book the van", offsetDays = -28))
            s.put(Task(id = newId(), title = "Buy boxes", offsetDays = -21))
            s.put(Expense(id = newId(), title = "Van hire", amount = 180.0, planned = true))
            wrote(before, s.data.value)
            o1.hold()
        }
        // Two of the phones were tried out alone before they were invited: one as it comes, one in the old "helper mode".
        for (p in listOf(phones[3], phones[5])) p.onUi {
            val s = p.store
            val before = s.data.value
            if (p.role == Role.HELPER) s.setDevice { it.copy(helper = true) }
            s.setMove { it.copy(name = "Trying it out", vanCapacityM3 = 9.0, setupDone = true) }
            val item = s.createItem("Old ${p.name} lamp", null)
            s.decide(item.id, Disposition.SELL, 12.0)
            s.createBox(s.nextHandCode().also { p.handCodes += it }, null)
            s.put(Task(id = newId(), title = "${p.name}'s own list"))
            s.put(Expense(id = newId(), title = "${p.name}'s own cost", amount = 5.0))
            wrote(before, s.data.value)
            p.hold()
        }
        // Everyone comes in through an invite. Omar first; after that either Organiser adds and invites, without the two syncing in between.
        invite(o1, phones[1])
        for (p in phones.drop(2).shuffled(rnd)) {
            time += 500 + rnd.nextInt(4000)
            invite(if (rnd.nextBoolean()) o1 else phones[1], p)
        }
    }

    private fun invite(by: Phone, p: Phone) {
        val person = by.onUi {
            val before = by.data
            val o = by.store.addPerson(p.name, p.role)
            wrote(before, by.data)
            o.valueOrNull() ?: throw IllegalStateException("${by.tag} could not add ${p.tag}: ${o.reason}")
        }
        val invite = by.host.inviteFor(person.id, local).valueOrNull() ?: throw IllegalStateException("no invite for ${p.tag}")
        val beforeJoin = p.data
        val beforeHost = by.data
        val r = p.net.submit(Callable { WifiSync.join(p.store, invite) }).get(60, TimeUnit.SECONDS)
        val hosted = by.heard.poll(40, TimeUnit.SECONDS)
        if (!r.ok || hosted?.ok != true) throw IllegalStateException("${p.tag} could not join ${by.tag}: ${r.error} / ${hosted?.error}")
        // Joining stamps the person on both phones; those are writes too.
        wrote(beforeJoin, p.data, onlyNewStamps = true)
        wrote(beforeHost, by.data, onlyNewStamps = true)
        p.hold(); by.hold()
        p.say("!joined as ${p.name} (${p.role.label}, letter ${p.store.device.value.letter}) from ${by.tag}'s invite")
        if (p.store.myRole != p.role) broke(RUN, "${p.tag} joined as ${p.store.myRole}, not ${p.role}")
        // Someone who photographed the invite tries it after it has been used.
        val hostWas = by.data
        val again = asOutsider { WifiSync.join(outsider, invite) }
        val told = by.heard.poll(40, TimeUnit.SECONDS)
        if (again.error != WifiSync.INVITE_EXPIRED || told?.error != WifiSync.INVITE_EXPIRED) broke(OUTSIDERS, "an invite already used by ${p.tag} was tried again: ${again.error} / ${told?.error}")
        if (by.data != hostWas) broke(OUTSIDERS, "${by.tag}'s data changed when its used invite was tried again")
        synchronized(outsiderTries) { outsiderTries += "an invite already used: ${again.error}" }
    }

    private fun oneRound() {
        round++
        time += 20 + rnd.nextInt(if (rnd.nextInt(6) == 0) 40_000 else 2_500)
        if (rnd.nextInt(10) == 0) outsiderTriesOn(phones[rnd.nextInt(phones.size)])
        val free = phones.shuffled(rnd).toMutableList()
        val jobs = ArrayList<Future<*>>()
        repeat(listOf(0, 1, 1, 1, 2, 2, 3)[rnd.nextInt(7)]) {
            if (free.size < 2) return@repeat
            val h = free.removeAt(0)
            val j = free.removeAt(0)
            val cut = if (rnd.nextInt(6) == 0) Wire.Cut(1 + rnd.nextInt(8), listOf(0, 1, 7, 60, 900, 5000, 30_000)[rnd.nextInt(7)]) else null
            val busy = rnd.nextInt(3) == 0
            val plan = rnd.nextLong()
            val retry = cut != null && rnd.nextInt(4) != 0
            jobs += pool.submit { sync(h, j, cut, busy, Random(plan), retry) }
        }
        for (p in if (loose) phones else free) {
            if (rnd.nextInt(5) == 0) continue
            val n = 1 + rnd.nextInt(if (loose) 8 else 4)
            jobs += p.ui.submit { repeat(n) { p.act() } }
        }
        for (j in jobs) j.get(180, TimeUnit.SECONDS)
    }

    /** Everyone syncs with everyone, round after round, until a whole round changes nothing anywhere. */
    private fun settle() {
        val pairs = ArrayList<Pair<Phone, Phone>>()
        for (a in phones) for (b in phones) if (a.index < b.index) pairs += a to b
        var quiet = false
        var met = HashMap<Pair<Int, Int>, Boolean>()
        for (turn in 1..8) {
            round++
            time += 1000
            val before = phones.map { it.data }
            met = HashMap()
            for ((a, b) in pairs.shuffled(rnd)) {
                val (h, j) = if (rnd.nextBoolean()) a to b else b to a
                met[a.index to b.index] = sync(h, j, null, false, rnd, retry = false)
            }
            if (phones.indices.all { phones[it].data == before[it] }) {
                quiet = true
                break
            }
        }
        if (!quiet) broke(RUN, "the phones were still changing each other's data after 8 rounds of everyone syncing with everyone")
        // The move is whoever can still sync with each other: the largest such group (the one with the first phone, if two are as large).
        val group = IntArray(phones.size) { it }
        fun find(x: Int): Int = if (group[x] == x) x else find(group[x]).also { group[x] = it }
        for ((k, ok) in met) if (ok) group[find(k.first)] = find(k.second)
        val groups = phones.groupBy { find(it.index) }.values
        community = groups.maxWithOrNull(compareBy({ it.size }, { -it.minOf { p -> p.index } })) ?: emptyList()
        for (p in phones) p.say("!at the end: ${if (p in community) "in the move" else "outside the move"}, ${p.store.myRole.label}")
    }

    // ---- one sync ----------------------------------------------------------------------

    /** What each side had and got in a sync, for checking the merge itself. */
    class Exchange(val host: Phone, val joiner: Phone) {
        var hostBefore: AppData? = null
        var hostAfter: AppData? = null
        var joinerBefore: AppData? = null
        var joinerAfter: AppData? = null
        var hostAtStart: AppData? = null
        var joinerAtStart: AppData? = null

        /** The joiner's data as it stood when it sent it. */
        var joinerSent: AppData? = null

        /** Who each phone was just before its merge. A merge can leave a phone with a stand-in for a person that did not count. */
        var hostWas = ""
        var joinerWas = ""
        var quiet = true
    }

    /** [j] scans [h]'s code. Returns true when both phones say it worked. */
    fun sync(h: Phone, j: Phone, cut: Wire.Cut?, busy: Boolean, plan: Random, retry: Boolean): Boolean {
        val x = Exchange(h, j)
        x.hostAtStart = h.data
        x.joinerAtStart = j.data
        fun meanwhile(p: Phone, where: String) {
            if (loose || !busy || plan.nextInt(100) >= 30) return
            x.quiet = false
            synchronized(stats) { stats.midSync++ }
            p.onUi { repeat(1 + plan.nextInt(2)) { p.act(" [while syncing with ${(if (p === h) j else h).tag}: $where]") } }
        }
        val wire = Wire(h.host.port, cut) { phase ->
            when (phase) {
                2 -> { meanwhile(h, "before any data"); meanwhile(j, "before any data") }
                3 -> { x.joinerSent = j.data; meanwhile(j, "after sending its data"); meanwhile(h, "before merging"); x.hostBefore = h.data; x.hostWas = h.personId }
                4 -> { x.hostAfter = h.data; meanwhile(h, "after merging"); meanwhile(j, "before merging"); x.joinerBefore = j.data; x.joinerWas = j.personId }
                5 -> { x.joinerAfter = j.data; meanwhile(j, "after merging"); meanwhile(h, "after merging") }
                6 -> { meanwhile(h, "before the photos"); meanwhile(j, "before the photos") }
                // While a phone is sending photo files it reads them from disk, so only the receiving one does things.
                7 -> meanwhile(h, "while receiving photos")
                8 -> meanwhile(j, "while receiving photos")
            }
        }
        val r: WifiSync.Result
        val hosted = ArrayList<WifiSync.Result>()
        try {
            r = j.net.submit(Callable { WifiSync.join(j.store, h.host.invite(local).copy(port = wire.port)) }).get(120, TimeUnit.SECONDS)
            repeat(wire.connections) { hosted += h.heard.poll(60, TimeUnit.SECONDS) ?: throw IllegalStateException("${h.tag} never finished serving ${j.tag}") }
        } finally {
            wire.close()
        }
        val ok = r.ok && hosted.size == 1 && hosted[0].ok
        synchronized(stats) {
            stats.syncs++
            if (wire.cutAt > 0) stats.cut++
            if (ok) stats.syncsOk++
            for (e in (listOf(r.error) + hosted.map { it.error }).filterNotNull().toSet()) stats.syncErrors.merge(SimStats.rule(e), 1, Int::plus)
        }
        val c0 = System.nanoTime()
        if (!loose) SimChecks(this).exchange(x, wire, ok)
        synchronized(stats) { stats.millis.merge("(of which checking each merge)", (System.nanoTime() - c0) / 1_000_000, Long::plus) }
        // Joining stamps nothing in an ordinary sync, but note any new stamp all the same.
        wrote(x.hostAtStart!!, h.data, onlyNewStamps = true)
        wrote(x.joinerAtStart!!, j.data, onlyNewStamps = true)
        h.hold(); j.hold()
        val how = when {
            ok -> "ok, ${r.photosSent} photos out, ${r.photosReceived} in"
            wire.cutAt > 0 -> "cut off in phase ${wire.cutAt}: ${r.error ?: "the joiner noticed nothing"} / ${hosted.firstOrNull()?.error ?: "the host noticed nothing"}"
            else -> "refused: ${r.error} / ${hosted.firstOrNull()?.error}"
        }
        j.say("synced with ${h.tag} (host): $how")
        h.say("hosted a sync with ${j.tag}: $how")
        if (!ok && retry) {
            synchronized(stats) { stats.retried++ }
            return sync(h, j, null, false, plan, retry = false)
        }
        return ok
    }

    companion object {
        const val MOVE = "move:"
        const val REFUSED = "→ no: "
        const val RUN = "The run finished and the phones went quiet"
        const val OUTSIDERS = "A phone outside the move (a used or photographed code, a wrong code, version 1.3) got nothing and changed nothing"
    }
}

/** Seeded ids, one source per phone thread, so that a run can be repeated exactly. Other threads get random ids as usual. */
internal class SimIds(seed: Long) {
    private val rnd = Random(seed)

    @Synchronized
    fun next(): String = UUID(rnd.nextLong(), rnd.nextLong()).toString()

    companion object {
        /** Inherited, so that the threads a phone's sync host starts for its visitors belong to that phone too. */
        val local = InheritableThreadLocal<SimIds?>()
        private val random: () -> String = { UUID.randomUUID().toString() }
        fun install() {
            idSource = { local.get()?.next() ?: random() }
        }

        fun uninstall() {
            idSource = random
        }
    }
}

/** What happened over one run or many. */
internal class SimStats {
    var actions = 0
    var accepted = 0
    var midSync = 0
    var syncs = 0
    var syncsOk = 0
    var cut = 0
    var retried = 0
    val refusals = TreeMap<String, Int>()
    val syncErrors = TreeMap<String, Int>()
    val notes = TreeMap<String, Int>()

    /** Where the time went. */
    val millis = TreeMap<String, Long>()

    fun add(o: SimStats) {
        actions += o.actions; accepted += o.accepted; midSync += o.midSync; syncs += o.syncs; syncsOk += o.syncsOk; cut += o.cut; retried += o.retried
        for ((k, v) in o.refusals) refusals.merge(k, v, Int::plus)
        for ((k, v) in o.syncErrors) syncErrors.merge(k, v, Int::plus)
        for ((k, v) in o.notes) notes.merge(k, v, Int::plus)
        for ((k, v) in o.millis) millis.merge(k, v, Long::plus)
    }

    companion object {
        /** A refusal with the names taken out, so that the same rule counts as one. */
        fun rule(reason: String): String = reason
            .replace(Regex("^.* already uses the letter .*$"), "Someone else already uses that letter.")
            .replace(Regex("^.* has been removed from this move, so their phone was not synced\\.$"), "(Their phone) has been removed from this move, so their phone was not synced.")
            .replace(Regex("^.* has been removed from this move, so this phone did not sync with theirs\\.$"), "(They) have been removed from this move, so this phone did not sync with theirs.")
            .replace(Regex("^.* has been removed from the move\\.$"), "(They) have been removed from the move.")
            .replace(Regex("^.* is already inside .*\\. Containers only go one inside another\\.$"), "(That container) is already inside another. Containers only go one inside another.")
            .replace(Regex("^.* has containers inside it, so it cannot go inside another\\.$"), "(That container) has containers inside it, so it cannot go inside another.")
            .replace(Regex("^.* (has|have) worked on it since you added it, (.*)$"), "(Someone else) has worked on it since you added it, $2")
            .take(160)
    }
}

/** Every record of a dataset with a key that says what kind it is. */
internal fun AppData.records(): Sequence<Pair<String, Rec>> = sequence {
    for (r in rooms) yield("room:" + r.id to r)
    for (r in items) yield("item:" + r.id to r)
    for (r in boxes) yield("box:" + r.id to r)
    for (r in photos) yield("photo:" + r.id to r)
    for (r in tasks) yield("task:" + r.id to r)
    for (r in expenses) yield("expense:" + r.id to r)
    for (r in supplies) yield("supply:" + r.id to r)
    for (r in trips) yield("trip:" + r.id to r)
    for (r in readings) yield("reading:" + r.id to r)
    for (r in entries) yield("entry:" + r.id to r)
    for (r in people) yield("person:" + r.id to r)
    for (r in proposals) yield("proposal:" + r.id to r)
    for (r in activity) yield("activity:" + r.id to r)
    for (r in hidden) yield("hidden:" + r.id to r)
    for (r in collect) yield("collect:" + r.id to r)
    for (r in damage) yield("damage:" + r.id to r)
    for (r in lines) yield("line:" + r.id to r)
    for (r in refused) yield("refused:" + r.id to r)
}
