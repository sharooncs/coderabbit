package app.housemove.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

/*
 * What the packing screens share: the pictures and words for each kind of container and
 * each category, the photo-led row a container is shown as in every list, and a few
 * small pieces (a photo slot, compact picture tiles, the note that says why the app
 * refused something).
 *
 * Wording: "box" stays the everyday word ("Pack a box", "Boxes"). A particular thing is
 * called what it is ("Seal suitcase"); "container" is only used where any kind is meant.
 */

// ---- pictures and words ------------------------------------------------------------

internal fun ContainerKind.picture(): ImageVector = when (this) {
    ContainerKind.CARTON -> Pic.Box
    ContainerKind.SUITCASE -> Icons.Default.Luggage
    ContainerKind.BAG -> Icons.Default.ShoppingBag
    ContainerKind.CRATE -> Icons.Default.ShoppingBasket
    ContainerKind.WARDROBE -> Icons.Default.Checkroom
    ContainerKind.OTHER -> Icons.Default.Widgets
}

/** The word on a kind's tile: a carton is a "Box" to everyone but the people who sell them. */
internal fun ContainerKind.tileLabel(): String = if (this == ContainerKind.CARTON) "Box" else label

/** "box", "suitcase", "wardrobe box": the kind in the middle of a sentence. */
internal fun ContainerKind.word(): String = noun.lowercase()

/** "Boxes", "Suitcases": the kind as the name of a group. */
internal fun ContainerKind.groupLabel(): String = when (this) {
    ContainerKind.CARTON -> "Boxes"
    ContainerKind.SUITCASE -> "Suitcases"
    ContainerKind.BAG -> "Bags"
    ContainerKind.CRATE -> "Crates"
    ContainerKind.WARDROBE -> "Wardrobe boxes"
    ContainerKind.OTHER -> "Other"
}

/** The picture for one of [BOX_CATEGORIES]. */
internal fun categoryPicture(category: String): ImageVector = when (category) {
    "Kitchen" -> Icons.Default.Restaurant
    "Clothes" -> Icons.Default.DryCleaning
    "Books" -> Icons.AutoMirrored.Filled.MenuBook
    "Documents" -> Icons.Default.Description
    "Toys" -> Icons.Default.Toys
    "Bathroom" -> Icons.Default.Bathtub
    "Tools" -> Icons.Default.Handyman
    "Electronics" -> Icons.Default.Cable
    "Decor" -> Icons.Default.LocalFlorist
    "Bedding" -> Icons.Default.Bed
    "Food" -> Icons.Default.Fastfood
    else -> Icons.Default.Category
}

/** What a container is called on screen: its own name, or "Suitcase B2" when it has none. */
internal fun Box.shownName(): String = name.ifBlank { "${kind.noun} $label" }

/** A container's name with the code to look for: "Kitchen box 4 (L004)", or "Suitcase B2" when it has no name. */
internal fun Box.nameAndCode(): String = if (name.isBlank()) "${kind.noun} $label" else "$name ($label)"

/** "box L012", "suitcase B2": how one container is pointed at from another's row. */
internal fun Box.pointer(): String = "${kind.word()} $label"

/** The picture for anything that travels: its kind for a container, a piece of furniture for an item. */
internal fun Movable.picture(): ImageVector = if (isBox) (kind ?: ContainerKind.CARTON).picture() else Pic.Item

/** What to watch out for when carrying something, as a picture and a word: the same words in every list. */
internal fun Movable.flags(): List<Pair<ImageVector, String>> = listOfNotNull(
    (Pic.FirstNight to "First night").takeIf { priority == Priority.FIRST_NIGHT },
    (Pic.Fragile to "Fragile").takeIf { fragile },
    (Pic.Upright to if (isBox) "This way up" else "Keep upright").takeIf { upright },
    (if (isBox) Pic.Heavy to "Heavy" else Pic.TwoPeople to "Two people").takeIf { heavy },
)

/**
 * Furniture and appliances are catalogued as well, and nobody packs a wardrobe into a box:
 * what plainly cannot go in a container is not offered one, on its own page or as a picture
 * to tap on a container's. Scanning its label into a container still works.
 */
internal fun Item.couldBePacked(): Boolean =
    !twoPerson && !needsDisassembly && !keepUpright && (volumeM3() ?: 0.0) <= ContainerKind.WARDROBE.m3

/** The category a container counts under: the one chosen, else the one its room and contents suggest. */
internal fun AppData.categoryOf(b: Box): String = b.category.ifBlank { suggestCategory(b) }

private val AUTO_NAME = Regex("^(.* box|Box|Suitcase|Bag|Crate|Wardrobe box|Container) \\d+$", RegexOption.IGNORE_CASE)

/** True while the name is one the app made up ("Kitchen box 4", "Suitcase 2"), so it may follow the kind. */
internal fun Box.hasAutoName(): Boolean = AUTO_NAME.matches(name.trim())

/**
 * [b] as another kind of container. A name the app made up is made up again for the new
 * kind, and "travels with me" follows the kind (a suitcase does, a carton does not)
 * unless the person has already said otherwise.
 */
internal fun AppData.withKind(b: Box, kind: ContainerKind): Box {
    if (kind == b.kind) return b
    return b.copy(
        kind = kind,
        name = if (b.hasAutoName()) defaultBoxName(kind, b.originRoomId) else b.name,
        travelsWithMe = if (b.travelsWithMe == b.kind.travelsWithMe) kind.travelsWithMe else b.travelsWithMe,
    )
}

/** Codes of printed stickers and tags: L and a number. Anything else was written by hand. */
internal val PRINTED_CODE = Regex("^L\\d+$", RegexOption.IGNORE_CASE)

/** L2 before L10, and printed labels grouped apart from each phone's handwritten ones. */
internal val CODE_ORDER = compareBy<Box>(
    { it.label.takeWhile { c -> !c.isDigit() }.uppercase() },
    { it.label.filter { c -> c.isDigit() }.take(9).toIntOrNull() ?: 0 },
    { it.label },
)

// ---- where a container has got to ----------------------------------------------------

/** The states a container goes through, as lists show and filter them. One carried by hand is never "on the van". */
internal enum class PackState { OPEN, SEALED, ON_VAN, BY_HAND, DELIVERED, UNPACKED }

internal fun Box.packState(): PackState = when {
    stage == Stage.LOADED && carriedByHand() -> PackState.BY_HAND
    stage == Stage.LOADED -> PackState.ON_VAN
    stage == Stage.DELIVERED -> PackState.DELIVERED
    stage == Stage.DONE -> PackState.UNPACKED
    sealed -> PackState.SEALED
    else -> PackState.OPEN
}

internal fun PackState.label(vehicle: String): String = when (this) {
    PackState.OPEN -> "Open"
    PackState.SEALED -> "Sealed"
    PackState.ON_VAN -> stageText(Stage.LOADED, isBox = true, vehicle)
    PackState.BY_HAND -> "Carried by hand"
    PackState.DELIVERED -> stageText(Stage.DELIVERED, isBox = true, vehicle)
    PackState.UNPACKED -> stageText(Stage.DONE, isBox = true, vehicle)
}

internal fun PackState.picture(): ImageVector = when (this) {
    PackState.OPEN -> Icons.Default.LockOpen
    PackState.SEALED -> Icons.Default.Lock
    PackState.ON_VAN -> Stage.LOADED.icon()
    PackState.BY_HAND -> Pic.WithMe
    PackState.DELIVERED -> Stage.DELIVERED.icon()
    PackState.UNPACKED -> Stage.DONE.icon()
}

@Composable
internal fun PackStateIcon(state: PackState, vehicle: String, modifier: Modifier = Modifier) {
    Icon(
        state.picture(),
        contentDescription = state.label(vehicle),
        tint = when (state) {
            PackState.OPEN -> Night.TextDim
            PackState.UNPACKED -> Night.MintLight
            else -> Night.Mint
        },
        modifier = modifier,
    )
}

// ---- small pieces --------------------------------------------------------------------

/** Why the app would not do what was just asked, in the Store's own words. */
@Composable
internal fun ContainerRefusal(reason: String, modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    GlassCard(modifier.fillMaxWidth(), tone = GlassTone.Red, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Block, contentDescription = null, tint = Night.RedPale, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(reason, color = Night.RedPale, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            GlassOrb(Icons.Default.Close, "Dismiss", Modifier.padding(end = 4.dp), size = 36.dp, onClick = onDismiss)
        }
    }
}

/**
 * Pick one of several things shown as pictures, like the kit's IconChoice but lower, so
 * two sets of them and the contents fit above the seal button on one screen.
 */
@Composable
internal fun <T> ContainerTiles(options: List<Choice<T>>, selected: T?, columns: Int, modifier: Modifier = Modifier, onPick: (T) -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(columns).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { c ->
                    val on = c.value == selected
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .glass(RoundedCornerShape(16.dp), if (on) GlassTone.Mint else GlassTone.Plain, shadow = 3.dp)
                            .selectable(selected = on, role = Role.RadioButton) { onPick(c.value) },
                    ) {
                        Column(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                        ) {
                            Icon(c.icon, contentDescription = null, tint = if (on) Night.Mint else Night.TextDim, modifier = Modifier.size(22.dp))
                            Text(
                                c.label, style = MaterialTheme.typography.labelMedium, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (on) Night.MintPale else Night.Text, textAlign = TextAlign.Center, maxLines = 2,
                            )
                        }
                        // A tick as well as the tint, so the choice does not depend on colour.
                        if (on) {
                            Icon(
                                Icons.Default.CheckCircle, contentDescription = null, tint = Night.Mint,
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(13.dp),
                            )
                        }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * The picture of a container: its photo with the kind as a small badge, or, until there
 * is a photo, the kind's own picture on glass.
 */
@Composable
internal fun ContainerThumb(box: Box, data: AppData, size: Dp, modifier: Modifier = Modifier) {
    val photo = data.photosOf(box.id, PhotoKind.CONTENTS).firstOrNull() ?: data.photosOf(box.id).firstOrNull()
    val shape = RoundedCornerShape(size * 0.24f)
    Box(modifier.size(size)) {
        if (photo != null) {
            PhotoThumb(photo, Modifier.fillMaxSize().clip(shape).border(1.dp, Night.GlassRim, shape))
            val badge = (size * 0.36f).coerceIn(18.dp, 26.dp)
            Box(
                Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp).size(badge).clip(CircleShape)
                    .background(Night.Panel).border(1.dp, Night.GlassRimHi, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(box.kind.picture(), contentDescription = box.kind.label, tint = Night.Mint, modifier = Modifier.size(badge * 0.6f)) }
        } else {
            Box(Modifier.fillMaxSize().glass(shape, shadow = 0.dp), contentAlignment = Alignment.Center) {
                Icon(box.kind.picture(), contentDescription = box.kind.label, tint = Night.Mint, modifier = Modifier.size(size * 0.46f))
            }
        }
    }
}

/**
 * Where something goes, as everywhere in the app: an arrow and the room in its colour.
 * Tapping it opens the rooms to choose another; [open] says whether they are showing.
 */
@Composable
internal fun GoingTo(dest: Room?, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = if (open) "Close the rooms" else "Choose another room", onClick = onToggle)
            .heightIn(min = 48.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Going to", tint = Night.TextSoft, modifier = Modifier.size(18.dp))
        RoomBadge(dest, Modifier.weight(1f, fill = false))
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

/**
 * The picture that leads a row, the same for a thing wherever it is listed: a container's
 * photo with its kind marked on it, an item's own photo, and for anything else (a cost,
 * say) its first photo. Until there is a photo, [icon] on glass stands in.
 */
@Composable
internal fun ThingThumb(ownerId: String, icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val data = appData()
    val shape = RoundedCornerShape(size * 0.24f)
    val box = data.box(ownerId)
    when {
        box != null -> ContainerThumb(box, data, size, modifier)
        data.item(ownerId) != null -> ItemPhoto(ownerId, modifier.size(size), shape, iconSize = size * 0.46f)
        else -> {
            val photo = data.photosOf(ownerId).firstOrNull()
            if (photo != null) PhotoThumb(photo, modifier.size(size).clip(shape).border(1.dp, Night.GlassRim, shape))
            else Box(modifier.size(size).glass(shape, shadow = 0.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Night.Mint, modifier = Modifier.size(size * 0.46f))
            }
        }
    }
}

/**
 * A square to photograph into: with no photo yet it is one "take a photo" target; with
 * one it shows it, opens it when tapped, and keeps a small camera button for more.
 */
@Composable
internal fun ContainerPhoto(ownerId: String, kind: PhotoKind, emptyText: String, modifier: Modifier = Modifier) {
    val store = LocalStore.current
    val photos = appData().photosOf(ownerId, kind)
    var camera by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Photo?>(null) }
    val shape = RoundedCornerShape(20.dp)
    Box(modifier) {
        val first = photos.firstOrNull()
        if (first == null) {
            Column(
                Modifier.fillMaxSize().glass(shape, shadow = 4.dp)
                    .clickable(role = Role.Button) { camera = true }
                    .semantics { contentDescription = "Take a photo of what's inside" }
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                Icon(Pic.Camera, contentDescription = null, tint = Night.Mint, modifier = Modifier.size(30.dp))
                Text(emptyText, style = MaterialTheme.typography.labelMedium, color = Night.MintPale, textAlign = TextAlign.Center, maxLines = 2)
            }
        } else {
            PhotoThumb(first, Modifier.fillMaxSize().clip(shape).border(1.dp, Night.GlassRim, shape).clickable { viewing = first })
            if (photos.size > 1) {
                Text(
                    "${photos.size}", color = Night.Text, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(CircleShape).background(Night.Panel.copy(alpha = 0.85f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            // A full 48dp to touch in the corner of the photo, though the disc drawn in it is smaller.
            Box(
                Modifier.align(Alignment.BottomEnd).size(48.dp).clip(CircleShape)
                    .clickable(role = Role.Button) { camera = true }
                    .semantics { contentDescription = "Take another photo of what's inside" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(Color(0xCC081220)).border(1.dp, Color.White.copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Pic.Camera, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) }
            }
        }
    }
    if (camera) {
        PhotoCamera(hint = kind.label, onPhoto = { store.addPhoto(ownerId, kind, it) }, onClose = { camera = false })
    }
    viewing?.let { p -> PhotoViewer(p, onClose = { viewing = null }) }
}

// ---- the row -------------------------------------------------------------------------

/** First line of what is written about a container's contents, or null. */
private fun Box.firstContents(): String? = contents.lineSequence().firstOrNull { it.isNotBlank() }?.trim()

/**
 * The words of a row: a name someone chose, then what is inside. A name the app made up
 * ("Kitchen box 4") only repeats the code, the room and the picture next to it, so it
 * gives way to the contents and shows only while nothing else is written down.
 */
private fun Box.rowWords(): String {
    val inside = firstContents()
    val named = name.trim().takeIf { it.isNotEmpty() && (inside == null || !hasAutoName()) }
    return listOfNotNull(named, inside).joinToString(" · ")
}

/**
 * One container as a glass row led by its photo: its code, where it goes in that room's
 * colour, what is in it, anything to watch out for and where it has got to.
 *
 * [children] are the containers inside it, shown as smaller rows under it. [inside] is
 * the container it is itself in, for when that one is not shown above it: the row then
 * carries a clear "Inside suitcase B1" mark. [trailing] replaces the picture of its state.
 */
@Composable
internal fun ContainerRow(
    box: Box,
    data: AppData,
    modifier: Modifier = Modifier,
    inside: Box? = null,
    children: List<Box> = emptyList(),
    showRoom: Boolean = inside == null,
    trailing: (@Composable () -> Unit)? = null,
    onOpen: (Box) -> Unit,
) {
    val vehicle = data.vehicle()
    // Counted as on every screen: the items in it, the containers inside it and what those hold.
    val carried = data.carriedBy(box.id)
    val scanned = data.entriesOf(box.id).size
    GlassCard(modifier.fillMaxWidth(), onClick = { onOpen(box) }, shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ContainerThumb(box, data, 64.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The code is what people look for on the thing itself, so it is never cut short: the room's name gives way.
                    Text(box.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    // Something inside another goes where that one goes, so it shows no room of its own.
                    // A long room name takes a second line before it is shortened.
                    if (showRoom) RoomBadge(data.room(box.destRoomId), Modifier.weight(1f, fill = false), maxLines = 2)
                }
                val words = box.rowWords()
                val nothing = words.isEmpty() && carried == 0 && scanned == 0
                if (words.isNotEmpty() || nothing) {
                    Text(
                        words.ifEmpty { "Nothing written down yet" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (nothing) Night.TextDim else Night.Text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                val flagged = box.priority != Priority.WHENEVER || box.fragile || box.heavy || box.thisWayUp || box.travelsWithMe || box.hazardous || box.damaged
                if (flagged || inside != null || carried > 0 || scanned > 0) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (inside != null) GlassChip("Inside ${inside.pointer()}", icon = Icons.Default.SubdirectoryArrowRight, tone = GlassTone.Amber)
                        InsideChip(carried)
                        if (scanned > 0) GlassChip("$scanned scanned", icon = Icons.Default.QrCodeScanner)
                        if (box.priority != Priority.WHENEVER) IconFlag(box.priority.icon(), box.priority.label)
                        if (box.fragile) IconFlag(Pic.Fragile, "Fragile")
                        if (box.heavy) IconFlag(Pic.Heavy, "Heavy")
                        if (box.thisWayUp) IconFlag(Pic.Upright, "This way up")
                        if (box.travelsWithMe) IconFlag(Pic.WithMe, "With me")
                        if (box.hazardous) IconFlag(Pic.Hazard, "Hazardous", error = true)
                        if (box.damaged) IconFlag(Pic.Damage, "Damaged", error = true)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            if (trailing != null) trailing() else PackStateIcon(box.packState(), vehicle)
        }
        children.forEach { child ->
            HorizontalDivider(color = Night.Hairline)
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(child) }.padding(start = 20.dp, top = 6.dp, bottom = 6.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.SubdirectoryArrowRight, contentDescription = "Inside ${box.pointer()}", tint = Night.TextDim, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                ContainerThumb(child, data, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(child.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val words = child.rowWords()
                    if (words.isNotEmpty()) {
                        Text(words, style = MaterialTheme.typography.bodySmall, color = Night.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // Where it is follows the outer one; only whether it is closed is its own.
                Icon(
                    if (child.sealed) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = if (child.sealed) "Sealed" else "Open",
                    tint = if (child.sealed) Night.Mint else Night.TextDim, modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** A catalogued item as a small row: its photo and name, and something to do with it at the end. */
@Composable
internal fun ContainerItemRow(item: Item, modifier: Modifier = Modifier, onOpen: () -> Unit, trailing: @Composable () -> Unit) {
    GlassCard(modifier.fillMaxWidth(), onClick = onOpen, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(start = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            ItemPhoto(item.id, Modifier.size(48.dp), RoundedCornerShape(12.dp), iconSize = 22.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!item.label.isNullOrBlank()) Hint(item.label!!)
            }
            trailing()
        }
    }
}
