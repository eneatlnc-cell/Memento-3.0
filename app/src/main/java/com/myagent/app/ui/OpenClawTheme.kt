package com.myagent.app.ui

import com.myagent.app.AppearanceThemeMode
import com.myagent.app.SkinColors
import com.myagent.app.SkinMode
import com.myagent.app.ui.design.ClawColors
import com.myagent.app.ui.design.ClawDarkColors
import com.myagent.app.ui.design.ClawLightColors
import com.myagent.app.ui.design.ClawRadii
import com.myagent.app.ui.design.ClawSpacing
import com.myagent.app.ui.design.LocalClawColors
import com.myagent.app.ui.design.LocalClawRadii
import com.myagent.app.ui.design.LocalClawSpacing
import com.myagent.app.ui.design.LocalClawTypography
import com.myagent.app.ui.design.clawMaterialColorScheme
import com.myagent.app.ui.design.clawTypography
import com.myagent.app.ui.design.materialTypography
import com.myagent.app.ui.design.skinColorsToClawColors
import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LocalMementoDarkTheme = staticCompositionLocalOf { true }

/**
 * 皮肤颜色令牌，由 MementoTheme 注入，供 MessageBubble 等组件消费。
 */
val LocalSkinColors = staticCompositionLocalOf<SkinColors> {
  SkinMode.NYX.darkColors()
}

/**
 * Memento 统一主题 — 合并了原 MementoTheme + ClawDesignTheme 的职责。
 *
 * 提供三层令牌：
 * 1. MaterialTheme (M3 ColorScheme) — 底层 Material3 控件
 * 2. LocalClawColors — Claw 组件系统的抽象层
 * 3. LocalMobileColors — 旧版移动端令牌（渐进废弃中）
 * 4. LocalSkinColors — 皮肤系统令牌（气泡形状、背景纹理）
 */
@Composable
fun MementoTheme(
  themeMode: AppearanceThemeMode = AppearanceThemeMode.Dark,
  skin: SkinMode = SkinMode.NYX,
  content: @Composable () -> Unit,
) {
  val isDark = themeMode.isDark(systemDark = isSystemInDarkTheme())
  val skinColors = if (isDark) skin.darkColors() else skin.lightColors()
  val clawColors = skinColorsToClawColors(skinColors)
  val mobileColors = if (isDark) darkMobileColors() else lightMobileColors()
  val typography = clawTypography(mobileFontFamily)

  MementoSystemBarAppearance(lightAppearance = !isDark)

  CompositionLocalProvider(
    LocalClawColors provides clawColors,
    LocalMobileColors provides mobileColors,
    LocalClawSpacing provides ClawSpacing(),
    LocalClawRadii provides ClawRadii(),
    LocalClawTypography provides typography,
    LocalSkinColors provides skinColors,
    LocalMementoDarkTheme provides isDark,
  ) {
    MaterialTheme(
      colorScheme = clawMaterialColorScheme(clawColors, isDark),
      typography = materialTypography(typography),
      shapes = Shapes(),
      content = content,
    )
  }
}

@Composable
internal fun MementoSystemBarAppearance(lightAppearance: Boolean) {
  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as? Activity)?.window ?: return@SideEffect
      WindowCompat
        .getInsetsController(window, window.decorView)
        .isAppearanceLightStatusBars = lightAppearance
      WindowCompat
        .getInsetsController(window, window.decorView)
        .isAppearanceLightNavigationBars = lightAppearance
    }
  }
}

/**
 * Overlay background token tuned for panels floating over the mobile canvas.
 */
@Composable
fun overlayContainerColor(): Color {
  val scheme = MaterialTheme.colorScheme
  val isDark = LocalMementoDarkTheme.current
  val base = if (isDark) scheme.surfaceContainerLow else scheme.surfaceContainerHigh
  return if (isDark) base else base.copy(alpha = 0.88f)
}

/**
 * Overlay icon token kept next to overlayContainerColor for callers outside the design package.
 */
@Composable
fun overlayIconColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant