package com.myagent.app

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 皮肤系统 — 视觉主题包。
 *
 * 当前仅保留午夜紫金（NYX）作为默认皮肤。
 * 后续版本可根据手机性能扩展更多皮肤。
 */
enum class SkinMode(
  val rawValue: String,
  val displayName: String,
  val description: String,
  val emoji: String,
) {
  NYX(
    rawValue = "nyx",
    displayName = "午夜紫金",
    description = "暗紫底色 + 金色点缀，高级神秘",
    emoji = "🌙",
  );

  fun darkColors(): SkinColors = SkinColors(
    canvas = Color(0xFF0A0A12),
    surface = Color(0xFF16162A),
    surfaceRaised = Color(0xFF1E1E3A),
    surfacePressed = Color(0xFF282850),
    border = Color(0xFF2A2A48),
    borderStrong = Color(0xFF3D3D68),
    text = Color(0xFFE8E0F0),
    textMuted = Color(0xFF9B90B8),
    textSubtle = Color(0xFF6B6088),
    primary = Color(0xFF7C5CE7),
    primaryText = Color(0xFFFFFFFF),
    accent = Color(0xFFE8B83A),
    success = Color(0xFF00D4AA),
    successSoft = Color(0xFF0F2B24),
    warning = Color(0xFFFDCB6E),
    warningSoft = Color(0xFF2B2412),
    danger = Color(0xFFFF7675),
    dangerSoft = Color(0xFF2C1414),
    userBubble = Color(0xFF7C5CE7),
    assistantBubble = Color(0xFF1E1E3A),
    bubbleRadius = BubbleRadius(16.dp, 16.dp, 6.dp, 16.dp),
    backgroundPattern = BackgroundPattern.DOTS,
  )

  fun lightColors(): SkinColors = SkinColors(
    canvas = Color(0xFFFAFBFC),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFF5F0FF),
    surfacePressed = Color(0xFFEDE5FF),
    border = Color(0xFFE0D8F0),
    borderStrong = Color(0xFFCCC0E0),
    text = Color(0xFF1A1828),
    textMuted = Color(0xFF5A5470),
    textSubtle = Color(0xFF8E88A8),
    primary = Color(0xFF7C5CE7),
    primaryText = Color(0xFFFFFFFF),
    accent = Color(0xFFD4A020),
    success = Color(0xFF00B894),
    successSoft = Color(0xFFE8F8F3),
    warning = Color(0xFFE8A844),
    warningSoft = Color(0xFFFFF8E8),
    danger = Color(0xFFE87070),
    dangerSoft = Color(0xFFFFECEC),
    userBubble = Color(0xFF7C5CE7),
    assistantBubble = Color(0xFFF5F0FF),
    bubbleRadius = BubbleRadius(16.dp, 16.dp, 6.dp, 16.dp),
    backgroundPattern = BackgroundPattern.SOLID,
  )

  companion object {
    fun fromRawValue(value: String?): SkinMode = NYX
  }
}

data class SkinColors(
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
  val accent: Color,
  val success: Color,
  val successSoft: Color,
  val warning: Color,
  val warningSoft: Color,
  val danger: Color,
  val dangerSoft: Color,
  val userBubble: Color,
  val assistantBubble: Color,
  val bubbleRadius: BubbleRadius,
  val backgroundPattern: BackgroundPattern,
)

data class BubbleRadius(
  val topStart: Dp = 12.dp,
  val topEnd: Dp = 12.dp,
  val bottomStart: Dp = 4.dp,
  val bottomEnd: Dp = 12.dp,
)

enum class BackgroundPattern {
  SOLID,
  GRADIENT,
  DOTS,
}