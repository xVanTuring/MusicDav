package tech.xvanturing.musicdav.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val VinylLoungeDarkColorScheme = darkColorScheme(
    primary = VinylGoldDark,
    onPrimary = OnVinylGoldDark,
    primaryContainer = VinylGoldContainerDark,
    onPrimaryContainer = OnVinylGoldContainerDark,
    secondary = WarmCreamDark,
    onSecondary = OnWarmCreamDark,
    secondaryContainer = WarmCreamContainerDark,
    onSecondaryContainer = OnWarmCreamContainerDark,
    tertiary = TerracottaDark,
    onTertiary = OnVinylGoldDark,
    background = EspressoBackgroundDark,
    onBackground = IvoryOnBackgroundDark,
    surface = EspressoSurfaceDark,
    onSurface = IvoryOnSurfaceDark,
    surfaceVariant = EspressoSurfaceVariantDark,
    onSurfaceVariant = MutedWarmGreyDark,
    surfaceContainerLowest = EspressoBackgroundDark,
    surfaceContainerLow = EspressoSurfaceContainerLowDark,
    surfaceContainer = EspressoSurfaceDark,
    surfaceContainerHigh = EspressoSurfaceContainerHighDark,
    surfaceContainerHighest = EspressoSurfaceContainerHighestDark,
    error = BrickRedDark,
    onError = OnBrickRedDark,
    errorContainer = BrickRedContainerDark,
    onErrorContainer = OnBrickRedContainerDark,
    outline = WarmOutlineDark,
    outlineVariant = WarmOutlineVariantDark,
    inverseSurface = IvoryOnBackgroundDark,
    inverseOnSurface = EspressoBackgroundDark,
    inversePrimary = AmberBronzeLight
)

private val VinylLoungeLightColorScheme = lightColorScheme(
    primary = AmberBronzeLight,
    onPrimary = OnAmberBronzeLight,
    primaryContainer = AmberBronzeContainerLight,
    onPrimaryContainer = OnAmberBronzeContainerLight,
    secondary = TaupeLight,
    onSecondary = OnTaupeLight,
    secondaryContainer = TaupeContainerLight,
    onSecondaryContainer = OnTaupeContainerLight,
    tertiary = TerracottaLight,
    onTertiary = OnAmberBronzeLight,
    background = IvoryPaperBackgroundLight,
    onBackground = EspressoOnBackgroundLight,
    surface = IvoryPaperSurfaceLight,
    onSurface = EspressoOnSurfaceLight,
    surfaceVariant = IvoryPaperSurfaceVariantLight,
    onSurfaceVariant = MutedWarmGreyLight,
    surfaceContainerLowest = IvoryPaperSurfaceLight,
    surfaceContainerLow = IvoryPaperSurfaceContainerLowLight,
    surfaceContainer = IvoryPaperSurfaceContainerHighLight,
    surfaceContainerHigh = IvoryPaperSurfaceContainerHighestLight,
    surfaceContainerHighest = IvoryPaperSurfaceContainerHighestLight,
    error = BrickRedLight,
    onError = OnBrickRedLight,
    errorContainer = BrickRedContainerLight,
    onErrorContainer = OnBrickRedContainerLight,
    outline = WarmOutlineLight,
    outlineVariant = WarmOutlineVariantLight,
    inverseSurface = EspressoOnBackgroundLight,
    inverseOnSurface = IvoryPaperBackgroundLight,
    inversePrimary = VinylGoldDark
)

/**
 * Vinyl Lounge theme: warm, dark-first, record-store mood.
 *
 * Dynamic color (Material You) is intentionally disabled by default so the
 * brand palette is what actually ships; the parameter is kept for callers
 * that explicitly want to opt back into wallpaper-derived color.
 */
@Composable
fun MusicDavTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) VinylLoungeDarkColorScheme else VinylLoungeLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
