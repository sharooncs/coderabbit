package app.housemove

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import app.housemove.data.*
import app.housemove.ui.AppRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One screen for the checks to open: a route, and optionally a change to the data
 * made just before it is opened (undone afterwards).
 */
internal class AuditScreen(
    val route: String,
    val name: String = route,
    val from: String = "",
    val prepare: ((Store) -> Unit)? = null,
) {
    /** Name of its picture file: the route with / replaced by _. */
    val file: String get() = name.replace(Regex("[^A-Za-z0-9_.-]+"), "_")
}

/**
 * THE LIST OF SCREENS. Every check opens each of these. To cover a new screen, add its
 * route here; give routes with an argument an id that exists in the sample data.
 *
 * Two kinds of route are picked up without being listed, so a newly added screen is
 * not missed: routes that AuditShotsTest renders, and routes in the navigation graph
 * that take no argument. The report names them, and names any route with an argument
 * that has no example here.
 */
internal val AUDIT_SCREENS: List<AuditScreen> = listOf(
    "home", "items", "item/wardrobe", "item/fridge", "item/sofa", "walk", "triage", "plan", "disposal", "electronics",
    "boxes", "box/box1", "box/box2", "box/box5", "pack", "labels", "lists", "packorder", "tasks",
    "moveday", "load", "unload", "reconcile", "sweep", "records/ORIGIN", "unpack", "budget", "reports",
    "rooms", "more", "sync", "settings", "search", "scan",
).map { AuditScreen(it) } + listOf(
    // The first-run screen only shows while the move is not set up yet.
    AuditScreen("setup") { s -> s.setMove { it.copy(setupDone = false) } },
)

/** Screens timed with the large move, and the ones where a search word is typed as well. */
internal val AUDIT_SPEED_SCREENS = listOf("items", "boxes", "home", "search")
internal val AUDIT_SPEED_TYPE_INTO = listOf("search")

/**
 * Automated audit checks: touch targets, names for screen readers, contrast, other
 * screen sizes and large text, how much typing each screen asks for, and speed with
 * a large move. They write build/audit/report.md and report.json (see AuditReport).
 *
 * These tests do not fail for what they find. They fail only when the checking
 * itself stops working, for instance when no screen can be opened at all.
 * Run them with tools/checks.sh.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuditChecksTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val rule = createComposeRule()

    private lateinit var store: Store
    private var nav: NavHostController? = null
    private var shown by mutableStateOf<Store?>(null)

    private companion object {
        /** The screen every run starts on. */
        const val START = "home"

        // Simulated devices. A new size needs a line here and a three-line test below.
        const val PHONE = "w411dp-h891dp-xxhdpi"
        const val SMALL = "w320dp-h640dp-xhdpi"
        const val FLIP = "w384dp-h940dp-xhdpi"
        const val LARGE_TEXT = "w411dp-h891dp-xhdpi"
        const val SPEED = "w411dp-h891dp-mdpi"
        const val SELF_CHECK = "w411dp-h1400dp-xxhdpi"

        /** How many screenfuls of a long screen are looked at: as it opens, then scrolled down. */
        const val MAX_VIEWS = 6
    }

    @After
    fun tidy() = AuditSampleData.cleanUp()

    // ---- opening screens -----------------------------------------------------------

    private fun show(s: Store) {
        store = s
        if (shown == null) {
            shown = s
            rule.setContent {
                val current = shown
                if (current != null) key(current) { AppRoot(current, startRoute = START, onNav = { nav = it }) }
            }
        } else {
            shown = s
        }
        rule.waitForIdle()
    }

    private fun graphRoutes(): List<String> {
        fun all(g: NavGraph): List<String> = g.flatMap { d -> if (d is NavGraph) all(d) else listOfNotNull(d.route) }
        var out = emptyList<String>()
        rule.runOnUiThread { out = all(nav!!.graph) }
        return out
    }

    /** The listed screens, plus routes found in AuditShotsTest and in the navigation graph. */
    private fun screens(): List<AuditScreen> {
        val known = AUDIT_SCREENS.map { it.route }.toMutableSet()
        val extra = ArrayList<AuditScreen>()
        for (r in AuditSampleData.shotsRoutes()) if (known.add(r)) extra += AuditScreen(r, from = "rendered by AuditShotsTest")
        for (r in graphRoutes()) if ('{' !in r && known.add(r)) extra += AuditScreen(r, from = "found in the navigation graph")
        return AUDIT_SCREENS.filter { it.prepare == null } + extra + AUDIT_SCREENS.filter { it.prepare != null }
    }

    /** Routes with an argument that nothing above gives an example for. */
    private fun unexampled(runs: List<ScreenRun>): List<ScreenRun> {
        val seen = runs.map { it.pattern }.toSet()
        return graphRoutes().filter { '{' in it && it !in seen }.map {
            ScreenRun(it, it, it, checked = false, note = "no example of this route is listed in AUDIT_SCREENS (AuditChecksTest.kt)")
        }
    }

    private fun fits(pattern: String, route: String): Boolean =
        Regex(pattern.split(Regex("\\{[^}]*\\}")).joinToString("[^/]+") { Regex.escape(it) }).matches(route)

    /**
     * Open [screen]. The answer says whether it is now on show; if not, why.
     * [undo] puts back the data that the screen's own preparation changed.
     */
    private fun open(screen: AuditScreen): Pair<ScreenRun, () -> Unit> {
        var undo = {}
        screen.prepare?.let { prepare ->
            val data = store.data.value
            val device = store.device.value
            undo = {
                rule.runOnUiThread { store.update { data }; store.setDevice { device } }
                rule.waitForIdle()
            }
            rule.runOnUiThread { prepare(store) }
            rule.waitForIdle()
        }
        var note = ""
        rule.runOnUiThread {
            try {
                nav!!.navigate(screen.route)
            } catch (_: IllegalArgumentException) {
                note = "there is no such route in this build"
            }
        }
        rule.waitForIdle()
        var pattern = ""
        rule.runOnUiThread { pattern = nav!!.currentBackStackEntry?.destination?.route.orEmpty() }
        if (note.isEmpty() && !fits(pattern, screen.route)) note = "it opens `$pattern` instead (the screen sends you on)"
        return ScreenRun(screen.name, screen.route, if (note.isEmpty()) pattern else "", checked = note.isEmpty(), note = note, from = screen.from) to undo
    }

    /** Take the picture of what is on show and read its semantics tree. */
    private fun look(picture: File): AuditTree {
        picture.parentFile?.mkdirs()
        rule.onRoot().captureRoboImage(picture.path)
        return AuditTree(
            rule.onRoot(useUnmergedTree = true).fetchSemanticsNode(),
            rule.onRoot(useUnmergedTree = false).fetchSemanticsNode(),
        )
    }

    /** Scroll the main list or column of the screen down by most of its height. False when it is at its end. */
    private fun scrollDown(tree: AuditTree): Boolean {
        fun place(n: AuditNode) = n.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        val scroller = tree.nodes.filter { n ->
            n.placed && n.config.contains(SemanticsActions.ScrollBy) && n.shown.width > 0f && n.shown.height > 0f &&
                place(n)?.let { it.value() < it.maxValue() - 0.5f } == true
        }.maxByOrNull { it.shown.width * it.shown.height } ?: return false
        val before = place(scroller)!!.value()
        val onScreen = rule.onNode(SemanticsMatcher("node ${scroller.id}") { n -> n.id == scroller.id }, useUnmergedTree = true)
        onScreen.performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> scroll(0f, scroller.shown.height * 0.7f) }
        rule.waitForIdle()
        val after = onScreen.fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke() ?: return false
        return abs(after - before) > 1f
    }

    private fun why(e: Throwable): String = "the check broke here: ${e.javaClass.simpleName}: ${clip(e.message.orEmpty(), 160)}"

    private fun seconds(since: Long) = ((System.nanoTime() - since) / 1e8).roundToInt() / 10.0

    // ---- the checks checked ------------------------------------------------------------

    /**
     * Runs the checks over a made-up screen with known problems planted in it (see
     * AuditSelfCheck.kt). This is the test that fails when the checks stop working.
     */
    @Test
    @Config(sdk = [34], qualifiers = SELF_CHECK)
    fun selfCheck() {
        val t0 = System.nanoTime()
        rule.setContent { PlantedProblems() }
        rule.waitForIdle()
        val picture = File(AuditReport.dir, "checks/selfcheck.png")
        val wrong = selfCheck(look(picture), readPixels(picture))
        AuditReport.save(SelfCheckPart(wrong, seconds(t0)))
        assertTrue("The audit checks no longer find exactly what is planted for them:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    // ---- 1, 2, 3 and 5: at the normal phone size -------------------------------------

    @Test
    @Config(sdk = [34], qualifiers = PHONE)
    fun phone() {
        val t0 = System.nanoTime()
        show(AuditSampleData.seeded(tmp.newFolder("audit")))
        val runs = ArrayList<ScreenRun>()
        val touch = ArrayList<TouchTarget>()
        val noName = ArrayList<Unnamed>()
        val fields = ArrayList<TypedField>()
        val cuts = ArrayList<CutOff>()
        val low = ArrayList<LowContrast>()
        val close = ArrayList<LowContrast>()
        val marked = ArrayList<String>()
        val notMeasured = ArrayList<NotMeasured>()
        val measurements = ArrayList<String>()
        val counts = ArrayList<ContrastCount>()
        var size = ""
        val pictures = "checks/phone"
        for (screen in screens()) {
            val (run, undo) = open(screen)
            try {
                if (!run.checked) {
                    runs += run
                    continue
                }
                val seen = HashSet<String>()
                val textDone = HashSet<String>()
                val found = ContrastResult()
                val marks = HashMap<Int, MutableList<Mark>>()
                var density = 1f
                var view = 1
                while (true) {
                    val picture = File(AuditReport.dir, "$pictures/${screen.file}${if (view == 1) "" else "-$view"}.png")
                    val tree = look(picture)
                    if (size.isEmpty()) size = "${tree.dp(tree.widthPx).roundToInt()}×${tree.dp(tree.heightPx).roundToInt()}dp"
                    val newTouch = touchTargets(tree, screen.name, view).filter { seen.add("touch " + it.key) }
                    val newNoName = unnamed(tree, screen.name, view).filter { seen.add("name " + it.key) }
                    touch += newTouch
                    noName += newNoName
                    fields += textFields(tree, screen.name).filter { seen.add("field " + it.key) }
                    if (view == 1) cuts += cutOffs(tree, screen.name)
                    contrast(tree, readPixels(picture), screen.name, view, textDone, found)
                    density = tree.density
                    marks.getOrPut(view) { ArrayList() } += newTouch.filter { it.tooSmall }.map { Mark(it.xDp, it.yDp, it.widthDp, it.heightDp, MARK_TOUCH) } +
                        newNoName.map { Mark(it.xDp, it.yDp, it.widthDp, it.heightDp, MARK_NAME) }
                    if (view >= MAX_VIEWS || !scrollDown(tree)) break
                    view++
                }
                found.settle()
                // A copy of each picture that has findings, with a box round each of them.
                for (f in found.low) marks.getOrPut(f.view) { ArrayList() } += Mark(f.xDp, f.yDp, f.widthDp, f.heightDp, MARK_CONTRAST)
                for ((v, boxes) in marks) {
                    if (boxes.isEmpty()) continue
                    val name = "${screen.file}${if (v == 1) "" else "-$v"}.png"
                    markUp(File(AuditReport.dir, "$pictures/$name"), File(AuditReport.dir, "checks/marked/$name"), boxes, density)
                    marked += "checks/marked/$name"
                }
                low += found.low
                close += found.close
                measurements += found.all
                notMeasured += found.notMeasured
                counts += ContrastCount(screen.name, found.measured, found.low.size, found.notMeasured.size, found.disabledSkipped, found.symbolsSkipped)
                runs += run.copy(views = view)
            } catch (e: Throwable) {
                runs += run.copy(checked = false, note = why(e))
            } finally {
                undo()
            }
        }
        runs += unexampled(runs)
        File(AuditReport.dir, "checks/contrast.csv").writeText(
            "screen,view,contrast,needed,text colour,background,colour given,size sp,weight,how,text\n" + measurements.joinToString("\n") + "\n",
        )
        AuditReport.save(
            PhonePart(size, AuditSampleData.source, runs, touch, noName, low, close, notMeasured, counts, fields, cuts, pictures, marked, seconds(t0)),
        )
        assertTrue("The audit checks could not check a single screen: " + runs.joinToString { it.route + ": " + it.note }, runs.any { it.checked })
    }

    // ---- 4: other screen sizes and large text ---------------------------------------

    private fun otherSize(key: String, title: String) {
        val t0 = System.nanoTime()
        show(AuditSampleData.seeded(tmp.newFolder("audit")))
        val runs = ArrayList<ScreenRun>()
        val cuts = ArrayList<CutOff>()
        val pictures = "sizes/$key"
        var w = 0
        var h = 0
        var scale = 1f
        for (screen in screens()) {
            val (run, undo) = open(screen)
            try {
                if (run.checked) {
                    val tree = look(File(AuditReport.dir, "$pictures/${screen.file}.png"))
                    w = tree.dp(tree.widthPx).roundToInt(); h = tree.dp(tree.heightPx).roundToInt(); scale = tree.fontScale
                    cuts += cutOffs(tree, screen.name)
                }
                runs += run
            } catch (e: Throwable) {
                runs += run.copy(checked = false, note = why(e))
            } finally {
                undo()
            }
        }
        AuditReport.save(SizePart(key, "$title, ${w}×${h}dp" + if (scale != 1f) ", text ×$scale" else "", w, h, scale, runs, cuts, pictures, seconds(t0)))
        assertTrue("The audit checks could not check a single screen at this size", runs.any { it.checked })
    }

    @Test
    @Config(sdk = [34], qualifiers = SMALL)
    fun smallPhone() = otherSize("small", "Small phone")

    @Test
    @Config(sdk = [34], qualifiers = FLIP)
    fun narrowTallPhone() = otherSize("flip", "Tall narrow phone (Galaxy Z Flip5)")

    @Test
    @Config(sdk = [34], qualifiers = LARGE_TEXT)
    fun largeText() {
        RuntimeEnvironment.setFontScale(1.3f)
        otherSize("largetext", "Large text")
    }

    // ---- 6: speed with a large move --------------------------------------------------

    private fun median(runs: List<Double>): Double = runs.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }

    private fun round(ms: Double) = (ms * 10).roundToInt() / 10.0

    private fun <T> timeData(name: String, after: (T) -> Unit = {}, block: () -> T): Timing {
        val runs = ArrayList<Double>()
        repeat(7) { i ->
            val t = System.nanoTime()
            val got = block()
            val ms = (System.nanoTime() - t) / 1e6
            after(got)
            if (i >= 2) runs += round(ms) // the first two runs are the warm-up
        }
        val m = median(runs)
        return Timing(name, "data", m, runs, AuditReport.DATA_LIMIT_MS, m > AuditReport.DATA_LIMIT_MS)
    }

    private class Timed(val runs: List<Double>, val nodes: Int)

    /**
     * Open [route] a few times; each time is from asking for it until Compose has nothing left to do.
     * With [typeWord] the time is instead from typing that word into the first text field of the screen.
     * Null when the screen (or a field to type into) does not exist.
     */
    private fun timeScreen(route: String, typeWord: String? = null, times: Int = 3): Timed? {
        val runs = ArrayList<Double>()
        var nodes = 0
        for (i in 0..times) {
            var known = true
            var t = System.nanoTime()
            rule.runOnUiThread {
                try {
                    nav!!.navigate(route)
                } catch (_: IllegalArgumentException) {
                    known = false
                }
            }
            if (!known) return null
            rule.waitForIdle()
            if (typeWord != null) {
                val field = rule.onAllNodes(hasSetTextAction())
                if (field.fetchSemanticsNodes().isEmpty()) return null
                t = System.nanoTime()
                field.onFirst().performTextReplacement(typeWord)
                rule.waitForIdle()
            }
            val ms = (System.nanoTime() - t) / 1e6
            nodes = rule.onAllNodes(SemanticsMatcher("anything") { true }, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).size
            rule.runOnUiThread { nav!!.popBackStack() }
            rule.waitForIdle()
            if (ms > 20_000) return Timed(listOf(round(ms)), nodes) // far too slow to repeat
            if (i > 0) runs += round(ms) // the first opening is the warm-up
        }
        return Timed(runs, nodes)
    }

    @Test
    @Config(sdk = [34], qualifiers = SPEED)
    fun speed() {
        val t0 = System.nanoTime()
        val dir = tmp.newFolder("large")
        val saved = File(dir, "data.json")
        saved.writeText(AuditSampleData.largeMoveJson())
        val big = Store(dir)
        val timings = ArrayList<Timing>()
        val notes = ArrayList<String>()

        timings += timeData("Save the whole move to disk") { big.flush() }
        val records = AuditSampleData.countRecords(saved)
        for ((list, wanted) in AuditSampleData.LARGE) {
            val got = records[list] ?: 0
            if (got < wanted * 0.9) notes += "The saved file holds $got $list where $wanted were meant: the data model has changed, so update AuditSampleData.largeMoveJson()."
        }
        timings += timeData("Load the move from disk", after = { it.close() }) { Store(dir) }
        val data = big.data.value
        timings += timeData("progress()") { data.progress() }
        timings += timeData("issues()") { data.issues() }
        timings += timeData("search(\"${AuditSampleData.COMMON_WORD}\")") { data.search(AuditSampleData.COMMON_WORD) }
            .let { it.copy(note = "${data.search(AuditSampleData.COMMON_WORD).size} matches") }
        timings += timeData("movables()") { data.movables() }

        // Screens: first with the small sample move, then with the large one, in the same warm state.
        // The screens asked for come first; then every other screen, since any of them may be the slow one.
        val small = AuditSampleData.seeded(tmp.newFolder("audit"))
        show(small)
        val others = (screens().filter { it.prepare == null && '/' !in it.route }.map { it.route } + AuditSampleData.LARGE_EXAMPLES) - AUDIT_SPEED_SCREENS.toSet()
        val plans = AUDIT_SPEED_SCREENS.map { it to null as String? } + AUDIT_SPEED_TYPE_INTO.map { it to AuditSampleData.COMMON_WORD } + others.map { it to null }
        fun times(route: String) = if (route in AUDIT_SPEED_SCREENS) 5 else 3
        val smallRuns = plans.associateWith { (route, word) -> runCatching { timeScreen(route, word, times(route)) }.getOrNull() }
        show(big)
        for (plan in plans) {
            val (route, word) = plan
            val asked = route in AUDIT_SPEED_SCREENS
            val name = if (word == null) "Open the $route screen" else "Type \"$word\" on the $route screen and see the matches"
            if (!asked && seconds(t0) > 150) {
                notes += "$name: not timed, the speed check had already taken long enough."
                continue
            }
            try {
                val timed = timeScreen(route, word, times(route))
                if (timed == null) {
                    if (asked) notes += "$name: not timed, the screen" + (if (word == null) " does not exist in this build." else " has no text field to type into.")
                    continue
                }
                val m = median(timed.runs)
                timings += Timing(
                    name, if (asked) "screen" else "other screen", m, timed.runs, AuditReport.SCREEN_LIMIT_MS, m > AuditReport.SCREEN_LIMIT_MS,
                    smallMoveMs = if (route in AuditSampleData.LARGE_EXAMPLES) null else smallRuns[plan]?.let { median(it.runs) }, nodes = timed.nodes,
                    note = if (timed.runs.size == 1) "one run only, too slow to repeat" else "",
                )
            } catch (e: Throwable) {
                notes += "$name: ${why(e)}"
            }
        }
        big.close()
        AuditReport.save(SpeedPart(records, AuditSampleData.COMMON_WORD, timings, notes, seconds(t0)))
        assertTrue("The speed check could not time a single screen: $notes", timings.any { it.kind == "screen" })
    }
}
