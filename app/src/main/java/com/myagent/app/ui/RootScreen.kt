package com.myagent.app.ui

import com.myagent.app.MainViewModel
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay

/**
 * 根路由 — v4.0 云端架构。
 *
 * 流程：欢迎页 → 激活 → 引导（API 配置提示）→ 主界面
 * 移除模型下载步骤（云端 API 无需下载模型）。
 */
private object Routes {
  const val WELCOME = "welcome"
  const val ACTIVATION = "activation"
  const val ONBOARDING = "onboarding"
  const val SHELL = "shell"
  const val SPLASH = "splash"
}

@Composable
fun RootScreen(viewModel: MainViewModel) {
  val welcomeDone by viewModel.welcomeCompleted.collectAsState()
  val isActivated by viewModel.isActivated.collectAsState()
  val onboardingCompleted by viewModel.onboardingCompleted.collectAsState()

  val startDestination = when {
    !welcomeDone -> Routes.WELCOME
    !isActivated -> Routes.ACTIVATION
    !onboardingCompleted -> Routes.ONBOARDING
    else -> Routes.SPLASH
  }

  val navController = rememberNavController()

  NavHost(
    navController = navController,
    startDestination = startDestination,
  ) {
    composable(Routes.SPLASH) {
      SplashScreen(
        onReady = {
          navController.navigate(Routes.SHELL) {
            popUpTo(Routes.SPLASH) { inclusive = true }
          }
        },
        modifier = Modifier.fillMaxSize(),
      )
    }
    composable(Routes.WELCOME) {
      WelcomeScreen(
        onStart = {
          viewModel.setWelcomeCompleted()
          navController.navigate(Routes.ACTIVATION) {
            popUpTo(Routes.WELCOME) { inclusive = true }
          }
        },
        modifier = Modifier.fillMaxSize(),
      )
    }
    composable(Routes.ACTIVATION) {
      ActivationScreen(
        onActivate = { code, onResult ->
          viewModel.activate(code, onResult = onResult)
        },
        modifier = Modifier.fillMaxSize(),
      )
    }
    composable(Routes.ONBOARDING) {
      OnboardingFlow(
        viewModel = viewModel,
        modifier = Modifier.fillMaxSize(),
        onComplete = {
          navController.navigate(Routes.SHELL) {
            popUpTo(Routes.ONBOARDING) { inclusive = true }
          }
        },
      )
    }
    composable(Routes.SHELL) {
      ShellScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
    }
  }
}

/**
 * SplashScreen — Memento 品牌启动画面。
 */
@Composable
private fun SplashScreen(
  onReady: () -> Unit,
  modifier: Modifier = Modifier,
) {
  LaunchedEffect(Unit) {
    delay(800)
    onReady()
  }

  val infiniteTransition = rememberInfiniteTransition(label = "splash")
  val alpha by infiniteTransition.animateFloat(
    initialValue = 0.4f,
    targetValue = 1.0f,
    animationSpec = infiniteRepeatable(
      animation = tween(1200, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "splashAlpha",
  )

  Box(
    modifier = modifier
      .background(MaterialTheme.colorScheme.background)
      .fillMaxSize(),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = "Memento",
        style = MaterialTheme.typography.headlineLarge.copy(
          fontWeight = FontWeight.Bold,
          fontSize = 36.sp,
          letterSpacing = 4.sp,
        ),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.alpha(alpha),
      )
      Spacer(modifier = Modifier.height(16.dp))
      Text(
        text = "记忆正在苏醒...",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
      )
    }
  }
}
