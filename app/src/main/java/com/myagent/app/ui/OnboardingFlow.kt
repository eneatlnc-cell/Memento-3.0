package com.myagent.app.ui

import com.myagent.app.MainViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 引导流程 — v4.0 云端架构。
 *
 * 移除模型下载步骤（云端 API 无需下载模型）。
 * 引导页简述能力，点击「开始使用」即完成，进入主界面。
 * API 密钥可在设置页配置（未配置时对话会提示）。
 *
 * @param onComplete 引导完成后回调，由 NavHost 触发导航到主界面。
 */
@Composable
fun OnboardingFlow(
  viewModel: MainViewModel,
  modifier: Modifier = Modifier,
  onComplete: () -> Unit = {},
) {
  var completed by remember { mutableStateOf(false) }

  Surface(modifier = modifier.fillMaxSize()) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        Text(
          text = "Memento",
          style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
          text = "云端智能助手\n对话 · 图片 · 视频，由 GPT-4o、DALL-E 3 与可灵驱动",
          style = MaterialTheme.typography.bodyMedium,
          textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = {
          if (!completed) {
            completed = true
            viewModel.setOnboardingCompleted(true)
            onComplete()
          }
        }) {
          Text("开始使用")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
          text = "提示：首次使用请在设置中配置 API 密钥或代理端点",
          style = MaterialTheme.typography.bodySmall,
          textAlign = TextAlign.Center,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
