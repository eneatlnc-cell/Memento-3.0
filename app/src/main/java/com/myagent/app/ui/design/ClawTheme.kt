package com.myagent.app.ui.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * App color tokens consumed by ClawTheme and bridged into Material components.
 */
@Immutable
data class ClawColors(
  val canvas: Color,
  val surface: Color,
  val surfaceRaised: Color,
  val surfacePressed: Color,
  val border: Color,
  val borderStrong: Color,
  val text: Color,
  val textMuted: Color,
  val textSubtle: Color,
  val primary: Color,
  val primaryText: Color,
  val success: Color,
  val successSoft: Color,
  val warning: Color,
  val warningSoft: Color,
  val danger: Color,
  val dangerSoft: Color,
)

/**
 * App spacing scale for Compose screens and shared controls.
 */
@Immutable
data class ClawSpacing(
  val xxxs: Dp = 4.dp,
  val xxs: Dp = 8.dp,
  val xs: Dp = 12.dp,
  val sm: Dp = 16.dp,
  val md: Dp = 20.dp,
  val lg: Dp = 24.dp,
  val xl: Dp = 32.dp,
  val xxl: Dp = 40.dp,
  val touchTarget: Dp = 48.dp,
)

/**
 * Radius scale for rows, panels, controls, sheets, and status pills.
 */
@Immutable
data class ClawRadii(
  val row: Dp = 4.dp,
  val panel: Dp = 5.dp,
  val control: Dp = 6.dp,
  val button: Dp = 8.dp,
  val sheet: Dp = 10.dp,
  val pill: Dp = 12.dp,
)

/**
 * App text styles kept independent from Material typography names.
 */
@Immutable
data class ClawTypography(
  val display: TextStyle,
  val title: TextStyle,
  val section: TextStyle,
  val body: TextStyle,
  val label: TextStyle,
  val caption: TextStyle,
  val mono: TextStyle,
)

internal val ClawDarkColors =
  ClawColors(
    canvas = Color(0xFF0A0A0F),
    surface = Color(0xFF14141F),
    surfaceRaised = Color(0xFF1C1C2E),
    surfacePressed = Color(0xFF252540),
    border = Color(0xFF2A2A3E),
    borderStrong = Color(0xFF3D3D5C),
    text = Color(0xFFE8EAF0),
    textMuted = Color(0xFF9B9FB8),
    textSubtle = Color(0xFF6B6F88),
    primary = Color(0xFF6C5CE7),
    primaryText = Color(0xFFFFFFFF),
    success = Color(0xFF00B894),
    successSoft = Color(0xFF0F2B24),
    warning = Color(0xFFFDCB6E),
    warningSoft = Color(0xFF2B2412),
    danger = Color(0xFFFF7675),
    dangerSoft = Color(0xFF2C1414),
  )

internal val ClawLightColors =
  ClawColors(
    canvas = Color(0xFFFAFBFC),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFFFFFFF),
    surfacePressed = Color(0xFFEDF0F5),
    border = Color(0xFFE0E4EC),
    borderStrong = Color(0xFFCCD2DC),
    text = Color(0xFF16181D),
    textMuted = Color(0xFF5A6072),
    textSubtle = Color(0xFF8E98A7),
    primary = Color(0xFF6C5CE7),
    primaryText = Color(0xFFFFFFFF),
    success = Color(0xFF00B894),
    successSoft = Color(0xFFE8F8F3),
    warning = Color(0xFFE8A844),
    warningSoft = Color(0xFFFFF8E8),
    danger = Color(0xFFE87070),
    dangerSoft = Color(0xFFFFECEC),
  )

internal val LocalClawColors = staticCompositionLocalOf { ClawDarkColors }
internal val LocalClawSpacing = staticCompositionLocalOf { ClawSpacing() }
internal val LocalClawRadii = staticCompositionLocalOf { ClawRadii() }
internal val LocalClawTypography = staticCompositionLocalOf { clawTypography(FontFamily.Default) }

/**
 * Composition-local access point for Memento Android 设计令牌.
 */
object ClawTheme {
  val colors: ClawColors
    @Composable
    @ReadOnlyComposable
    get() = LocalClawColors.current

  val spacing: ClawSpacing
    @Composable
    @ReadOnlyComposable
    get() = LocalClawSpacing.current

  val radii: ClawRadii
    @Composable
    @ReadOnlyComposable
    get() = LocalClawRadii.current

  val type: ClawTypography
    @Composable
    @ReadOnlyComposable
    get() = LocalClawTypography.current
}

/**
 * Returns the system dark-mode preference for callers that expose theme selection.
 */
@Composable
internal fun rememberClawDarkPreference(): Boolean = isSystemInDarkTheme()

internal fun clawTypography(fontFamily: FontFamily) =
  ClawTypography(
    display =
      TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
      ),
    title =
      TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.sp,
      ),
    section =
      TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
      ),
    body =
      TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp,
      ),
    label =
      TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
      ),
    caption =
      TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.5.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
      ),
    mono =
      TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
      ),
  )

internal fun materialTypography(type: ClawTypography) =
  Typography(
    displayMedium = type.display,
    titleLarge = type.title,
    titleMedium = type.section,
    bodyLarge = type.body,
    labelLarge = type.label,
    labelSmall = type.caption,
  )

internal fun clawMaterialColorScheme(
  colors: ClawColors,
  dark: Boolean,
) = if (dark) {
  darkColorScheme(
    primary = colors.primary,
    onPrimary = colors.primaryText,
    background = colors.canvas,
    onBackground = colors.text,
    surface = colors.surface,
    onSurface = colors.text,
    surfaceVariant = colors.surfaceRaised,
    onSurfaceVariant = colors.textMuted,
    outline = colors.border,
    error = colors.danger,
    onError = colors.primaryText,
  )
} else {
  lightColorScheme(
    primary = colors.primary,
    onPrimary = colors.primaryText,
    background = colors.canvas,
    onBackground = colors.text,
    surface = colors.surface,
    onSurface = colors.text,
    surfaceVariant = colors.surfaceRaised,
    onSurfaceVariant = colors.textMuted,
    outline = colors.border,
    error = colors.danger,
    onError = colors.primaryText,
  )
}