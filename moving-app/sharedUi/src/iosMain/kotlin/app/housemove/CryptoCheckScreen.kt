package app.housemove

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.housemove.data.AppFolders
import app.housemove.data.CryptoCheck
import app.housemove.data.File
import app.housemove.ui.AppBackground
import app.housemove.ui.HouseMoveTheme
import app.housemove.ui.Night
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSProcessInfo

/*
 * The crypto check's screen, for the UI tests in CI (LaunchTests.testCryptoKitOpensAndSealsAsAndroidDoes): launched with
 * `-HMCryptoCheck YES`, the app runs CryptoCheck (shared, iosMain) with the CryptoKit the Swift app gave it, and shows one
 * line per check and the summary, "Crypto check passed" or "Crypto check failed". Like demo mode, only Xcode, xcodebuild and
 * the UI tests can pass launch arguments: an app opened from the Home Screen, TestFlight or a link never shows this. Its
 * keys are kept under a Keychain service of their own, removed when it ends, and its files in Caches.
 */

/** Was the app launched with `-HMCryptoCheck YES`? */
internal fun cryptoCheckRequested(): Boolean {
    val args = NSProcessInfo.processInfo.arguments.map { it.toString() }
    val i = args.indexOf("-HMCryptoCheck")
    return i >= 0 && args.getOrNull(i + 1) == "YES"
}

@Composable
internal fun CryptoCheckScreen(crypto: AppleCrypto) {
    var lines by remember { mutableStateOf(listOf("Crypto check running")) }
    LaunchedEffect(Unit) {
        lines = withContext(Dispatchers.Default) {
            val result = CryptoCheck.run(SwiftCrypto(crypto), File(AppFolders.caches.path + "/crypto-check"))
            listOf(CryptoCheck.summary(result)) + result.map { it.text }
        }
    }
    HouseMoveTheme {
        AppBackground {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (line in lines) Text(line, color = Night.Text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
