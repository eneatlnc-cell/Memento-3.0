package com.myagent.app.ui

import com.myagent.app.MainViewModel
import com.myagent.app.NodeApp
import com.myagent.app.model.ModelDownloadState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay

/**
 * 根路由 — 5 步闭环流程，使用 Navigation Compose NavHost：
 * 1. 欢迎页 → 2. 激活 → 3. 模型下载 → 4. 对话
 *
 * 严格拦截：模型未下载完成，即使 onboarding 标记为完成也不允许进入主界面。
 * v3.1：修复主线程 SHA256 阻塞，增加 SplashScreen。
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
  val downloadState by viewModel.downloadState.collectAsState()
  val runtimeInitialized by viewModel.runtimeInitialized.collectAsState()
  val context = LocalContext.current

  // 轻量级检查：仅判断文件是否存在，不做 SHA256（SHA256 在后台执行）
  val modelFileExists = (context.applicationContext as NodeApp).modelInstaller.isModelFileExists()

  // 模型就绪判断：文件存在即视为就绪（SHA256 校验在 NodeRuntime 内部后台完成）
  // 不再依赖 downloadState 做回退 — 修复"模型未安装也能进入主界面"的 bug
  val modelReady = modelFileExists

  // startDestination 必须是稳定常量，不可依赖 runtimeInitialized 等异步状态。
  // 否则 runtimeInitialized 从 false→true 时 startDestination 在 SPLASH/SHELL 间跳变，
  // 导致 NavHost back stack 与 startDestination 撕裂崩溃。
  // SPLASH→SHELL 的跳转改由下方 LaunchedEffect 显式管理。
  val startDestination = when {
    !welcomeDone -> Routes.WELCOME
    !isActivated -> Routes.ACTIVATION
    !onboardingCompleted -> Routes.ONBOARDING
    !modelReady -> Routes.ONBOARDING
    else -> Routes.SPLASH  // 模型就绪后统一从 SPLASH 启动，等待 runtime 初始化完成再跳 SHELL
  }

  val navController = rememberNavController()

  // SPLASH → SHELL 跳转：等待 runtime 初始化完成（JNI 模型加载，数秒）。
  // 不使用固定 delay，避免 runtime 未就绪时强行进入 SHELL 导致状态撕裂。
  // 仅在当前处于 SPLASH 时触发导航，避免重复跳转。
  LaunchedEffect(runtimeInitialized) {
    if (runtimeInitialized && navController.currentDestination?.route == Routes.SPLASH) {
      navController.navigate(Routes.SHELL) {
        popUpTo(Routes.SPLASH) { inclusive = true }
      }
    }
  }

  NavHost(
    navController = navController,
    startDestination = startDestination,
  ) {
    // SplashScreen
    composable(Routes.SPLASH) {
      SplashScreen(
        onReady = {
          // 兜底：若 runtime 已就绪但 LaunchedEffect 未触发（理论上不会），允许手动跳转
          if (runtimeInitialized && navController.currentDestination?.route == Routes.SPLASH) {
            navController.navigate(Routes.SHELL) {
              popUpTo(Routes.SPLASH) { inclusive = true }
            }
          }
        },
        modifier = Modifier.fillMaxSize(),
      )
    }
    // 步骤1：欢迎页
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
    // 步骤2：激活页
    composable(Routes.ACTIVATION) {
      ActivationScreen(
        onActivate = { code, onResult ->
          viewModel.activate(code, onResult = onResult)
        },
        modifier = Modifier.fillMaxSize(),
      )
    }
    // 步骤3-4：引导流程（模型下载）
    composable(Routes.ONBOARDING) {
      OnboardingFlow(
        viewModel = viewModel,
        modifier = Modifier.fillMaxSize(),
        onComplete = {
          // 不直接跳 SHELL：此时 runtimeInitialized 还是 false，跳 SHELL 会导致
          // startDestination（SPLASH）与 back stack（SHELL）撕裂崩溃。
          // 改为跳 SPLASH，由 LaunchedEffect(runtimeInitialized) 统一管理 SPLASH→SHELL。
          navController.navigate(Routes.SPLASH) {
            popUpTo(Routes.ONBOARDING) { inclusive = true }
          }
        },
      )
    }
    // 步骤5：主界面
    composable(Routes.SHELL) {
      ShellScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
    }
  }
}

/**
 * SplashScreen — Memento 品牌启动画面。
 *
 * 呼吸动画展示品牌名，runtime 在后台初始化完成后自动跳转主界面。
 * 解决启动后长时间空白的问题。
 */
@Composable
private fun SplashScreen(
  onReady: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // H-U1 修复：原 delay(800) 写在从未被读取的 showSplash 上（死代码），导致 splash 一进入即跳转；
  // 将 delay 移到此处，让品牌画面实际展示 800ms 再调用 onReady()
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
      verticalArrangement = Arrangement.Center,
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