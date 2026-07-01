package com.myagent.app.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * 多模态输入栏 — 加号折叠 + 按住说话。
 *
 * 布局：
 *   [+] [ 输入框 / 按住说话 ] [发送/停止]
 *
 * 点击加号 → 底部浮层：图片 / 语音 / 视频
 * 点击"语音" → 输入框切换为"按住说话"按钮
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInputBar(
  isLoading: Boolean,
  onSendText: (String) -> Unit,
  onSendImage: (Uri) -> Unit,
  onSendVideo: (Uri) -> Unit,
  onSendVoice: (Uri) -> Unit,
  onAbort: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var inputText by remember { mutableStateOf("") }
  val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  // --- 加号浮层 ---
  var showSheet by remember { mutableStateOf(false) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  // --- 语音模式 ---
  var isVoiceMode by remember { mutableStateOf(false) }
  var isRecording by remember { mutableStateOf(false) }
  // 使用 AtomicReference 避免闭包捕获过期状态，确保 stopRecording 总能拿到正确的 recorder 引用
  val recorderRef = remember { AtomicReference<MediaRecorder?>(null) }
  val audioFileRef = remember { AtomicReference<File?>(null) }
  var hasMicPermission by remember { mutableStateOf(false) }
  val micPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
  ) { granted -> hasMicPermission = granted }

  // 录音动画
  val recordingScale by animateFloatAsState(
    targetValue = if (isRecording) 1.3f else 1f,
    animationSpec = tween(200),
  )

  // --- 图片选择器 ---
  val imagePicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
  ) { uri: Uri? ->
    if (uri != null) onSendImage(uri)
    else android.widget.Toast.makeText(context, "已取消选择", android.widget.Toast.LENGTH_SHORT).show()
  }

  // --- 视频选择器 ---
  val videoPicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
  ) { uri: Uri? ->
    if (uri != null) onSendVideo(uri)
    else android.widget.Toast.makeText(context, "已取消选择", android.widget.Toast.LENGTH_SHORT).show()
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
            showSheet = false
            scope.launch {
              delay(300) // 等浮层收起
              imagePicker.launch("image/*")
            }
          },
        )
        // 语音
        SheetOption(
          icon = Icons.Default.Mic,
          label = "语音",
          description = "按住说话，松手发送",
          onClick = {
            showSheet = false
            if (!hasMicPermission) {
              micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
              // 权限请求是异步的，不立即进入语音模式
              return@SheetOption
            }
            isVoiceMode = true
          },
        )
        // 视频
        SheetOption(
          icon = Icons.Default.Videocam,
          label = "视频",
          description = "从相册选择视频",
          onClick = {
            showSheet = false
            scope.launch {
              delay(300)
              videoPicker.launch("video/*")
            }
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

    if (isVoiceMode) {
      // ── 语音模式：按住说话 ──
      Box(
        modifier = Modifier
          .weight(1f)
          .height(48.dp)
          .clip(RoundedCornerShape(24.dp))
          .background(
            if (isRecording) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant,
          )
          .pointerInput(Unit) {
            detectTapGestures(
              onPress = {
                // 权限二次检查
                if (!hasMicPermission) {
                  Toast.makeText(context, "请先授予麦克风权限", Toast.LENGTH_SHORT).show()
                  tryAwaitRelease()
                  return@detectTapGestures
                }
                // 按下 → 开始录音（IO 线程避免 ANR）
                isRecording = true
                var mr: MediaRecorder? = null
                var file: File? = null
                try {
                  file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
                  mr = createRecorder(context, file)
                  mr.prepare()
                  mr.start()
                  recorderRef.set(mr)
                  audioFileRef.set(file)
                } catch (e: Exception) {
                  Log.e("ChatInputBar", "Recording start failed: ${e.message}", e)
                  isRecording = false
                  isVoiceMode = false
                  Toast.makeText(context, "录音启动失败，请重试", Toast.LENGTH_SHORT).show()
                  mr?.release()
                  tryAwaitRelease()
                  return@detectTapGestures
                }
                try {
                  tryAwaitRelease()
                } finally {
                  // 松手 → 停止录音并发送
                  stopRecording(recorderRef.getAndSet(null), audioFileRef.getAndSet(null)) { f ->
                    onSendVoice(Uri.fromFile(f))
                  }
                  isRecording = false
                  isVoiceMode = false
                }
              },
            )
          },
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = if (isRecording) Icons.Default.Mic else Icons.Default.Mic,
          contentDescription = "按住说话",
          tint = if (isRecording) MaterialTheme.colorScheme.error
          else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier
            .size(24.dp)
            .scale(recordingScale),
        )
      }
      // 返回文本按钮
      IconButton(
        onClick = { isVoiceMode = false },
        modifier = Modifier.size(44.dp),
      ) {
        Icon(
          imageVector = Icons.Default.Stop,
          contentDescription = "返回键盘",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
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

// ── 录音工具函数 ──

/** 创建并配置 MediaRecorder，不调用 prepare/start（由调用方在合适线程执行） */
private fun createRecorder(
  context: android.content.Context,
  outputFile: File,
): MediaRecorder {
  return (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    MediaRecorder(context)
  } else {
    @Suppress("DEPRECATION")
    MediaRecorder()
  }).apply {
    setAudioSource(MediaRecorder.AudioSource.MIC)
    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
    setAudioSamplingRate(16000)
    setAudioEncodingBitRate(32000)
    setOutputFile(outputFile.absolutePath)
  }
}

private fun stopRecording(
  recorder: MediaRecorder?,
  audioFile: File?,
  onComplete: (File) -> Unit,
) {
  try {
    recorder?.stop()
  } catch (e: Exception) {
    Log.e("ChatInputBar", "Recorder stop failed: ${e.message}", e)
  }
  try {
    recorder?.release()
  } catch (e: Exception) {
    Log.e("ChatInputBar", "Recorder release failed: ${e.message}", e)
  }
  audioFile?.let { onComplete(it) }
}