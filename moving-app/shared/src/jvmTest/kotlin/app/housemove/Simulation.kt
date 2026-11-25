package app.housemove

import app.housemove.data.*
import app.housemove.sync.AutoListener
import app.housemove.sync.AutoSession
import app.housemove.sync.AutoSlot
import app.housemove.sync.Meetings
import app.housemove.sync.Strong
import app.housemove.sync.WifiSync
import app.housemove.sync.memberPhone
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
 * Each phone keeps its moves as the app does, in a list of moves ([Moves]): it
 * starts or joins Maple Road through that list, and some phones keep or start
 * another move beside it ("Storage", another family's move) or join one another
 * phone started. They switch between their moves, work in whichever is open,
 * scan labels found in more than one, and sync or scan codes from the move they
 * have open; the checks see that the moves stay apart.
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
        private val bytes = Random(sim.seed * 7_919 + index * 31L + 5)
        val ui: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread({ SimIds.local.set(ids); SimBytes.local.set(bytes); r.run() }, "ui-$tag").apply { isDaemon = true } }
        val net: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread({ SimIds.local.set(ids); SimBytes.local.set(bytes); r.run() }, "net-$tag").apply { isDaemon = true } }

        /** Syncing by itself: this phone's listening socket, for the move it has open, and its one sync by itself at a time. */
        lateinit var auto: AutoListener
        val slot = AutoSlot()

        /** What the listener did with each connection, in turn: what was synced, if anything ([Heard.answered] null: nothing). */
        val autoHeard = LinkedBlockingQueue<Heard>()

        @Volatile
        var lastAnswer: AutoSession.Answered? = null

        val phoneId: String get() = store.phone.id

        /** Has this phone ever been more than a Helper's phone, under whichever person? Then it may hold what Helpers are not sent. */
        @Volatile
        var sawMore = false

        /** Every person this phone has been. It gets a new one when the move turns out never to have had the one it joined as. */
        val selves = HashSet<String>()
        val dir = File(sim.root, tag).apply { mkdirs() }
        val clock: () -> Long = { sim.time + offset }

        /** The moves on this phone, kept as the app keeps them ([Moves]): Maple Road, and any other it starts or joins. */
        lateinit var moves: Moves

        /** Maple Road on this phone: the move the run is about, and the one its checks look at. */
        lateinit var store: Store
        lateinit var host: WifiSync.Host

        /** The move in use on this phone, the one its person works on: Maple Road, or another of its moves. */
        val current: Store get() = moves.current.value ?: store
        val inMaple: Boolean get() = current === store

        /** What this phone brought from a move of its own into Maple Road when it joined ("Bring your things"): in both moves since. */
        val brought = HashSet<String>()
        val broughtFiles = HashSet<String>()

        /** The move the last backup this phone made is of. */
        var backupOf = ""

        val opened: Boolean get() = ::moves.isInitialized

        /** The phone's list of moves, opened from its folders as the app does at start. */
        fun openMoves(): Moves = Moves(File(dir, "files"), File(dir, "nobackup"), { "$name's phone" }, clock)

        /** Every move's Store on this phone, in the list's order. Call on the phone's own thread. */
        fun stores(): List<Store> = moves.cards.value.mapNotNull { moves.store(it.moveId) }

        /** What each move on the phone holds, by move ID: its data and its photo files. Call on the phone's own thread. */
        fun snapshot(): Map<String, Pair<AppData, Set<String>>> =
            stores().associate { it.moveId to (it.data.value to (it.photoDir.list()?.toSet() ?: emptySet())) }
        val heard = LinkedBlockingQueue<WifiSync.Result>()
        lateinit var hands: Hands

        /** Codes [Store.nextHandCode] gave this phone, each with the move it was given in: as "move ID/code". */
        val handCodes = ArrayList<String>()

        /** Every version (by its stamp) of each record this phone has ever held. */
        val held = HashMap<String, HashSet<Long>>()
        var labelsSeen = 0
        var backup: ByteArray? = null

        /**
         * This phone let go of [key], and another phone holds its copy from [upTo]: the versions this phone held up to
         * then are that phone's to keep from here on, not this one's (see [Simulation.handedOver]).
         */
        fun gaveAway(key: String, upTo: Long) = synchronized(lock) {
            val mine = held[key] ?: return@synchronized
            mine.removeIf { it <= upTo }
            if (mine.isEmpty()) held.remove(key)
        }

        /** An edit opened on a screen earlier and not saved yet. */
        var pending: (() -> String)? = null

        /** The move whose screen that edit is on. Opening another move replaces every screen, and the edit with it. */
        var pendingIn: Store? = null
        private var said = 0
        private val lock = Any()

        val data: AppData get() = store.data.value
        val personId: String get() = store.device.value.personId

        fun <T> onUi(f: () -> T): T = ui.submit(Callable { f() }).get(120, TimeUnit.SECONDS)

        fun say(text: String) = synchronized(lock) {
            sim.trace(this, said++, text)
        }

        fun hold(d: AppData = data, s: Store = store) = synchronized(lock) {
            val me = s.device.value.personId
            for ((k, r) in d.records()) held.getOrPut(k) { HashSet() }.add(r.updatedAt)
            held.getOrPut(MOVE) { HashSet() }.add(d.move.updatedAt)
            if (d.move.labelsPrinted > labelsSeen) labelsSeen = d.move.labelsPrinted
            if (d.person(me)?.role.let { it != null && it != Role.HELPER }) sawMore = true
            selves += me
        }

        /**
         * One random action in the move in use, with everything it wrote to Maple Road noted down. Runs on this phone's
         * own thread. What is done in another move is in the trace with that move's name before it.
         */
        fun act(note: String = "") {
            val before = data
            val elsewhere = current.takeIf { it !== store }?.let { "[" + it.data.value.move.name.ifBlank { "another move" } + "] " } ?: ""
            val text = try {
                hands.one()
            } catch (e: Exception) {
                sim.broke(RUN, "$tag threw ${e.javaClass.simpleName}: ${e.message}")
                "threw $e"
            }
            sim.wrote(before, data)
            hold()
            sim.count(text)
            say(elsewhere + text + note)
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

    /**
     * A Helper's phone lets go of what Helpers are not sent once a full sync has given it all to an Organiser's or a
     * Decider's phone ([Store.fullSyncDone]). Of those records, what [p] held at [before] and no longer holds after a
     * sync with [peer] is from then on [peer]'s to keep, as far as [peer]'s phone holds it: the versions [p] held up to
     * the one [peer] holds count as [peer]'s, not [p]'s, when the end is checked. So when [peer] leaves the move without
     * passing them on, they go with it, as everything else that only its phone held does. Anything [p] let go that
     * [peer] does not hold stays [p]'s, and the end of the run checks it as before: kept, or rightly left out.
     */
    private fun handedOver(p: Phone, before: AppData, peer: Phone) {
        val after = p.data
        if (!after.hasOrganiser() || after.roleOf(p.store.device.value) != Role.HELPER) return
        val to = after.person(peer.personId)?.takeIf { !it.removed }?.role
        if (to != Role.ORGANISER && to != Role.DECIDER) return
        val still = HashSet<String>()
        for ((k, _) in after.records()) still += k
        var theirs: Map<String, Long>? = null
        for ((k, r) in before.records()) {
            if (!hidden(r) || k in still) continue
            val now = theirs ?: HashMap<String, Long>().also { m -> for ((key, x) in peer.data.records()) m[key] = x.updatedAt }.also { theirs = it }
            p.gaveAway(k, now[k] ?: continue)
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
                p.auto.stop()
            } catch (_: Exception) {
            }
            try {
                p.onUi { if (p.opened) p.stores().forEach { it.close() } }
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
        val how = rnd.nextInt(5)
        val said: String = when (how) {
            3, 4 -> outsiderTriesByItself(m, how == 4)
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
        // This seed's phones may have been played before in this process (the same seed, run again): their meetings, which
        // are kept in memory for ten minutes, are not this run's.
        Meetings.forget { it.endsWith("-$seed-phone") && it.startsWith("psim-") }
        for ((i, w) in who.withIndex()) {
            val p = Phone(this, i, w.first, w.second, w.third, errors[i])
            // Each phone keeps its moves as the app does ([Moves]). Its phone ID is made at random the first time; here it is
            // fixed so that the run repeats. The name its person goes by is in the phone's own settings.
            File(p.dir, "nobackup").mkdirs()
            File(p.dir, "nobackup/phone-id").writeText("psim-$i-$seed-phone")
            File(p.dir, "files").mkdirs()
            File(p.dir, "files/phone.json").writeText("""{"name":"${w.second}"}""")
            p.onUi {
                p.moves = p.openMoves()
                p.hands = Hands(p)
                // Started on the phone's own thread, so that the threads that answer take its seeded ids and bytes.
                p.auto = AutoListener(true, { p.moves.current.value }, p.slot, {}, { _, a -> p.lastAnswer = a }) {
                    p.autoHeard.add(Heard(p.lastAnswer.also { p.lastAnswer = null }))
                }.also { it.start() }
            }
            phones += p
        }
        val xdir = File(root, "X1").apply { mkdirs() }
        File(xdir, "device.json").writeText("""{"name":"Visitor","letter":"V"}""")
        outsider = asOutsider { Store(xdir) { time + 7_000 } }
        asOutsider { outsider.createBox("V1", null) }
        val o1 = phones[0]
        o1.onUi {
            val s = o1.moves.create("Maple Road", yourName = "Asha")
            o1.store = s
            val before = s.data.value
            // A new move's phone takes a letter at random; here it is fixed so that the run repeats.
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
        // Started from the phone's own thread, so that the threads that serve its syncs take their ids from the same seeded source.
        o1.host = o1.onUi { WifiSync.host(o1.store, o1.clock) { o1.heard.add(it) } }
        // Two of the phones were tried out alone before they were invited: one as it comes, one in the old "helper mode".
        // The first brings its things into Maple Road when it joins ("Bring your things?"); the second does or does not.
        // A phone that does not keeps its own move beside Maple Road.
        bringing = setOf(3) + if (rnd.nextBoolean()) setOf(5) else emptySet()
        for (p in listOf(phones[3], phones[5])) p.onUi {
            val s = p.moves.create("Trying it out")
            s.setDevice { it.copy(letter = "${"AZZYZXZ"[p.index]}") }
            val before = s.data.value
            if (p.role == Role.HELPER) s.setDevice { it.copy(helper = true) }
            s.setMove { it.copy(name = "Trying it out", vanCapacityM3 = 9.0, setupDone = true) }
            val item = s.createItem("Old ${p.name} lamp", null)
            s.decide(item.id, Disposition.SELL, 12.0)
            val ends = if (p.index in bringing) o1.store.moveId else s.moveId
            s.createBox(s.nextHandCode().also { p.handCodes += "$ends/$it" }, null)
            s.put(Task(id = newId(), title = "${p.name}'s own list"))
            s.put(Expense(id = newId(), title = "${p.name}'s own cost", amount = 5.0))
            if (p.index in bringing) {
                wrote(before, s.data.value)
                p.hold(s.data.value, s)
            }
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
        // The phone's own move, if it was tried out alone: what it brings comes from there.
        val own = p.moves.current.value
        val bring = p.index in bringing
        val beforeJoin = if (bring) own!!.data.value else AppData()
        val ownFiles = own?.photoDir?.list()?.toSet() ?: emptySet()
        val beforeHost = by.data
        // As the app joins: the invite is read first ("Join Maple Road as Dan, Decider?"), then the phone's list of moves joins it.
        val r = p.net.submit(Callable {
            val check = p.moves.readInvite(invite.encode()) ?: throw IllegalStateException("${p.tag} could not read its invite")
            if (check.card != null) broke(MOVES_APART, "${p.tag} was told it already holds Maple Road before it ever joined it")
            if ((check.bringOffer != null) != (own != null)) broke(MOVES_APART, "${p.tag} was ${if (own == null) "" else "not "}offered to bring its own things: ${check.bringOffer}")
            p.moves.join(check, bringThings = bring)
        }).get(60, TimeUnit.SECONDS)
        val hosted = by.heard.poll(40, TimeUnit.SECONDS)
        if (!r.ok || hosted?.ok != true) throw IllegalStateException("${p.tag} could not join ${by.tag}: ${r.error} / ${hosted?.error}")
        p.store = p.moves.current.value ?: throw IllegalStateException("${p.tag} joined but has no move in use")
        if (p.store.moveId != by.store.moveId) throw IllegalStateException("${p.tag} joined, but its move in use is ${p.store.moveId}, not Maple Road")
        if (own != null && p.moves.cards.value.none { it.matches(own.moveId) }) broke(MOVES_APART, "${p.tag}'s own move went when it joined Maple Road")
        if (bring) {
            for ((k, _) in beforeJoin.records()) p.brought += k
            p.broughtFiles += ownFiles
        }
        p.host = p.onUi { WifiSync.host(p.store, p.clock) { p.heard.add(it) } }
        // Joining stamps the person on both phones; those are writes too.
        wrote(beforeJoin, p.data, onlyNewStamps = true)
        wrote(beforeHost, by.data, onlyNewStamps = true)
        p.hold(); by.hold()
        handedOver(p, beforeJoin, by)
        p.say("!joined as ${p.name} (${p.role.label}, letter ${p.store.device.value.letter}) from ${by.tag}'s invite")
        if (p.store.myRole != p.role) broke(RUN, "${p.tag} joined as ${p.store.myRole}, not ${p.role}")
        // The join was a meeting: as the engine does at once, the new phone syncs by itself with the one it joined, and so
        // gets the move's keys for syncing by itself.
        if (!autoSync(by, p, null, false, Random(seed + p.index), retry = false)) {
            broke(AUTO, "${p.tag} could not start syncing by itself with ${by.tag}, whose invite it had just used")
        }
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
        // Phones that cannot meet: one sends an update file through a messaging app, another opens it (SimUpdates).
        if (rnd.nextInt(4) == 0) SimUpdates.pass(this, phones[rnd.nextInt(phones.size)], phones[rnd.nextInt(phones.size)], rnd)
        if (rnd.nextInt(3) == 0) elsewhere()
        val free = phones.shuffled(rnd).toMutableList()
        val jobs = ArrayList<Future<*>>()
        repeat(listOf(0, 1, 1, 1, 2, 2, 3)[rnd.nextInt(7)]) {
            if (free.size < 2) return@repeat
            val h = free.removeAt(0)
            val j = free.removeAt(0)
            // A phone syncs the move it has open. When either has another move open, that is what they sync, or try to.
            // A sync by itself or by code: the two phones of a pair either find each other on the Wi-Fi or one scans the other's code.
            val byItself = rnd.nextInt(3) == 0
            if (!h.inMaple || !j.inMaple) {
                jobs += pool.submit { if (byItself) crossByItself(h, j) else crossSync(h, j) }
                return@repeat
            }
            // A sync by itself has two more phases at its end, the offers of keys, which may be cut off too.
            val cut = if (rnd.nextInt(6) == 0) Wire.Cut(1 + rnd.nextInt(if (byItself) 10 else 8), listOf(0, 1, 7, 60, 900, 5000, 30_000)[rnd.nextInt(7)]) else null
            val busy = rnd.nextInt(3) == 0
            val plan = rnd.nextLong()
            val retry = cut != null && rnd.nextInt(4) != 0
            jobs += pool.submit { if (byItself) autoSync(h, j, cut, busy, Random(plan), retry) else sync(h, j, cut, busy, Random(plan), retry) }
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
        // Everyone goes back to Maple Road to finish it.
        for (p in phones) p.onUi { if (!p.inMaple) p.pending = null; p.moves.open(p.store.moveId) }
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

    // ---- other moves on the phones ------------------------------------------------------

    /** Which of the two phones tried out alone bring their things into Maple Road when they join (by index). */
    private var bringing: Set<Int> = emptySet()

    /** The moves the phones started beside Maple Road, by ID, with their names. */
    val others = LinkedHashMap<String, String>()

    private val otherNames = listOf("Storage", "Elm Street", "Gran's flat", "Garage sale", "Uni room", "The allotment")

    private fun note(what: String) = synchronized(stats) { stats.notes.merge(what, 1, Int::plus) }

    /**
     * Something about the phone's other moves, done while nothing else runs: a phone starts a move of its own (it is its
     * Organiser), switches to another of its moves, scans a label of one of them, invites another phone into one, or
     * scans the sync code of a move it is not in.
     */
    private fun elsewhere() {
        val p = phones[rnd.nextInt(phones.size)]
        val q = phones.filter { it !== p }[rnd.nextInt(phones.size - 1)]
        when (rnd.nextInt(12)) {
            0, 1 -> startMove(p)
            2, 3, 4, 5 -> switchMove(p)
            6, 7 -> scanAcross(p)
            8, 9 -> inviteElsewhere(p, q)
            else -> strangeCode(p, q)
        }
    }

    /** [p] starts a move of its own beside Maple Road, through its list of moves, and puts a few things in it. It is then in use. */
    private fun startMove(p: Phone) {
        if (p.moves.cards.value.size >= 3 || others.size >= 4) return switchMove(p)
        val name = otherNames[rnd.nextInt(otherNames.size)]
        val storage = name == "Storage"
        val s = p.onUi {
            val s = p.moves.create(name)
            p.pending = null
            // A new move's phone takes a letter it uses in none of its other moves, at random; here one such letter is picked
            // from the run's own choices, so that the run repeats. The phone takes it only if it is so.
            val taken = s.lettersElsewhereNow()
            val free = ('A'..'Z').map { "$it" }.filter { it !in taken }
            s.setDevice { it.copy(letter = free[p.rnd.nextInt(free.size)]) }
            s.setMove { it.copy(name = name, setupDone = true, kind = if (storage) MoveKind.STORAGE else MoveKind.SELF, moveDay = 20_610L + p.rnd.nextInt(30)) }
            val rooms = listOf("Loft", "Shed").map { s.createRoom(Home.ORIGIN, it) }
            if (!storage) s.createRoom(Home.DEST, "Hall")
            for (n in 1..2) s.createItem(listOf("Bike", "Lamp", "Desk", "Rug")[p.rnd.nextInt(4)] + " " + n, rooms[n % 2].id)
            // Old printed labels, as Maple Road has too, and a code written by hand.
            s.createBox("L%03d".format(1 + p.rnd.nextInt(6)), rooms[0].id)
            s.createBox(s.nextHandCode().also { synchronized(p.handCodes) { p.handCodes += "${s.moveId}/$it" } }, rooms[1].id)
            s
        }
        synchronized(others) { others[s.moveId] = name }
        note("moves started on a phone beside Maple Road")
        p.say("!started a move of its own, $name (${s.moveId.take(8)}), as its Organiser, letter ${s.device.value.letter}")
    }

    /** [p] opens another of its moves, as its list of moves does: back to Maple Road more often than not. */
    private fun switchMove(p: Phone) {
        val cards = p.moves.cards.value
        if (cards.size < 2) return
        val maple = cards.first { it.matches(p.store.moveId) }
        val to = if (!p.inMaple && rnd.nextInt(10) < 6) maple else cards[rnd.nextInt(cards.size)]
        val opened = p.onUi { p.moves.open(to.moveId) }
        p.pending = null
        if (opened == null || p.current !== opened || !opened.identity.value.matches(to.moveId)) {
            broke(MOVES_APART, "${p.tag} opened ${to.name} (${to.moveId.take(8)}) from its list, but has ${p.current.data.value.move.name} (${p.current.moveId.take(8)}) in use")
        }
        note("switches between the moves on a phone")
        p.say("switched to ${to.name.ifBlank { "a move" }}")
    }

    /**
     * [p] scans a label of one of its moves: printed with its move's tag, printed before tags (also as "HM:L001"), or written by hand.
     * Its list of moves must name every one of its moves that holds that label, and no other; for a tagged one, the move of the tag.
     */
    private fun scanAcross(p: Phone) {
        val stores = p.onUi { p.stores() }
        fun labels(d: AppData) = d.liveBoxes.map { it.label } + d.liveItems.mapNotNull { it.label }
        val all = stores.flatMap { labels(it.data.value) }.filter { it.isNotBlank() }.distinctBy { it.uppercase() }.sorted()
        if (all.isEmpty()) return
        val code = all[rnd.nextInt(all.size)]
        val holding = stores.filter { s -> labels(s.data.value).any { it.equals(code, ignoreCase = true) } }
        val tagged = rnd.nextInt(3) == 0
        val of = holding[rnd.nextInt(holding.size)]
        val text = when {
            tagged -> labelQrText(code, of.moveTag)
            rnd.nextBoolean() -> "HM:$code"
            else -> code
        }
        val got = p.onUi { p.moves.movesHolding(text) }.map { it.moveId }.toSet()
        val want = if (tagged) p.moves.cards.value.filter { of.moveTag in it.tags }.map { it.moveId }.toSet() else holding.map { it.moveId }.toSet()
        if (got != want) {
            broke(SCANNED, "${p.tag} scanned $text: its list of moves says ${got.map { others[it] ?: it.take(8) }}, but ${want.map { others[it] ?: it.take(8) }} hold it")
        }
        if (!tagged && want.size > 1) note("old labels found in more than one move on a phone, so the screen asks which")
        p.say("scanned the label $text: in ${got.size} of its moves")
    }

    /**
     * [p] adds [q]'s person to one of its own other moves and shows the invite; [q] reads it and joins through its list of moves.
     * Joining puts the move in a card of its own, opened; nothing in [q]'s other moves changes.
     */
    private fun inviteElsewhere(p: Phone, q: Phone) {
        val choices = p.onUi { p.stores() }.filter { s -> s !== p.store && s.can(Action.MANAGE_PEOPLE) && q.moves.cards.value.none { it.matches(s.moveId) } }
        if (choices.isEmpty()) return startMove(p)
        val s = choices[rnd.nextInt(choices.size)]
        val role = Role.entries[rnd.nextInt(3)]
        val person = p.onUi { s.addPerson(q.name, role) }.valueOrNull() ?: return
        val heard = LinkedBlockingQueue<WifiSync.Result>()
        val host = p.onUi { WifiSync.host(s, p.clock) { heard.add(it) } }
        try {
            val invite = host.inviteFor(person.id, local).valueOrNull() ?: return
            val before = q.onUi { q.snapshot() }
            val r = q.net.submit(Callable {
                val check = q.moves.readInvite(invite.encode()) ?: throw IllegalStateException("${q.tag} could not read an invite")
                if (check.card != null) broke(MOVES_APART, "${q.tag} was told it holds ${check.card?.name}, which it never joined")
                q.moves.join(check, bringThings = false).also { if (it.ok) q.pending = null }
            }).get(60, TimeUnit.SECONDS)
            val hosted = heard.poll(40, TimeUnit.SECONDS)
            val name = s.data.value.move.name
            if (!r.ok || hosted?.ok != true) {
                note("invites into another move that did not go through")
                q.say("!could not join $name from ${p.tag}'s invite: ${r.error} / ${hosted?.error}")
                return
            }
            val now = q.current
            if (!now.identity.value.matches(s.moveId) || q.moves.cards.value.size != before.size + 1) {
                broke(MOVES_APART, "${q.tag} joined $name, but has ${now.data.value.move.name} in use and ${q.moves.cards.value.size} moves (had ${before.size})")
            }
            val after = q.onUi { q.snapshot() }
            for ((id, was) in before) if (after[id] != was) broke(MOVES_APART, "${q.tag}'s move ${others[id] ?: "Maple Road"} changed when it joined $name")
            note("phones that joined another move beside Maple Road")
            q.say("!joined $name as ${q.name} (${role.label}, letter ${now.device.value.letter}) from ${p.tag}'s invite")
        } finally {
            host.stop()
        }
    }

    /**
     * [q] shows the sync code of the move it has open, and [p], which is not in that move, scans it through its list of moves.
     * Nothing is exchanged, both phones say they are on different moves, and no move is added to [p] or changed on either.
     */
    private fun strangeCode(p: Phone, q: Phone) {
        val m = q.current
        if (p.moves.cards.value.any { it.matches(m.moveId) }) return switchMove(p)
        val heard = LinkedBlockingQueue<WifiSync.Result>()
        val host = q.onUi { WifiSync.host(m, q.clock) { heard.add(it) } }
        try {
            val mine = p.onUi { p.snapshot() }
            val theirs = q.onUi { q.snapshot() }
            val r = p.net.submit(Callable {
                val check = p.moves.readInvite(host.invite(local).encode()) ?: throw IllegalStateException("${p.tag} could not read a sync code")
                p.moves.join(check)
            }).get(60, TimeUnit.SECONDS)
            val hosted = heard.poll(40, TimeUnit.SECONDS)
            val name = m.data.value.move.name
            if (r.ok || r.joinedAs != null || hosted?.ok == true) broke(CROSS, "${p.tag} synced with ${q.tag}'s $name by its plain code, a move it is not in")
            if (r.error != WifiSync.WRONG_MOVE || hosted?.error != WifiSync.WRONG_MOVE) {
                broke(CROSS, "${p.tag} scanned the code of ${q.tag}'s $name, a move it is not in, and the phones said “${r.error}” / “${hosted?.error}”, not that they are on different moves")
            }
            if (p.onUi { p.snapshot() } != mine) broke(CROSS, "${p.tag}'s moves changed when it scanned the code of ${q.tag}'s $name, which it is not in")
            if (q.onUi { q.snapshot() } != theirs) broke(CROSS, "${q.tag}'s moves changed when ${p.tag}, not in $name, scanned its code")
            val left = File(p.dir, "files/moves").listFiles()?.filter { it.name.endsWith(".new") }.orEmpty()
            if (left.isNotEmpty()) broke(CROSS, "${p.tag} was left with a move half joined (${left.first().name}) after a code it could not use")
            note("sync codes of a move the phone is not in, scanned through its list of moves")
            p.say("scanned the code of ${q.tag}'s $name: ${r.error}")
        } finally {
            host.stop()
        }
    }

    /**
     * [h] shows the sync code of the move it has open, and [j] scans it from the move it has open, as the Sync screen does.
     * On different moves nothing is exchanged and both say so; on the same one (a move beside Maple Road both are in) it is
     * an ordinary sync of that move, and nothing else on either phone changes.
     */
    private fun crossSync(h: Phone, j: Phone) {
        val hs = h.current
        val js = j.current
        val same = hs.identity.value.matches(js.identity.value)
        val heard = LinkedBlockingQueue<WifiSync.Result>()
        val host = h.onUi { WifiSync.host(hs, h.clock) { heard.add(it) } }
        try {
            val hBefore = if (loose) null else h.onUi { h.snapshot() }
            val jBefore = if (loose) null else j.onUi { j.snapshot() }
            val r = j.net.submit(Callable { WifiSync.join(js, host.invite(local), letNewPhonesIn = js.isAlone()) }).get(120, TimeUnit.SECONDS)
            val hosted = heard.poll(60, TimeUnit.SECONDS)
            val names = "${j.tag} in ${js.data.value.move.name} and ${h.tag} in ${hs.data.value.move.name}"
            synchronized(stats) {
                stats.crossSyncs++
                for (e in listOfNotNull(r.error, hosted?.error).toSet()) stats.syncErrors.merge(SimStats.rule(e), 1, Int::plus)
            }
            if (!same) {
                if (r.ok || hosted?.ok == true) broke(CROSS, "$names synced, though they are on different moves")
                if (r.error != WifiSync.WRONG_MOVE || hosted?.error != WifiSync.WRONG_MOVE) {
                    broke(CROSS, "$names tried to sync and were told “${r.error}” / “${hosted?.error}”, not that they are on different moves")
                }
            }
            if (hBefore != null && jBefore != null) {
                val hAfter = h.onUi { h.snapshot() }
                val jAfter = j.onUi { j.snapshot() }
                for ((p, before, after, open) in listOf(Quad(h, hBefore, hAfter, hs), Quad(j, jBefore, jAfter, js))) {
                    for ((id, was) in before) {
                        if (same && id == open.moveId) continue
                        if (after[id] != was) broke(CROSS, "$names ${if (same) "synced their move" else "tried to sync"}, and ${p.tag}'s ${others[id] ?: "Maple Road"} changed")
                    }
                }
            }
            val how = if (r.ok) "ok" else r.error
            j.say("synced ${js.data.value.move.name} with ${h.tag}, which has ${hs.data.value.move.name} open: $how")
            h.say("hosted ${j.tag}, which has ${js.data.value.move.name} open, from ${hs.data.value.move.name}: $how")
        } finally {
            host.stop()
        }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    /**
     * A phone let in as it is gets a Helper's grant in the name of the person of the phone that let it in, as the sync begins
     * ([Store.letIn]), on a copy of that person the merge of the same sync may stamp over with the other phone's newer one.
     * That copy's grants are noted as written, so the end does not take them for grants nobody made.
     */
    private fun letIn(p: Phone, before: AppData) {
        val me = p.personId
        for (person in p.data.people) {
            val had = before.person(person.id)?.grants?.toSet() ?: emptySet()
            if (person.grants.none { it !in had && it.by == me && it.role == Role.HELPER && !it.removed }) continue
            synchronized(versions) { versions.getOrPut("person:" + person.id) { ArrayList() } += person }
        }
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
        letIn(h, x.hostAtStart!!)
        letIn(j, x.joinerAtStart!!)
        h.hold(); j.hold()
        handedOver(h, x.hostAtStart!!, j)
        handedOver(j, x.joinerAtStart!!, h)
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

    // ---- syncing by itself --------------------------------------------------------------

    /** What a phone's listener for syncing by itself did with one connection. */
    class Heard(val answered: AutoSession.Answered?)

    /**
     * [j] finds [h] on the Wi-Fi and syncs with it by itself (AutoSession), through a [Wire] whose phases are those of a
     * code sync once the handshake is over, so that everything [sync] checks is checked here too. Whether it may at all
     * is each phone's to say from what it knew before: a phone whose person was removed, or one the other does not know
     * as a phone of the move, must get nothing. Returns true when both phones say it went through.
     */
    fun autoSync(h: Phone, j: Phone, cut: Wire.Cut?, busy: Boolean, plan: Random, retry: Boolean): Boolean {
        val x = Exchange(h, j)
        x.hostAtStart = h.data
        x.joinerAtStart = j.data
        val hostTakes = x.hostAtStart!!.memberPhone(j.phoneId) != null
        val joinerTakes = x.joinerAtStart!!.memberPhone(h.phoneId) != null
        fun meanwhile(p: Phone, where: String) {
            if (loose || !busy || plan.nextInt(100) >= 30) return
            x.quiet = false
            synchronized(stats) { stats.midSync++ }
            p.onUi { repeat(1 + plan.nextInt(2)) { p.act(" [while syncing by itself with ${(if (p === h) j else h).tag}: $where]") } }
        }
        val wire = Wire(h.auto.port, cut, skip = AUTO_HANDSHAKE) { phase ->
            when (phase) {
                2 -> { meanwhile(h, "before any data"); meanwhile(j, "before any data") }
                3 -> { x.joinerSent = j.data; meanwhile(j, "after sending its data"); meanwhile(h, "before merging"); x.hostBefore = h.data; x.hostWas = h.personId }
                4 -> { x.hostAfter = h.data; meanwhile(h, "after merging"); meanwhile(j, "before merging"); x.joinerBefore = j.data; x.joinerWas = j.personId }
                5 -> { x.joinerAfter = j.data; meanwhile(j, "after merging"); meanwhile(h, "after merging") }
                6 -> { meanwhile(h, "before the photos"); meanwhile(j, "before the photos") }
                7 -> meanwhile(h, "while receiving photos")
                8 -> meanwhile(j, "while receiving photos")
            }
        }
        val d: AutoSession.Dialled
        var heard: Heard? = null
        try {
            d = j.net.submit(Callable { AutoSession.dial(j.store, "127.0.0.1", wire.port) }).get(120, TimeUnit.SECONDS)
            // The answering phone is done with the connection before either phone does anything more.
            if (wire.connections > 0) heard = h.autoHeard.poll(60, TimeUnit.SECONDS) ?: throw IllegalStateException("${h.tag} never finished answering ${j.tag}")
        } finally {
            wire.close()
        }
        val r = d.result
        val answered = heard?.answered?.result
        val ok = d.word == "OK" && r != null && r.ok && answered?.ok == true
        synchronized(stats) {
            stats.autoSyncs++
            if (ok) stats.autoOk++
            if (d.word == "NO" || (d.word == "LOST" && wire.phases == 0 && wire.cutAt == 0)) stats.autoRefused++
            if (wire.cutAt > 0) stats.autoCut++
            for (e in listOfNotNull(r?.error, answered?.error).toSet()) stats.syncErrors.merge(SimStats.rule(e), 1, Int::plus)
        }
        // Only phones of the move, as each knew it then, sync by themselves: never one taken out, nor a stranger. Phones that
        // keep working while they sync may let a phone back in meanwhile: then it counts if it was in at the start or the end.
        val hostTakesNow = h.data.memberPhone(j.phoneId) != null
        val joinerTakesNow = j.data.memberPhone(h.phoneId) != null
        val outside = if (loose) (!hostTakes && !hostTakesNow) || (!joinerTakes && !joinerTakesNow) else !hostTakes || !joinerTakes
        if ((d.word == "OK" || heard?.answered != null) && outside) {
            broke(AUTO, "${j.tag} synced by itself with ${h.tag}, though ${if (!hostTakes) "${h.tag} did not count ${j.tag}" else "${j.tag} did not count ${h.tag}"} as a phone of the move", listOf(h.personId, j.personId))
        }
        if (!loose && (d.word == "OK" || heard?.answered != null)) SimChecks(this).exchange(x, wire, ok)
        wrote(x.hostAtStart!!, h.data, onlyNewStamps = true)
        wrote(x.joinerAtStart!!, j.data, onlyNewStamps = true)
        letIn(h, x.hostAtStart!!)
        letIn(j, x.joinerAtStart!!)
        h.hold(); j.hold()
        handedOver(h, x.hostAtStart!!, j)
        handedOver(j, x.joinerAtStart!!, h)
        val how = when {
            ok -> "ok by ${d.via?.name?.lowercase()} key, ${r!!.photosSent} photos out, ${r.photosReceived} in"
            wire.cutAt > 0 -> "cut off in phase ${wire.cutAt}: ${r?.error ?: d.word} / ${answered?.error ?: "the answering phone noticed nothing"}"
            else -> "not synced: ${d.word}${r?.error?.let { ", $it" } ?: ""} / ${answered?.error ?: "-"}"
        }
        j.say("synced by itself with ${h.tag}: $how")
        h.say("was found by ${j.tag} and synced by itself: $how")
        if (!ok && retry && d.word != "NO") {
            synchronized(stats) { stats.retried++ }
            return autoSync(h, j, null, false, plan, retry = false)
        }
        return ok
    }

    /**
     * [j] dials [h] by itself while either has another move than Maple Road open: on different moves the tags differ and
     * nothing is exchanged; on the same one (a move beside Maple Road both are in) it is that move's sync. Nothing of
     * any other move on either phone changes.
     */
    private fun crossByItself(h: Phone, j: Phone) {
        val hs = h.current
        val js = j.current
        val same = hs.identity.value.matches(js.identity.value)
        val hBefore = if (loose) null else h.onUi { h.snapshot() }
        val jBefore = if (loose) null else j.onUi { j.snapshot() }
        // Through a wire, which counts whether the dialling phone connected at all (with no key to prove with, it does not).
        val wire = Wire(h.auto.port, null, skip = AUTO_HANDSHAKE)
        val d = try {
            j.net.submit(Callable { AutoSession.dial(js, "127.0.0.1", wire.port) }).get(120, TimeUnit.SECONDS).also {
                if (wire.connections > 0) h.autoHeard.poll(60, TimeUnit.SECONDS)
            }
        } finally {
            wire.close()
        }
        val names = "${j.tag} in ${js.data.value.move.name} and ${h.tag} in ${hs.data.value.move.name}"
        synchronized(stats) { stats.crossSyncs++; stats.autoCross++ }
        if (!same && d.word == "OK") broke(CROSS, "$names synced by themselves, though they are on different moves")
        if (hBefore != null && jBefore != null) {
            for ((p, before, open) in listOf(Triple(h, hBefore, hs), Triple(j, jBefore, js))) {
                val after = p.onUi { p.snapshot() }
                for ((id, was) in before) {
                    if (same && id == open.moveId) continue
                    if (after[id] != was) broke(CROSS, "$names ${if (same) "synced their move by themselves" else "tried to sync by themselves"}, and ${p.tag}'s ${others[id] ?: "Maple Road"} changed")
                }
            }
        }
        j.say("tried to sync ${js.data.value.move.name} by itself with ${h.tag}, which has ${hs.data.value.move.name} open: ${d.word}")
    }

    /**
     * The outsider on the same Wi-Fi tries [m]'s listener for syncing by itself: with the tag [m] advertises (anyone on the
     * network can read it) and made-up proofs, or ([own]) with its own move and keys. It must be told no, or not answered.
     */
    private fun outsiderTriesByItself(m: Phone, own: Boolean): String {
        val word = if (own) {
            asOutsider {
                outsider.ring.mintIfNone(outsider.data.value)
                AutoSession.dial(outsider, "127.0.0.1", m.auto.port).word
            }
        } else {
            try {
                java.net.Socket("127.0.0.1", m.auto.port).use { s ->
                    s.soTimeout = 10_000
                    val out = java.io.DataOutputStream(s.getOutputStream().buffered())
                    val din = java.io.DataInputStream(s.getInputStream())
                    out.writeUTF(AutoSession.MAGIC); out.writeUTF(AutoSession.advertisedTag(m.current.identity.value, System.currentTimeMillis())); out.writeUTF("")
                    out.write(ByteArray(32) { it.toByte() }); out.flush()
                    din.readFully(ByteArray(32))
                    out.writeInt(3)
                    repeat(3) { n -> out.write(ByteArray(32) { (n * 7 + it).toByte() }) }
                    out.flush()
                    din.readUTF()
                }
            } catch (e: Exception) {
                "nothing (${e.javaClass.simpleName})"
            }
        }
        m.autoHeard.poll(30, TimeUnit.SECONDS)
        if (word == "OK" || word == "SYNC") broke(OUTSIDERS, "a phone outside the move was let in by ${m.tag} to sync by itself (${if (own) "its own move's keys" else "made-up proofs"})")
        return if (own) "syncing by itself with its own move's keys: $word" else "syncing by itself with made-up proofs: $word"
    }

    companion object {
        /** The direction changes of the handshake of syncing by itself before its sync begins (AutoSession). */
        const val AUTO_HANDSHAKE = 6
        const val AUTO = "Only phones of the move, as each knew it, sync by themselves: never a removed person's phone or a stranger, and every phone that joins can start"
        const val MOVE = "move:"
        const val REFUSED = "→ no: "
        const val RUN = "The run finished and the phones went quiet"
        const val OUTSIDERS = "A phone outside the move (a used or photographed code, a wrong code, version 1.3) got nothing and changed nothing"
        const val MOVES_APART = "Nothing from one move appears in another on any phone: records, photos, people (one card per move)"
        const val CROSS = "Syncs and joins between phones on different moves exchange nothing and say so"
        const val LETTERS_APART = "A phone's letters and label numbers never clash across its moves"
        const val SCANNED = "A scanned label is found in exactly the moves on the phone that hold it"
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

/** Seeded bytes for the nonces and keys of syncing by itself, one source per phone thread, so that a run repeats exactly. */
internal object SimBytes {
    /** Inherited, as [SimIds.local] is. */
    val local = InheritableThreadLocal<Random?>()
    private val strong: (Int) -> ByteArray = Strong.bytes

    fun install() {
        Strong.bytes = { n -> local.get()?.let { r -> ByteArray(n).also { b -> synchronized(r) { r.nextBytes(b) } } } ?: strong(n) }
    }

    fun uninstall() {
        Strong.bytes = strong
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

    /** Syncs tried between phones of which one or both had another move than Maple Road open. */
    var crossSyncs = 0

    /** Syncs by themselves (AutoSession) of Maple Road: tried, went through, refused at the handshake, cut off; and tried with another move open. */
    var autoSyncs = 0
    var autoOk = 0
    var autoRefused = 0
    var autoCut = 0
    var autoCross = 0
    val refusals = TreeMap<String, Int>()
    val syncErrors = TreeMap<String, Int>()
    val notes = TreeMap<String, Int>()

    /** Where the time went. */
    val millis = TreeMap<String, Long>()

    fun add(o: SimStats) {
        actions += o.actions; accepted += o.accepted; midSync += o.midSync; syncs += o.syncs; syncsOk += o.syncsOk; cut += o.cut; retried += o.retried
        crossSyncs += o.crossSyncs
        autoSyncs += o.autoSyncs; autoOk += o.autoOk; autoRefused += o.autoRefused; autoCut += o.autoCut; autoCross += o.autoCross
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
