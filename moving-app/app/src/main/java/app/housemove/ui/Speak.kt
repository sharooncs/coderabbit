package app.housemove.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Something that can listen to a few spoken words and hand them back as text.
 * The app uses the phone's own "speak now" sheet; tests provide a fake.
 */
fun interface Speech {
    fun listen(prompt: String, onText: (String) -> Unit)
}

/** Set in tests to stand in for the phone's speech service. Null means: use the phone's. */
val LocalSpeech = staticCompositionLocalOf<Speech?> { null }

/**
 * Can this phone take spoken words? When it cannot, [SpeakButton] draws nothing, so words
 * beside it ("Say their name") should say "type" instead.
 */
@Composable
fun canSpeak(): Boolean {
    if (LocalSpeech.current != null) return true
    return LocalPlatform.current.canSpeak()
}

/**
 * A round microphone button: say it instead of typing it.
 *
 * It opens the phone's own speech sheet, so the app itself needs no microphone
 * permission. On a phone with no speech service the button is not shown, which
 * is why it must never be the only way to enter something.
 *
 * @param prompt what the speech sheet asks, e.g. "What is in the box?"
 * @param label words to show beside the microphone ("Say why"). The button is then a
 *   glass pill, for places where a bare microphone would not say what it is for.
 * @param onText the words that were heard, never blank
 */
@Composable
fun SpeakButton(
    prompt: String,
    modifier: Modifier = Modifier,
    contentDescription: String = "Say it",
    size: Dp = 48.dp,
    label: String? = null,
    onText: (String) -> Unit,
) {
    val fake = LocalSpeech.current
    if (fake != null) {
        Microphone(label, contentDescription, modifier, size) { fake.listen(prompt, onText) }
        return
    }
    // The phone's own speech sheet, where it has one (Android's; the iPhone's waits for step 3).
    val listen = LocalPlatform.current.rememberSpeech(onText) ?: return
    Microphone(label, contentDescription, modifier, size) { listen(prompt) }
}

/** The button itself: an orb, or with a [label] a pill that says what to say. */
@Composable
private fun Microphone(label: String?, contentDescription: String, modifier: Modifier, size: Dp, onClick: () -> Unit) {
    if (label == null) {
        GlassOrb(Icons.Default.Mic, contentDescription, modifier, size = size, onClick = onClick)
        return
    }
    GlassPill(
        modifier.heightIn(min = size).semantics { this.contentDescription = contentDescription }, onClick = onClick,
        contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Icon(Icons.Default.Mic, contentDescription = null, tint = Night.Mint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        // The description above already names the button: the words are not read a second time.
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics { })
    }
}
