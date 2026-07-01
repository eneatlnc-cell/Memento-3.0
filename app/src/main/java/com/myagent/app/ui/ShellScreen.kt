package com.myagent.app.ui

import com.myagent.app.MainViewModel
import com.myagent.app.ui.chat.ChatScreen
import com.myagent.app.ui.design.ClawBottomNav
import com.myagent.app.ui.design.ClawNavItem
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * 主界面 Shell — 底部导航栏切换聊天页和设置页。
 *
 * v3.0: 移除人格选择叠加层。主题由父级 MementoTheme 统一提供。
 */
@Composable
fun ShellScreen(
  viewModel: MainViewModel,
  modifier: Modifier = Modifier,
) {
  var selectedTab by rememberSaveable { mutableStateOf("chat") }

  val navItems = listOf(
    ClawNavItem(key = "chat", label = "聊天", icon = Icons.Outlined.ChatBubbleOutline),
    ClawNavItem(key = "settings", label = "设置", icon = Icons.Outlined.Settings),
  )

  Scaffold(
    modifier = modifier,
    bottomBar = {
      ClawBottomNav(
        items = navItems,
        selectedKey = selectedTab,
        onSelect = { selectedTab = it },
      )
    },
  ) { innerPadding ->
    when (selectedTab) {
      "chat" -> ChatScreen(
        viewModel = viewModel,
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding),
      )
      "settings" -> SettingsScreen(
        viewModel = viewModel,
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding),
      )
    }
  }
}