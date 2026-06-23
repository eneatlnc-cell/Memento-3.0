package com.myagent.app.ui

import com.myagent.app.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal data class MobileColors(
  val surface: Color,
  val surfaceStrong: Color,
  val cardSurface: Color,
  val border: Color,
  val borderStrong: Color,
  val text: Color,
  val textSecondary: Color,
  val textTertiary: Color,
  val accent: Color,
  val accentSoft: Color,
  val accentBorderStrong: Color,
  val success: Color,
  val successSoft: Color,
  val warning: Color,
  val warningSoft: Color,
  val danger: Color,
  val dangerSoft: Color,
  val codeBg: Color,
  val codeText: Color,
  val codeBorder: Color,
  val codeAccent: Color,
  val chipBorderConnected: Color,
  val chipBorderConnecting: Color,
  val chipBorderWarning: Color,
  val chipBorderError: Color,
)

internal fun lightMobileColors() =
  MobileColors(
    surface = Color(0xFFFAFBFC),
    surfaceStrong = Color(0xFFEFF3F8),
    cardSurface = Color(0xFFFFFFFF),
    border = Color(0xFFE0E4EC),
    borderStrong = Color(0xFFCCD2DC),
    text = Color(0xFF16181D),
    textSecondary = Color(0xFF5A6072),
    textTertiary = Color(0xFF8E98A7),
    accent = Color(0xFF6C5CE7),
    accentSoft = Color(0xFFF0EDFF),
    accentBorderStrong = Color(0xFF5A4BD1),
    success = Color(0xFF00B894),
    successSoft = Color(0xFFE8F8F3),
    warning = Color(0xFFE8A844),
    warningSoft = Color(0xFFFFF8E8),
    danger = Color(0xFFE87070),
    dangerSoft = Color(0xFFFFECEC),
    codeBg = Color(0xFFEFF3F8),
    codeText = Color(0xFF172033),
    codeBorder = Color(0xFFD7DDE7),
    codeAccent = Color(0xFF6C5CE7),
    chipBorderConnected = Color(0xFFB8E6D0),
    chipBorderConnecting = Color(0xFFD5C8FA),
    chipBorderWarning = Color(0xFFEED8B8),
    chipBorderError = Color(0xFFF3C8C8),
  )

internal fun darkMobileColors() =
  MobileColors(
    surface = Color(0xFF14141F),
    surfaceStrong = Color(0xFF1C1C2E),
    cardSurface = Color(0xFF1A1A28),
    border = Color(0xFF2A2A3E),
    borderStrong = Color(0xFF3D3D5C),
    text = Color(0xFFE8EAF0),
    textSecondary = Color(0xFF9B9FB8),
    textTertiary = Color(0xFF6B6F88),
    accent = Color(0xFF7C6FF0),
    accentSoft = Color(0xFF1E1A3E),
    accentBorderStrong = Color(0xFF6C5CE7),
    success = Color(0xFF00B894),
    successSoft = Color(0xFF0F2B24),
    warning = Color(0xFFFDCB6E),
    warningSoft = Color(0xFF2B2412),
    danger = Color(0xFFFF7675),
    dangerSoft = Color(0xFF2C1414),
    codeBg = Color(0xFF0F0F1A),
    codeText = Color(0xFFE8EAEE),
    codeBorder = Color(0xFF2A2A3E),
    codeAccent = Color(0xFF7C6FF0),
    chipBorderConnected = Color(0xFF1A3D28),
    chipBorderConnecting = Color(0xFF2A1E5C),
    chipBorderWarning = Color(0xFF3D2E16),
    chipBorderError = Color(0xFF3D1E1E),
  )

// Defaulting to light tokens keeps previews/tests usable when a screen forgets to
// provide the app theme; production roots override this composition local.
internal val LocalMobileColors = staticCompositionLocalOf { lightMobileColors() }

internal object MobileColorsAccessor {
  val current: MobileColors
    @Composable get() = LocalMobileColors.current
}

// Keep these accessors while screens migrate to `MobileColorsAccessor.current`.
// Each getter must stay composable so callers always read the active theme.
internal val mobileSurface: Color @Composable get() = LocalMobileColors.current.surface
internal val mobileSurfaceStrong: Color @Composable get() = LocalMobileColors.current.surfaceStrong
internal val mobileCardSurface: Color @Composable get() = LocalMobileColors.current.cardSurface
internal val mobileBorder: Color @Composable get() = LocalMobileColors.current.border
internal val mobileBorderStrong: Color @Composable get() = LocalMobileColors.current.borderStrong
internal val mobileText: Color @Composable get() = LocalMobileColors.current.text
internal val mobileTextSecondary: Color @Composable get() = LocalMobileColors.current.textSecondary
internal val mobileTextTertiary: Color @Composable get() = LocalMobileColors.current.textTertiary
internal val mobileAccent: Color @Composable get() = LocalMobileColors.current.accent
internal val mobileAccentSoft: Color @Composable get() = LocalMobileColors.current.accentSoft
internal val mobileAccentBorderStrong: Color @Composable get() = LocalMobileColors.current.accentBorderStrong
internal val mobileSuccess: Color @Composable get() = LocalMobileColors.current.success
internal val mobileSuccessSoft: Color @Composable get() = LocalMobileColors.current.successSoft
internal val mobileWarning: Color @Composable get() = LocalMobileColors.current.warning
internal val mobileWarningSoft: Color @Composable get() = LocalMobileColors.current.warningSoft
internal val mobileDanger: Color @Composable get() = LocalMobileColors.current.danger
internal val mobileDangerSoft: Color @Composable get() = LocalMobileColors.current.dangerSoft
internal val mobileCodeBg: Color @Composable get() = LocalMobileColors.current.codeBg
internal val mobileCodeText: Color @Composable get() = LocalMobileColors.current.codeText
internal val mobileCodeBorder: Color @Composable get() = LocalMobileColors.current.codeBorder
internal val mobileCodeAccent: Color @Composable get() = LocalMobileColors.current.codeAccent

// Build the page backdrop from semantic surfaces so light/dark palettes keep
// their contrast relationship without duplicating raw color stops.
internal val mobileBackgroundGradient: Brush
  @Composable get() {
    val colors = LocalMobileColors.current
    return Brush.verticalGradient(
      listOf(
        colors.surface,
        colors.surfaceStrong,
        colors.surfaceStrong,
      ),
    )
  }

internal val mobileFontFamily =
  FontFamily(
    Font(resId = R.font.manrope_400_regular, weight = FontWeight.Normal),
    Font(resId = R.font.manrope_500_medium, weight = FontWeight.Medium),
    Font(resId = R.font.manrope_600_semibold, weight = FontWeight.SemiBold),
    Font(resId = R.font.manrope_700_bold, weight = FontWeight.Bold),
  )

internal val mobileDisplay =
  TextStyle(
    fontFamily = mobileFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 34.sp,
    lineHeight = 40.sp,
    letterSpacing = (-0.8).sp,
  )

internal val mobileTitle1 =
  TextStyle(
    fontFamily = mobileFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 24.sp,
    lineHeight = 30.sp,
    letterSpacing = (-0.5).sp,
  )

internal val mobileTitle2 =
  TextStyle(
    fontFamily = mobileFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 20.sp,
    lineHeight = 26.sp,
    letterSpacing = (-0.3).sp,
  )

internal val mobileHeadline =
  TextStyle(
    fontFamily = mobileFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 16.sp,
    lineHeight = 22.sp,
    letterSpacing = (-0.1).sp,
  )

internal val mobileBody =
  TextStyle(
    fontFamily = mobileFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 15.sp,
    lineHeight = 22.sp,
  )

internal val mobileCallout =
  TextStyle(
    fontFamily = mobileFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
    lineHeight = 20.sp,
  )

internal val mobileCaption1 =
  TextStyle(
    fontFamily = mobileFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.2.sp,
  )

internal val mobileCaption2 =
  TextStyle(
    fontFamily = mobileFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    letterSpacing = 0.4.sp,
  )
