package com.myagent.app

import com.myagent.app.ui.MementoTheme
import com.myagent.app.ui.RootScreen
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat

/**
 * Memento v2.0 主 Activity — 单一 Activity 架构，使用 Jetpack Compose。
 *
 * 启动流程：欢迎页 → 激活页 → 模型下载 → 人格选择 → 对话。
 */
class MainActivity : ComponentActivity() {
  private val viewModel: MainViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    WindowCompat.setDecorFitsSystemWindows(window, false)

    setContent {
      val appearanceThemeMode by viewModel.appearanceThemeMode.collectAsState()
      MementoTheme(themeMode = appearanceThemeMode) {
        RootScreen(viewModel = viewModel)
      }
    }
  }
}