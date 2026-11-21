package app.housemove.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

private fun speechIntent(prompt: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
}

/**
 * A round microphone button: say it instead of typing it.
 *
 * It opens the phone's own speech sheet, so the app itself needs no microphone
 * permission. On a phone with no speech service the button is not shown, which
 * is why it must never be the only way to enter something.
 *
 * @param prompt what the speech sheet asks, e.g. "What is in the box?"
 * @param onText the words that were heard, never blank
 */
@Composable
fun SpeakButton(
    prompt: String,
    modifier: Modifier = Modifier,
    contentDescription: String = "Say it",
    size: Dp = 48.dp,
    onText: (String) -> Unit,
) {
    val fake = LocalSpeech.current
    if (fake != null) {
        GlassOrb(Icons.Default.Mic, contentDescription, modifier, size = size) { fake.listen(prompt, onText) }
        return
    }
    val context = LocalContext.current
    val available = remember { speechIntent("").resolveActivity(context.packageManager) != null }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let(onText)
        }
    }
    if (!available) return
    GlassOrb(Icons.Default.Mic, contentDescription, modifier, size = size) {
        try {
            launcher.launch(speechIntent(prompt))
        } catch (e: ActivityNotFoundException) {
            // The speech service vanished between the check and the tap; typing still works.
        }
    }
}
