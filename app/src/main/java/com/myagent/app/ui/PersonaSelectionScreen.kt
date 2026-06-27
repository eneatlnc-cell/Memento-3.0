package com.myagent.app.ui

import com.myagent.app.model.PersonaType
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 全屏仪式感人格选择界面。
 *
 * 设计原则：
 * - 一次选择，终生绑定
 * - 全屏沉浸式，动态渐变背景
 * - 四张卡片横向滑动，选中态放大+发光+上浮
 * - 确认后播放光效动画，2 秒后回调 onConfirmed
 */
@Composable
fun PersonaSelectionScreen(
  onConfirmed: (PersonaType) -> Unit,
  onDismiss: () -> Unit,
) {
  val personas = PersonaType.entries
  val pagerState = rememberPagerState(
    initialPage = 0,
    pageCount = { personas.size },
  )
  val scope = rememberCoroutineScope()
  val screenWidth = LocalConfiguration.current.screenWidthDp.dp
  val cardWidth = screenWidth * 0.82f

  // 确认动画状态
  var isConfirming by remember { mutableStateOf(false) }
  var showSuccessOverlay by remember { mutableStateOf(false) }
  val successAlpha = remember { Animatable(0f) }
  val lightScale = remember { Animatable(0f) }

  // 动态渐变背景
  val selectedIndex = pagerState.currentPage
  val bgColors = when (selectedIndex) {
    0 -> listOf(Color(0xFF1a1a2e), Color(0xFF6C3CE0), Color(0xFF16213e))
    1 -> listOf(Color(0xFF1a1a2e), Color(0xFFE94560), Color(0xFF16213e))
    2 -> listOf(Color(0xFF1a1a2e), Color(0xFF00d4aa), Color(0xFF16213e))
    3 -> listOf(Color(0xFF1a1a2e), Color(0xFF4ECDC4), Color(0xFF16213e))
    else -> listOf(Color(0xFF1a1a2e), Color(0xFF6C3CE0), Color(0xFF16213e))
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Brush.verticalGradient(bgColors))
  ) {
    Column(
      modifier = Modifier.fillMaxSize(),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Spacer(modifier = Modifier.height(60.dp))

      // 标题
      Text(
        text = "你的灵机，由你定义",
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 32.dp),
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = "选择一种人格，灵机将用这种风格陪伴你",
        fontSize = 15.sp,
        color = Color.White.copy(alpha = 0.7f),
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 32.dp),
      )

      Spacer(modifier = Modifier.height(44.dp))

      // 卡片区域
      HorizontalPager(
        state = pagerState,
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f),
        beyondViewportPageCount = 1,
      ) { page ->
        val persona = personas[page]
        val isSelected = page == selectedIndex

        // 选中态动画
        val cardScale by animateFloatAsState(
          targetValue = if (isSelected) 1.08f else 0.88f,
          animationSpec = tween(300, easing = FastOutSlowInEasing),
          label = "cardScale",
        )
        val cardElevation by animateDpAsState(
          targetValue = if (isSelected) 24.dp else 4.dp,
          animationSpec = tween(300, easing = FastOutSlowInEasing),
          label = "cardElevation",
        )
        val cardOffsetY by animateDpAsState(
          targetValue = if (isSelected) (-12).dp else 0.dp,
          animationSpec = tween(300, easing = FastOutSlowInEasing),
          label = "cardOffsetY",
        )
        val glowAlpha by animateFloatAsState(
          targetValue = if (isSelected) 0.5f else 0f,
          animationSpec = tween(400),
          label = "glowAlpha",
        )

        Box(
          modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = (screenWidth - cardWidth) / 2),
          contentAlignment = Alignment.Center,
        ) {
          // 外发光
          if (isSelected) {
            Box(
              modifier = Modifier
                .size(cardWidth + 32.dp)
                .offset(y = cardOffsetY)
                .background(
                  Brush.radialGradient(
                    colors = listOf(
                      personaCardColor(persona).copy(alpha = glowAlpha),
                      Color.Transparent,
                    ),
                  ),
                  RoundedCornerShape(28.dp),
                ),
            )
          }

          // 卡片本体
          Column(
            modifier = Modifier
              .width(cardWidth)
              .offset(y = cardOffsetY)
              .scale(cardScale)
              .shadow(cardElevation, RoundedCornerShape(24.dp))
              .clip(RoundedCornerShape(24.dp))
              .background(
                Color(0xFF1E1E36).copy(alpha = 0.95f),
              )
              .then(
                if (isSelected) {
                  Modifier.border(2.dp, personaCardColor(persona), RoundedCornerShape(24.dp))
                } else {
                  Modifier.border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(24.dp))
                }
              )
              .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            // Emoji
            Text(
              text = persona.emoji,
              fontSize = 64.sp,
            )
            Spacer(modifier = Modifier.height(20.dp))

            // 人格名称
            Text(
              text = persona.displayName,
              fontSize = 28.sp,
              fontWeight = FontWeight.Bold,
              color = Color.White,
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 一句话描述
            Text(
              text = persona.tagline,
              fontSize = 16.sp,
              color = Color.White.copy(alpha = 0.7f),
              textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 选中指示器
            if (isSelected) {
              Box(
                modifier = Modifier
                  .size(12.dp)
                  .clip(CircleShape)
                  .background(personaCardColor(persona)),
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(24.dp))

      // 页面指示器
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
      ) {
        repeat(personas.size) { index ->
          val isCurrent = index == selectedIndex
          Box(
            modifier = Modifier
              .padding(horizontal = 4.dp)
              .size(if (isCurrent) 28.dp else 8.dp, 8.dp)
              .clip(RoundedCornerShape(4.dp))
              .background(
                if (isCurrent) personaCardColor(personas[index])
                else Color.White.copy(alpha = 0.3f),
              ),
          )
        }
      }

      Spacer(modifier = Modifier.height(32.dp))

      // 确认按钮
      Button(
        onClick = {
          isConfirming = true
          scope.launch {
            // 光效动画
            lightScale.animateTo(3f, tween(600))
            delay(400)
            showSuccessOverlay = true
            successAlpha.animateTo(1f, tween(400))
            delay(1200)
            onConfirmed(personas[selectedIndex])
          }
        },
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 32.dp)
          .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = personaCardColor(personas[selectedIndex]),
        ),
        enabled = !isConfirming,
      ) {
        Text(
          text = if (isConfirming) "绑定中..." else "确认绑定，终身有效",
          fontSize = 17.sp,
          fontWeight = FontWeight.Bold,
          color = Color.White,
        )
      }

      Spacer(modifier = Modifier.height(40.dp))
    }

    // 确认光效叠加层
    if (isConfirming) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .alpha(lightScale.value.coerceIn(0f, 1f) * 0.6f)
          .background(
            Brush.radialGradient(
              colors = listOf(
                personaCardColor(personas[selectedIndex]).copy(alpha = 0.8f),
                Color.Transparent,
              ),
            ),
          ),
      )
    }

    // 成功提示叠加层
    AnimatedVisibility(
      visible = showSuccessOverlay,
      enter = fadeIn(tween(300)) + scaleIn(initialScale = 0.8f, animationSpec = tween(300)),
      exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.8f, animationSpec = tween(200)),
    ) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color(0xFF0d0d1a).copy(alpha = 0.95f)),
        contentAlignment = Alignment.Center,
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            text = personas[selectedIndex].emoji,
            fontSize = 80.sp,
          )
          Spacer(modifier = Modifier.height(20.dp))
          Text(
            text = "灵机已记住你的选择",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = "人格：${personas[selectedIndex].displayName}",
            fontSize = 16.sp,
            color = personaCardColor(personas[selectedIndex]),
          )
        }
      }
    }
  }
}

/**
 * 每种人格对应的主题色
 */
private fun personaCardColor(persona: PersonaType): Color = when (persona) {
  PersonaType.FUNNY -> Color(0xFF6C3CE0)
  PersonaType.WARM -> Color(0xFFE94560)
  PersonaType.SHARP -> Color(0xFF00d4aa)
  PersonaType.SCHOLAR -> Color(0xFF4ECDC4)
}