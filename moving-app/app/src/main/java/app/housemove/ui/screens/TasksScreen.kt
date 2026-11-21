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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@Composable
fun TasksScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val data = appData()
    val now = today()
    val tasks = data.liveTasks

    var editingId by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<Task?>(null) }
    var showDone by rememberSaveable { mutableStateOf(false) }

    val byDue = compareBy<Task>({ data.dueDay(it) ?: Long.MAX_VALUE }, { it.offsetDays ?: Int.MAX_VALUE }, { it.title })
    val open = tasks.filter { !it.done }.sortedWith(byDue)
    val overdue = open.filter { (data.dueDay(it) ?: Long.MAX_VALUE) < now }
    val soon = open.filter { val d = data.dueDay(it); d != null && d >= now && d <= now + 7 }
    val later = open.filter { (data.dueDay(it) ?: Long.MIN_VALUE) > now + 7 }
    val undated = open.filter { data.dueDay(it) == null }
    val done = tasks.filter { it.done }.sortedWith(byDue)

    Screen(
        "Timeline",
        fab = {
            FloatingActionButton(onClick = { draft = Task(id = newId(), title = "") }) {
                Icon(Icons.Default.Add, contentDescription = "Add a task")
            }
        },
    ) {
        if (tasks.isEmpty()) {
            ScrollColumn {
                Section("Nothing on the timeline yet") {
                    Text(
                        "The self-move checklist is a ready-made list of about 50 jobs, from six weeks before the move to " +
                            "two weeks after: booking the van, changing your address, defrosting the freezer, reading the meters."
                    )
                    Text("Each job gets a date worked out from your move day. You can change, add or delete any of them.")
                    Button(onClick = { seedTasks().forEach { store.put(it) } }) { Text("Add the self-move checklist") }
                    Hint("Only do this on one phone, then sync. Otherwise every job appears twice.")
                }
                if (data.move.moveDay == null) MoveDateCard { nav.open("settings") }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                item(key = "progress") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ProgressRow("Jobs done", Ratio(done.size, tasks.size))
                        if (data.move.moveDay == null) MoveDateCard { nav.open("settings") }
                        else Hint("Move day: ${fmtDay(data.move.moveDay)} (${relDay(data.move.moveDay, now)})")
                    }
                }
                val groups = listOf(
                    Triple("Overdue", overdue, true),
                    Triple("Next 7 days", soon, false),
                    Triple("Later", later, false),
                    Triple("No date yet", undated, false),
                )
                groups.forEach { (title, list, urgent) ->
                    if (list.isNotEmpty()) {
                        item(key = "head-$title") { GroupHeader(title, list.size, urgent) }
                        items(list, key = { it.id }) { t ->
                            TaskRow(t, data.dueDay(t), now, onToggle = { store.put(t.copy(done = it)) }, onClick = { editingId = t.id })
                        }
                    }
                }
                if (done.isNotEmpty()) {
                    item(key = "head-done") {
                        Row(
                            Modifier.fillMaxWidth().clickable { showDone = !showDone }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Done (${done.size})",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                if (showDone) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (showDone) "Hide finished jobs" else "Show finished jobs",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (showDone) {
                        items(done, key = { it.id }) { t ->
                            TaskRow(t, data.dueDay(t), now, onToggle = { store.put(t.copy(done = it)) }, onClick = { editingId = t.id })
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
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Set your move date and every job gets its own due date.",
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Button(onClick = onSet) { Text("Set the move date") }
        }
    }
}

@Composable
private fun GroupHeader(title: String, count: Int, urgent: Boolean) {
    Text(
        "$title ($count)",
        style = MaterialTheme.typography.titleSmall,
        color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
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
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!task.done && due != null && due < now) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New job" else "Edit job") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextInput(title, onChange = { title = it }, label = "What needs doing", key = initial.id, singleLine = false)
                TextInput(category, onChange = { category = it }, label = "Group (optional)", key = initial.id + ":cat", placeholder = "e.g. Van, Admin, Pack")
                DateButton(dueDay, onPick = { dueDay = it }, label = "Due date", modifier = Modifier.fillMaxWidth())
                val offset = initial.offsetDays
                if (dueDay == null && offset != null) {
                    Hint(
                        "No fixed date: it follows your move day. " + offsetText(offset) +
                            (moveDay?.let { " (${fmtDayShort(it + offset)})" } ?: "") + "."
                    )
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
