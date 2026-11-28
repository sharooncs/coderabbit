package app.housemove.ui.screens

import androidx.compose.foundation.layout.*
import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.filled.HowToVote
import app.housemove.ui.icons.filled.Rule
import app.housemove.ui.icons.filled.Share
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.title
import app.housemove.pdf.Decisions
import app.housemove.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ask for decisions: for family who never visit. A picture list of the things with no decision yet goes to them
 * through any messaging app; they reply with a number and a word for each, and the answers are tapped into Triage.
 * No joining, no server.
 */
@Composable
fun AskDecisionsScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalPlatform.current
    val data = appData()
    val scope = rememberCoroutineScope()
    val items = remember(data) { Decisions.toAsk(data) }
    var working by remember { mutableStateOf(false) }

    Screen("Ask for decisions") {
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        ScrollColumn {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center) {
                GlassOrb(Icons.Default.HowToVote, null, size = 72.dp)
            }
            Text(
                "For family who cannot come: send them a list with a photo of each thing that has no decision yet. " +
                    "They reply with the number and one word for each, and you tap their answers into Triage.",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (items.isEmpty()) {
                EmptyHint("Every item has a decision, so there is nothing to ask about.")
            } else {
                Section(countOf(items.size, "thing") + " to ask about") {
                    items.take(8).forEachIndexed { i, item ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 40.dp).semantics(mergeDescendants = true) {},
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("${i + 1}", style = MaterialTheme.typography.titleMedium, color = Night.MintPale, modifier = Modifier.widthIn(min = 28.dp))
                            Text(item.title(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            RoomBadge(data.room(item.originRoomId))
                        }
                    }
                    if (items.size > 8) Hint("and ${items.size - 8} more, all on the list")
                }
                // The list is a PDF for other apps: where a phone makes none yet (the iPhone, until step 3), it is left out.
                if (context.papers != null && context.shareFiles) BigAction(
                    Icons.Default.Share, "Share the list", enabled = !working,
                    onClick = {
                        working = true
                        scope.launch(Dispatchers.Main) {
                            val file = withContext(Dispatchers.IO) {
                                try {
                                    context.papers!!.decisions(store)
                                } catch (_: Exception) {
                                    null
                                }
                            }
                            working = false
                            if (file != null) context.share(file, "application/pdf") else toast(context, "The list could not be made. Try again in a moment.")
                        }
                    },
                )
            }
            BigAction(Icons.Default.Rule, "Tap their answers into Triage", onClick = { nav.open("triage") }, primary = false)
        }
    }
}
