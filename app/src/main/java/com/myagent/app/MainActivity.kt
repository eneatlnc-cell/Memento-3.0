package com.myagent.app

import com.myagent.app.ui.LingjiSplashScreen
import com.myagent.app.ui.OpenClawTheme
import com.myagent.app.ui.RootScreen
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 灵机 v2.0 主 Activity — 单一 Activity 架构，使用 Jetpack Compose。
 *
 * 启动流程：品牌像素 splash（1s 淡入淡出） → 主界面。
 */
class MainActivity : ComponentActivity() {
  private val viewModel: MainViewModel by viewModels()
  private var initializedViewModel: MainViewModel? = null
  private var foreground = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    WindowCompat.setDecorFitsSystemWindows(window, false)

    setContent {
      var showSplash by remember { mutableStateOf(true) }
      var activeViewModel by remember { mutableStateOf<MainViewModel?>(null) }

      LaunchedEffect(Unit) {
        withFrameNanos { }
        withContext(Dispatchers.Default) {
          (application as NodeApp).prefs
        }
        val readyViewModel = viewModel
        activateViewModel(readyViewModel)
        activeViewModel = readyViewModel
      }

      if (showSplash) {
        LingjiSplashScreen(
          onSplashComplete = { showSplash = false }
        )
      } else {
        val currentViewModel = activeViewModel
        if (currentViewModel == null) {
          // 极端情况：ViewModel 还没初始化完，显示纯黑背景
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(Color.Black)
          )
        } else {
          val appearanceThemeMode by currentViewModel.appearanceThemeMode.collectAsState()
          OpenClawTheme(themeMode = appearanceThemeMode) {
            RootScreen(viewModel = currentViewModel)
          }
        }
      }
    }
  }

  override fun onStart() {
    super.onStart()
    foreground = true
    initializedViewModel?.setForeground(true)
  }

  override fun onStop() {
    foreground = false
    initializedViewModel?.setForeground(false)
    super.onStop()
  }

  private fun activateViewModel(readyViewModel: MainViewModel) {
    if (initializedViewModel != null) return
    initializedViewModel = readyViewModel
    readyViewModel.setForeground(foreground)
  }
}