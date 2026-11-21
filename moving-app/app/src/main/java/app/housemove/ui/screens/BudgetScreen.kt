package app.housemove.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.EXPENSE_CATEGORIES
import app.housemove.data.Expense
import app.housemove.data.PhotoKind
import app.housemove.data.Ratio
import app.housemove.data.Store
import app.housemove.data.Supply
import app.housemove.data.fmtDayShort
import app.housemove.data.fmtNum
import app.housemove.data.newId
import app.housemove.data.suggestSupplies
import app.housemove.data.today
import app.housemove.data.vehicle
import app.housemove.ui.BigAction
import app.housemove.ui.Choice
import app.housemove.ui.ConfirmDialog
import app.housemove.ui.DateButton
import app.housemove.ui.EmptyState
import app.housemove.ui.Hint
import app.housemove.ui.IconChoice
import app.housemove.ui.IconFlag
import app.housemove.ui.LocalStore
import app.housemove.ui.NumberInput
import app.housemove.ui.PhotoStrip
import app.housemove.ui.Ring
import app.housemove.ui.Screen
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

private fun mdCategoryIcon(category: String): ImageVector = when (category) {
    "Van" -> Icons.Default.LocalShipping
    "Fuel" -> Icons.Default.LocalGasStation
    "Supplies" -> Icons.Default.Inventory2
    "Helpers" -> Icons.Default.Group
    "Storage" -> Icons.Default.Warehouse
    "Cleaning" -> Icons.Default.CleaningServices
    "Fees" -> Icons.Default.ReceiptLong
    else -> Icons.Default.MoreHoriz
}

/** The category as shown: movers bring a truck, so "Van" reads "Truck" for them. */
private fun mdCategoryLabel(category: String, vehicle: String): String =
    if (category == "Van") vehicle.replaceFirstChar { it.uppercase() } else category

/** What to buy for the move, and what the move is costing. */
@Composable
fun BudgetScreen() {
    val store = LocalStore.current
    var tab by rememberSaveable { mutableStateOf(0) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    Screen("Supplies and costs") {
        TabRow(selectedTabIndex = tab) {
            MdIconTab(tab == 0, { tab = 0 }, "Supplies", Icons.Default.ShoppingCart)
            MdIconTab(tab == 1, { tab = 1 }, "Costs", Icons.Default.Payments)
        }
        if (tab == 0) MdSupplies(Modifier.weight(1f)) else MdCosts(
            Modifier.weight(1f),
            onAdd = {
                val e = Expense(id = newId(), day = today(), category = EXPENSE_CATEGORIES.first())
                store.put(e)
                editingId = e.id
            },
            onEdit = { editingId = it },
        )
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
    val ready = supplies.count { it.have >= it.need }
    val toBuy = supplies.sumOf { (it.need - it.have).coerceAtLeast(0) }
    var newName by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<Supply?>(null) }

    LazyColumn(modifier.fillMaxWidth()) {
        if (supplies.isEmpty()) item(key = "empty") {
            EmptyState(
                Icons.Default.ShoppingCart, "No shopping list yet",
                text = "Boxes, tape, blankets and tools, counted from your rooms and items.",
                action = "Suggest a list for my home",
                onAction = { data.suggestSupplies().forEach { (name, need) -> store.put(Supply(newId(), name, need)) } },
            )
        } else item(key = "summary") {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MdProgressHeader(
                    Ratio(ready, supplies.size),
                    "$ready of ${supplies.size} ready",
                    sub = if (toBuy == 0) "You have everything on the list" else "$toBuy still to buy",
                )
                Hint("Need is what the move takes. Have is what you already own.")
            }
        }
        items(supplies, key = { it.id }) { s ->
            val got = s.need > 0 && s.have >= s.need
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Have of need, as a small ring that turns into a tick.
                    Ring(
                        Ratio(s.have.coerceAtMost(s.need), s.need), size = 36.dp, stroke = 4.dp,
                        color = if (got) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    ) {
                        if (got) Icon(
                            Icons.Default.Check, contentDescription = "Got it",
                            tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        s.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (s.have < s.need) IconFlag(Icons.Default.ShoppingCart, "${s.need - s.have} to buy")
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
private fun MdCosts(modifier: Modifier, onAdd: () -> Unit, onEdit: (String) -> Unit) {
    val data = appData()
    val currency = data.move.currency
    val vehicle = data.vehicle()
    val expenses = data.liveExpenses.sortedWith(compareByDescending<Expense> { it.day }.thenBy { it.title })
    val paid = expenses.filter { !it.planned }.sumOf { it.amount }
    val quoted = expenses.filter { it.planned }.sumOf { it.amount }

    LazyColumn(modifier.fillMaxWidth()) {
        item(key = "totals") {
            Card(
                Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // How much of the expected total is already paid.
                        Ring(Ratio((paid * 100).toInt(), ((paid + quoted) * 100).toInt()), size = 64.dp, stroke = 7.dp) {
                            Icon(Icons.Default.Payments, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                mdMoney(currency, paid + quoted),
                                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                            )
                            Text("Expected in total", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        MdTotal("Paid so far", mdMoney(currency, paid), Modifier.weight(1f))
                        MdTotal("Quoted, not paid yet", mdMoney(currency, quoted), Modifier.weight(1f))
                    }
                }
            }
        }
        item(key = "add") {
            // In the page itself rather than a floating button, so it is always there on this tab.
            BigAction(Icons.Default.Add, "Add a cost", onAdd, Modifier.padding(horizontal = 16.dp))
        }
        if (expenses.isEmpty()) item(key = "empty") {
            EmptyState(Icons.Default.ReceiptLong, "No costs yet", text = "Add each cost and keep a photo of the receipt.")
        }
        items(expenses, key = { it.id }) { e ->
            Row(
                Modifier.fillMaxWidth().clickable { onEdit(e.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        mdCategoryIcon(e.category), contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        e.title.ifBlank { "Untitled cost" },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            listOf(mdCategoryLabel(e.category, vehicle), fmtDayShort(e.day)).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (e.planned) IconFlag(Icons.Default.ReceiptLong, "Quote")
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(mdMoney(currency, e.amount), style = MaterialTheme.typography.titleMedium)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun MdTotal(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MdExpenseDialog(e: Expense, onClose: () -> Unit) {
    val store = LocalStore.current
    val data = appData()
    val currency = data.move.currency.trim()
    val vehicle = data.vehicle()
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
                IconChoice(
                    EXPENSE_CATEGORIES.map { Choice(it, mdCategoryLabel(it, vehicle), mdCategoryIcon(it)) },
                    selected = e.category,
                    onPick = { c -> mdEditExpense(store, e.id) { it.copy(category = c) } },
                    columns = if (mdNarrow(310f)) 2 else 4,
                )
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
