package app.housemove.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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

private class TaskGroup(val key: String, val title: String, val icon: ImageVector, val tasks: List<Task>)

@Composable
fun TasksScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val data = appData()
    val now = today()
    val tasks = data.liveTasks

    var editingId by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<Task?>(null) }
    // Groups that are folded away. Finished jobs start out of sight.
    var closed by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() }),
    ) { mutableStateOf(setOf("done")) }

    val byDue = compareBy<Task>({ data.dueDay(it) ?: Long.MAX_VALUE }, { it.offsetDays ?: Int.MAX_VALUE }, { it.title })
    val open = tasks.filter { !it.done }.sortedWith(byDue)
    val done = tasks.filter { it.done }.sortedWith(byDue)
    val groups = listOf(
        TaskGroup("overdue", "Overdue", Icons.Default.ErrorOutline, open.filter { (data.dueDay(it) ?: Long.MAX_VALUE) < now }),
        TaskGroup("soon", "Next 7 days", Icons.Default.Today, open.filter { val d = data.dueDay(it); d != null && d >= now && d <= now + 7 }),
        TaskGroup("later", "Later", Icons.Default.DateRange, open.filter { (data.dueDay(it) ?: Long.MIN_VALUE) > now + 7 }),
        TaskGroup("undated", "No date yet", Icons.Default.EventBusy, open.filter { data.dueDay(it) == null }),
        TaskGroup("done", "Done", Icons.Default.CheckCircle, done),
    )

    Screen(
        "Timeline",
        fab = {
            FloatingActionButton(containerColor = Night.Mint, contentColor = Night.OnMint, onClick = { draft = Task(id = newId(), title = "") }) {
                Icon(Icons.Default.Add, contentDescription = "Add a task")
            }
        },
    ) {
        if (tasks.isEmpty()) {
            ScrollColumn {
                val kind = data.move.kind
                val ready = remember(kind) { seedTasks(kind).size }
                EmptyState(
                    Icons.Default.Checklist, "Nothing on the timeline yet",
                    text = "Start with a ready-made list of $ready jobs, each dated from your move day.",
                    action = "Add the checklist", onAction = { seedTasks(data.move.kind).forEach { store.put(it) } },
                )
                Hint("Add it on one phone only, then sync.", Modifier.fillMaxWidth().wrapContentWidth())
                if (data.move.moveDay == null) MoveDateCard { nav.open("settings") }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                item(key = "progress") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        GlassCard(
                            Modifier.fillMaxWidth(),
                            tone = GlassTone.Mint,
                        ) {
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
                        if (data.move.moveDay == null) MoveDateCard { nav.open("settings") }
                    }
                }
                groups.forEach { g ->
                    if (g.tasks.isNotEmpty()) {
                        val shown = g.key !in closed
                        item(key = "head-${g.key}") {
                            GroupHeader(g, shown) { closed = if (shown) closed + g.key else closed - g.key }
                        }
                        if (shown) {
                            items(g.tasks, key = { it.id }) { t ->
                                TaskRow(t, data.dueDay(t), now, onToggle = { store.put(t.copy(done = it)) }, onClick = { editingId = t.id })
                            }
                        }
                    }
                }
            }
        }
    }

    val editing = tasks.firstOrNull { it.id == editingId }
    if (editing != null) {
        TaskDialog(
            initial = editing,
            isNew = false,
            moveDay = data.move.moveDay,
            onSave = { e ->
                val fresh = store.data.value.liveTasks.firstOrNull { it.id == e.id } ?: e
                store.put(fresh.copy(title = e.title, category = e.category, dueDay = e.dueDay, notes = e.notes))
            },
            onDelete = { store.delete(editing) },
            onDismiss = { editingId = null },
        )
    }
    draft?.let { d ->
        TaskDialog(
            initial = d,
            isNew = true,
            moveDay = data.move.moveDay,
            onSave = { store.put(it) },
            onDelete = null,
            onDismiss = { draft = null },
        )
    }
}

@Composable
private fun MoveDateCard(onSet: () -> Unit) {
    GlassCard(tone = GlassTone.Amber) {
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
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(group.icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            "${group.title} (${group.tasks.size})",
            style = MaterialTheme.typography.titleSmall,
            color = color,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (open) "Hide these jobs" else "Show these jobs",
            tint = scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TaskRow(task: Task, due: Long?, now: Long, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = task.done, onCheckedChange = onToggle)
        Column(Modifier.weight(1f).padding(top = 8.dp, bottom = 8.dp, end = 16.dp)) {
            Text(
                task.title.ifBlank { "Untitled job" },
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (task.done) TextDecoration.LineThrough else null,
                color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            val offset = task.offsetDays
            val timing = when {
                due != null -> fmtDayShort(due) + " · " + relDay(due, now)
                offset != null -> offsetText(offset)
                else -> null
            }
            val sub = listOfNotNull(task.category.takeIf { it.isNotBlank() }, timing).joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TaskDialog(
    initial: Task,
    isNew: Boolean,
    moveDay: Long?,
    onSave: (Task) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var title by remember(initial.id) { mutableStateOf(initial.title) }
    var category by remember(initial.id) { mutableStateOf(initial.category) }
    var dueDay by remember(initial.id) { mutableStateOf(initial.dueDay) }
    var notes by remember(initial.id) { mutableStateOf(initial.notes) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        modifier = Modifier.dialogRim(),
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New job" else "Edit job") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextInput(title, onChange = { title = it }, label = "What needs doing", key = initial.id, singleLine = false)
                TextInput(category, onChange = { category = it }, label = "Group (optional)", key = initial.id + ":cat", placeholder = "e.g. Admin, Pack, Settle")
                DateButton(dueDay, onPick = { dueDay = it }, label = "Due date", modifier = Modifier.fillMaxWidth())
                val offset = initial.offsetDays
                if (dueDay == null && offset != null) {
                    Hint("Follows your move day: " + offsetText(offset).lowercase() + (moveDay?.let { " (${fmtDayShort(it + offset)})" } ?: "") + ".")
                }
                TextInput(notes, onChange = { notes = it }, label = "Notes", key = initial.id + ":notes", singleLine = false)
                if (onDelete != null) {
                    TextButton(
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Delete this job")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    onSave(initial.copy(title = title.trim(), category = category.trim(), dueDay = dueDay, notes = notes.trim()))
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            title = "Delete this job?",
            text = "\"${initial.title}\" will be removed from the timeline on every phone at the next sync.",
            onConfirm = {
                onDelete()
                onDismiss()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
