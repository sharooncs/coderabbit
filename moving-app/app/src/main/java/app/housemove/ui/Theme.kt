package app.housemove.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF1F5E96),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4F7),
    onPrimaryContainer = Color(0xFF0A2A47),
    secondary = Color(0xFF8A5A00),
    secondaryContainer = Color(0xFFFFE0B0),
    onSecondaryContainer = Color(0xFF2C1B00),
    tertiary = Color(0xFF2E7D32),
    tertiaryContainer = Color(0xFFCDEBCF),
    onTertiaryContainer = Color(0xFF0B2A0D),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9CCBFB),
    onPrimary = Color(0xFF00325A),
    primaryContainer = Color(0xFF124A7A),
    onPrimaryContainer = Color(0xFFD3E4F7),
    secondary = Color(0xFFF5BE5B),
    secondaryContainer = Color(0xFF5C3F00),
    onSecondaryContainer = Color(0xFFFFE0B0),
    tertiary = Color(0xFF9BD49F),
    tertiaryContainer = Color(0xFF1E4F22),
    onTertiaryContainer = Color(0xFFCDEBCF),
)

@Composable
fun HouseMoveTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
