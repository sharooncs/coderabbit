package app.housemove.ui.iphone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.housemove.data.Role
import app.housemove.ui.HouseMark
import app.housemove.ui.Night
import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.automirrored.filled.ArrowForward
import app.housemove.ui.icons.filled.Check
import app.housemove.ui.icons.filled.Close
import app.housemove.ui.icons.filled.Description
import app.housemove.ui.icons.filled.Groups
import app.housemove.ui.icons.filled.Person
import app.housemove.ui.icons.filled.PhoneAndroid
import app.housemove.ui.icons.filled.PhotoCamera
import app.housemove.ui.icons.filled.Refresh

/*
 * Joining, on an iPhone that holds no move: the start screen (the invite comes from the Camera, a paste or typing),
 * "Join Maple Road as Priya, Decider?", the wait, and what went wrong, in plain words.
 */

/** The join screens, as far as joining has got. */
@Composable
fun JoinFlow(model: AppModel) {
    when (val step = model.join) {
        is JoinStep.Start -> StartScreen(model, step)
        is JoinStep.Asking -> AskScreen(model, step)
        is JoinStep.Working -> WorkingScreen(step)
        is JoinStep.Failed -> FailedScreen(model, step)
    }
}

@Composable
private fun StartScreen(model: AppModel, step: JoinStep.Start) {
    var typed by remember { mutableStateOf("") }
    fun go() {
        val text = typed
        typed = ""
        model.use(text)
    }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            HouseMark(Modifier.size(88.dp), contentDescription = "HouseMove house mark")
            Spacer(Modifier.height(10.dp))
            Text("HouseMove", style = MaterialTheme.typography.displaySmall, color = Night.Text, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(4.dp))
            Text(
                "See your move on this iPhone: rooms, things and boxes.",
                style = MaterialTheme.typography.bodyLarge, color = Night.TextSoft, textAlign = TextAlign.Center,
            )
        }
        step.note?.let { note ->
            Panel(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, tone = Tone.Amber) {
                Text(note, style = MaterialTheme.typography.bodyMedium, color = Night.Text)
            }
        }
        // Demo mode only (the UI tests): the invite of the sample move this app hosts inside itself.
        model.demo?.let { demo ->
            Panel(tone = Tone.Amber) {
                Text("Demo mode", style = MaterialTheme.typography.titleMedium, color = Night.Text)
                Hint("A sample move inside this app. Its invite:")
                Text(demo.inviteCode, style = MaterialTheme.typography.bodySmall, color = Night.Text, modifier = Modifier.testTag("demo-invite"))
            }
        }
        Heading("Join a move")
        Panel {
            Step(1, Icons.Filled.PhoneAndroid, "On the Android phone, tap People (the round button at the top right), then the person this iPhone is for. Show their invite.")
            Step(2, Icons.Filled.PhotoCamera, "On this iPhone, open the Camera and point it at the code. Tap Open in HouseMove.")
            Hint("Both phones need to be on the same Wi-Fi.")
        }
        Text(
            "Or paste the invite here",
            style = MaterialTheme.typography.titleMedium, color = Night.Text, modifier = Modifier.padding(top = 4.dp),
        )
        CodeField(typed, { typed = it }, "Invite", onGo = { go() }, modifier = Modifier.testTag("invite-field"))
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallButton("Paste", Icons.Filled.Description, onClick = {
                val text = model.paste()
                if (text == null) model.noteNothingToPaste() else model.use(text)
            })
            SmallButton("Join", Icons.AutoMirrored.Filled.ArrowForward, onClick = { go() }, tone = Tone.Mint)
        }
        Spacer(Modifier.height(8.dp))
        Hint(model.buildLabel, Modifier.fillMaxWidth(), center = true)
    }
}

@Composable
private fun Step(n: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    // With large text the words need the width more than the picture does.
    val roomy = LocalDensity.current.fontScale <= 1.3f
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
        Box(Modifier.defaultMinSize(36.dp, 36.dp).glass(CircleShape, Tone.Mint).padding(horizontal = 10.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.titleMedium, color = Night.Text)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Night.Text, modifier = Modifier.weight(1f).padding(top = 6.dp))
        if (roomy) {
            Spacer(Modifier.width(8.dp))
            Icon(icon, contentDescription = null, tint = Night.MintLight, modifier = Modifier.padding(top = 6.dp).size(22.dp))
        }
    }
}

@Composable
private fun AskScreen(model: AppModel, step: JoinStep.Asking) {
    val invite = step.check.invite
    Frame(
        "Join a move",
        bottom = {
            BigButton("Yes, join", Icons.Filled.Check, onClick = { model.yes() })
            BigButton("Not now", Icons.Filled.Close, onClick = { model.notNow() }, primary = false)
        },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(80.dp).glass(CircleShape, Tone.Mint), contentAlignment = Alignment.Center) {
                    Icon(if (invite.helperCode) Icons.Filled.Groups else Icons.Filled.Person, contentDescription = null, tint = Night.MintPale, modifier = Modifier.size(40.dp))
                }
                Text(
                    step.check.question, style = MaterialTheme.typography.headlineSmall, color = Night.Text, textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                invite.role?.let { role -> Hint(Words.roleLine(role), center = true) }
            }
            if (invite.helperCode) {
                Text("Your name, so the others know who you are", style = MaterialTheme.typography.titleSmall, color = Night.Text)
                WordsField(step.name, { model.setName(it) }, "Your name", leading = Icons.Filled.Person)
            }
            Panel {
                Text(Words.LOCAL_NETWORK_ASK, style = MaterialTheme.typography.bodyMedium, color = Night.Text)
                if (invite.role != Role.HELPER) {
                    Text(
                        "The Android phone will then ask whether this is the right phone. Tap Right there.",
                        style = MaterialTheme.typography.bodyMedium, color = Night.Text,
                    )
                }
            }
            Hint("Nothing is sent until you tap Yes.")
        }
    }
}

@Composable
private fun WorkingScreen(step: JoinStep.Working) {
    val (title, text) = when (step.stage) {
        JoinStep.Stage.CONNECTING -> "Joining ${step.move}…" to "Talking to the Android phone. This takes a few seconds. Keep it showing the invite."
        JoinStep.Stage.CHECKING_WIFI -> "Checking the Wi-Fi…" to
            "The Android phone did not answer. If your iPhone asks whether HouseMove may find devices on your local network, tap Allow."
        JoinStep.Stage.WAITING_FOR_RIGHT -> "Waiting for the Android phone" to
            "It shows \"Joined: ${step.who.ifBlank { "…" }}, on iPhone\". Tap Right there, and the move comes across."
    }
    Frame("Join a move") {
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        ) {
            Spacer(Modifier.height(40.dp))
            CircularProgressIndicator(color = Night.Mint, modifier = Modifier.size(56.dp))
            Text(
                title, style = MaterialTheme.typography.titleLarge, color = Night.Text, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite; heading() },
            )
            Text(text, style = MaterialTheme.typography.bodyLarge, color = Night.TextSoft, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun FailedScreen(model: AppModel, step: JoinStep.Failed) {
    Frame(
        "Join a move",
        bottom = {
            if (step.retry != null) BigButton("Try again", Icons.Filled.Refresh, onClick = { model.retry() })
            BigButton("Use another invite", Icons.Filled.Description, onClick = { model.startAgain() }, primary = step.retry == null)
        },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ReportPanel(step.report, onSettings = { model.openSettings() }, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Hint("Nothing of the move is on this iPhone yet.")
        }
    }
}
