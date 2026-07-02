package com.myagent.app.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 多模态输入栏 — 加号折叠菜单。
 *
 * 布局：
 *   [+] [ 输入框 ] [发送/停止]
 *
 * 点击加号 → 底部浮层：图片 / 视频
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInputBar(
  isLoading: Boolean,
  onSendText: (String) -> Unit,
  onSendImage: (Uri) -> Unit,
  onSendVideo: (Uri) -> Unit,
  onAbort: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var inputText by remember { mutableStateOf("") }
  val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current
  val scope = rememberCoroutineScope()

  // --- 加号浮层 ---
  var showSheet by remember { mutableStateOf(false) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  // --- 图片选择器 ---
  val imagePicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
  ) { uri: Uri? ->
    if (uri != null) onSendImage(uri)
  }

  // --- 视频选择器 ---
  val videoPicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
  ) { uri: Uri? ->
    if (uri != null) onSendVideo(uri)
  }

  /** 等待浮层关闭后启动文件选择器 */
  fun launchAfterSheetClose(block: () -> Unit) {
    showSheet = false
    keyboardController?.hide()
    scope.launch {
      sheetState.hide()
      delay(100)
      block()
    }
  }

  // ── 加号浮层 ──
  if (showSheet) {
    ModalBottomSheet(
      onDismissRequest = { showSheet = false },
      sheetState = sheetState,
      shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 32.dp),
      ) {
        // 图片
        SheetOption(
          icon = Icons.Default.Image,
          label = "图片",
          description = "从相册选择图片",
          onClick = {
            launchAfterSheetClose { imagePicker.launch("image/*") }
          },
        )
        // 视频
        SheetOption(
          icon = Icons.Default.Videocam,
          label = "视频",
          description = "从相册选择视频",
          onClick = {
            launchAfterSheetClose { videoPicker.launch("video/*") }
          },
        )
      }
    }
  }

  // ── 主输入栏 ──
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // 加号按钮
    IconButton(
      onClick = { showSheet = true },
      modifier = Modifier.size(44.dp),
      enabled = !isLoading,
    ) {
      Icon(
        imageVector = Icons.Default.Add,
        contentDescription = "更多",
        tint = if (isLoading) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    // ── 文本模式 ──
    OutlinedTextField(
      value = inputText,
      onValueChange = { inputText = it },
      placeholder = { Text("和 Memento 说点什么...") },
      modifier = Modifier.weight(1f),
      maxLines = 4,
      shape = RoundedCornerShape(24.dp),
    )

    Spacer(modifier = Modifier.width(4.dp))

    if (isLoading) {
      IconButton(onClick = onAbort) {
        Icon(
          imageVector = Icons.Default.Stop,
          contentDescription = "停止生成",
          tint = MaterialTheme.colorScheme.error,
        )
      }
    } else {
      IconButton(
        onClick = {
          val text = inputText.trim()
          if (text.isNotEmpty()) {
            onSendText(text)
            inputText = ""
            focusManager.clearFocus()
            keyboardController?.hide()
          }
        },
      ) {
        Icon(
          imageVector = Icons.Default.Send,
          contentDescription = "发送",
          tint = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}

// ── 浮层选项 ──

@Composable
private fun SheetOption(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  description: String,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 24.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(48.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceVariant),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = label,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(24.dp),
      )
    }
    Spacer(modifier = Modifier.width(16.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
      )
      Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    // 点击区域
    IconButton(onClick = onClick) {
      Icon(
        imageVector = Icons.Default.Add,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}