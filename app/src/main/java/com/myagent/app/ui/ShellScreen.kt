package com.myagent.app.ui

import com.myagent.app.MainViewModel
import com.myagent.app.model.PersonaType
import com.myagent.app.ui.chat.ChatScreen
import com.myagent.app.ui.design.ClawBottomNav
import com.myagent.app.ui.design.ClawDesignTheme
import com.myagent.app.ui.design.ClawNavItem
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * 主界面 Shell — 底部导航栏切换聊天页和设置页。
 *
 * v2.0：集成全屏仪式感人格选择界面。
 */
@Composable
fun ShellScreen(
  viewModel: MainViewModel,
  modifier: Modifier = Modifier,
) {
  var selectedTab by rememberSaveable { mutableStateOf("chat") }
  var showPersonaSelection by remember { mutableStateOf(false) }

  val navItems = listOf(
    ClawNavItem(key = "chat", label = "聊天", icon = Icons.Outlined.ChatBubbleOutline),
    ClawNavItem(key = "settings", label = "设置", icon = Icons.Outlined.Settings),
  )

  ClawDesignTheme {
    Scaffold(
      modifier = modifier,
      bottomBar = {
        if (!showPersonaSelection) {
          ClawBottomNav(
            items = navItems,
            selectedKey = selectedTab,
            onSelect = { selectedTab = it },
          )
        }
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
          onRequestPersonaSelection = { showPersonaSelection = true },
        )
      }
    }

    // 全屏人格选择叠加层
    AnimatedVisibility(
      visible = showPersonaSelection,
      enter = fadeIn(),
      exit = fadeOut(),
    ) {
      PersonaSelectionScreen(
        onConfirmed = { type: PersonaType ->
          viewModel.lockPersona(type)
          showPersonaSelection = false
        },
        onDismiss = {
          showPersonaSelection = false
        },
      )
    }
  }
}