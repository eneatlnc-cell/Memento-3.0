package com.myagent.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.delay

/**
 * Memento 品牌开屏 — 像素块风格，黑白底色。
 *
 * 设计：
 * - 纯黑背景
 * - "Memento" 居中，像素块拼成（白色）
 * - "INSPIRATION" 下方小字，像素块拼成（灰色）
 * - 先淡入 → 停留 1 秒 → 淡出 → 进入主界面
 */

// ── 像素字模：灵（16×16 网格，1=亮, 0=暗） ──
private val LING_ROWS = listOf(
  0b0000001111000000L,
  0b0000011111100000L,
  0b0000111111110000L,
  0b0001111001111000L,
  0b0011100000111000L,
  0b0111000000011100L,
  0b0111000000011100L,
  0b0111000000011100L,
  0b0111000000011100L,
  0b0011100110111000L,
  0b0001111111110000L,
  0b0000111111100000L,
  0b0000011111000000L,
  0b0000000110000000L,
  0b0000000110000000L,
  0b0000000000000000L,
)

// ── 像素字模：机（16×16 网格） ──
private val JI_ROWS = listOf(
  0b0110000000001100L,
  0b0110000000001100L,
  0b0110000000001100L,
  0b0110000000001100L,
  0b0110000000001100L,
  0b0111111111111100L,
  0b0111111111111100L,
  0b0110000000001100L,
  0b0110000000001100L,
  0b0110000000001100L,
  0b0110000000001100L,
  0b0110000000001100L,
  0b0110000000001100L,
  0b0110000000001100L,
  0b0110000000001100L,
  0b0000000000000000L,
)

// ── 5×7 像素英文字体（仅用于 "INSPIRATION" 所需字符） ──
private val PIXEL_FONT: Map<Char, List<Int>> = mapOf(
  'I' to listOf(0x0E, 0x04, 0x04, 0x04, 0x04, 0x04, 0x0E),
  'N' to listOf(0x11, 0x19, 0x15, 0x13, 0x11, 0x11, 0x11),
  'S' to listOf(0x0E, 0x11, 0x10, 0x0E, 0x01, 0x11, 0x0E),
  'P' to listOf(0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10),
  'R' to listOf(0x1E, 0x11, 0x11, 0x1E, 0x14, 0x12, 0x11),
  'A' to listOf(0x0E, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11),
  'T' to listOf(0x1F, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04),
  'O' to listOf(0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E),
)

@Composable
fun LingjiSplashScreen(
  onSplashComplete: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val alpha = remember { Animatable(0f) }

  LaunchedEffect(Unit) {
    // 淡入 300ms
    alpha.animateTo(1f, animationSpec = tween(300))
    // 停留 1 秒
    delay(1000)
    // 淡出 300ms
    alpha.animateTo(0f, animationSpec = tween(300))
    onSplashComplete()
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black),
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val w = size.width
      val h = size.height
      val alphaValue = alpha.value

      // ── 中文标题 "Memento" ──
      val cnPixelSize = w * 0.022f  // 每个像素块的大小
      val cnGap = cnPixelSize * 0.3f
      val cnGrid = 16  // 16×16 网格
      val cnStep = cnPixelSize + cnGap

      // 两个字之间的间距
      val charSpacing = cnPixelSize * 3f

      // 两个字的总宽度
      val cnTotalWidth = cnStep * cnGrid * 2f + charSpacing
      val cnStartX = (w - cnTotalWidth) / 2f
      val cnStartY = h * 0.32f

      drawPixelChar(LING_ROWS, cnStartX, cnStartY, cnPixelSize, cnGap, cnGrid, alphaValue)
      drawPixelChar(JI_ROWS, cnStartX + cnStep * cnGrid + charSpacing, cnStartY, cnPixelSize, cnGap, cnGrid, alphaValue)

      // ── 英文副标题 "INSPIRATION" ──
      val enPixelSize = cnPixelSize * 0.55f
      val enGap = enPixelSize * 0.25f
      val enGridW = 5
      val enGridH = 7
      val enStep = enPixelSize + enGap
      val enCharSpacing = enPixelSize * 2f

      val text = "INSPIRATION"
      val enTotalWidth = text.length * enStep * enGridW + (text.length - 1) * enCharSpacing
      val enStartX = (w - enTotalWidth) / 2f
      val enStartY = cnStartY + cnStep * cnGrid + enPixelSize * 4f

      text.forEachIndexed { index, ch ->
        val rows = PIXEL_FONT[ch] ?: return@forEachIndexed
        val cx = enStartX + index * (enStep * enGridW + enCharSpacing)
        drawPixelChar5x7(rows, cx, enStartY, enPixelSize, enGap, enGridW, enGridH, alphaValue)
      }
    }
  }
}

// ── 绘制 16×16 像素汉字 ──
private fun DrawScope.drawPixelChar(
  rows: List<Long>,
  startX: Float,
  startY: Float,
  pixelSize: Float,
  gap: Float,
  gridSize: Int,
  alpha: Float,
) {
  val step = pixelSize + gap
  for (row in 0 until gridSize) {
    val bits = rows.getOrElse(row) { 0L }
    for (col in 0 until gridSize) {
      val mask = 1L shl (gridSize - 1 - col)
      if ((bits and mask) != 0L) {
        drawRect(
          color = Color.White.copy(alpha = alpha),
          topLeft = Offset(startX + col * step, startY + row * step),
          size = Size(pixelSize, pixelSize),
        )
      }
    }
  }
}

// ── 绘制 5×7 像素英文 ──
private fun DrawScope.drawPixelChar5x7(
  rows: List<Int>,
  startX: Float,
  startY: Float,
  pixelSize: Float,
  gap: Float,
  gridW: Int,
  gridH: Int,
  alpha: Float,
) {
  val step = pixelSize + gap
  for (row in 0 until gridH) {
    val bits = rows.getOrElse(row) { 0 }
    for (col in 0 until gridW) {
      val mask = 1 shl (gridW - 1 - col)
      if ((bits and mask) != 0) {
        drawRect(
          color = Color.White.copy(alpha = alpha * 0.7f),
          topLeft = Offset(startX + col * step, startY + row * step),
          size = Size(pixelSize, pixelSize),
        )
      }
    }
  }
}