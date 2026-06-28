package com.myagent.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * 欢迎页 — 品牌首次曝光，与上传设计图一致。
 *
 * 深空背景 + 紫色星形图标 + 标题 + 副标题 + 开始按钮。
 * 点击"开始使用"后进入激活流程。
 */
@Composable
fun WelcomeScreen(
  onStart: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val infiniteTransition = rememberInfiniteTransition(label = "starPulse")
  val starPulse by infiniteTransition.animateFloat(
    initialValue = 0.85f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(2000, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "starPulse",
  )
  val glowPulse by infiniteTransition.animateFloat(
    initialValue = 0.3f,
    targetValue = 0.6f,
    animationSpec = infiniteRepeatable(
      animation = tween(1800, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "glowPulse",
  )

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color(0xFF0A0A0F)),
    contentAlignment = Alignment.Center,
  ) {
    // 背景光晕
    Canvas(modifier = Modifier.fillMaxSize()) {
      val cx = size.width / 2f
      val cy = size.height * 0.38f
      drawCircle(
        brush = Brush.radialGradient(
          colors = listOf(
            Color(0xFF7C5CFF).copy(alpha = glowPulse * 0.25f),
            Color(0xFF7C5CFF).copy(alpha = 0.05f),
            Color.Transparent,
          ),
          center = Offset(cx, cy),
          radius = size.width * 0.5f,
        ),
        radius = size.width * 0.5f,
        center = Offset(cx, cy),
      )
    }

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      // 紫色星形图标
      StarIcon(
        modifier = Modifier.size(100.dp),
        pulse = starPulse,
      )

      Spacer(modifier = Modifier.height(40.dp))

      // 主标题
      Text(
        text = "欢迎来到 Memento",
        color = Color.White,
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
      )

      Spacer(modifier = Modifier.height(10.dp))

      // 副标题
      Text(
        text = "你的 AI 搭子，永远在线",
        color = Color(0xFF888899),
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        textAlign = TextAlign.Center,
      )

      Spacer(modifier = Modifier.height(56.dp))

      // 开始使用按钮
      Button(
        onClick = onStart,
        modifier = Modifier
          .fillMaxWidth()
          .height(52.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = Color(0xFF7C5CFF),
          contentColor = Color.White,
        ),
        shape = RoundedCornerShape(26.dp),
      ) {
        Text(
          text = "开始使用",
          fontSize = 17.sp,
          fontWeight = FontWeight.SemiBold,
        )
      }
    }
  }
}

/**
 * 四角星形图标，带动画脉冲效果。
 */
@Composable
private fun StarIcon(
  modifier: Modifier = Modifier,
  pulse: Float,
) {
  Canvas(modifier = modifier) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val outerR = size.width * 0.42f
    val innerR = size.width * 0.15f
    val starColor = Color(0xFF7C5CFF)

    // 外发光
    drawCircle(
      color = starColor.copy(alpha = 0.15f * pulse),
      radius = outerR * 1.25f,
      center = Offset(cx, cy),
    )

    // 主星形：4 个尖角 + 4 个内凹
    val pointCount = 4
    val path = androidx.compose.ui.graphics.Path()
    for (i in 0 until pointCount * 2) {
      val angle = (i.toFloat() / (pointCount * 2)) * 2 * Math.PI.toFloat() - Math.PI.toFloat() / 2
      val r = if (i % 2 == 0) outerR else innerR
      val x = cx + r * cos(angle)
      val y = cy + r * sin(angle)
      if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()

    drawPath(
      path = path,
      color = starColor.copy(alpha = pulse),
      style = androidx.compose.ui.graphics.drawscope.Fill,
    )

    drawPath(
      path = path,
      color = starColor.copy(alpha = 0.3f * pulse),
      style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    // 中心亮点
    drawCircle(
      color = Color.White.copy(alpha = 0.6f * pulse),
      radius = innerR * 0.5f,
      center = Offset(cx, cy),
    )
  }
}