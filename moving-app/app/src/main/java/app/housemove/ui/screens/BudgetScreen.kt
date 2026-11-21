package app.housemove.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.EXPENSE_CATEGORIES
import app.housemove.data.Expense
import app.housemove.data.PhotoKind
import app.housemove.data.Store
import app.housemove.data.Supply
import app.housemove.data.fmtDayShort
import app.housemove.data.fmtNum
import app.housemove.data.newId
import app.housemove.data.suggestSupplies
import app.housemove.data.today
import app.housemove.ui.ChipPicker
import app.housemove.ui.ConfirmDialog
import app.housemove.ui.DateButton
import app.housemove.ui.EmptyHint
import app.housemove.ui.Flag
import app.housemove.ui.Hint
import app.housemove.ui.LocalStore
import app.housemove.ui.NumberInput
import app.housemove.ui.PhotoStrip
import app.housemove.ui.Screen
import app.housemove.ui.Section
import app.housemove.ui.SwitchRow
import app.housemove.ui.TextInput
import app.housemove.ui.appData

private fun mdMoney(currency: String, amount: Double): String = (currency.trim() + " " + fmtNum(amount)).trim()

/** Change a supply or cost using its latest saved copy, so quick taps never overwrite each other. */
private fun mdEditSupply(store: Store, id: String, f: (Supply) -> Supply) {
    store.data.value.supplies.firstOrNull { it.id == id && !it.deleted }?.let { store.put(f(it)) }
}

private fun mdEditExpense(store: Store, id: String, f: (Expense) -> Expense) {
    store.data.value.expenses.firstOrNull { it.id == id && !it.deleted }?.let { store.put(f(it)) }
}

/** What to buy for the move, and what the move is costing. */
@Composable
fun BudgetScreen() {
    val store = LocalStore.current
    var tab by rememberSaveable { mutableStateOf(0) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    Screen(
        "Supplies and costs",
        fab = {
            if (tab == 1) ExtendedFloatingActionButton(
                onClick = {
                    val e = Expense(id = newId(), day = today(), category = EXPENSE_CATEGORIES.first())
                    store.put(e)
                    editingId = e.id
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add a cost") },
            )
        },
    ) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Supplies") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Costs") })
        }
        if (tab == 0) MdSupplies(Modifier.weight(1f)) else MdCosts(Modifier.weight(1f)) { editingId = it }
    }

    val editing = appData().liveExpenses.firstOrNull { it.id == editingId }
    if (editing != null) MdExpenseDialog(editing, onClose = { editingId = null })
}

// ---- supplies --------------------------------------------------------------

@Composable
private fun MdSupplies(modifier: Modifier) {
    val store = LocalStore.current
    val data = appData()
    val supplies = data.liveSupplies
    val toBuy = supplies.filter { it.have < it.need }
    var newName by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<Supply?>(null) }

    LazyColumn(modifier.fillMaxWidth()) {
        if (supplies.isEmpty()) item(key = "empty") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "No shopping list yet. The app can suggest one from your rooms and the items you are moving: boxes, tape, blankets, straps and tools.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { data.suggestSupplies().forEach { (name, need) -> store.put(Supply(newId(), name, need)) } },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Suggest a list for my home") }
            }
        } else item(key = "summary") {
            Section("Still to buy", Modifier.padding(16.dp)) {
                if (toBuy.isEmpty()) Text("You have everything on the list.", style = MaterialTheme.typography.bodyLarge)
                else {
                    Text(
                        "${toBuy.size} of ${supplies.size} things, ${toBuy.sumOf { it.need - it.have }} in total",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    toBuy.forEach { s ->
                        Text("${s.need - s.have} × ${s.name}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Hint("\"Need\" is how many the move takes. \"Have\" is how many you already have. Change either with − and +.")
            }
        }
        items(supplies, key = { it.id }) { s ->
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        s.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (s.need > 0 && s.have >= s.need) Flag("Got it")
                    IconButton(onClick = { removing = s }) { Icon(Icons.Default.Delete, contentDescription = "Remove ${s.name}") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MdStepper("Need", s.need, s.name) { v -> mdEditSupply(store, s.id) { it.copy(need = v) } }
                    MdStepper("Have", s.have, s.name) { v -> mdEditSupply(store, s.id) { it.copy(have = v) } }
                }
            }
            HorizontalDivider()
        }
        item(key = "add") {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Add something else") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        store.put(Supply(newId(), newName.trim(), 1))
                        newName = ""
                    },
                ) { Text("Add") }
            }
        }
    }

    removing?.let { s ->
        ConfirmDialog(
            title = "Remove from the list?",
            text = s.name,
            confirm = "Remove",
            onConfirm = { store.delete(s) },
            onDismiss = { removing = null },
        )
    }
}

@Composable
private fun MdStepper(label: String, value: Int, what: String, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = { onChange((value - 1).coerceAtLeast(0)) }, enabled = value > 0) {
            Icon(Icons.Default.Remove, contentDescription = "$label one fewer: $what")
        }
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 28.dp),
        )
        IconButton(onClick = { onChange(value + 1) }) {
            Icon(Icons.Default.Add, contentDescription = "$label one more: $what")
        }
    }
}

// ---- costs -----------------------------------------------------------------

@Composable
private fun MdCosts(modifier: Modifier, onEdit: (String) -> Unit) {
    val data = appData()
    val currency = data.move.currency
    val expenses = data.liveExpenses.sortedWith(compareByDescending<Expense> { it.day }.thenBy { it.title })
    val paid = expenses.filter { !it.planned }.sumOf { it.amount }
    val quoted = expenses.filter { it.planned }.sumOf { it.amount }

    LazyColumn(modifier.fillMaxWidth()) {
        item(key = "totals") {
            Section("Totals", Modifier.padding(16.dp)) {
                MdTotalRow("Paid so far", mdMoney(currency, paid))
                MdTotalRow("Quoted, not paid yet", mdMoney(currency, quoted))
                HorizontalDivider()
                MdTotalRow("Expected in total", mdMoney(currency, paid + quoted), strong = true)
            }
        }
        if (expenses.isEmpty()) item(key = "empty") {
            EmptyHint("No costs yet. Add the van hire, fuel, boxes and anything else, and keep a photo of each receipt.")
        }
        items(expenses, key = { it.id }) { e ->
            Row(
                Modifier.fillMaxWidth().clickable { onEdit(e.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        e.title.ifBlank { "Untitled cost" },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            listOf(e.category, fmtDayShort(e.day)).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (e.planned) Flag("Quote")
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(mdMoney(currency, e.amount), style = MaterialTheme.typography.titleMedium)
            }
            HorizontalDivider()
        }
        // Room for the "Add a cost" button so it never covers the last row.
        item(key = "fabspace") { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
private fun MdTotalRow(label: String, value: String, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = if (strong) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun MdExpenseDialog(e: Expense, onClose: () -> Unit) {
    val store = LocalStore.current
    val currency = appData().move.currency.trim()
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Cost") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextInput(e.title, { v -> mdEditExpense(store, e.id) { it.copy(title = v) } }, "What was it for?", key = e.id)
                NumberInput(
                    e.amount.takeIf { it != 0.0 },
                    { v -> mdEditExpense(store, e.id) { it.copy(amount = v ?: 0.0) } },
                    if (currency.isBlank()) "Amount" else "Amount ($currency)",
                    Modifier.fillMaxWidth(),
                    key = e.id,
                )
                ChipPicker(EXPENSE_CATEGORIES, e.category, { it }, { c -> mdEditExpense(store, e.id) { it.copy(category = c) } })
                DateButton(e.day, { d -> if (d != null) mdEditExpense(store, e.id) { it.copy(day = d) } }, "Date", allowClear = false)
                SwitchRow("This is a quote, not paid yet", e.planned, { p -> mdEditExpense(store, e.id) { it.copy(planned = p) } })
                PhotoStrip(e.id, PhotoKind.RECEIPT, label = "Receipt")
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
        dismissButton = { TextButton(onClick = { confirmDelete = true }) { Text("Delete") } },
    )
    if (confirmDelete) ConfirmDialog(
        title = "Delete this cost?",
        text = e.title.ifBlank { "Untitled cost" },
        onConfirm = {
            store.data.value.photosOf(e.id).forEach { store.delete(it) }
            store.delete(e)
            onClose()
        },
        onDismiss = { confirmDelete = false },
    )
}
