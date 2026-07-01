package com.myagent.app.ui

import com.myagent.app.AppearanceThemeMode
import com.myagent.app.MainViewModel
import com.myagent.app.model.ModelDownloadState
import com.myagent.app.model.PersonaType
import com.myagent.app.multimodal.VideoConfig
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 设置页面 — v2.0 仪式感人格 + 视频画质设置。
 *
 * 拆分为独立的 Section 组件，每个弹窗独立管理状态。
 */
@Composable
fun SettingsScreen(
  viewModel: MainViewModel,
  modifier: Modifier = Modifier,
  onRequestPersonaSelection: () -> Unit = {},
) {
  val currentPersona by viewModel.currentPersona.collectAsState()
  val personaSelected by viewModel.personaSelected.collectAsState()
  val appearanceMode by viewModel.appearanceThemeMode.collectAsState()
  val downloadState by viewModel.downloadState.collectAsState()
  val videoConfig by viewModel.videoConfig.collectAsState()
  var showAppearanceDialog by remember { mutableStateOf(false) }
  var showVideoDialog by remember { mutableStateOf(false) }
  var showDataDialog by remember { mutableStateOf(false) }
  var showAboutDialog by remember { mutableStateOf(false) }

  Column(
    modifier = modifier
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
  ) {
    Text(
      text = "设置",
      style = MaterialTheme.typography.headlineMedium,
      modifier = Modifier.padding(bottom = 16.dp),
    )

    // ── 人格设置 ──
    PersonaSection(
      personaSelected = personaSelected,
      currentPersona = currentPersona,
      onRequestPersonaSelection = onRequestPersonaSelection,
    )

    HorizontalDivider()

    // ── 数据管理 ──
    SettingsRow(
      icon = Icons.Default.Delete,
      title = "数据管理",
      subtitle = "聊天记录、记忆数据",
      onClick = { showDataDialog = true },
    )

    HorizontalDivider()

    // ── 关于 ──
    SettingsRow(
      icon = Icons.Default.Info,
      title = "关于 Memento",
      subtitle = "版本 2.0.0",
      onClick = { showAboutDialog = true },
    )

    HorizontalDivider()

    // ── 外观设置 ──
    SettingsRow(
      icon = Icons.Default.Palette,
      title = "外观",
      subtitle = when (appearanceMode) {
        AppearanceThemeMode.System -> "跟随系统"
        AppearanceThemeMode.Light -> "浅色"
        AppearanceThemeMode.Dark -> "深色"
      },
      onClick = { showAppearanceDialog = true },
    )

    HorizontalDivider()

    // ── 视频画质设置 ──
    SettingsRow(
      icon = Icons.Default.Videocam,
      title = "视频画质",
      subtitle = videoConfigLabel(videoConfig),
      onClick = { showVideoDialog = true },
    )

    HorizontalDivider()

    // ── 模型下载 ──
    DownloadSection(
      state = downloadState,
      onStartDownload = { viewModel.resetModelDownload() },
    )

    Spacer(modifier = Modifier.height(32.dp))

    Text(
      text = "Memento v2.0",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }

  // 弹窗
  if (showAppearanceDialog) {
    AppearanceDialog(
      currentMode = appearanceMode,
      onSelect = {
        viewModel.setAppearanceThemeMode(it)
        showAppearanceDialog = false
      },
      onDismiss = { showAppearanceDialog = false },
    )
  }

  if (showVideoDialog) {
    VideoConfigDialog(
      currentConfig = videoConfig,
      onSelect = {
        viewModel.setVideoConfig(it)
        showVideoDialog = false
      },
      onDismiss = { showVideoDialog = false },
    )
  }

  if (showAboutDialog) {
    AboutDialog(
      onDismiss = { showAboutDialog = false },
    )
  }

  if (showDataDialog) {
    DataDialog(
      onClearChat = {
        viewModel.clearChatHistory()
        showDataDialog = false
      },
      onClearAll = {
        viewModel.clearAllMemories()
        showDataDialog = false
      },
      onDismiss = { showDataDialog = false },
    )
  }
}

// ── 人格区域 ──

@Composable
private fun PersonaSection(
  personaSelected: Boolean,
  currentPersona: PersonaType,
  onRequestPersonaSelection: () -> Unit,
) {
  if (personaSelected) {
    PersonaLockedRow(currentPersona = currentPersona)
  } else {
    SettingsRow(
      icon = Icons.Default.Person,
      title = "选择人格",
      subtitle = "当前：${currentPersona.displayName}（默认，可更改）",
      onClick = onRequestPersonaSelection,
    )
  }
}

@Composable
private fun PersonaLockedRow(currentPersona: PersonaType) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 16.dp, horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = Icons.Default.Lock,
      contentDescription = null,
      tint = Color(0xFF6C5CE7),
    )
    Spacer(modifier = Modifier.width(16.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = "AI 人格",
        style = MaterialTheme.typography.bodyLarge,
      )
      Text(
        text = "${currentPersona.emoji} ${currentPersona.displayName}（已锁定，终身有效）",
        style = MaterialTheme.typography.bodyMedium,
        color = Color(0xFF6C5CE7),
      )
    }
  }
}

// ── 通用设置行 ──

@Composable
private fun SettingsRow(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  title: String,
  subtitle: String,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(vertical = 16.dp, horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
    )
    Spacer(modifier = Modifier.width(16.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(text = title, style = MaterialTheme.typography.bodyLarge)
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Icon(
      imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

// ── 视频画质 ──

private fun videoConfigLabel(config: VideoConfig): String {
  val presetIndex = VideoConfig.PRESETS.indexOfFirst {
    it.width == config.width && it.height == config.height && it.fps == config.fps
  }
  return if (presetIndex >= 0) {
    VideoConfig.PRESET_LABELS[presetIndex]
  } else {
    "${config.width}x${config.height} · ${config.fps}fps · ${config.maxDuration}s"
  }
}

@Composable
private fun VideoConfigDialog(
  currentConfig: VideoConfig,
  onSelect: (VideoConfig) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("视频画质") },
    text = {
      Column {
        VideoConfig.PRESETS.forEachIndexed { index, config ->
          val isSelected = config.width == currentConfig.width &&
            config.height == currentConfig.height &&
            config.fps == currentConfig.fps
          SelectableRow(
            label = VideoConfig.PRESET_LABELS[index],
            detail = "${config.width}×${config.height} · ${config.fps}fps · 最长${config.maxDuration}s",
            selected = isSelected,
            onClick = { onSelect(config) },
          )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text("取消") }
    },
  )
}

// ── 关于弹窗 ──

@Composable
private fun AboutDialog(
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("关于 Memento") },
    text = {
      Column {
        Text(
          text = "Memento v2.0.0",
          style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = "你的 AI 搭子，永远在线。",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
          text = "基于 Google AI Edge LiteRT-LM 引擎，端侧推理，数据不出设备。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text = "隐私政策",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.clickable { /* TODO: 打开隐私政策链接 */ },
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "用户协议",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.clickable { /* TODO: 打开用户协议链接 */ },
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
          text = "© 2024 Memento Team",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text("关闭") }
    },
  )
}

// ── 数据管理弹窗 ──

@Composable
private fun DataDialog(
  onClearChat: () -> Unit,
  onClearAll: () -> Unit,
  onDismiss: () -> Unit,
) {
  var showConfirmClearMemory by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("数据管理") },
    text = {
      Column {
        Text(
          text = "数据存储在本地设备上。",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        // 清除缓存 — 无需二次确认
        Button(
          onClick = {
            onClearChat()
            onDismiss()
          },
          modifier = Modifier.fillMaxWidth(),
          colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
          ),
        ) {
          Text("清除缓存")
        }
        Spacer(modifier = Modifier.height(8.dp))
        // 清除记忆 — 需要二次确认
        Button(
          onClick = { showConfirmClearMemory = true },
          modifier = Modifier.fillMaxWidth(),
          colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFFFFECEC),
            contentColor = Color(0xFFE87070),
          ),
        ) {
          Text("清除记忆")
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text("关闭") }
    },
  )

  // 二次确认：清除记忆
  if (showConfirmClearMemory) {
    AlertDialog(
      onDismissRequest = { showConfirmClearMemory = false },
      title = { Text("确认清除记忆") },
      text = { Text("确定要清除所有记忆数据吗？此操作不可撤销。") },
      confirmButton = {
        TextButton(
          onClick = {
            showConfirmClearMemory = false
            onClearAll()
            onDismiss()
          },
        ) { Text("确定", color = Color(0xFFE87070)) }
      },
      dismissButton = {
        TextButton(onClick = { showConfirmClearMemory = false }) { Text("取消") }
      },
    )
  }
}

// ── 外观弹窗 ──

@Composable
private fun AppearanceDialog(
  currentMode: AppearanceThemeMode,
  onSelect: (AppearanceThemeMode) -> Unit,
  onDismiss: () -> Unit,
) {
  val modes = listOf(
    AppearanceThemeMode.System to "跟随系统",
    AppearanceThemeMode.Light to "浅色",
    AppearanceThemeMode.Dark to "深色",
  )

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("选择外观") },
    text = {
      Column {
        for ((mode, label) in modes) {
          SelectableRow(
            label = label,
            selected = mode == currentMode,
            onClick = { onSelect(mode) },
          )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text("取消") }
    },
  )
}

// ── 通用可选项行 ──

@Composable
private fun SelectableRow(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
  detail: String? = null,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = onClick)
    Spacer(modifier = Modifier.width(8.dp))
    Column {
      Text(text = label, style = MaterialTheme.typography.bodyMedium)
      if (detail != null) {
        Text(
          text = detail,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

// ── 模型下载区域 ──

@Composable
private fun DownloadSection(
  state: ModelDownloadState,
  onStartDownload: () -> Unit,
) {
  val isDownloading = state is ModelDownloadState.Downloading || state is ModelDownloadState.Verifying
  val isCompleted = state is ModelDownloadState.Completed

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 12.dp, horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = Icons.Default.Download,
      contentDescription = null,
      tint = if (isCompleted) Color(0xFF4ECDC4) else MaterialTheme.colorScheme.primary,
    )
    Spacer(modifier = Modifier.width(16.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(text = "AI 模型", style = MaterialTheme.typography.bodyLarge)
      Text(
        text = when {
          isCompleted -> "模型已就绪"
          isDownloading -> "正在下载..."
          state is ModelDownloadState.Failed -> "下载失败，点击下载"
          state is ModelDownloadState.Idle -> "未下载，点击下载"
          else -> "未下载，点击下载"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (!isDownloading && !isCompleted) {
      Button(
        onClick = onStartDownload,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4ECDC4)),
      ) {
        Text("下载")
      }
    }
  }
  HorizontalDivider()
}