package com.myagent.app

import com.myagent.app.ui.OpenClawTheme
import com.myagent.app.ui.RootScreen
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 灵机 v2.0 主 Activity — 单一 Activity 架构，使用 Jetpack Compose。
 */
class MainActivity : ComponentActivity() {
  private val viewModel: MainViewModel by viewModels()
  private var initializedViewModel: MainViewModel? = null
  private var foreground = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    WindowCompat.setDecorFitsSystemWindows(window, false)

    setContent {
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

      val currentViewModel = activeViewModel
      if (currentViewModel == null) {
        OpenClawTheme {
          StartupSurface()
        }
      } else {
        val appearanceThemeMode by currentViewModel.appearanceThemeMode.collectAsState()
        OpenClawTheme(themeMode = appearanceThemeMode) {
          RootScreen(viewModel = currentViewModel)
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

@Composable
private fun StartupSurface() {
  Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
  ) {
    Image(
      painter = painterResource(id = R.drawable.splash_bg),
      contentDescription = "灵机",
      modifier = Modifier.fillMaxSize(),
      contentScale = ContentScale.Crop,
    )
  }
}