package app.housemove.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

/** "2 weeks before move day", "On move day", "3 days after move day". */
private fun offsetText(offset: Int): String {
    fun span(days: Int): String =
        if (days % 7 == 0) "${days / 7} week${if (days == 7) "" else "s"}" else "$days day${if (days == 1) "" else "s"}"
    return when {
        offset == 0 -> "On move day"
        offset < 0 -> span(-offset) + " before move day"
        else -> span(offset) + " after move day"
    }
}

/** When a job is due, in a few words, or null when it has no date at all. */
private fun timing(data: AppData, task: Task, now: Long): String? {
    val due = data.dueDay(task)
    val offset = task.offsetDays
    return when {
        due != null -> fmtDayShort(due) + " · " + relDay(due, now)
        offset != null -> offsetText(offset)
        else -> null
    }
}

/** What a suggested change to a job would do, in a few words. */
private fun changeText(data: AppData, now: Task, p: Task, me: Person?): String {
    val parts = ArrayList<String>()
    if (p.title != now.title) parts += "Rename to \"${p.title}\""
    if (p.assignedTo != now.assignedTo) parts += if (p.assignedTo == null) "For nobody in particular" else "Give it to " + mdWho(data, p.assignedTo, me)
    if (data.dueDay(p) != data.dueDay(now)) parts += data.dueDay(p)?.let { "Due " + fmtDayShort(it) } ?: "No date"
    if (p.notes != now.notes) parts += if (p.notes.isBlank()) "Remove the note" else "Note: " + p.notes
    return parts.joinToString(" · ")
}

private class TaskGroup(val key: String, val title: String, val icon: ImageVector, val tasks: List<Task>)

/**
 * The jobs of the move, by when they are due. Each person sees the jobs given to them
 * first. An Organiser adds and changes jobs; a Decider's additions and changes are
 * suggestions that wait on the job for an Organiser; a Helper ticks off their own.
 */
@Composable
fun TasksScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val data = appData()
    val now = today()
    val context = LocalContext.current
    val tasks = data.liveTasks
    val me = store.me
    val shared = mdShared(data)
    val mayPlan = mdCan(Action.DECIDE)
    val maySuggest = mdCan(Action.SUGGEST)
    val maySetDate = mdCan(Action.MOVE_SETTINGS)

    // The one job that is open for changes.
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    // Groups that are folded away. Finished jobs start out of sight.
    var closed by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() }),
    ) { mutableStateOf(setOf("done")) }

    // Suggestions still waiting: new jobs are listed on their own, changes on the job they are about.
    val known = tasks.map { it.id }.toSet()
    val waiting = data.waitingProposals().filter { it.kind == ProposalKind.TASK && it.task != null }
    val suggested = waiting.filter { it.targetId !in known }
    val changes = waiting.filter { it.targetId in known }.groupBy { it.targetId }

    val byDue = compareBy<Task>({ data.dueDay(it) ?: Long.MAX_VALUE }, { it.offsetDays ?: Int.MAX_VALUE }, { it.title })
    val open = tasks.filter { !it.done }.sortedWith(byDue)
    val done = tasks.filter { it.done }.sortedWith(byDue)
    val mine = if (shared && me != null) open.filter { it.assignedTo == me.id } else emptyList()
    val rest = if (mine.isEmpty()) open else open.filter { it.assignedTo != me?.id }
    val groups = listOf(
        TaskGroup("mine", "Yours", Icons.Default.Person, mine),
        TaskGroup("overdue", "Overdue", Icons.Default.ErrorOutline, rest.filter { (data.dueDay(it) ?: Long.MAX_VALUE) < now }),
        TaskGroup("soon", "Next 7 days", Icons.Default.Today, rest.filter { val d = data.dueDay(it); d != null && d >= now && d <= now + 7 }),
        TaskGroup("later", "Later", Icons.Default.DateRange, rest.filter { (data.dueDay(it) ?: Long.MIN_VALUE) > now + 7 }),
        TaskGroup("undated", "No date yet", Icons.Default.EventBusy, rest.filter { data.dueDay(it) == null }),
        TaskGroup("done", "Done", Icons.Default.CheckCircle, done),
    )

    Screen("Timeline") {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
            if (tasks.isEmpty()) item(key = "empty") {
                val kind = data.move.kind
                val ready = remember(kind) { seedTasks(kind).size }
                if (mayPlan) EmptyState(
                    Icons.Default.Checklist, "Nothing on the timeline yet",
                    text = "Start with a ready-made list of $ready jobs, each dated from your move day." +
                        if (shared) " Add it on one phone only." else "",
                    action = "Add the checklist",
                    onAction = { if (mdMay(context, store, Action.DECIDE)) seedTasks(data.move.kind).forEach { store.put(it) } },
                ) else EmptyState(Icons.Default.Checklist, "Nothing on the timeline yet", text = "Jobs appear here once an Organiser adds them.")
            } else item(key = "progress") {
                GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), tone = GlassTone.Mint, sheen = true) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Ring(Ratio(done.size, tasks.size), size = 72.dp, stroke = 8.dp) {
                            Text(
                                "${done.size}", style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("of ${tasks.size} job${if (tasks.size == 1) "" else "s"} done", style = MaterialTheme.typography.titleMedium)
                            data.move.moveDay?.let { day ->
                                Text(
                                    "Move day ${fmtDay(day)} · ${relDay(day, now)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (data.move.moveDay == null && maySetDate) item(key = "date") { MoveDateCard { nav.open("settings") } }
            if (maySuggest) item(key = "add") { TaskAdder(mayPlan) }

            if (suggested.isNotEmpty()) {
                item(key = "head-suggested") { PlainHeader("Suggested jobs", suggested.size, Icons.Default.HourglassTop) }
                items(suggested, key = { "p" + it.id }) { p -> SuggestedRow(p, timing(data, p.task!!, now)) }
            }
            groups.forEach { g ->
                if (g.tasks.isNotEmpty()) {
                    val shown = g.key !in closed
                    item(key = "head-${g.key}") {
                        GroupHeader(g, shown) { closed = if (shown) closed + g.key else closed - g.key }
                    }
                    if (shown) {
                        items(g.tasks, key = { it.id }) { t ->
                            TaskRow(
                                t, timing(data, t, now), open = openId == t.id, changes = changes[t.id].orEmpty(),
                                // In "Yours" every job is this person's own, so the name is left off.
                                showWho = shared && g.key != "mine", mayPlan = mayPlan, maySuggest = maySuggest,
                                onOpen = { openId = if (openId == t.id) null else t.id },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoveDateCard(onSet: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), tone = GlassTone.Amber) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Jobs get their dates from your move day.")
            Button(onClick = onSet) {
                Icon(Icons.Default.Event, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Set the move date")
            }
        }
    }
}

/**
 * One line to add a job without typing: say it. The keyboard is one tap away for a phone
 * with no speech. An Organiser's job is added at once; a Decider's waits for approval.
 */
@Composable
private fun TaskAdder(mayPlan: Boolean) {
    val store = LocalStore.current
    val context = LocalContext.current
    var typing by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    fun add(said: String) {
        val title = said.trim().replaceFirstChar { it.uppercase() }
        if (title.isEmpty()) return
        val task = Task(id = newId(), title = title)
        if (!mayPlan) mdTold(context, store.suggestTask(task))
        else if (mdMay(context, store, Action.DECIDE)) store.put(task)
    }
    fun done() {
        text = ""
        typing = false
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MdAdder(
            if (mayPlan) "New job" else "Suggest a job", if (mayPlan) "Add a task" else "Suggest a task",
            prompt = "What needs doing?", sayIt = "Say a new job", onType = { typing = !typing }, onSaid = { add(it) },
        )
        if (typing) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, label = { Text("What needs doing") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add(text); done() }),
                colors = glassFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MdRowButton("Add", onClick = { add(text); done() }, modifier = Modifier.enabledLook(text.isNotBlank()), icon = Icons.Default.Check)
                MdRowButton("Cancel", onClick = { done() }, forward = false)
            }
        }
    }
}

/** A heading on the open ground that does not fold: its picture, its name and how many. */
@Composable
private fun PlainHeader(title: String, count: Int, icon: ImageVector) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 22.dp, end = 20.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Night.AmberPale, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = Night.AmberPale, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.width(8.dp))
        GlassChip(count.toString(), tone = GlassTone.Amber)
    }
}

/** A group's title with its count. Being late is said here, once, not on every row. */
@Composable
private fun GroupHeader(group: TaskGroup, open: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val color = when (group.key) {
        "overdue" -> scheme.error
        "done" -> scheme.tertiary
        else -> scheme.primary
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).heightIn(min = 48.dp).padding(start = 22.dp, end = 20.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(group.icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(group.title, style = MaterialTheme.typography.titleSmall, color = color, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.width(8.dp))
        GlassChip(group.tasks.size.toString(), tone = if (group.key == "overdue") GlassTone.Red else GlassTone.Plain)
        Spacer(Modifier.weight(1f))
        Icon(
            if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (open) "Hide these jobs" else "Show these jobs",
            tint = scheme.onSurfaceVariant,
        )
    }
}

/** A job someone has suggested and that is not on the timeline yet, with the tag and, for an Organiser, the answer buttons. */
@Composable
private fun SuggestedRow(p: Proposal, timing: String?) {
    val data = appData()
    val task = p.task ?: return
    val forWhom = task.assignedTo?.let { "For " + mdWho(data, it, LocalStore.current.me) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).glass(RoundedCornerShape(22.dp), shadow = 4.dp).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(task.title.ifBlank { "Untitled job" }, style = MaterialTheme.typography.bodyLarge)
        MdApproval(p, what = listOfNotNull(forWhom, timing).joinToString(" · "))
    }
}

/** The tick of a job: a round mark, glowing mint with a tick once done. Only shown to someone who may tick it. */
@Composable
private fun Tick(done: Boolean, title: String, onChange: (Boolean) -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape)
            .toggleable(value = done, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = onChange)
            .semantics { contentDescription = title },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(28.dp).drawBehind {
                if (done) drawOrb(OrbKind.Primary, halo = false)
                else {
                    drawCircle(Color.White.copy(alpha = 0.08f))
                    drawCircle(Color.White.copy(alpha = 0.7f), radius = size.minDimension / 2f - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Default.Check, contentDescription = null, tint = Night.OnMint, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * One job as a glass row. Its tick is there for whoever may tick it. Tapping the row
 * opens it for changes; suggestions waiting on it are shown on it either way.
 */
@Composable
private fun TaskRow(
    task: Task,
    timing: String?,
    open: Boolean,
    changes: List<Proposal>,
    showWho: Boolean,
    mayPlan: Boolean,
    maySuggest: Boolean,
    onOpen: () -> Unit,
) {
    val store = LocalStore.current
    val context = LocalContext.current
    val data = appData()
    val me = store.me
    val mayTick = data.mayTick(me, task)
    val opens = maySuggest || task.notes.isNotBlank()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)
            .glass(RoundedCornerShape(20.dp), if (open) GlassTone.Mint else GlassTone.Plain, shadow = 3.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().then(if (opens) Modifier.clickable(onClickLabel = if (open) "Close" else "Open", onClick = onOpen) else Modifier)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (mayTick) Tick(task.done, task.title.ifBlank { "Untitled job" }) { mdTold(context, store.tick(task.id, it)) }
            else Box(Modifier.size(width = 44.dp, height = 52.dp), contentAlignment = Alignment.Center) {
                // Not theirs to tick: a quiet mark that says whether it is done, and nothing to tap.
                if (task.done) Icon(Icons.Default.Check, contentDescription = "Done", tint = Night.MintLight, modifier = Modifier.size(20.dp))
                else Box(Modifier.size(8.dp).clip(CircleShape).drawBehind { drawCircle(Night.Slate) })
            }
            Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    task.title.ifBlank { "Untitled job" },
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null,
                    color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                val sub = listOfNotNull(task.category.takeIf { it.isNotBlank() }, timing).joinToString(" · ")
                if (sub.isNotBlank()) {
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (showWho && task.assignedTo != null) {
                    GlassChip(mdWho(data, task.assignedTo, me).replaceFirstChar { it.uppercase() }, icon = Icons.Default.Person, tone = GlassTone.Mint)
                }
            }
            if (opens) Icon(
                if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        changes.forEach { p ->
            MdApproval(p, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), what = p.task?.let { changeText(data, task, it, me) })
        }
        if (open && opens) TaskDetail(task, mayPlan, maySuggest)
    }
}

/**
 * What can be changed about a job, all by tapping or speaking: who it is for, when it is
 * due, a note. The title and the note can be typed behind "Edit". An Organiser's changes
 * are saved as they are made; a Decider's are gathered and sent as one suggestion.
 */
@Composable
private fun TaskDetail(task: Task, mayPlan: Boolean, maySuggest: Boolean) {
    val store = LocalStore.current
    val context = LocalContext.current
    val shared = mdShared(appData())
    var draft by remember(task.id, task.updatedAt) { mutableStateOf(task) }
    var title by remember(task.id, task.updatedAt) { mutableStateOf(task.title) }
    var notes by remember(task.id, task.updatedAt) { mutableStateOf(task.notes) }
    var editing by rememberSaveable(task.id) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    /** The job as it is saved right now, so quick changes never overwrite each other. */
    fun fresh(): Task = store.data.value.task(task.id) ?: task

    /** An Organiser's change, saved at once. The plain save cannot answer, so it is asked first and says why not. */
    fun save(next: Task) {
        if (mdMay(context, store, Action.DECIDE)) store.put(next)
    }
    val proposed = draft.copy(title = title.trim().ifBlank { task.title }, notes = notes.trim())
    val changed = proposed.title != task.title || proposed.notes != task.notes ||
        proposed.assignedTo != task.assignedTo || proposed.dueDay != task.dueDay

    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (notes.isNotBlank() && !editing) Text(notes, style = MaterialTheme.typography.bodyMedium, color = Night.TextSoft)
        if (maySuggest) {
            if (shared) {
                Text("Who does it", style = MaterialTheme.typography.labelLarge, color = Night.TextDim)
                MdPeople(draft.assignedTo, onPick = { id ->
                    if (mayPlan) mdTold(context, store.assign(task.id, id)) else draft = draft.copy(assignedTo = id)
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateButton(
                    draft.dueDay, label = "Due", modifier = Modifier.weight(1f),
                    onPick = { day -> if (mayPlan) save(fresh().copy(dueDay = day)) else draft = draft.copy(dueDay = day) },
                )
                SpeakButton("A note for this job", contentDescription = "Say a note") { said ->
                    val joined = if (notes.isBlank()) said else "$notes. $said"
                    if (mayPlan) save(fresh().copy(notes = joined)) else notes = joined
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                GlassChip("Edit", icon = Icons.Default.Edit, selected = editing, onClick = { editing = !editing })
                if (mayPlan) GlassChip("Delete this job", icon = Icons.Default.Delete, onClick = { confirmDelete = true })
            }
            if (editing) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, label = { Text("What needs doing") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = glassFieldColors(), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it }, label = { Text("Notes") }, minLines = 2,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = glassFieldColors(), modifier = Modifier.fillMaxWidth(),
                )
                if (mayPlan) MdRowButton(
                    "Save", icon = Icons.Default.Check,
                    onClick = {
                        save(fresh().copy(title = proposed.title, notes = proposed.notes))
                        editing = false
                    },
                )
            }
            if (!mayPlan && changed) {
                BigAction(
                    Icons.Default.Send, "Suggest this change",
                    onClick = {
                        if (mdTold(context, store.suggestTask(proposed))) {
                            draft = task
                            title = task.title
                            notes = task.notes
                            editing = false
                        }
                    },
                )
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this job?",
            text = "\"${task.title}\" will be removed from the timeline on every phone at the next sync.",
            onConfirm = { save(fresh().copy(deleted = true)) },
            onDismiss = { confirmDelete = false },
        )
    }
}
