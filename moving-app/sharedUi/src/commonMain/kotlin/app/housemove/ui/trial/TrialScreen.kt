package app.housemove.ui.trial

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.housemove.ui.AppBackground
import app.housemove.ui.HouseMark
import app.housemove.ui.HouseMoveTheme
import app.housemove.ui.Night

/**
 * A stand-in screen for the first iPhone test builds (step 0e of design/ios-plan.md). It is not a HouseMove
 * screen: it shows that the shared look works on a real iPhone (the Outfit font, the app's icons, a long list that
 * scrolls smoothly, the safe areas round the Dynamic Island and the home indicator, the largest text sizes and
 * VoiceOver) and links every icon, so the app's size is close to what the real screens will make it.
 *
 * [buildLabel] is the line naming the build ("Test build 0.1 (202611241030)"); the iPhone app passes it in.
 */
@Composable
fun TrialScreen(buildLabel: String) {
    TrialScreen(buildLabel, WindowInsets.safeDrawing, rememberLazyListState())
}

/** The same, with the safe area and the list's scroll position given (the tests render it as on an iPhone). */
@Composable
internal fun TrialScreen(buildLabel: String, safeArea: WindowInsets, listState: LazyListState) {
    HouseMoveTheme {
        // The ground runs under the status bar and the home indicator; everything on it stays inside the safe area.
        AppBackground {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().windowInsetsPadding(safeArea).testTag("trial-list"),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "head") { Head(buildLabel) }
                item(key = "rooms") { SectionTitle("Rooms and things", "${TrialRows.size} made-up things to scroll through") }
                items(TrialRows, key = { it.index }) { ThingRow(it) }
                item(key = "icons") {
                    SectionTitle("Icons", "The ${TrialIcons.size} icons the Android app uses, drawn here from the shared code")
                }
                item(key = "icon-grid") { IconGrid() }
                item(key = "end") {
                    Text(
                        "End of the test list",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Night.TextDim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Head(buildLabel: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HouseMark(Modifier.size(128.dp), contentDescription = "HouseMove house mark")
        Spacer(Modifier.height(20.dp))
        Text(
            "HouseMove",
            style = MaterialTheme.typography.displaySmall,
            color = Night.Text,
            modifier = Modifier.semantics { heading() }.testTag("title"),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "This test copy checks the HouseMove look on your iPhone: the font, the icons, a long list to " +
                "scroll and the edges of the screen.",
            style = MaterialTheme.typography.bodyLarge,
            color = Night.TextDim,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            buildLabel,
            style = MaterialTheme.typography.labelLarge,
            color = Night.MintLight,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("build"),
        )
    }
}

@Composable
private fun SectionTitle(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Night.Text, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(2.dp))
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = Night.TextDim)
    }
}

private val RowShape = RoundedCornerShape(18.dp)

/** One thing: its icon, its room's coloured chip, its name and a note. Read out as one line by VoiceOver. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThingRow(row: TrialRow) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(Night.GlassMid, RowShape)
            .border(1.dp, Night.Hairline, RowShape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .clearAndSetSemantics { contentDescription = "${row.title}, ${row.room.name}. ${row.note}" }
            .testTag("row-${row.index}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).background(Night.PanelHigh, CircleShape).border(1.dp, Night.GlassRim, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(row.icon, contentDescription = null, tint = Night.MintLight, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.titleMedium, color = Night.Text)
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                RoomChip(row.room)
                Text(row.note, style = MaterialTheme.typography.bodySmall, color = Night.TextDim)
            }
        }
    }
}

private val ChipShape = RoundedCornerShape(8.dp)

/** The room's colour as on the tape and door signs, with black or white text, whichever reads better on it. */
@Composable
private fun RoomChip(room: TrialRoom) {
    val text = if (room.color.luminance() > 0.179f) Color.Black else Color.White
    Text(
        room.name,
        style = MaterialTheme.typography.labelMedium,
        color = text,
        modifier = Modifier
            .background(room.color, ChipShape)
            .border(1.dp, Color.White.copy(alpha = 0.42f), ChipShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Every icon the app uses, each with its name for VoiceOver, in tiles of at least 48 points across the width. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconGrid() {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 8.dp
        val columns = ((maxWidth + gap) / (48.dp + gap)).toInt().coerceAtLeast(1)
        val tile = (maxWidth - gap * (columns - 1)) / columns
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
            maxItemsInEachRow = columns,
        ) {
            TrialIcons.forEach { (name, icon) ->
                Box(
                    Modifier.size(tile).background(Night.GlassMid, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = name, tint = Night.Text, modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}
