package app.housemove.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Chair
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
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
    Tab("home", "Home", Icons.Outlined.Home),
    Tab("items", "Items", Icons.Outlined.Chair),
    Tab("boxes", "Boxes", Icons.Outlined.Inventory2),
    Tab("moveday", "Move day", Icons.Outlined.LocalShipping),
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
 * Standard page frame: glass top bar, optional dock at the bottom, content column.
 * Pass [tab] (the route of the selected destination) on top-level screens to show the dock.
 * The content column does not scroll by itself; use [ScrollColumn] or a LazyColumn inside.
 * [subtitle] is a small line above the title. Buttons in [actions] should be [TopAction]s.
 */
@Composable
fun Screen(
    title: String,
    back: Boolean = true,
    tab: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    fab: @Composable () -> Unit = {},
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Frame(tab = tab, topBar = { TopBar(title, subtitle, back, actions) }, fab = fab, content = content)
}

/**
 * What [Screen] is built on, for the rare screen that needs its own top bar (Home).
 * Screens are transparent: the ground is drawn once by [AppBackground]. The dock floats
 * over the bottom of the content, which is given room so nothing ends up under it.
 * [dockAction] puts a large glowing button in the middle of a taller dock.
 */
@Composable
fun Frame(
    tab: String?,
    topBar: @Composable () -> Unit,
    fab: @Composable () -> Unit = {},
    dockAction: DockAction? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tabs = if (tab == null) emptyList() else dockTabs(appData())
    val labelHeight = with(LocalDensity.current) { 16.sp.toDp() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val plan = if (tab == null) null else dockPlan(maxWidth, tabs.size, dockAction != null, labelHeight)
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = Night.Text,
            topBar = topBar,
            // Keeps the content and any floating button clear of the dock, which is drawn on top below.
            bottomBar = { if (plan != null) Spacer(Modifier.navigationBarsPadding().height(plan.reserve)) },
            floatingActionButton = fab,
        ) { inner ->
            Column(
                Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner).imePadding(),
                content = content,
            )
        }
        if (plan != null && tab != null) Dock(tab, tabs, plan, dockAction, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun TopBar(title: String, subtitle: String?, back: Boolean, actions: @Composable RowScope.() -> Unit) {
    val nav = LocalNav.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp).heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (back) {
            GlassOrb(Icons.Default.ChevronLeft, "Back", onClick = { nav.popBackStack() })
            Spacer(Modifier.width(12.dp))
        }
        // With a line above it and a button either side, the title is centred, as in the design.
        val centred = back && subtitle != null
        Column(
            Modifier.weight(1f).semantics(mergeDescendants = true) { heading() },
            horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Night.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                title,
                style = if (back) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** A round glass button for the top bar. Every one needs a [description]: there is no label. */
@Composable
fun TopAction(icon: ImageVector, description: String, kind: OrbKind = OrbKind.Plain, onClick: () -> Unit) {
    GlassOrb(icon, description, kind = kind, onClick = onClick)
}

/** Search and scan buttons for the top bar of top-level screens. */
@Composable
fun TopActions() {
    val nav = LocalNav.current
    TopAction(Icons.Default.Search, "Find anything") { nav.open("search") }
    TopAction(Icons.Default.QrCodeScanner, "Scan a label") { nav.open("scan") }
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

/**
 * Tabs across the top of a screen's content: one glass pill, with the chosen tab lit
 * in mint glass inside it. Use in place of Material's TabRow.
 */
@Composable
fun GlassTabRow(selected: Int, modifier: Modifier = Modifier, tabs: @Composable () -> Unit) {
    TabRow(
        selectedTabIndex = selected,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp).glass(CircleShape, shadow = 4.dp),
        containerColor = Color.Transparent,
        contentColor = Night.Text,
        // The chosen tab: mint glass behind its words (which Material itself announces as selected).
        indicator = { positions ->
            if (selected < positions.size) {
                Box(Modifier.tabIndicatorOffset(positions[selected]).fillMaxHeight().zIndex(-1f).padding(4.dp).glass(CircleShape, GlassTone.Mint, shadow = 0.dp))
            }
        },
        divider = {},
        tabs = tabs,
    )
}

/** A titled glass panel. [tone] tints it when the whole panel says something (a warning, a result). */
@Composable
fun Section(
    title: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    tone: GlassTone = GlassTone.Plain,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(modifier.fillMaxWidth(), tone = tone, shape = RoundedCornerShape(26.dp), sheen = true) {
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
        colors = glassFieldColors(),
        modifier = modifier,
    )
}

/**
 * Colours for text fields on the night ground: a faint glass fill, a rim that is still
 * easy to see, and mint when the field has focus. Pass as `colors` to any OutlinedTextField.
 */
@Composable
fun glassFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color(0x14FFFFFF),
    unfocusedContainerColor = Color(0x0DFFFFFF),
    disabledContainerColor = Color(0x08FFFFFF),
    errorContainerColor = Color(0x14FF6B5E),
    focusedBorderColor = Night.Mint,
    unfocusedBorderColor = Color(0x70FFFFFF),
    focusedLabelColor = Night.MintPale,
    unfocusedLabelColor = Night.TextDim,
    focusedTextColor = Night.Text,
    unfocusedTextColor = Night.Text,
    cursorColor = Night.Mint,
    focusedPlaceholderColor = Night.TextDim,
    unfocusedPlaceholderColor = Night.TextDim,
    focusedLeadingIconColor = Night.TextDim,
    unfocusedLeadingIconColor = Night.TextDim,
    focusedTrailingIconColor = Night.Text,
    unfocusedTrailingIconColor = Night.TextDim,
)

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
        colors = glassFieldColors(),
        modifier = modifier,
    )
}

/**
 * A switch with its label. The whole row is the switch: one thing to tap, and one stop
 * for a screen reader, which says the label and whether it is on.
 */
@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, sub: String? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Hint(sub)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A tick box with its label. Like [SwitchRow], the whole row is the tick box. */
@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, sub: String? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(14.dp))
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
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            GlassChip(label(o), selected = o == selected, onClick = { onPick(o) })
        }
    }
}

/** Date button with a picker. A null day means "not set". */
@Composable
fun DateButton(day: Long?, onPick: (Long?) -> Unit, label: String, modifier: Modifier = Modifier, allowClear: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    GlassPill(modifier, onClick = { open = true }, sheen = true, horizontalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Event, contentDescription = null, tint = Night.Mint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (day == null) label else "$label: ${fmtDay(day)}", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
    if (open) DatePickerPanel(day, label, allowClear, onPick, onClose = { open = false })
}

// ---- things that open over a screen ------------------------------------------

/**
 * A question or a short form over the screen, in the look: the solid glass panel of
 * [dialogPanel], the title (with its [icon] in an orb, if given), a line of [text] and/or
 * [content], and [buttons] along the bottom, right-aligned. Buttons are the kit's
 * [PillButton]s: glass to go back, mint for the thing to do, red glass for what deletes.
 * Tapping outside it or Back calls [onDismiss], as with any dialog.
 */
@Composable
fun GlassDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    icon: ImageVector? = null,
    buttons: @Composable FlowRowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    BasicAlertDialog(onDismissRequest = onDismiss, modifier = modifier) {
        Column(
            Modifier.dialogPanel().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides Night.Text) {
                Row(Modifier.semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        GlassOrb(icon, null, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(title, style = MaterialTheme.typography.titleLarge)
                }
                // What is said in between gives way first, so the buttons stay on screen.
                Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (text != null) Text(text, style = MaterialTheme.typography.bodyLarge, color = Night.TextSoft)
                    content()
                }
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = buttons,
                )
            }
        }
    }
}

/** The button that closes a [GlassDialog] without doing anything: clear glass. */
@Composable
fun DialogCancel(onClick: () -> Unit, label: String = "Cancel") = PillButton(label, onClick, primary = false)

/**
 * Asks before something is done. [danger] (deleting, erasing) puts the [confirm] word on red
 * glass with a bin, as on the Delete buttons of the pages; otherwise it is the mint pill.
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String = "Delete",
    danger: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    GlassDialog(title, onDismiss, text = text, buttons = {
        DialogCancel(onDismiss)
        PillButton(
            confirm, onClick = { onConfirm(); onDismiss() },
            icon = if (danger) Icons.Default.Delete else null, primary = !danger, tone = if (danger) GlassTone.Red else GlassTone.Plain,
        )
    })
}

/**
 * The calendar for picking a day, on the panel of a [GlassDialog], with [label] (what the day
 * is for) as its title and the kit's buttons. Material's calendar is 360dp wide whatever the
 * screen, so on a narrower phone the whole panel is drawn smaller rather than cut off.
 */
@Composable
fun DatePickerPanel(day: Long?, label: String, allowClear: Boolean, onPick: (Long?) -> Unit, onClose: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = (day ?: today()) * 86_400_000L)
    val fit = ((LocalConfiguration.current.screenWidthDp - 8) / 360f).coerceAtMost(1f)
    val colors = nightDatePickerColors()
    BasicAlertDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.requiredWidth(360.dp).heightIn(max = 568.dp).then(if (fit < 1f) Modifier.scale(fit) else Modifier).dialogPanel()) {
            Box(Modifier.weight(1f, fill = false)) {
                DatePicker(
                    state = state, colors = colors,
                    title = { Text(label, Modifier.padding(start = 24.dp, end = 12.dp, top = 18.dp), style = MaterialTheme.typography.labelLarge, color = Night.MintPale) },
                )
            }
            FlowRow(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (allowClear) PillButton("Clear", onClick = { onPick(null); onClose() }, primary = false)
                DialogCancel(onClose)
                PillButton("OK", onClick = {
                    state.selectedDateMillis?.let { onPick(Math.floorDiv(it, 86_400_000L)) }
                    onClose()
                })
            }
        }
    }
}

/** The calendar in the kit's colours: mint for the chosen day and year, a mint ring round today, glass for typing a date. */
@Composable
private fun nightDatePickerColors(): DatePickerColors = DatePickerDefaults.colors(
    containerColor = Color.Transparent,
    titleContentColor = Night.MintPale,
    headlineContentColor = Night.Text,
    weekdayContentColor = Night.TextDim,
    subheadContentColor = Night.TextDim,
    navigationContentColor = Night.Text,
    yearContentColor = Night.Text,
    currentYearContentColor = Night.MintLight,
    selectedYearContentColor = Night.OnMint,
    selectedYearContainerColor = Night.Mint,
    dayContentColor = Night.Text,
    selectedDayContentColor = Night.OnMint,
    selectedDayContainerColor = Night.Mint,
    todayContentColor = Night.MintLight,
    todayDateBorderColor = Night.Mint,
    dividerColor = Night.Hairline,
    dateTextFieldColors = glassFieldColors(),
)

// ---- display ---------------------------------------------------------------

@Composable
fun ProgressRow(label: String, ratio: Ratio, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(ratio.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        Meter(ratio.fraction, Modifier.fillMaxWidth(), height = 8.dp)
    }
}

fun RoomColor.color() = Color(argb)
fun RoomColor.onColor() = if (darkText) Color.Black else Color.White

/**
 * Coloured pill showing a room. Shows "No room" when null. The colour is the one on the
 * tape and the door sign, so it is not adjusted for the dark ground; a light rim keeps
 * the darker ones (navy, brown, grey) visible against it.
 *
 * Squeezed into a narrow space it never dwindles to "…": it shows the room's short code
 * (the one on its labels, "KIT") and, with no room even for that, its colour alone.
 * With [maxLines] 2 a name of two words that does not fit on one line takes two
 * ("Main / bedroom") before anything is shortened.
 */
@Composable
fun RoomBadge(room: Room?, modifier: Modifier = Modifier, maxLines: Int = 1) {
    val c = room?.takeIf { it.home == Home.DEST }?.let { roomColor(it.color) }
    val bg = c?.color() ?: Color(0xFF26364B)
    val fg = c?.onColor() ?: Night.TextDim
    val shape = RoundedCornerShape(8.dp)
    val name = room?.name ?: "No room"
    val code = room?.let { r -> r.code.ifBlank { suggestRoomCode(r.name) } } ?: "–"
    val style = MaterialTheme.typography.labelMedium
    Layout(
        content = {
            Text(name, color = fg, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
            // The stand-in is read out as the room's name, and is not a second text for anything that looks for texts.
            Text(code, color = fg, style = style, maxLines = 1, softWrap = false, modifier = Modifier.clearAndSetSemantics { contentDescription = name })
        },
        modifier = modifier.clip(shape).background(bg).border(1.dp, Color.White.copy(alpha = if (c != null) 0.42f else 0.2f), shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val (full, short) = measurables
        // The whole name where it fits (on two lines if allowed and its longest word fits); a shortened
        // name only where enough of it is left to read.
        val shown = when {
            full.maxIntrinsicWidth(constraints.maxHeight) <= constraints.maxWidth || constraints.maxWidth >= 56.dp.roundToPx() -> full.measure(loose)
            maxLines > 1 && full.minIntrinsicWidth(constraints.maxHeight) <= constraints.maxWidth -> full.measure(loose)
            short.maxIntrinsicWidth(constraints.maxHeight) <= constraints.maxWidth -> short.measure(loose)
            else -> null
        }
        val height = shown?.height ?: short.measure(loose).height
        layout(maxOf(shown?.width ?: 0, constraints.minWidth), maxOf(height, constraints.minHeight)) { shown?.place(0, 0) }
    }
}

@Composable
fun ColorDot(index: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(14.dp).clip(CircleShape).background(roomColor(index).color()).border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape))
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
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        rooms.forEach { r ->
            GlassChip(
                r.name,
                selected = r.id == selectedId,
                onClick = { onPick(if (r.id == selectedId && allowNone) null else r.id) },
                leading = if (home == Home.DEST) {
                    { ColorDot(r.color) }
                } else null,
            )
        }
    }
}

/** Where something is, in words. Pass [vehicle] from `data.vehicle()` so movers read "truck". */
fun stageText(stage: Stage, isBox: Boolean, vehicle: String = "van"): String = when (stage) {
    Stage.AT_ORIGIN -> "At old home"
    Stage.LOADED -> "On the $vehicle"
    Stage.DELIVERED -> "Delivered"
    Stage.DONE -> if (isBox) "Unpacked" else "In place"
}

/**
 * Where something is now, in words: as [stageText] says it, except "Carried by hand" for what
 * is on its way with someone rather than on the van (the Store's [statusOf]).
 */
fun AppData.statusText(m: Movable): String = statusOf(m).let { if (it == m.stage.label) stageText(m.stage, m.isBox, vehicle()) else it }

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
                .background(dest?.let { roomColor(it.color).color() } ?: Night.Hairline)
                .border(1.dp, Color.White.copy(alpha = if (dest != null) 0.4f else 0f), RoundedCornerShape(3.dp))
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
                    "→ " + (dest?.name ?: "no room") + " · " + data.statusText(m),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (m.fragile) Flag("Fragile")
                if (m.upright) Flag("Upright")
                if (m.heavy) Flag("Heavy")
                if (m.damaged) Flag("Damaged", error = true)
                if (m.priority == Priority.FIRST_NIGHT) Flag("First night")
            }
        }
        trailing?.invoke()
    }
}

/** The fill and text colours of a flag: amber for "take care", red for "something is wrong". */
internal fun flagColors(error: Boolean): Pair<Color, Color> =
    if (error) Night.RedDeep.copy(alpha = 0.30f) to Night.RedPale else Night.AmberDeep.copy(alpha = 0.24f) to Night.AmberPale

@Composable
fun Flag(text: String, error: Boolean = false) {
    val (bg, fg) = flagColors(error)
    Text(
        text,
        color = fg,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** A big tappable menu row with its picture in a small glass orb, used on hub screens. */
@Composable
fun MenuRow(icon: ImageVector, title: String, sub: String? = null, badge: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassOrb(size = 40.dp) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Hint(sub)
        }
        if (badge != null) {
            GlassChip(badge)
            Spacer(Modifier.width(8.dp))
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun toast(context: android.content.Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
