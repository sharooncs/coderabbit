package app.housemove.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.Action
import app.housemove.data.AppData
import app.housemove.data.EXPENSE_CATEGORIES
import app.housemove.data.Expense
import app.housemove.data.Photo
import app.housemove.data.PhotoKind
import app.housemove.data.Proposal
import app.housemove.data.ProposalKind
import app.housemove.data.Ratio
import app.housemove.data.Store
import app.housemove.data.Supply
import app.housemove.data.fmtDayShort
import app.housemove.data.fmtNum
import app.housemove.data.mayPay
import app.housemove.data.newId
import app.housemove.data.suggestSupplies
import app.housemove.data.today
import app.housemove.data.vehicle
import app.housemove.data.waitingProposals
import app.housemove.ui.*

private fun mdMoney(currency: String, amount: Double): String = (currency.trim() + " " + fmtNum(amount)).trim()

/** Change a supply using its latest saved copy, so quick taps never overwrite each other. */
private fun mdEditSupply(store: Store, id: String, f: (Supply) -> Supply) {
    store.data.value.supplies.firstOrNull { it.id == id && !it.deleted }?.let { store.put(f(it)) }
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

/** What a cost is called: its name if someone gave it one, otherwise what kind of cost it is. */
private fun mdCostTitle(e: Expense, vehicle: String): String = e.title.ifBlank { mdCategoryLabel(e.category, vehicle).ifBlank { "Cost" } }

/** What a suggested change to a cost would do, in a few words. */
private fun mdCostChange(now: Expense, p: Expense, currency: String, vehicle: String): String {
    val parts = ArrayList<String>()
    if (p.amount != now.amount) parts += mdMoney(currency, now.amount) + " → " + mdMoney(currency, p.amount)
    if (p.category != now.category) parts += mdCategoryLabel(p.category, vehicle)
    if (p.title != now.title) parts += if (p.title.isBlank()) "No name" else "Name: " + p.title
    if (p.day != now.day) parts += fmtDayShort(p.day)
    return parts.joinToString(" · ")
}

/** The amount most recently entered for a category: the same kind of thing is often paid again. */
private fun mdLastAmount(data: AppData, category: String): Double =
    data.liveExpenses.filter { it.category == category }.maxByOrNull { it.updatedAt }?.amount ?: 0.0

private val MD_AMOUNTS = listOf(10.0, 20.0, 50.0, 100.0, 200.0, 500.0)
private const val MD_NEW = "new"

/**
 * What to buy for the move, and what the move is costing. A Helper sees the shopping
 * list only: costs are not shown to Helpers, and their phones are not sent them.
 */
@Composable
fun BudgetScreen() {
    val seesCosts = mdCan(Action.SUGGEST)
    var tab by rememberSaveable { mutableStateOf(0) }

    Screen(if (seesCosts) "Supplies and costs" else "Supplies") {
        if (seesCosts) GlassTabRow(tab) {
            MdIconTab(tab == 0, { tab = 0 }, "Supplies", Icons.Default.ShoppingCart)
            MdIconTab(tab == 1, { tab = 1 }, "Costs", Icons.Default.Payments)
        }
        if (!seesCosts || tab == 0) MdSupplies(Modifier.weight(1f)) else MdCosts(Modifier.weight(1f))
    }
}

// ---- supplies --------------------------------------------------------------

@Composable
private fun MdSupplies(modifier: Modifier) {
    val store = LocalStore.current
    val data = appData()
    val supplies = data.liveSupplies
    val ready = supplies.count { it.have >= it.need }
    val toBuy = supplies.sumOf { (it.need - it.have).coerceAtLeast(0) }
    var typing by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }
    var removing by remember { mutableStateOf<Supply?>(null) }
    fun add(said: String) {
        val name = said.trim().replaceFirstChar { it.uppercase() }
        if (name.isNotEmpty()) store.put(Supply(newId(), name, 1))
    }

    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
        if (supplies.isEmpty()) item(key = "empty") {
            EmptyState(
                Icons.Default.ShoppingCart, "No shopping list yet",
                text = "Boxes, tape, blankets and tools, counted from your rooms and items.",
                action = "Suggest a list for my home",
                onAction = { data.suggestSupplies().forEach { (name, need) -> store.put(Supply(newId(), name, need)) } },
            )
        } else item(key = "summary") {
            MdProgressHeader(
                Ratio(ready, supplies.size),
                "$ready of ${supplies.size} ready",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                sub = if (toBuy == 0) "You have everything on the list" else "$toBuy still to buy",
            )
        }
        items(supplies, key = { it.id }) { s ->
            val got = s.need > 0 && s.have >= s.need
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    .glass(RoundedCornerShape(22.dp), shadow = 4.dp).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
            ) {
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
                    GlassOrb(Icons.Default.Delete, "Remove ${s.name}", size = 36.dp, onClick = { removing = s })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MdStepper("Need", s.need, s.name) { v -> mdEditSupply(store, s.id) { it.copy(need = v) } }
                    MdStepper("Have", s.have, s.name) { v -> mdEditSupply(store, s.id) { it.copy(have = v) } }
                }
            }
        }
        item(key = "add") {
            // Something not on the list: say it. The keyboard is one tap away for a phone with no speech.
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MdAdder(
                    "Something else", "Add something else to the list",
                    prompt = "What else do you need?", sayIt = "Say what else you need", onType = { typing = !typing }, onSaid = { add(it) },
                )
                if (typing) Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        colors = glassFieldColors(),
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Add something else") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { add(newName); newName = "" }),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    MdRowButton("Add", onClick = { add(newName); newName = "" }, modifier = Modifier.enabledLook(newName.isNotBlank()))
                }
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
        GlassOrb(
            contentDescription = "$label one fewer: $what", size = 34.dp, modifier = Modifier.enabledLook(value > 0),
            onClick = { if (value > 0) onChange(value - 1) },
        ) { Text("−", style = MaterialTheme.typography.titleMedium) }
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 28.dp),
        )
        GlassOrb(contentDescription = "$label one more: $what", size = 34.dp, onClick = { onChange(value + 1) }) {
            Text("+", style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ---- costs -----------------------------------------------------------------

@Composable
private fun MdCosts(modifier: Modifier) {
    val data = appData()
    val currency = data.move.currency
    val vehicle = data.vehicle()
    val mayPlan = mdCan(Action.DECIDE)
    val expenses = data.liveExpenses.sortedWith(compareByDescending<Expense> { it.day }.thenBy { it.title })
    val paid = expenses.filter { !it.planned }.sumOf { it.amount }
    val quoted = expenses.filter { it.planned }.sumOf { it.amount }
    // Suggestions still waiting: new costs are listed on their own, changes on the cost they are about.
    val known = expenses.map { it.id }.toSet()
    val waiting = data.waitingProposals().filter { it.kind == ProposalKind.COST && it.expense != null }
    val suggested = waiting.filter { it.targetId !in known }
    val changes = waiting.filter { it.targetId in known }.groupBy { it.targetId }
    // The cost being added or changed, in its place in the list: MD_NEW, an id, or none.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
        item(key = "totals") {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), tone = GlassTone.Mint, sheen = true) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    // How much of the expected total is already paid.
                    Ring(Ratio((paid * 100).toInt(), ((paid + quoted) * 100).toInt()), size = 60.dp, stroke = 7.dp) {
                        Icon(Icons.Default.Payments, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(mdMoney(currency, paid + quoted), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "Paid ${mdMoney(currency, paid)} · still to pay ${mdMoney(currency, quoted)}",
                            style = MaterialTheme.typography.bodyMedium, color = Night.TextSoft,
                        )
                    }
                }
            }
        }
        item(key = "add") {
            // In the page itself, so it is there on opening; the form opens in its place.
            if (editing == MD_NEW) MdCostEditor(null, onClose = { editing = null })
            else BigAction(
                Icons.Default.Add, if (mayPlan) "Add a cost" else "Suggest a cost", { editing = MD_NEW },
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        items(suggested, key = { "p" + it.id }) { p -> MdSuggestedCost(p) }
        if (expenses.isEmpty() && suggested.isEmpty() && editing == null) item(key = "empty") {
            EmptyState(Icons.Default.ReceiptLong, "No costs yet")
        }
        items(expenses, key = { it.id }) { e ->
            if (editing == e.id) MdCostEditor(e, onClose = { editing = null })
            else MdCostRow(e, changes[e.id].orEmpty(), onOpen = { editing = e.id })
        }
    }
}

/** A cost someone has suggested and that is not a cost yet, with the tag and, for an Organiser, the answer buttons. */
@Composable
private fun MdSuggestedCost(p: Proposal) {
    val data = appData()
    val e = p.expense ?: return
    val vehicle = data.vehicle()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).glass(RoundedCornerShape(22.dp), shadow = 4.dp).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MdThumb(e.id, mdCategoryIcon(e.category))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(mdCostTitle(e, vehicle), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(fmtDayShort(e.day), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Text(mdMoney(data.move.currency, e.amount), style = MaterialTheme.typography.titleMedium)
        }
        MdApproval(p, Modifier.padding(start = 6.dp, end = 6.dp, bottom = 4.dp))
    }
}

/**
 * One cost as a glass row, led by its receipt when there is one. Whoever may pay it
 * (an Organiser, or the Decider whose suggestion was approved) gets "Mark paid" and the
 * camera for the receipt right on the row. Tapping the row opens it for changes.
 */
@Composable
private fun MdCostRow(e: Expense, changes: List<Proposal>, onOpen: () -> Unit) {
    val store = LocalStore.current
    val context = LocalContext.current
    val data = appData()
    val vehicle = data.vehicle()
    val currency = data.move.currency
    val mayPay = data.mayPay(store.me, e)
    var camera by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).glass(RoundedCornerShape(22.dp), shadow = 4.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Change", onClick = onOpen).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MdThumb(e.id, mdCategoryIcon(e.category))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(mdCostTitle(e, vehicle), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(mdCategoryLabel(e.category, vehicle).takeIf { e.title.isNotBlank() }, fmtDayShort(e.day)).filter { !it.isNullOrBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (e.planned) GlassChip("Not paid yet", icon = Icons.Default.Schedule, tone = GlassTone.Amber)
            }
            Spacer(Modifier.width(12.dp))
            Text(mdMoney(currency, e.amount), style = MaterialTheme.typography.titleMedium)
        }
        changes.forEach { p ->
            MdApproval(p, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), what = p.expense?.let { mdCostChange(e, it, currency, vehicle) })
        }
        if (e.planned && mayPay) {
            Row(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                MdRowButton("Mark paid", onClick = { mdTold(context, store.markPaid(e.id)) }, icon = Icons.Default.Check)
                MdRowButton("Receipt", onClick = { camera = true }, forward = false, icon = Icons.Default.PhotoCamera)
            }
        }
    }
    if (camera) {
        CameraDialog(
            hint = "Receipt", newFile = { store.newPhotoFile() },
            onPhoto = { mdTold(context, store.attachReceipt(e.id, it)) }, onClose = { camera = false },
        )
    }
}

/**
 * The receipts of a cost: one wide camera button, the gallery, then what has been taken.
 * A cost that is [saved] takes its receipts through the Store's own check, which says why
 * when it refuses; one still being entered keeps them until it is saved or abandoned.
 */
@Composable
private fun MdReceipts(expenseId: String, saved: Boolean, modifier: Modifier = Modifier) {
    val store = LocalStore.current
    val context = LocalContext.current
    val photos = appData().photosOf(expenseId, PhotoKind.RECEIPT)
    var camera by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Photo?>(null) }
    fun keep(file: java.io.File) {
        if (saved) mdTold(context, store.attachReceipt(expenseId, file)) else store.addPhoto(expenseId, PhotoKind.RECEIPT, file)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        uris.forEach { uri ->
            val f = store.newPhotoFile()
            if (importImage(context, uri, f)) keep(f)
        }
    }
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.glass(RoundedCornerShape(18.dp), GlassTone.Mint, shadow = 0.dp).clickable { camera = true }
                .heightIn(min = 60.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = Night.Mint)
            Spacer(Modifier.width(10.dp))
            Text(if (photos.isEmpty()) "Photograph the receipt" else "Another photo", style = MaterialTheme.typography.labelLarge, color = Night.MintPale)
        }
        GlassOrb(Icons.Default.PhotoLibrary, "Add a receipt from the gallery", onClick = {
            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        })
        photos.forEach { p -> PhotoThumb(p, Modifier.size(60.dp).clip(RoundedCornerShape(16.dp)).clickable { viewing = p }) }
    }
    if (camera) CameraDialog(hint = "Receipt", newFile = { store.newPhotoFile() }, onPhoto = { keep(it) }, onClose = { camera = false })
    viewing?.let { p -> PhotoViewer(p, onClose = { viewing = null }) }
}

/** How much: the amount large, four round buttons to nudge it, round sums to tap, and the keyboard behind "Other amount". */
@Composable
private fun MdAmount(amount: Double, currency: String, onChange: (Double) -> Unit) {
    var other by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MdNudge("−10", "10 less") { onChange((amount - 10).coerceAtLeast(0.0)) }
            MdNudge("−1", "1 less") { onChange((amount - 1).coerceAtLeast(0.0)) }
            Text(
                mdMoney(currency, amount), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f),
            )
            MdNudge("+1", "1 more") { onChange(amount + 1) }
            MdNudge("+10", "10 more") { onChange(amount + 10) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            MD_AMOUNTS.forEach { preset -> GlassChip(fmtNum(preset), selected = amount == preset, onClick = { onChange(preset) }) }
            GlassChip("Other amount", icon = Icons.Default.Keyboard, selected = other, onClick = { other = !other })
        }
        if (other) {
            NumberInput(
                amount.takeIf { it != 0.0 }, { onChange(it ?: 0.0) },
                if (currency.isBlank()) "Amount" else "Amount (${currency.trim()})", Modifier.fillMaxWidth(), key = "other",
            )
        }
    }
}

@Composable
private fun MdNudge(text: String, description: String, onClick: () -> Unit) {
    GlassOrb(contentDescription = description, size = 42.dp, onClick = onClick) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/**
 * Adding or changing a cost without the keyboard: a picture tile for what it was for,
 * round sums and a stepper for how much, and a photo of the receipt in place of a
 * description. A name and another date are there for whoever wants them, folded away.
 * For an Organiser it saves; for a Decider it is sent as a suggestion to approve.
 */
@Composable
private fun MdCostEditor(existing: Expense?, onClose: () -> Unit) {
    val store = LocalStore.current
    val context = LocalContext.current
    val data = appData()
    val currency = data.move.currency
    val vehicle = data.vehicle()
    val mayPlan = mdCan(Action.DECIDE)
    val id = rememberSaveable { existing?.id ?: newId() }
    // A new cost starts as the last one did: the same kind of thing is often paid again.
    val last = remember { data.liveExpenses.maxByOrNull { it.updatedAt } }
    var category by rememberSaveable {
        mutableStateOf(existing?.category?.takeIf { it.isNotBlank() } ?: last?.category?.takeIf { it in EXPENSE_CATEGORIES } ?: EXPENSE_CATEGORIES.first())
    }
    var amount by rememberSaveable { mutableStateOf(existing?.amount ?: mdLastAmount(data, category)) }
    var touched by rememberSaveable { mutableStateOf(existing != null) }
    var planned by rememberSaveable { mutableStateOf(existing?.planned ?: false) }
    var title by rememberSaveable { mutableStateOf(existing?.title ?: "") }
    var day by rememberSaveable { mutableStateOf(existing?.day ?: today()) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Paying and the receipt are for an Organiser, or the Decider whose suggestion for this cost was approved.
    val mayPay = if (existing != null) data.mayPay(store.me, existing) else mayPlan

    fun close(keepPhotos: Boolean) {
        // Receipts photographed for a cost that was then abandoned go with it.
        if (existing == null && !keepPhotos) store.data.value.photosOf(id).forEach { store.delete(it) }
        onClose()
    }

    fun save() {
        if (existing == null) {
            val e = Expense(id = id, title = title.trim(), amount = amount, category = category, day = day, planned = planned)
            if (!mayPlan) {
                if (mdTold(context, store.suggestCost(e.copy(planned = true)))) close(keepPhotos = true)
            } else if (mdMay(context, store, Action.DECIDE)) {
                store.put(e)
                close(keepPhotos = true)
            }
        } else {
            val now = store.data.value.expense(id) ?: existing
            val next = now.copy(title = title.trim(), amount = amount, category = category, day = day)
            if (!mayPlan) {
                if (next == now || mdTold(context, store.suggestCost(next))) onClose()
            } else if (mdMay(context, store, Action.DECIDE)) {
                store.put(next)
                onClose()
            }
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).glass(RoundedCornerShape(26.dp), sheen = true).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    existing != null -> mdCostTitle(existing, vehicle)
                    mayPlan -> "New cost"
                    else -> "Suggest a cost"
                },
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            GlassOrb(Icons.Default.Close, "Close without saving", size = 40.dp, onClick = { close(keepPhotos = false) })
        }
        IconChoice(
            EXPENSE_CATEGORIES.map { Choice(it, mdCategoryLabel(it, vehicle), mdCategoryIcon(it)) },
            selected = category,
            onPick = { c ->
                category = c
                if (!touched) amount = mdLastAmount(data, c)
            },
            columns = if (mdNarrow(310f)) 2 else 4,
        )
        MdAmount(amount, currency) {
            amount = it
            touched = true
        }
        val unpaid = if (existing != null) existing.planned else planned
        if (mayPay) MdReceipts(id, saved = existing != null)
        else if (existing == null) Hint("An Organiser approves it. Then you can mark it paid and add the receipt.")
        // What is rarely changed: a quote that is not paid yet, a name, another day.
        Fold(
            "Details", Icons.Default.Tune,
            summary = listOfNotNull(
                (if (unpaid) "Not paid yet" else "Paid").takeIf { mayPay }, title.ifBlank { "No name" }, fmtDayShort(day),
            ).joinToString(" · "),
            key = "costmore:$id",
        ) {
            if (mayPay) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                fun setUnpaid(p: Boolean) {
                    if (existing != null) mdTold(context, store.markPaid(id, paid = !p)) else planned = p
                }
                GlassChip("Paid", selected = !unpaid, onClick = { setUnpaid(false) })
                GlassChip("Not paid yet", selected = unpaid, onClick = { setUnpaid(true) })
            }
            TextInput(title, { title = it }, "Name", key = id)
            DateButton(day, { d -> if (d != null) day = d }, "Date", allowClear = false)
        }
        BigAction(
            if (mayPlan) Icons.Default.Check else Icons.Default.Send,
            when {
                amount <= 0.0 -> "Choose an amount"
                existing != null && mayPlan -> "Save · " + mdMoney(currency, amount)
                existing != null -> "Suggest this change"
                mayPlan -> "Add cost · " + mdMoney(currency, amount)
                else -> "Suggest cost · " + mdMoney(currency, amount)
            },
            onClick = { save() }, enabled = amount > 0.0,
        )
        if (existing != null && mayPlan) {
            GlassChip("Delete this cost", icon = Icons.Default.Delete, onClick = { confirmDelete = true })
        }
    }

    if (confirmDelete && existing != null) ConfirmDialog(
        title = "Delete this cost?",
        text = mdCostTitle(existing, vehicle) + " · " + mdMoney(currency, existing.amount),
        onConfirm = {
            if (mdMay(context, store, Action.DECIDE)) {
                store.data.value.photosOf(existing.id).forEach { store.delete(it) }
                store.delete(existing)
                onClose()
            }
        },
        onDismiss = { confirmDelete = false },
    )
}
