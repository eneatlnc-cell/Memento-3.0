package com.myagent.app.ui

import com.myagent.app.MainViewModel
import com.myagent.app.ui.chat.ChatScreen
import com.myagent.app.ui.design.ClawDesignTheme
import com.myagent.app.ui.design.ClawNavItem
import com.myagent.app.ui.design.ClawScaffold
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * 主界面 Shell — 底部导航栏切换聊天页和设置页。
 */
@Composable
fun ShellScreen(
  viewModel: MainViewModel,
  modifier: Modifier = Modifier,
) {
  var selectedTab by rememberSaveable { mutableIntStateOf(0) }

  val navItems = listOf(
    ClawNavItem("聊天", Icons.Outlined.ChatBubbleOutline, 0),
    ClawNavItem("设置", Icons.Outlined.Settings, 1),
  )

  ClawDesignTheme {
    Scaffold(
      modifier = modifier,
      bottomBar = {
        ClawScaffold(
          items = navItems,
          selectedIndex = selectedTab,
          onItemSelected = { selectedTab = it },
        )
      },
    ) { innerPadding ->
      when (selectedTab) {
        0 -> ChatScreen(
          viewModel = viewModel,
          modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        )
        1 -> SettingsScreen(
          viewModel = viewModel,
          modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        )
      }
    }
  }
}