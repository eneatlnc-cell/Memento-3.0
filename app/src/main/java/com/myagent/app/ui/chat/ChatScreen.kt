package com.myagent.app.ui.chat

import com.myagent.app.MainViewModel
import com.myagent.app.chat.ChatMessage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 聊天页面 — 多模态对话：文本 + 图片 + 语音。
 *
 * 交互：
 * - 点击空白区域收起键盘
 * - 发送消息后自动收起键盘
 */
@Composable
fun ChatScreen(
  viewModel: MainViewModel,
  modifier: Modifier = Modifier,
) {
  val messages by viewModel.chatMessages.collectAsState()
  val streamingText by viewModel.chatStreamingText.collectAsState()
  val isLoading by viewModel.chatLoading.collectAsState()
  val error by viewModel.chatError.collectAsState()

  var inputText by remember { mutableStateOf("") }
  val listState = rememberLazyListState()
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current

  // 自动滚动到最新消息
  LaunchedEffect(messages.size, streamingText) {
    if (messages.isNotEmpty()) {
      listState.animateScrollToItem(messages.size - 1)
    }
  }

  // 点击空白区域收起键盘
  Column(
    modifier = modifier
      .clickable(
        indication = null,
        interactionSource = remember { MutableInteractionSource() },
      ) {
        focusManager.clearFocus()
        keyboardController?.hide()
      }
  ) {
    // 消息列表
    LazyColumn(
      state = listState,
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth(),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (messages.isEmpty() && !isLoading) {
        item {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(32.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = "开始和 Memento 聊天吧！\n支持文字、图片、语音",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              fontSize = 16.sp,
            )
          }
        }
      }

      items(messages, key = { it.id }) { message ->
        MessageBubble(message = message)
      }

      // 流式文字
      if (!streamingText.isNullOrEmpty()) {
        item {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 4.dp),
            contentAlignment = Alignment.CenterStart,
          ) {
            Text(
              text = streamingText!!,
              modifier = Modifier
                .background(
                  color = MaterialTheme.colorScheme.surfaceVariant,
                  shape = RoundedCornerShape(12.dp),
                )
                .padding(12.dp)
                .widthIn(max = 300.dp),
              fontSize = 15.sp,
            )
          }
        }
      }

      // 错误提示
      if (error != null) {
        item {
          Text(
            text = error!!,
            color = MaterialTheme.colorScheme.error,
            fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 4.dp),
          )
        }
      }
    }

    // 输入区域 — 多模态入口
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // 语音入口
      IconButton(
        onClick = {
          // TODO: 启动语音录制（步骤5逻辑占位）
        },
        modifier = Modifier.size(44.dp),
      ) {
        Icon(
          imageVector = Icons.Default.Mic,
          contentDescription = "语音输入",
          tint = if (isLoading) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
          else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      // 图片入口
      IconButton(
        onClick = {
          // TODO: 打开图片选择器（步骤5逻辑占位）
        },
        modifier = Modifier.size(44.dp),
      ) {
        Icon(
          imageVector = Icons.Default.Image,
          contentDescription = "图片输入",
          tint = if (isLoading) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
          else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      // 文本输入
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
        IconButton(onClick = { viewModel.abortChat() }) {
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
              viewModel.sendChat(text)
              inputText = ""
              // 发送后收起键盘
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
}

/**
 * 多模态消息气泡 — 支持文字、图片、语音。
 */
@Composable
private fun MessageBubble(message: ChatMessage) {
  val isUser = message.role == "user"

  Box(
    modifier = Modifier.fillMaxWidth(),
    contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
  ) {
    Column(
      modifier = Modifier
        .widthIn(max = 300.dp)
        .clip(
          RoundedCornerShape(
            topStart = 12.dp,
            topEnd = 12.dp,
            bottomStart = if (isUser) 12.dp else 4.dp,
            bottomEnd = if (isUser) 4.dp else 12.dp,
          ),
        )
        .background(
          if (isUser) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.surfaceVariant,
        )
        .padding(12.dp),
    ) {
      when (message.type) {
        "image" -> {
          Box(
            modifier = Modifier
              .width(200.dp)
              .height(150.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(
                if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
              ),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.Default.Image,
              contentDescription = "图片",
              tint = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
              else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
              modifier = Modifier.size(40.dp),
            )
          }
          if (message.content.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = message.content,
              color = if (isUser) MaterialTheme.colorScheme.onPrimary
              else MaterialTheme.colorScheme.onSurface,
              fontSize = 13.sp,
            )
          }
        }
        "voice" -> {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Default.PlayArrow,
              contentDescription = "播放",
              tint = if (isUser) MaterialTheme.colorScheme.onPrimary
              else MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = if (message.content.isNotEmpty()) message.content else "语音消息",
              color = if (isUser) MaterialTheme.colorScheme.onPrimary
              else MaterialTheme.colorScheme.onSurface,
              fontSize = 14.sp,
            )
          }
        }
        else -> {
          if (message.content.isNotEmpty()) {
            Text(
              text = message.content,
              color = if (isUser) MaterialTheme.colorScheme.onPrimary
              else MaterialTheme.colorScheme.onSurface,
              fontSize = 15.sp,
            )
          }
          if (message.attachmentUri != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = "[附件]",
              color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f)
              else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
              fontSize = 12.sp,
            )
          }
        }
      }
    }
  }
}