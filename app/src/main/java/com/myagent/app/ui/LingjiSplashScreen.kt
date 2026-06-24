package com.myagent.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 灵机开屏页 — 宇宙主题，Canvas 矢量绘制，适配所有屏幕。
 *
 * 设计元素：
 * - 深空背景（navy → black 渐变）
 * - 散布的星星（随机分布，大小不一）
 * - 银河斜带（半透明雾状带）
 * - 底部极光（teal/cyan 渐变）
 * - 星轨椭圆环（缓慢旋转）
 * - "灵机" 中文标题（发光效果）
 * - "INSPIRATION" 英文副标题
 */
@Composable
fun LingjiSplashScreen(modifier: Modifier = Modifier) {
  val textMeasurer = rememberTextMeasurer()

  // 星轨旋转动画
  val infiniteTransition = rememberInfiniteTransition(label = "orbit")
  val orbitRotation by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
      animation = tween(20000, easing = LinearEasing),
      repeatMode = RepeatMode.Restart,
    ),
    label = "orbitRotation",
  )

  // 星星闪烁
  val starTwinkle by infiniteTransition.animateFloat(
    initialValue = 0.3f,
    targetValue = 1.0f,
    animationSpec = infiniteRepeatable(
      animation = tween(3000, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "starTwinkle",
  )

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color(0xFF070C1A)),
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val w = size.width
      val h = size.height
      val cx = w / 2f
      val cy = h / 2f

      // 1. 深空背景渐变
      drawRect(
        brush = Brush.verticalGradient(
          colors = listOf(
            Color(0xFF0A0F1A),
            Color(0xFF050810),
            Color(0xFF020408),
          ),
        ),
      )

      // 2. 银河斜带（左上→右下）
      drawGalaxyBand(w, h)

      // 3. 星星
      drawStars(w, h, starTwinkle)

      // 4. 底部极光
      drawAurora(w, h)

      // 5. 星轨椭圆环
      drawOrbitalRing(cx, cy, w, h, orbitRotation)

      // 6. 文字
      drawSplashText(textMeasurer, cx, cy, w, starTwinkle)
    }
  }
}

// ── 银河斜带 ──
private fun DrawScope.drawGalaxyBand(w: Float, h: Float) {
  val path = Path().apply {
    moveTo(-w * 0.2f, -h * 0.1f)
    lineTo(w * 1.2f, h * 0.7f)
    lineTo(w * 1.2f, h * 0.75f)
    lineTo(-w * 0.2f, -h * 0.05f)
    close()
  }
  drawPath(
    path = path,
    brush = Brush.linearGradient(
      colors = listOf(
        Color(0x00FFFFFF),
        Color(0x08AACCFF),
        Color(0x04AACCFF),
        Color(0x00FFFFFF),
      ),
    ),
  )
}

// ── 星星 ──
private fun DrawScope.drawStars(w: Float, h: Float, twinkle: Float) {
  val rng = Random(42)
  val starCount = 120
  val starColors = listOf(
    Color(0xFFFFFFFF),
    Color(0xFFD4EFFF),
    Color(0xFFA8D8FF),
    Color(0xFFFFF8DC),
    Color(0xFFFFCCCC),
  )

  for (i in 0 until starCount) {
    val x = rng.nextFloat() * w
    val y = rng.nextFloat() * h * 0.85f
    val radius = (rng.nextFloat() * 1.8f + 0.3f)
    val alpha = (0.3f + rng.nextFloat() * 0.7f) * twinkle.coerceIn(0.3f, 1f)
    val color = starColors[rng.nextInt(starColors.size)].copy(alpha = alpha)
    drawCircle(color = color, radius = radius, center = Offset(x, y))
  }

  // 一些稍大的亮星
  for (i in 0 until 15) {
    val x = rng.nextFloat() * w
    val y = rng.nextFloat() * h * 0.7f
    val radius = (rng.nextFloat() * 2.5f + 1.5f)
    val alpha = (0.5f + rng.nextFloat() * 0.5f) * twinkle.coerceIn(0.4f, 1f)
    drawCircle(
      color = Color.White.copy(alpha = alpha),
      radius = radius,
      center = Offset(x, y),
    )
    // 光晕
    drawCircle(
      color = Color(0xFF4ECDC4).copy(alpha = alpha * 0.15f),
      radius = radius * 3f,
      center = Offset(x, y),
    )
  }
}

// ── 底部极光 ──
private fun DrawScope.drawAurora(w: Float, h: Float) {
  // 右下角 teal 极光
  drawCircle(
    brush = Brush.radialGradient(
      colors = listOf(
        Color(0x304ECDC4),
        Color(0x104ECDC4),
        Color(0x00000000),
      ),
      center = Offset(w * 0.85f, h * 0.95f),
      radius = w * 0.6f,
    ),
    radius = w * 0.6f,
    center = Offset(w * 0.85f, h * 0.95f),
  )

  // 左下角 cyan 极光
  drawCircle(
    brush = Brush.radialGradient(
      colors = listOf(
        Color(0x20A8E6CF),
        Color(0x08A8E6CF),
        Color(0x00000000),
      ),
      center = Offset(w * 0.15f, h * 0.9f),
      radius = w * 0.5f,
    ),
    radius = w * 0.5f,
    center = Offset(w * 0.15f, h * 0.9f),
  )
}

// ── 星轨椭圆环 ──
private fun DrawScope.drawOrbitalRing(
  cx: Float, cy: Float, w: Float, h: Float, rotation: Float,
) {
  val ringCx = cx
  val ringCy = cy - h * 0.02f
  val ringRx = w * 0.38f
  val ringRy = h * 0.12f

  // 主轨道环
  drawOval(
    color = Color.White.copy(alpha = 0.25f),
    topLeft = Offset(ringCx - ringRx, ringCy - ringRy),
    size = Size(ringRx * 2, ringRy * 2),
    style = Stroke(width = 1.2f),
  )

  // 轨道高光弧段（模拟旋转）
  val sweepAngle = 60f
  val startAngle = rotation
  val arcPath = Path().apply {
    addArc(
      oval = androidx.compose.ui.geometry.Rect(
        ringCx - ringRx, ringCy - ringRy,
        ringCx + ringRx, ringCy + ringRy,
      ),
      startAngleDegrees = startAngle,
      sweepAngleDegrees = sweepAngle,
    )
  }
  drawPath(
    path = arcPath,
    color = Color(0xFFA8E6CF).copy(alpha = 0.6f),
    style = Stroke(width = 1.5f),
  )
}

// ── 文字 ──
private fun DrawScope.drawSplashText(
  measurer: TextMeasurer,
  cx: Float, cy: Float, w: Float, twinkle: Float,
) {
  val titleStyle = TextStyle(
    color = Color(0xFFA8E6CF),
    fontSize = 42.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 6.sp,
  )
  val subtitleStyle = TextStyle(
    color = Color(0xFFA8E6CF).copy(alpha = 0.7f),
    fontSize = 14.sp,
    fontWeight = FontWeight.Light,
    letterSpacing = 8.sp,
  )

  val titleResult = measurer.measure("灵机", titleStyle)
  val subtitleResult = measurer.measure("INSPIRATION", subtitleStyle)

  val titleX = cx - titleResult.size.width / 2f
  val titleY = cy - titleResult.size.height / 2f - subtitleResult.size.height / 2f - 8f
  val subtitleX = cx - subtitleResult.size.width / 2f
  val subtitleY = titleY + titleResult.size.height + 12f

  // 文字发光层
  for (i in 3 downTo 1) {
    val glowAlpha = (0.15f * i) * twinkle.coerceIn(0.4f, 1f)
    drawText(
      textLayoutResult = titleResult,
      topLeft = Offset(titleX, titleY),
      color = Color(0xFF4ECDC4).copy(alpha = glowAlpha),
      alpha = glowAlpha,
    )
  }

  // 主体文字
  drawText(
    textLayoutResult = titleResult,
    topLeft = Offset(titleX, titleY),
  )

  // 副标题
  drawText(
    textLayoutResult = subtitleResult,
    topLeft = Offset(subtitleX, subtitleY),
  )
}

// ── 预览 ──
@Composable
private fun LingjiSplashScreenPreview() {
  LingjiSplashScreen()
}