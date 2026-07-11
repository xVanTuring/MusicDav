package tech.xvanturing.musicdav.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Vinyl Lounge palette.
 *
 * Warm, dark-first, record-store mood: amber/gold primary, warm brick-red
 * accent, warm ivory/espresso neutrals. Two grouped palettes below feed the
 * darkColorScheme()/lightColorScheme() wiring in Theme.kt.
 */

// ---------------------------------------------------------------------------
// Dark scheme - "深墨" (primary mood)
// ---------------------------------------------------------------------------

// Neutrals / surfaces
val EspressoBackgroundDark = Color(0xFF14110F)
val EspressoSurfaceDark = Color(0xFF1C1815)
val EspressoSurfaceVariantDark = Color(0xFF2A241D)
val EspressoSurfaceContainerLowDark = Color(0xFF211C17)
val EspressoSurfaceContainerHighDark = Color(0xFF2E271F)
val EspressoSurfaceContainerHighestDark = Color(0xFF382F25)
val IvoryOnBackgroundDark = Color(0xFFEDE6DB)
val IvoryOnSurfaceDark = Color(0xFFEDE6DB)
val MutedWarmGreyDark = Color(0xFFB5AA99)
val WarmOutlineDark = Color(0xFF574E43)
val WarmOutlineVariantDark = Color(0xFF383029)

// Brand
val VinylGoldDark = Color(0xFFE3A63C) // luminous warm gold - primary
val OnVinylGoldDark = Color(0xFF241A0A)
val VinylGoldContainerDark = Color(0xFF4A3A1E)
val OnVinylGoldContainerDark = Color(0xFFF5D89B)

val WarmCreamDark = Color(0xFFC9B99F) // secondary
val OnWarmCreamDark = Color(0xFF322A1C)
val WarmCreamContainerDark = Color(0xFF3A3225)
val OnWarmCreamContainerDark = Color(0xFFE7DBC6)

val TerracottaDark = Color(0xFFD98C6A) // tertiary accent

val BrickRedDark = Color(0xFFD6584A) // error / favorite heart / destructive
val OnBrickRedDark = Color(0xFFFFFFFF)
val BrickRedContainerDark = Color(0xFF4A211C)
val OnBrickRedContainerDark = Color(0xFFF7C6BE)

// ---------------------------------------------------------------------------
// Light scheme - "象牙白"
// ---------------------------------------------------------------------------

// Neutrals / surfaces
val IvoryPaperBackgroundLight = Color(0xFFF7F1E8)
val IvoryPaperSurfaceLight = Color(0xFFFFFBF4)
val IvoryPaperSurfaceVariantLight = Color(0xFFECE3D3)
val IvoryPaperSurfaceContainerLowLight = Color(0xFFF2EADC)
val IvoryPaperSurfaceContainerHighLight = Color(0xFFEDE4D5)
val IvoryPaperSurfaceContainerHighestLight = Color(0xFFE7DDCC)
val EspressoOnBackgroundLight = Color(0xFF221C14)
val EspressoOnSurfaceLight = Color(0xFF221C14)
val MutedWarmGreyLight = Color(0xFF6B5F4E)
val WarmOutlineLight = Color(0xFF897E6B)
val WarmOutlineVariantLight = Color(0xFFD4C8B4)

// Brand
val AmberBronzeLight = Color(0xFFA6741C) // deep amber/bronze - primary
val OnAmberBronzeLight = Color(0xFFFFFFFF)
val AmberBronzeContainerLight = Color(0xFFF3DBA8)
val OnAmberBronzeContainerLight = Color(0xFF362703)

val TaupeLight = Color(0xFF6F6250) // secondary
val OnTaupeLight = Color(0xFFFFFFFF)
val TaupeContainerLight = Color(0xFFEADCC4)
val OnTaupeContainerLight = Color(0xFF29231A)

val TerracottaLight = Color(0xFF9E5A3C) // tertiary accent

val BrickRedLight = Color(0xFFB23A2E) // error / favorite heart / destructive
val OnBrickRedLight = Color(0xFFFFFFFF)
val BrickRedContainerLight = Color(0xFFF7D9D3)
val OnBrickRedContainerLight = Color(0xFF3B0A05)

// ---------------------------------------------------------------------------
// Semantic status colors (theme-agnostic; the palette otherwise has no green)
// ---------------------------------------------------------------------------

val StatusOnline = Color(0xFF3E9B4F) // mid green - "available/online" indicator, reads on both ivory and espresso
