package com.offlinepdf.studio.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ==========================================
// User Defined Design System Color Palettes
// ==========================================

// --- LIGHT MODE PALETTE ---
val LightPrimary = Color(0xFFD71920)
val LightPrimaryHover = Color(0xFFB5121B)
val LightPrimaryLight = Color(0xFFFDEBEC)

val LightBg = Color(0xFFFFFFFF)
val LightSurface = Color(0xFFF7F7F8)
val LightSurface2 = Color(0xFFF0F1F3)

val LightHeading = Color(0xFF202124)
val LightText = Color(0xFF454950)
val LightMuted = Color(0xFF777D86)

val LightBorder = Color(0xFFE2E4E8)

val LightAccent = Color(0xFFF5C542)
val LightSuccess = Color(0xFF16834A)
val LightError = Color(0xFFD92D35)

// --- DARK MODE PALETTE ---
val DarkPrimary = Color(0xFFF0444B)
val DarkPrimaryHover = Color(0xFFFF6268)
val DarkPrimaryLight = Color(0xFF3B2023)

val DarkBg = Color(0xFF121212)
val DarkSurface = Color(0xFF1C1C1F)
val DarkSurface2 = Color(0xFF26262A)

val DarkHeading = Color(0xFFF5F5F5)
val DarkText = Color(0xFFD1D1D6)
val DarkMuted = Color(0xFF96969E)

val DarkBorder = Color(0xFF35353B)

val DarkAccent = Color(0xFFF5C542)
val DarkSuccess = Color(0xFF36B878)
val DarkError = Color(0xFFFF646B)

// ==========================================
// Compatibility Dynamic Theme-Aware Aliases
// ==========================================
val DarkMainBg: Color @Composable get() = AppTheme.bg
val DarkSecondaryBg: Color @Composable get() = AppTheme.surface
val DarkToolCard: Color @Composable get() = AppTheme.surface
val DarkBottomNav: Color @Composable get() = AppTheme.surface
val PinkAccent: Color @Composable get() = AppTheme.primary
val PinkAccentVariant: Color @Composable get() = AppTheme.primaryHover

val TextPrimaryDark: Color @Composable get() = AppTheme.heading
val TextSecondaryDark: Color @Composable get() = AppTheme.muted
val PrimaryText: Color @Composable get() = AppTheme.heading
val SecondaryText: Color @Composable get() = AppTheme.muted
val BorderDark: Color @Composable get() = AppTheme.border

val LightMainBg: Color @Composable get() = LightBg
val LightSecondaryBg: Color @Composable get() = LightSurface
val LightToolCard: Color @Composable get() = LightSurface2
val LightBottomNav: Color @Composable get() = LightSurface
val TextPrimaryLight: Color @Composable get() = LightHeading
val TextSecondaryLight: Color @Composable get() = LightText
val BorderLight: Color @Composable get() = LightBorder

val StatusSuccess: Color @Composable get() = AppTheme.success
val StatusError: Color @Composable get() = AppTheme.error
val StatusWarning: Color @Composable get() = AppTheme.accent

// Pastel Accents for Tool Icons
val PastelRed = Color(0xFFFF8A80)
val PastelBlue = Color(0xFF82B1FF)
val PastelGreen = Color(0xFFA7F3D0)
val PastelOrange = Color(0xFFFFD180)
val PastelPurple = Color(0xFFEA80FC)
val PastelTeal = Color(0xFFA7FFEB)
val PastelYellow = Color(0xFFFFF59D)
val PastelCyan = Color(0xFF80DEEA)
