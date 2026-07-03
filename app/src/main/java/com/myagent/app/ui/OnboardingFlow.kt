package com.myagent.app.ui

import com.myagent.app.MainViewModel
import com.myagent.app.model.DownloadForegroundService
import com.myagent.app.model.ModelDownloadState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * 引导流程 — 模型下载（强制）。
 *
 * v3.0: 移除人格选择步骤。模型下载完成后直接进入主界面。
 * Memento 不再预设人格，表达风格由对话历史自然塑造。
 *
 * @param onComplete 引导完成后回调，由 NavHost 触发导航到主界面。
 */
@Composable
fun OnboardingFlow(
  viewModel: MainViewModel,
  modifier: Modifier = Modifier,
  onComplete: () -> Unit = {},
) {
  val downloadState by viewModel.downloadState.collectAsState()
  val context = LocalContext.current
  // 防止重复调用 onComplete
  var completed by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    viewModel.startModelDownload()
  }

  // 下载完成后直接完成引导（包括后台下载完成后的场景）
  LaunchedEffect(downloadState) {
    if (downloadState is ModelDownloadState.Completed && !completed) {
      completed = true
      viewModel.setOnboardingCompleted(true)
      onComplete()
    }
  }

  val retryCount by viewModel.downloadRetryCount.collectAsState()

  Surface(modifier = modifier) {
    ModelDownloadScreen(
      state = downloadState,
      onExit = {
        DownloadForegroundService.start(context)
      },
      onRetry = {
        viewModel.resetModelDownload()
      },
      retryCount = retryCount,
    )
  }
}