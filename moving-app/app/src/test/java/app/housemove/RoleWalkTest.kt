package app.housemove

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import app.housemove.data.Action
import app.housemove.data.AppData
import app.housemove.data.Disposition
import app.housemove.data.Home
import app.housemove.data.Role
import app.housemove.data.Store
import app.housemove.data.mayCarryOut
import app.housemove.data.mayTick
import app.housemove.data.title
import app.housemove.data.visibleTo
import app.housemove.ui.AppRoot
import app.housemove.ui.LocalSpeech
import app.housemove.ui.LocalSyncAddresses
import app.housemove.ui.Speech
import app.housemove.ui.shownName
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Walks the whole app as each kind of person: an Organiser, a Decider, a Helper, and
 * someone on a move with nobody else. Every route of the navigation graph is opened on
 * that person's phone, and Home, the Everything list and Settings once more with no move
 * day set. The walk fails when a screen crashes, and when a core screen shows a control
 * its person may not use.
 *
 * Whether a control may be used is never written down here. Each entry of [GATES] names
 * what a control does (an [Action], or the rule for one item or task) and the phone's own
 * Store is asked, so the walk always follows the table in data/People.kt.
 *
 * For each person the walk leaves, under build/audit/roles/<who>/:
 *   <route>.png    every screen at full length, as that person sees it
 *   controls.txt   everything that can be tapped on each screen, by name
 *   walk.md        what was opened and what was found
 * Findings on the item, container and move-day screens are written up but do not fail
 * the walk (they are not this test's to keep right); those on the core screens do.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1800dp-mdpi")
class RoleWalkTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val rule = createComposeRule()

    private var nav: NavHostController? = null

    // ---- what is on a screen -------------------------------------------------------------

    /** Something a person can tap, as a screen reader would name it. */
    private class Control(val texts: List<String>, val described: List<String>, val kind: String) {
        val name: String get() = (texts + described).joinToString(" · ")
        fun says(vararg exactly: String) = texts.any { it in exactly }
        fun mentions(part: String) = (texts + described).any { it.contains(part, ignoreCase = true) }
    }

    private fun controls(): List<Control> =
        rule.onAllNodes(SemanticsMatcher("can be tapped") { it.config.contains(SemanticsActions.OnClick) && !it.config.contains(SemanticsProperties.Disabled) })
            .fetchSemanticsNodes(atLeastOneRootRequired = false).map { n ->
                Control(
                    n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text.trim() }.filter { it.isNotEmpty() },
                    n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().map { it.trim() }.filter { it.isNotEmpty() },
                    n.config.getOrNull(SemanticsProperties.Role)?.toString() ?: if (n.config.contains(SemanticsActions.SetText)) "Field" else "Tap",
                )
            }

    /** Every piece of text on the screen, whether or not it can be tapped. */
    private fun texts(): List<String> =
        rule.onAllNodes(SemanticsMatcher("has text") { it.config.contains(SemanticsProperties.Text) }, useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    // ---- the rules, asked of the Store -----------------------------------------------------

    /** One screen of the walk: the name of its picture, and the route that opens it. */
    private class Stop(val name: String, val route: String)

    /**
     * Something on a screen that does one of the things the rules distinguish.
     * [on] says which screens it is looked for on (by picture name), [isIt] recognises the
     * control, and [may] asks the Store's rules whether this phone's person may use it.
     */
    private class Gate(
        val what: String,
        val on: Regex,
        val isIt: (Control, AppData) -> Boolean,
        val may: (Store, Stop, Control?) -> Boolean,
    )

    private fun can(vararg anyOf: Action): (Store, Stop, Control?) -> Boolean = { s, _, _ -> anyOf.any { s.can(it) } }

    private val decisions = Disposition.entries.filter { it != Disposition.UNDECIDED }.map { it.label }.toTypedArray()
    private val roleNames = Role.entries.map { it.label }.toTypedArray()

    /** The screens of the core area: a finding on one of these fails the walk. */
    private val core = Regex("(home|overview|setup|more|sync|rooms|people|people_add|person_.*|approvals|activity|settings|search|scan)(_nodate)?")

    private val everywhere = Regex(".*")
    private val hubs = Regex("(home|more)(_nodate)?")
    private val anItem = Regex("item_(.+)")

    private val GATES = listOf(
        // People.
        Gate("add a person", Regex("people"), { c, _ -> c.says("Add a person") }, can(Action.MANAGE_PEOPLE)),
        Gate("open a person to change them", Regex("people"), { c, d -> c.texts.any { t -> d.people.any { it.shownName() == t } } }, can(Action.MANAGE_PEOPLE)),
        Gate("add someone and show their invite", Regex("people_add"), { c, _ -> c.says("Add and show invite") }, can(Action.MANAGE_PEOPLE)),
        Gate(
            "invite, change the role of or remove a person", Regex("person_.*"),
            { c, _ ->
                c.mentions("invite") || c.mentions("new phone") || c.mentions("Remove") || (c.mentions("back") && c.mentions("Add ")) ||
                    (c.kind == "RadioButton" && c.says(*roleNames))
            },
            can(Action.MANAGE_PEOPLE),
        ),
        // The move itself.
        Gate(
            "change the move settings", Regex("settings.*"),
            { c, _ -> c.texts.firstOrNull()?.startsWith("Move day") == true || c.says("Names", "What you use it for", "Parts of the app", "Sizes, money and book titles") },
            can(Action.MOVE_SETTINGS),
        ),
        Gate("be asked for the move date on Home", Regex("home_nodate"), { c, _ -> c.says("Set the date") }, can(Action.MOVE_SETTINGS)),
        Gate(
            "be told that the move has no date (only someone who may set it is)", Regex("(home|more)_nodate"),
            { c, _ -> c.texts.firstOrNull() == "Settings" && c.described.contains("Needs attention") }, can(Action.MOVE_SETTINGS),
        ),
        Gate("erase the move", Regex("settings.*"), { c, _ -> c.says("Start again") || c.mentions("Erase") }, can(Action.ERASE)),
        Gate("restore a backup", Regex("sync"), { c, _ -> c.mentions("Restore") }, can(Action.RESTORE_BACKUP)),
        // Suggestions.
        Gate(
            "approve or decline a suggestion", everywhere,
            { c, _ -> c.says("Approve", "Decline", "Select all", "Not now") || c.texts.any { it.startsWith("Approve ") } }, can(Action.APPROVE),
        ),
        Gate("answer a question with a decision", Regex("approvals"), { c, _ -> c.kind == "RadioButton" && c.says(*decisions) }, can(Action.APPROVE)),
        // What Home and the Everything list offer.
        Gate("be offered Triage", hubs, { c, _ -> c.texts.firstOrNull() == "Triage" }, can(Action.DECIDE, Action.SUGGEST)),
        Gate("be offered the room plan", hubs, { c, _ -> c.texts.firstOrNull() in listOf("Plan rooms", "Plan destinations") }, can(Action.DECIDE, Action.SUGGEST)),
        Gate("be offered costs", hubs, { c, _ -> c.texts.firstOrNull()?.contains("cost", ignoreCase = true) == true }, can(Action.DECIDE, Action.SUGGEST)),
        Gate(
            "be offered the list of things to sell, donate or bin", hubs, { c, _ -> c.texts.firstOrNull() == "Sell, donate, bin" },
            can(Action.DECIDE, Action.SUGGEST, Action.CARRY_OUT),
        ),

        // ---- the item, container and move-day screens: written up, not failed on ----
        Gate(
            "decide or suggest what happens to a thing", Regex("item_.*|triage|items|walk"),
            { c, _ -> c.kind in listOf("RadioButton", "Button") && c.texts.size == 1 && c.says(*decisions) }, can(Action.DECIDE, Action.SUGGEST),
        ),
        Gate(
            "choose or suggest the room a thing goes to", Regex("item_.*"),
            { c, d -> c.kind == "RadioButton" && c.texts.size == 1 && d.roomsOf(Home.DEST).any { it.name == c.texts[0] } }, can(Action.DECIDE, Action.SUGGEST),
        ),
        Gate("suggest rooms on the room plan", Regex("plan"), { c, _ -> c.mentions("Suggest") || c.mentions("Accept all") }, can(Action.DECIDE, Action.SUGGEST)),
        Gate(
            "carry out the decision on this item (listed, sold, gone)", anItem,
            { c, _ -> c.kind == "Switch" && (c.mentions("Listed") || c.mentions("gone")) },
            { s, stop, _ -> s.data.value.item(anItem.matchEntire(stop.name)!!.groupValues[1])?.let { s.data.value.mayCarryOut(s.me, it) } == true },
        ),
        Gate(
            "carry out a decision from the list of things going", Regex("disposal"),
            { c, _ -> c.kind == "Switch" && (c.mentions("Listed") || c.mentions("Gone")) },
            { s, _, c ->
                // The control names its item ("Gone: Old sofa"); the rule is asked for that item. With no control in hand: for any.
                val d = s.data.value
                d.liveItems.filter { i -> c == null || c.described.any { it.endsWith(": " + i.title()) } }.any { d.mayCarryOut(s.me, it) }
            },
        ),
        Gate("see costs", Regex("budget"), { c, _ -> c.kind == "Tab" && c.says("Costs") }, can(Action.DECIDE, Action.SUGGEST)),
        Gate("add a job to the timeline", Regex("tasks"), { c, _ -> c.described.contains("Add a task") }, can(Action.DECIDE)),
        Gate("suggest a job for the timeline", Regex("tasks"), { c, _ -> c.described.contains("Suggest a task") }, { s, _, _ -> s.can(Action.SUGGEST) && !s.can(Action.DECIDE) }),
        Gate(
            "tick off a job", Regex("tasks"), { c, d -> c.kind == "Checkbox" && d.liveTasks.any { it.title == c.name } },
            { s, _, c ->
                val d = s.data.value
                d.liveTasks.filter { c == null || it.title == c.name }.any { d.mayTick(s.me, it) }
            },
        ),
    )

    // ---- walking ---------------------------------------------------------------------------

    private class Finding(val stop: String, val what: String, val control: String, val core: Boolean)

    private fun graphRoutes(): List<String> {
        fun all(g: NavGraph): List<String> = g.flatMap { d -> if (d is NavGraph) all(d) else listOfNotNull(d.route) }
        var out = emptyList<String>()
        rule.runOnUiThread { out = all(nav!!.graph) }
        return out
    }

    /** The screens a route pattern stands for in the sample move; null when the walk has no example for it. */
    private fun stops(pattern: String, d: AppData, me: String): List<Stop>? = when {
        '{' !in pattern -> listOf(Stop(pattern.replace('/', '_'), pattern))
        pattern == "item/{id}" -> listOf("wardrobe", "sofa", "table", "mirror", "bike", "fridge").filter { d.item(it) != null }.map { Stop("item_$it", "item/$it") }
        pattern == "box/{id}" -> listOf("box1", "box2", "box5").filter { d.box(it) != null }.map { Stop("box_$it", "box/$it") }
        pattern == "records/{home}" -> Home.entries.map { Stop("records_${it.name}", "records/${it.name}") }
        pattern.startsWith("sync?") -> listOf(Stop("sync", "sync"))
        pattern.startsWith("person/{id}") -> {
            // This phone's person, someone else who has joined, someone still to join and someone removed.
            val others = d.people.filter { it.id != me }
            listOfNotNull(
                d.people.firstOrNull { it.id == me }, others.firstOrNull { it.joinedAt != 0L && !it.removed },
                others.firstOrNull { it.joinedAt == 0L && !it.removed }, others.firstOrNull { it.removed },
            ).map { Stop("person_" + it.shownName().lowercase().replace(Regex("[^a-z0-9]+"), "_"), "person/${it.id}") }
        }
        else -> null
    }

    private fun open(route: String) {
        rule.runOnUiThread { nav!!.navigate(route) }
        rule.waitForIdle()
        // Photos are decoded on another thread: give them a moment so they are in the picture.
        Thread.sleep(120)
        rule.waitForIdle()
    }

    private fun currentPattern(): String {
        var r = ""
        rule.runOnUiThread { r = nav!!.currentBackStackEntry?.destination?.route.orEmpty() }
        return r
    }

    /** Does [route] open the destination [pattern]? What follows a "?" is optional in both. */
    private fun fits(pattern: String, route: String): Boolean =
        Regex(pattern.substringBefore('?').split(Regex("\\{[^}]*\\}")).joinToString("[^/]+") { Regex.escape(it) }).matches(route.substringBefore('?'))

    /** After a walk: the controls it was allowed to find and did not, and the names of the screens it looked at. */
    private var notFound: List<Gate> = emptyList()
    private var visited: List<String> = emptyList()

    /**
     * Open every route on [store]'s phone, then Home, the Everything list and Settings once
     * more with no move day set ([setMoveDay] changes the date as an Organiser would and
     * brings the change to this phone). [check] can add findings of its own for each screen.
     */
    private fun walk(
        who: String, store: Store, setMoveDay: (Long?) -> Unit,
        check: (Stop, List<String>) -> List<Finding> = { _, _ -> emptyList() },
    ): List<Finding> {
        rule.setContent {
            CompositionLocalProvider(LocalSpeech provides Speech { _, _ -> }, LocalSyncAddresses provides { listOf("127.0.0.1") }) {
                AppRoot(store, startRoute = "home", onNav = { nav = it })
            }
        }
        rule.waitForIdle()
        val dir = File("build/audit/roles/$who")
        dir.deleteRecursively()
        dir.mkdirs()
        val me = store.device.value.personId
        val plan = ArrayList<Stop>()
        val unexampled = ArrayList<String>()
        for (pattern in graphRoutes()) stops(pattern, store.data.value, me)?.let { plan += it } ?: run { unexampled += pattern }
        assertTrue("The walk has no example for these routes; add one to stops(): $unexampled", unexampled.isEmpty())

        val findings = ArrayList<Finding>()
        val seenAllowed = HashSet<Gate>()
        val looked = HashSet<Gate>()
        val dump = StringBuilder()
        val opened = ArrayList<String>()

        fun look(stop: Stop) {
            val landed = currentPattern()
            opened += if (fits(landed, stop.route)) stop.name else "${stop.name} (sends on to $landed)"
            val now = store.data.value
            val shown = controls()
            rule.onRoot().captureRoboImage(File(dir, "${stop.name}.png").path)
            dump.appendLine("## ${stop.name}")
            shown.forEach { dump.appendLine("  [${it.kind}] ${it.name}") }
            dump.appendLine()
            for (g in GATES) {
                if (!g.on.matches(stop.name)) continue
                val there = shown.filter { g.isIt(it, now) }
                if (g.may(store, stop, null)) looked += g
                for (c in there) {
                    if (g.may(store, stop, c)) seenAllowed += g
                    else findings += Finding(stop.name, "may not ${g.what}, but is shown", c.name, core.matches(stop.name))
                }
            }
            findings += check(stop, texts())
        }

        for (stop in plan) {
            open(stop.route)
            look(stop)
        }

        // The same hubs with no move day set: that is when the date is asked for, of those who may set it.
        val day = store.data.value.move.moveDay
        rule.runOnUiThread { setMoveDay(null) }
        rule.waitForIdle()
        assertTrue("the move day could not be cleared for the walk", store.data.value.move.moveDay == null)
        for (name in listOf("home", "more", "settings")) {
            open(name)
            look(Stop("${name}_nodate", name))
        }
        rule.runOnUiThread { setMoveDay(day) }
        rule.waitForIdle()

        File(dir, "controls.txt").writeText(dump.toString())
        notFound = looked.filter { it !in seenAllowed }
        visited = plan.map { it.name } + listOf("home_nodate", "more_nodate", "settings_nodate")
        val md = StringBuilder()
        md.appendLine("# The app as $who (${store.myRole.label}" + (if (store.data.value.livePeople.size > 1) "" else ", nobody else on the move") + ")")
        md.appendLine()
        md.appendLine("May: " + Action.entries.filter { store.can(it) }.joinToString { it.name.lowercase() }.ifBlank { "nothing" } + ".")
        md.appendLine("May not: " + Action.entries.filter { !store.can(it) }.joinToString { it.name.lowercase() }.ifBlank { "nothing is refused" } + ".")
        md.appendLine()
        md.appendLine("Opened ${opened.size} screens without a crash: " + opened.joinToString() + ".")
        md.appendLine()
        val (mine, theirs) = findings.partition { it.core }
        md.appendLine("## Core screens: " + if (mine.isEmpty()) "nothing found" else "${mine.size} found")
        mine.forEach { md.appendLine("- `${it.stop}`: ${it.what}: “${it.control}”") }
        md.appendLine()
        md.appendLine("## Item, container and move-day screens: " + if (theirs.isEmpty()) "nothing found" else "${theirs.size} found")
        theirs.forEach { md.appendLine("- `${it.stop}`: ${it.what}: “${it.control}”") }
        md.appendLine()
        if (notFound.isNotEmpty()) {
            md.appendLine("## Allowed here but not found on any screen")
            md.appendLine()
            md.appendLine("Either the sample move gives no occasion for it, or the control has been renamed and the walk no longer knows it:")
            notFound.forEach { md.appendLine("- ${it.what}") }
        }
        File(dir, "walk.md").writeText(md.toString())
        println(md)
        return findings
    }

    private fun failOnCore(findings: List<Finding>) {
        val mine = findings.filter { it.core }
        assertTrue("Shown although the rules do not allow it:\n" + mine.joinToString("\n") { "${it.stop}: ${it.what}: ${it.control}" }, mine.isEmpty())
    }

    private fun phones() = AuditSampleData.phones { tmp.newFolder() }

    /** Change the move day on the Organiser's phone and bring it to [phone], as a sync would. */
    private fun viaOrganiser(organiser: Store, phone: Store): (Long?) -> Unit = { day ->
        organiser.changeMove { it.copy(moveDay = day) }
        if (phone !== organiser) phone.mergeIn(organiser.data.value.visibleTo(phone.myRole))
    }

    // ---- the four walks --------------------------------------------------------------------

    @Test
    fun organiser() {
        val p = phones()
        failOnCore(walk("organiser", p.organiser, viaOrganiser(p.organiser, p.organiser)))
        // An Organiser may do everything, so every control of the core screens that the walk knows must
        // turn up: if one does not, it has been renamed and the walks of the other roles would pass blindly.
        val lost = notFound.filter { g -> visited.any { g.on.matches(it) && core.matches(it) } }
        assertTrue("The walk no longer finds these controls on an Organiser's phone: " + lost.map { it.what }, lost.isEmpty())
    }

    @Test
    fun decider() {
        val p = phones()
        failOnCore(walk("decider", p.decider, viaOrganiser(p.organiser, p.decider)))
    }

    @Test
    fun helper() {
        val p = phones()
        failOnCore(walk("helper", p.helper, viaOrganiser(p.organiser, p.helper)))
    }

    /** A move with one person: nothing is refused, and nothing about roles is on the screens. */
    @Test
    fun onePerson() {
        val store = AuditSampleData.alone(tmp.newFolder())
        assertTrue("Alone, nothing is refused", Action.entries.all { store.can(it) })
        // Screens that are about roles by their nature: adding someone is choosing their role,
        // and the inbox is only reached by someone who went looking for it.
        val aboutRoles = Regex("people_add|approvals")
        val roleWords = Regex("\\b(Organisers?|Deciders?|Helpers?|[Aa]pprov(e|ed|al|als))\\b")
        val findings = walk("alone", store, viaOrganiser(store, store)) { stop, texts ->
            if (aboutRoles.matches(stop.name)) emptyList()
            else texts.filter { roleWords.containsMatchIn(it) && it != "Helper mode" }.distinct()
                .map { Finding(stop.name, "talks about roles to someone who is alone", it, core.matches(stop.name)) }
        }
        failOnCore(findings)
    }
}
