package com.myagent.app.ui

import com.myagent.app.MainViewModel
import com.myagent.app.model.ModelDownloadState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/**
 * 根路由 — 5 步闭环流程，使用 Navigation Compose NavHost：
 * 1. 欢迎页 → 2. 激活 → 3. 模型下载 → 4. 人格选择 → 5. 对话
 *
 * 严格拦截：模型未下载完成，即使 onboarding 标记为完成也不允许进入主界面。
 */
private object Routes {
  const val WELCOME = "welcome"
  const val ACTIVATION = "activation"
  const val ONBOARDING = "onboarding"
  const val SHELL = "shell"
}

@Composable
fun RootScreen(viewModel: MainViewModel) {
  val welcomeDone by viewModel.welcomeCompleted.collectAsState()
  val isActivated by viewModel.isActivated.collectAsState()
  val onboardingCompleted by viewModel.onboardingCompleted.collectAsState()
  val downloadState by viewModel.downloadState.collectAsState()

  // 模型就绪判断：
  // - Completed 直接通过
  // - onboarding 已完成 + Idle（Runtime 未启动时的默认值）→ 信任上次已下载
  val modelReady = when (downloadState) {
    is ModelDownloadState.Completed -> true
    is ModelDownloadState.Idle -> onboardingCompleted
    else -> false
  }

  val startDestination = when {
    !welcomeDone -> Routes.WELCOME
    !isActivated -> Routes.ACTIVATION
    !onboardingCompleted || !modelReady -> Routes.ONBOARDING
    else -> Routes.SHELL
  }

  val navController = rememberNavController()

  NavHost(
    navController = navController,
    startDestination = startDestination,
  ) {
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
        onActivate = { code ->
          val success = viewModel.activate(code)
          if (success) {
            navController.navigate(Routes.ONBOARDING) {
              popUpTo(Routes.ACTIVATION) { inclusive = true }
            }
          }
          success
        },
        modifier = Modifier.fillMaxSize(),
      )
    }
    // 步骤3-4：引导流程（模型下载 → 人格选择）
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
    // 步骤5：主界面
    composable(Routes.SHELL) {
      ShellScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
    }
  }
}