package app.housemove.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.housemove.data.*

val LocalStore = staticCompositionLocalOf<Store> { error("Store not provided") }
val LocalNav = staticCompositionLocalOf<NavHostController> { error("Nav not provided") }

/** The current dataset; recomposes when anything changes. */
@Composable
fun appData(): AppData = LocalStore.current.data.collectAsState().value

@Composable
fun deviceInfo(): DeviceInfo = LocalStore.current.device.collectAsState().value

// ---- navigation ------------------------------------------------------------

data class Tab(val route: String, val label: String, val icon: ImageVector)

val TABS = listOf(
    Tab("home", "Home", Icons.Default.Home),
    Tab("items", "Items", Icons.Default.Chair),
    Tab("boxes", "Boxes", Icons.Default.Inventory2),
    Tab("moveday", "Move day", Icons.Default.LocalShipping),
    Tab("more", "More", Icons.Default.Menu),
)

fun NavHostController.goTab(route: String) = navigate(route) {
    popUpTo("home") { saveState = true }
    launchSingleTop = true
    restoreState = true
}

fun NavHostController.open(route: String) = navigate(route) { launchSingleTop = true }

fun NavHostController.openMovable(m: Movable) = open(if (m.isBox) "box/${m.id}" else "item/${m.id}")

/**
 * Standard page frame: top bar, optional bottom tabs, content column.
 * Pass [tab] (the route of the selected tab) on top-level screens to show the bottom bar.
 * The content column does not scroll by itself; use [ScrollColumn] or a LazyColumn inside.
 */
@Composable
fun Screen(
    title: String,
    back: Boolean = true,
    tab: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    fab: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val nav = LocalNav.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (back) IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = actions,
            )
        },
        bottomBar = {
            if (tab != null) NavigationBar {
                TABS.forEach { t ->
                    NavigationBarItem(
                        selected = t.route == tab,
                        onClick = { if (t.route != tab) nav.goTab(t.route) },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label, maxLines = 1) },
                    )
                }
            }
        },
        floatingActionButton = fab,
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner).imePadding(),
            content = content,
        )
    }
}

/** Search and scan buttons for the top bar of top-level screens. */
@Composable
fun TopActions() {
    val nav = LocalNav.current
    IconButton(onClick = { nav.open("search") }) { Icon(Icons.Default.Search, contentDescription = "Find anything") }
    IconButton(onClick = { nav.open("scan") }) { Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan a label") }
}

// ---- layout helpers --------------------------------------------------------

@Composable
fun ScrollColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** A titled card. */
@Composable
fun Section(
    title: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null || action != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title ?: "", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    action?.invoke()
                }
            }
            content()
        }
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(24.dp),
    )
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

// ---- inputs ----------------------------------------------------------------

/**
 * Text field that keeps its own editing state and reports every change.
 * Pass the record id as [key] so the field resets when it shows a different record.
 * [mic] adds a dictation button that appends what was said.
 */
@Composable
fun TextInput(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    key: Any? = null,
    singleLine: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text,
    mic: Boolean = false,
    placeholder: String? = null,
) {
    var text by remember(key) { mutableStateOf(value) }
    val context = LocalContext.current
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val said = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) {
            text = if (text.isBlank()) said else "$text, $said"
            onChange(text)
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, capitalization = KeyboardCapitalization.Sentences),
        trailingIcon = if (!mic) null else {
            {
                IconButton(onClick = {
                    try {
                        speech.launch(
                            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        )
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, "No voice input on this phone. Use the keyboard's microphone.", Toast.LENGTH_LONG).show()
                    }
                }) { Icon(Icons.Default.Mic, contentDescription = "Dictate") }
            }
        },
        modifier = modifier,
    )
}

@Composable
fun NumberInput(
    value: Double?,
    onChange: (Double?) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    key: Any? = null,
) {
    var text by remember(key) { mutableStateOf(fmtNum(value)) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it.replace(',', '.').trim().toDoubleOrNull())
        },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, sub: String? = null) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Hint(sub)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, sub: String? = null) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Hint(sub)
        }
    }
}

/** A wrapping row of single-choice chips. */
@Composable
fun <T> ChipPicker(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onPick(o) }, label = { Text(label(o)) })
        }
    }
}

/** Date button with a picker. A null day means "not set". */
@Composable
fun DateButton(day: Long?, onPick: (Long?) -> Unit, label: String, modifier: Modifier = Modifier, allowClear: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier) {
        Icon(Icons.Default.Event, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (day == null) label else "$label: ${fmtDay(day)}")
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (day ?: today()) * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onPick(Math.floorDiv(it, 86_400_000L)) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = {
                Row {
                    if (allowClear) TextButton(onClick = { onPick(null); open = false }) { Text("Clear") }
                    TextButton(onClick = { open = false }) { Text("Cancel") }
                }
            },
        ) { DatePicker(state = state) }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String = "Delete",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- display ---------------------------------------------------------------

@Composable
fun ProgressRow(label: String, ratio: Ratio, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(ratio.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { ratio.fraction },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
        )
    }
}

fun RoomColor.color() = Color(argb)
fun RoomColor.onColor() = if (darkText) Color.Black else Color.White

/** Coloured pill showing a room. Shows "No room" when null. */
@Composable
fun RoomBadge(room: Room?, modifier: Modifier = Modifier) {
    val c = room?.let { roomColor(it.color) }
    val bg = if (room?.home == Home.DEST && c != null) c.color() else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (room?.home == Home.DEST && c != null) c.onColor() else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        room?.name ?: "No room",
        color = fg,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun ColorDot(index: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(14.dp).clip(CircleShape).background(roomColor(index).color()))
}

/** Chips for picking a room of one home. New-home rooms show their colour. */
@Composable
fun RoomPicker(
    home: Home,
    selectedId: String?,
    onPick: (String?) -> Unit,
    modifier: Modifier = Modifier,
    allowNone: Boolean = true,
) {
    val rooms = appData().roomsOf(home)
    if (rooms.isEmpty()) {
        Hint("No rooms yet. Add them under More → Rooms.", modifier)
        return
    }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        rooms.forEach { r ->
            FilterChip(
                selected = r.id == selectedId,
                onClick = { onPick(if (r.id == selectedId && allowNone) null else r.id) },
                label = { Text(r.name) },
                leadingIcon = if (home == Home.DEST) {
                    { ColorDot(r.color) }
                } else null,
            )
        }
    }
}

fun stageText(stage: Stage, isBox: Boolean): String = when (stage) {
    Stage.AT_ORIGIN -> "At old home"
    Stage.LOADED -> "On the van"
    Stage.DELIVERED -> "Delivered"
    Stage.DONE -> if (isBox) "Unpacked" else "In place"
}

/** One row for a box or item, with its destination colour, where it is, and warning flags. */
@Composable
fun MovableRow(
    m: Movable,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val data = appData()
    val nav = LocalNav.current
    val dest = data.room(m.destRoomId)
    Row(
        modifier.fillMaxWidth().clickable { if (onClick != null) onClick() else nav.openMovable(m) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(6.dp).height(44.dp).clip(RoundedCornerShape(3.dp))
                .background(dest?.let { roomColor(it.color).color() } ?: MaterialTheme.colorScheme.outlineVariant)
        )
        Spacer(Modifier.width(12.dp))
        Icon(
            if (m.isBox) Icons.Default.Inventory2 else Icons.Default.Chair,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(m.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "→ " + (dest?.name ?: "no room") + " · " + stageText(m.stage, m.isBox),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (m.fragile) Flag("Fragile")
                if (m.heavy) Flag("Heavy")
                if (m.damaged) Flag("Damaged", error = true)
                if (m.priority == Priority.FIRST_NIGHT) Flag("First night")
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun Flag(text: String, error: Boolean = false) {
    val bg = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Text(
        text,
        color = fg,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** A big tappable menu row with an icon, used on hub screens. */
@Composable
fun MenuRow(icon: ImageVector, title: String, sub: String? = null, badge: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Hint(sub)
        }
        if (badge != null) {
            Text(badge, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun toast(context: android.content.Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
