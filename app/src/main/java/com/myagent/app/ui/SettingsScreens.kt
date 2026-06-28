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
import androidx.compose.material.icons.filled.Download
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

    // ── 人格设置（仪式感） ──
    if (personaSelected) {
      // 已锁定：显示当前人格，不可点击
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
    } else {
      // 未选择：显示入口
      SettingsRow(
        icon = Icons.Default.Person,
        title = "选择人格",
        subtitle = "当前：${currentPersona.displayName}（默认，可更改）",
        onClick = onRequestPersonaSelection,
      )
    }

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

  // 外观选择弹窗
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

  // 视频画质选择弹窗
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
}

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

// ── 视频画质标签 ──
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
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { onSelect(config) }
              .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(
              selected = isSelected,
              onClick = { onSelect(config) },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
              Text(
                text = VideoConfig.PRESET_LABELS[index],
                style = MaterialTheme.typography.bodyMedium,
              )
              Text(
                text = "${config.width}×${config.height} · ${config.fps}fps · 最长${config.maxDuration}s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text("取消") }
    },
  )
}

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
      Text(
        text = "AI 模型",
        style = MaterialTheme.typography.bodyLarge,
      )
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
        colors = ButtonDefaults.buttonColors(
          containerColor = Color(0xFF4ECDC4),
        ),
      ) {
        Text("下载")
      }
    }
  }
  HorizontalDivider()
}

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
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { onSelect(mode) }
              .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(
              selected = mode == currentMode,
              onClick = { onSelect(mode) },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = label)
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text("取消") }
    },
  )
}