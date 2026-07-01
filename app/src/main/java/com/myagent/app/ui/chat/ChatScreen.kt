package com.myagent.app.ui.chat

import com.myagent.app.BackgroundPattern
import com.myagent.app.MainViewModel
import com.myagent.app.ui.LocalSkinColors
import android.media.MediaPlayer
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 聊天页面 — 编排层。
 *
 * 职责：
 * - 收集 StateFlow（messages / streamingText / isLoading / error）
 * - 组合子组件：消息列表 + 输入栏
 * - 不持有 UI 渲染细节，全部委托给子组件
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

  val listState = rememberLazyListState()
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val skinColors = LocalSkinColors.current
  // 并发 TTS 播放防护
  var isPlayingTts by remember { mutableStateOf(false) }

  // 自动滚动到最新消息 — 仅当用户在底部附近时自动滚动，避免打断用户回看历史消息
  LaunchedEffect(messages.size, streamingText) {
    if (messages.isNotEmpty()) {
      val layout = listState.layoutInfo
      val lastVisible = layout.visibleItemsInfo.lastOrNull()
      val isAtBottom = lastVisible != null && lastVisible.index >= messages.size - 2
      if (isAtBottom) {
        listState.scrollToItem(messages.size - 1)
      }
    }
  }

  // 主动搭话 — 首次进入聊天页时检查
  LaunchedEffect(Unit) {
    val proactiveMsg = viewModel.checkProactive(isAppLaunch = true)
    if (proactiveMsg != null) {
      // 搭话作为系统消息插入，不触发模型推理
      viewModel.insertSystemMessage(proactiveMsg)
    }
  }

  Column(
    modifier = modifier
      .background(skinColors.canvas)
      .clickable(
        indication = null,
        interactionSource = remember { MutableInteractionSource() },
      ) {
        focusManager.clearFocus()
        keyboardController?.hide()
      }
      .imePadding() // 键盘弹出时自动上推输入框，避免被遮挡
  ) {
    // ── 消息列表 ──
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
          EmptyChatHint()
        }
      }

      items(messages, key = { it.id }) { message ->
        // 流式输出中时，隐藏空内容的助手消息（由 StreamingTextBubble 替代显示）
        if (message.role == "assistant" && message.content.isEmpty() && !streamingText.isNullOrEmpty()) {
          return@items
        }
        MessageBubble(
          message = message,
          onPlayTts = { text ->
            if (!isPlayingTts) {
              isPlayingTts = true
              scope.launch {
                try {
                  playTts(viewModel, context, message.id, text)
                } finally {
                  isPlayingTts = false
                }
              }
            }
          },
        )
      }

      // 加载中 + 无流式文本 → 显示 typing 指示器
      if (isLoading && streamingText.isNullOrEmpty()) {
        item {
          TypingIndicator()
        }
      }

      // 流式文字
      streamingText?.let { text ->
        item {
          StreamingTextBubble(text = text)
        }
      }

      // 错误提示
      error?.let { err ->
        item {
          Text(
            text = err,
            color = MaterialTheme.colorScheme.error,
            fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 4.dp),
          )
        }
      }
    }

    // ── 输入栏（多模态） ──
    ChatInputBar(
      isLoading = isLoading,
      onSendText = { text -> viewModel.sendChat(text) },
      onSendImage = { uri -> viewModel.sendImage(uri) },
      onSendVideo = { uri -> viewModel.sendVideo(uri) },
      onSendVoice = { uri -> viewModel.sendVoice(uri) },
      onAbort = { viewModel.abortChat() },
    )
  }
}

@Composable
private fun EmptyChatHint() {
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

@Composable
private fun StreamingTextBubble(text: String) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp),
    contentAlignment = Alignment.CenterStart,
  ) {
    Text(
      text = text,
      modifier = Modifier
        .clip(RoundedCornerShape(12.dp))
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

/**
 * Memento 正在思考的动画指示器 — 三个跳动的点。
 */
@Composable
private fun TypingIndicator() {
  val infiniteTransition = rememberInfiniteTransition(label = "typing")
  val dotCount = 3
  val dotAlpha = List(dotCount) { index ->
    infiniteTransition.animateFloat(
      initialValue = 0.3f,
      targetValue = 1.0f,
      animationSpec = infiniteRepeatable(
        animation = tween(400, delayMillis = index * 150),
        repeatMode = RepeatMode.Reverse,
      ),
      label = "dotAlpha$index",
    )
  }

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp),
    contentAlignment = Alignment.CenterStart,
  ) {
    Row(
      modifier = Modifier
        .clip(RoundedCornerShape(12.dp))
        .background(
          color = MaterialTheme.colorScheme.surfaceVariant,
          shape = RoundedCornerShape(12.dp),
        )
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "Memento 正在思考",
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.size(4.dp))
      repeat(dotCount) { index ->
        Box(
          modifier = Modifier
            .size(6.dp)
            .alpha(dotAlpha[index].value)
            .background(
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              shape = CircleShape,
            ),
        )
        if (index < dotCount - 1) {
          Spacer(modifier = Modifier.size(4.dp))
        }
      }
    }
  }
}

private suspend fun playTts(
  viewModel: MainViewModel,
  context: android.content.Context,
  messageId: String,
  text: String,
) {
  val tmp = File(context.cacheDir, "tts_${messageId}.wav")
  try {
    val wav = withContext(Dispatchers.Default) {
      viewModel.synthesizeSpeech(text)
    }
    if (wav.isEmpty()) {
      android.widget.Toast.makeText(context, "无法播放语音", android.widget.Toast.LENGTH_SHORT).show()
      return
    }
    FileOutputStream(tmp).use { it.write(wav) }
    withContext(Dispatchers.Main) {
      val mp = MediaPlayer()
      try {
        mp.setDataSource(tmp.absolutePath)
        mp.setOnPreparedListener { it.start() }
        mp.setOnCompletionListener {
          it.release()
          tmp.delete() // 播放完成后清理临时文件
        }
        mp.setOnErrorListener { _, _, _ ->
          mp.release()
          tmp.delete()
          android.widget.Toast.makeText(context, "无法播放语音", android.widget.Toast.LENGTH_SHORT).show()
          true
        }
        mp.prepareAsync()
      } catch (e: Exception) {
        mp.release()
        tmp.delete()
        android.widget.Toast.makeText(context, "无法播放语音", android.widget.Toast.LENGTH_SHORT).show()
      }
    }
  } catch (e: Exception) {
    tmp.delete()
    withContext(Dispatchers.Main) {
      android.widget.Toast.makeText(context, "无法播放语音", android.widget.Toast.LENGTH_SHORT).show()
    }
  }
}