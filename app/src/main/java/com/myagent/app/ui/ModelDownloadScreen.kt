package com.myagent.app.ui

import com.myagent.app.model.ModelDownloadState
import com.myagent.app.model.downloadedText
import com.myagent.app.model.speedText
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 模型下载进度页 — 强制下载，不支持 Mock 跳过。
 *
 * 交互：
 * - 下载中：显示进度 + 速度 + "可退出当前界面，但请勿删除进程"
 * - 下载失败（< 3次）：自动重试
 * - 下载失败（3次）：显示错误 + 重试按钮
 * - 下载完成：自动进入下一步
 */
@Composable
fun ModelDownloadScreen(
  state: ModelDownloadState,
  onExit: () -> Unit,
  onRetry: () -> Unit,
  retryCount: Int = 0,
  modifier: Modifier = Modifier,
) {
  val infiniteTransition = rememberInfiniteTransition(label = "pulse")
  val pulse by infiniteTransition.animateFloat(
    initialValue = 0.7f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(1500, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "pulse",
  )

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(
        Brush.verticalGradient(
          colors = listOf(
            Color(0xFF0A1528),
            Color(0xFF071021),
            Color(0xFF040A18),
          ),
        ),
      ),
    contentAlignment = Alignment.Center,
  ) {
    // 背景装饰：旋转光晕
    Canvas(modifier = Modifier.fillMaxSize()) {
      val cx = size.width / 2f
      val cy = size.height * 0.35f
      drawCircle(
        brush = Brush.radialGradient(
          colors = listOf(
            Color(0x154ECDC4),
            Color(0x054ECDC4),
            Color(0x00000000),
          ),
          center = Offset(cx, cy),
          radius = size.width * 0.45f,
        ),
        radius = size.width * 0.45f,
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
      CircularBrainIcon(
        modifier = Modifier.size(100.dp),
        isActive = state is ModelDownloadState.Downloading || state is ModelDownloadState.Verifying,
        pulse = pulse,
      )

      Spacer(modifier = Modifier.height(32.dp))

      when (state) {
        is ModelDownloadState.Idle,
        is ModelDownloadState.Downloading -> {
          val downloading = state as? ModelDownloadState.Downloading

          Text(
            text = "正在激活 Memento",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFA8E6CF),
            textAlign = TextAlign.Center,
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = "首次使用需要加载基座大模型（约 3.7 GB，展开后约 5.5 GB）",
            fontSize = 14.sp,
            color = Color(0xFFA8E6CF).copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "推荐骁龙 8 Gen2+ / 天玑 9200+ · 8GB+ RAM",
            fontSize = 12.sp,
            color = Color(0xFFA8E6CF).copy(alpha = 0.35f),
            textAlign = TextAlign.Center,
          )
          Spacer(modifier = Modifier.height(28.dp))

          // 进度条
          if (downloading != null && downloading.totalBytes > 0) {
            LinearProgressIndicator(
              progress = { downloading.progress / 100f },
              modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
              color = Color(0xFF4ECDC4),
              trackColor = Color(0xFF4ECDC4).copy(alpha = 0.15f),
              strokeCap = StrokeCap.Round,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
              text = downloading.downloadedText(),
              fontSize = 13.sp,
              color = Color(0xFFA8E6CF).copy(alpha = 0.7f),
              textAlign = TextAlign.Center,
            )

            if (downloading.speedBytesPerSec > 0) {
              Text(
                text = "下载速度：${downloading.speedText()}",
                fontSize = 12.sp,
                color = Color(0xFFA8E6CF).copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
              )
            }
          } else {
            LinearProgressIndicator(
              modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
              color = Color(0xFF4ECDC4),
              trackColor = Color(0xFF4ECDC4).copy(alpha = 0.15f),
              strokeCap = StrokeCap.Round,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
              text = "正在连接服务器...",
              fontSize = 13.sp,
              color = Color(0xFFA8E6CF).copy(alpha = 0.6f),
            )
          }

          if (retryCount > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = "第 $retryCount 次重试中...",
              fontSize = 12.sp,
              color = Color(0xFFFFA94D).copy(alpha = 0.7f),
              textAlign = TextAlign.Center,
            )
          }

          Spacer(modifier = Modifier.height(32.dp))

          // 退出按钮（不中止下载）
          OutlinedButton(
            onClick = onExit,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
              contentColor = Color(0xFFA8E6CF).copy(alpha = 0.6f),
            ),
          ) {
            Text("可退出当前界面，但请勿删除进程")
          }
        }

        is ModelDownloadState.Verifying -> {
          Text(
            text = "正在校验模型文件...",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFA8E6CF),
            textAlign = TextAlign.Center,
          )
          Spacer(modifier = Modifier.height(16.dp))
          LinearProgressIndicator(
            modifier = Modifier
              .fillMaxWidth()
              .height(6.dp)
              .clip(RoundedCornerShape(3.dp)),
            color = Color(0xFF4ECDC4),
            trackColor = Color(0xFF4ECDC4).copy(alpha = 0.15f),
            strokeCap = StrokeCap.Round,
          )
        }

        is ModelDownloadState.Completed -> {
          Text(
            text = "激活完成！",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF4ECDC4),
            textAlign = TextAlign.Center,
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = "Memento 已就绪，开始你的 AI 之旅吧",
            fontSize = 14.sp,
            color = Color(0xFFA8E6CF).copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
          )
        }

        is ModelDownloadState.Failed -> {
          val isMaxRetries = retryCount >= 3

          Text(
            text = if (isMaxRetries) "下载失败，请检查网络后重试" else "下载失败",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFF6B6B),
            textAlign = TextAlign.Center,
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = state.error,
            fontSize = 14.sp,
            color = Color(0xFFFF6B6B).copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
          )
          Spacer(modifier = Modifier.height(24.dp))
          Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
              containerColor = Color(0xFF4ECDC4),
            ),
          ) {
            Text("重新下载")
          }
        }
      }
    }
  }
}

/**
 * 脑回路风格圆形图标，带旋转动效。
 */
@Composable
private fun CircularBrainIcon(
  modifier: Modifier = Modifier,
  isActive: Boolean,
  pulse: Float,
) {
  val rotation by rememberInfiniteTransition(label = "brainRotate").animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
      animation = tween(12000, easing = LinearEasing),
      repeatMode = RepeatMode.Restart,
    ),
    label = "brainRotate",
  )

  Canvas(modifier = modifier) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val r = size.width * 0.4f

    drawCircle(
      color = Color(0xFF4ECDC4).copy(alpha = 0.15f * pulse),
      radius = r * 1.15f,
      center = Offset(cx, cy),
    )

    drawCircle(
      color = Color(0xFFA8E6CF).copy(alpha = 0.3f),
      radius = r,
      center = Offset(cx, cy),
      style = Stroke(width = 1.5f),
    )

    val nodeCount = 8
    for (i in 0 until nodeCount) {
      val angle = (i.toFloat() / nodeCount) * 2 * PI.toFloat() + Math.toRadians(rotation.toDouble()).toFloat()
      val nx = cx + r * 0.65f * cos(angle)
      val ny = cy + r * 0.65f * sin(angle)
      drawCircle(
        color = Color(0xFF4ECDC4).copy(alpha = if (isActive) 0.8f * pulse else 0.4f),
        radius = 3f,
        center = Offset(nx, ny),
      )
      drawLine(
        color = Color(0xFFA8E6CF).copy(alpha = 0.2f),
        start = Offset(cx, cy),
        end = Offset(nx, ny),
        strokeWidth = 0.8f,
      )
    }

    drawCircle(
      color = Color(0xFF4ECDC4).copy(alpha = if (isActive) pulse else 0.5f),
      radius = 6f,
      center = Offset(cx, cy),
    )
    drawCircle(
      color = Color(0xFFA8E6CF).copy(alpha = 0.3f),
      radius = 12f,
      center = Offset(cx, cy),
    )
  }
}