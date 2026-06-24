package com.myagent.app.ui

import com.myagent.app.MainViewModel
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

/**
 * 根路由 — 根据 onboarding 状态决定显示欢迎页还是主界面。
 */
@Composable
fun RootScreen(viewModel: MainViewModel) {
  val onboardingCompleted by viewModel.onboardingCompleted.collectAsState()

  if (!onboardingCompleted) {
    OnboardingFlow(viewModel = viewModel, modifier = Modifier.fillMaxSize())
    return
  }

  ShellScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
}