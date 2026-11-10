package com.sensitivemobility.subatlas

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

// Palette « océan » : bleu abyssal, turquoise lagon, écume.
private val Abyss = Color(0xFF062A40)
private val Ocean = Color(0xFF0B4F6C)
private val Lagoon = Color(0xFF1B9AAA)

// États des items / des listes.
val StatusOk = Color(0xFF0E9F7E)      // vert lagon
val StatusKo = Color(0xFFE5484D)      // corail
val StatusWarn = Color(0xFFF2A33A)    // ambre
val StatusNeutral = Color(0xFF4A6FA5) // bleu acier

private val LightColors = lightColorScheme(
    primary = Ocean,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEEF1),
    onPrimaryContainer = Abyss,
    secondary = Lagoon,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFBDE7EC),
    onSecondaryContainer = Abyss,
    background = Color(0xFFEAF7F8),
    onBackground = Color(0xFF0A2533),
    surface = Color(0xFFF7FDFD),
    onSurface = Color(0xFF0A2533),
    surfaceVariant = Color(0xFFD3EAED),
    onSurfaceVariant = Color(0xFF38616E),
    outline = Color(0xFF6B8E99),
    outlineVariant = Color(0xFFB5D3D9),
    inverseSurface = Abyss, // barre du haut
    inverseOnSurface = Color(0xFFEAF7F8),
    error = StatusKo,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5CC8D6),
    onPrimary = Color(0xFF06222E),
    primaryContainer = Ocean,
    onPrimaryContainer = Color(0xFFD8F3F6),
    secondary = Color(0xFF3E8FCB),
    onSecondary = Color(0xFF06222E),
    secondaryContainer = Color(0xFF11536A),
    onSecondaryContainer = Color(0xFFD8F3F6),
    background = Color(0xFF07202F),
    onBackground = Color(0xFFE3F4F6),
    surface = Color(0xFF0D3247),
    onSurface = Color(0xFFE3F4F6),
    surfaceVariant = Color(0xFF14455E),
    onSurfaceVariant = Color(0xFF9CC3CD),
    outline = Color(0xFF5E8794),
    outlineVariant = Color(0xFF244A5B),
    inverseSurface = Color(0xFF041824), // barre du haut
    inverseOnSurface = Color(0xFFE3F4F6),
    error = Color(0xFFFF8A8E),
)

/** Seuils de vitesse de remontée en vigueur (réglages), lus par les courbes de plongée. */
internal val LocalAscentScale = staticCompositionLocalOf { AscentScale() }

@Composable
fun SubAtlasTheme(mode: ThemeMode, strings: Strings, ascent: AscentScale = AscentScale(), content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
        CompositionLocalProvider(LocalStrings provides strings, LocalAscentScale provides ascent, content = content)
    }
}

/** Fond de carte selon le type de plongée : la couleur de base du thème, teintée de la couleur choisie pour ce type. */
@Composable
internal fun diveCardColor(kind: DiveKind?, settings: Settings): Color {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val index = kind?.let { settings.colorIndex(it) } ?: return base
    val accent = DIVE_PALETTE.getOrNull(index)?.let { Color(it) } ?: return base
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return lerp(base, accent, if (dark) 0.32f else 0.26f)
}
