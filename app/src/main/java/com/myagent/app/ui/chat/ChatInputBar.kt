package com.myagent.app.ui.chat

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.myagent.app.multimodal.KeyFrameStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 多模态输入栏 — 三段式工作流入口。
 *
 * v3.3 工作流：图形 / 帧 / 合成
 * - 图形：元素替换对话框，产出图片 + 可选关键帧
 * - 帧：基于缓存关键帧或新生成关键帧，逐帧改造
 * - 合成：取当前缓存关键帧，逐帧渲染合成 MP4
 *
 * 三段式通过 KeyFrameStore 单例传递关键帧状态。
 *
 * 布局：
 *   [附件 chip 列表] [关键帧缓存指示器]
 *   [+] [ 输入框 ] [发送/停止]
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInputBar(
  isLoading: Boolean,
  onSendText: (String) -> Unit,
  onSendImages: (List<Uri>, String) -> Unit,
  onSendVideo: (Uri, String) -> Unit,
  onAbort: () -> Unit,
  onComposeVideo: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var inputText by remember { mutableStateOf("") }
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current
  val scope = rememberCoroutineScope()
  val context = LocalContext.current

  // 附件状态（图形工坊产出）
  val pendingImages = remember { mutableStateListOf<Uri>() }
  var pendingHasUserElement by remember { mutableStateOf(false) }
  var pendingHasOriginalElement by remember { mutableStateOf(false) }
  var pendingKeyFrameCount by remember { mutableStateOf(0) }
  var pendingVideo by remember { mutableStateOf<Uri?>(null) }

  // KeyFrameStore 状态（帧/合成工坊用）
  val cachedKeyFrames by KeyFrameStore.keyFrames.collectAsState()
  val sourceLabel by KeyFrameStore.sourceLabel.collectAsState()

  // 超限提示
  var errorMessage by remember { mutableStateOf<String?>(null) }

  // 浮层
  var showSheet by remember { mutableStateOf(false) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  // 对话框
  var showImageDialog by remember { mutableStateOf(false) }
  var showFrameDialog by remember { mutableStateOf(false) }

  /** 等待浮层关闭后启动下一步 */
  fun launchAfterSheetClose(block: () -> Unit) {
    showSheet = false
    keyboardController?.hide()
    scope.launch {
      sheetState.hide()
      delay(100)
      block()
    }
  }

  /** 发送：根据附件类型分发 */
  fun send() {
    val text = inputText.trim()
    val hasImages = pendingImages.isNotEmpty()
    val hasVideo = pendingVideo != null

    if (text.isEmpty() && !hasImages && !hasVideo) return

    when {
      hasImages -> {
        val parts = mutableListOf<String>()
        if (pendingHasUserElement) parts.add("[用户元素]")
        if (pendingHasOriginalElement) parts.add("[原图元素]")
        if (pendingKeyFrameCount > 0) parts.add("[关键帧×$pendingKeyFrameCount]")
        val prefix = if (parts.isNotEmpty()) parts.joinToString(" ") + " " else ""
        onSendImages(pendingImages.toList(), prefix + text)
      }
      hasVideo -> onSendVideo(pendingVideo!!, text)
      else -> onSendText(text)
    }

    inputText = ""
    pendingImages.clear()
    pendingHasUserElement = false
    pendingHasOriginalElement = false
    pendingKeyFrameCount = 0
    pendingVideo = null
    focusManager.clearFocus()
    keyboardController?.hide()
  }

  // ── 错误提示 ──
  errorMessage?.let { msg ->
    AlertDialog(
      onDismissRequest = { errorMessage = null },
      title = { Text("提示") },
      text = { Text(msg) },
      confirmButton = {
        TextButton(onClick = { errorMessage = null }) { Text("知道了") }
      },
    )
  }

  // ── 加号浮层：图形 / 帧 / 合成 ──
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
        // 图形：元素替换对话框
        SheetOption(
          icon = Icons.Default.Image,
          label = "图形",
          description = "元素到元素替换，产出图片与关键帧",
          onClick = { launchAfterSheetClose { showImageDialog = true } },
        )
        // 帧：关键帧改造工坊
        SheetOption(
          icon = Icons.Default.AutoAwesome,
          label = "帧",
          description = "生成或改造关键帧（当前缓存 ${cachedKeyFrames.size} 帧）",
          onClick = { launchAfterSheetClose { showFrameDialog = true } },
        )
        // 合成：关键帧 → MP4
        SheetOption(
          icon = Icons.Default.Movie,
          label = "合成",
          description = if (cachedKeyFrames.isEmpty()) "缓存无关键帧，请先在「帧」中准备"
          else "将 ${cachedKeyFrames.size} 帧合成为 MP4 视频",
          enabled = cachedKeyFrames.isNotEmpty(),
          onClick = {
            launchAfterSheetClose {
              if (cachedKeyFrames.isEmpty()) {
                errorMessage = "缓存中没有关键帧，请先在「图形」或「帧」中准备关键帧"
              } else {
                onComposeVideo()
              }
            }
          },
        )
      }
    }
  }

  // ── 图形对话框 ──
  if (showImageDialog) {
    ImageCompositeDialog(
      onDismiss = { showImageDialog = false },
      onSubmit = { userElement, originalElement, keyFrames ->
        pendingImages.clear()
        userElement?.let { pendingImages.add(it) }
        originalElement?.let { pendingImages.add(it) }
        pendingImages.addAll(keyFrames)
        pendingHasUserElement = userElement != null
        pendingHasOriginalElement = originalElement != null
        pendingKeyFrameCount = keyFrames.size
        pendingVideo = null

        // 若有关键帧，同步到 KeyFrameStore 供「帧」「合成」使用
        if (keyFrames.isNotEmpty()) {
          KeyFrameStore.setKeyFrames(keyFrames, "图形工坊")
        }

        showImageDialog = false
      },
      onError = { msg -> errorMessage = msg },
    )
  }

  // ── 帧对话框 ──
  if (showFrameDialog) {
    FrameWorkshopDialog(
      onDismiss = { showFrameDialog = false },
      onError = { msg -> errorMessage = msg },
    )
  }

  // ── 主输入栏 ──
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp, vertical = 6.dp),
  ) {
    // 关键帧缓存指示器
    if (cachedKeyFrames.isNotEmpty()) {
      KeyFrameCacheIndicator(
        count = cachedKeyFrames.size,
        source = sourceLabel,
        frames = cachedKeyFrames,
        onClear = { KeyFrameStore.clear() },
      )
      Spacer(modifier = Modifier.height(6.dp))
    }

    // 附件 chip 列表
    if (pendingImages.isNotEmpty() || pendingVideo != null) {
      AttachmentChipRow(
        images = pendingImages,
        video = pendingVideo,
        onRemoveImage = { idx -> pendingImages.removeAt(idx) },
        onClearVideo = { pendingVideo = null },
      )
      Spacer(modifier = Modifier.height(6.dp))
    }

    // 输入栏
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth(),
    ) {
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

      OutlinedTextField(
        value = inputText,
        onValueChange = { inputText = it },
        placeholder = {
          val hint = when {
            pendingImages.isNotEmpty() -> {
              val parts = mutableListOf<String>()
              if (pendingHasUserElement) parts.add("用户元素")
              if (pendingHasOriginalElement) parts.add("原图元素")
              if (pendingKeyFrameCount > 0) parts.add("关键帧×$pendingKeyFrameCount")
              "为${parts.joinToString("+")}添加说明（可选）..."
            }
            pendingVideo != null -> "为视频添加说明（可选）..."
            cachedKeyFrames.isNotEmpty() -> "输入帧改造指令，或选「合成」生成视频..."
            else -> "和 Memento 说点什么..."
          }
          Text(hint)
        },
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
        IconButton(onClick = { send() }) {
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

// ════════════════════════════════════════════════════════
// 图形工坊：元素到元素替换和关键帧提交
// ════════════════════════════════════════════════════════

/**
 * 元素到元素替换和关键帧提交对话框。
 *
 * 布局：（+ + - +）
 * - 左侧两个入口：用户元素（1张）+ 原图元素（1张）
 * - 右侧入口：关键帧（4-12张）
 */
@Composable
private fun ImageCompositeDialog(
  onDismiss: () -> Unit,
  onSubmit: (userElement: Uri?, originalElement: Uri?, keyFrames: List<Uri>) -> Unit,
  onError: (String) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var userElement by remember { mutableStateOf<Uri?>(null) }
  var originalElement by remember { mutableStateOf<Uri?>(null) }
  val keyFrames = remember { mutableStateListOf<Uri>() }

  val userElementPicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent(),
  ) { uri: Uri? -> if (uri != null) userElement = uri }

  val originalElementPicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent(),
  ) { uri: Uri? -> if (uri != null) originalElement = uri }

  val keyFramePicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetMultipleContents(),
  ) { uris: List<Uri> ->
    if (uris.isNotEmpty()) {
      scope.launch {
        val valid = validateKeyFrames(context, uris, keyFrames.size)
        if (valid.error != null) onError(valid.error)
        keyFrames.clear()
        keyFrames.addAll(valid.accepted)
      }
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("图形工坊：元素替换与关键帧") },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          ElementSlot(
            label = "用户元素",
            subLabel = "1 张",
            uri = userElement,
            onClick = { userElementPicker.launch("image/*") },
            onRemove = { userElement = null },
            modifier = Modifier.weight(1f),
          )
          Spacer(modifier = Modifier.width(4.dp))
          ElementSlot(
            label = "原图元素",
            subLabel = "1 张",
            uri = originalElement,
            onClick = { originalElementPicker.launch("image/*") },
            onRemove = { originalElement = null },
            modifier = Modifier.weight(1f),
          )
          Icon(
            imageVector = Icons.Default.SwapHoriz,
            contentDescription = "替换",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
          )
          ElementSlot(
            label = "关键帧",
            subLabel = "${keyFrames.size}/12",
            uri = keyFrames.firstOrNull(),
            count = keyFrames.size,
            onClick = { keyFramePicker.launch("image/*") },
            onRemove = { keyFrames.clear() },
            modifier = Modifier.weight(1f),
          )
        }

        if (keyFrames.isNotEmpty()) {
          Text(
            text = "已选关键帧 ${keyFrames.size} 张",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          LazyRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            items(keyFrames.size) { idx ->
              Box(
                modifier = Modifier
                  .size(48.dp)
                  .clip(RoundedCornerShape(4.dp))
                  .background(MaterialTheme.colorScheme.surfaceVariant),
              ) {
                AsyncImage(
                  model = keyFrames[idx],
                  contentDescription = "关键帧 ${idx + 1}",
                  modifier = Modifier.fillMaxWidth(),
                )
                IconButton(
                  onClick = { keyFrames.removeAt(idx) },
                  modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
                ) {
                  Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "移除",
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(10.dp),
                  )
                }
              }
            }
          }
        }

        Text(
          text = "选择你的元素和原图中要替换的元素，以及 4-12 张关键帧。" +
            "提交后关键帧会进入缓存，可在「帧」中改造，「合成」为 MP4。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          fontSize = 12.sp,
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          if (keyFrames.isNotEmpty() && keyFrames.size < 4) {
            onError("关键帧至少需要 4 张（当前 ${keyFrames.size} 张）")
            return@TextButton
          }
          onSubmit(userElement, originalElement, keyFrames.toList())
        },
        enabled = userElement != null || originalElement != null || keyFrames.isNotEmpty(),
      ) { Text("提交") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
  )
}

// ════════════════════════════════════════════════════════
// 帧工坊：关键帧改造
// ════════════════════════════════════════════════════════

/**
 * 帧工坊对话框 — 查看/改造缓存中的关键帧。
 *
 * 用户可：
 * - 查看当前缓存的所有关键帧
 * - 删除单帧
 * - 清空全部
 * - 通过输入框发送改造指令（如「把第3帧的背景换成夜晚」）
 *
 * 关键帧来源：
 * - 图形工坊提交的
 * - 帧工坊中新生成的（通过指令让模型生成）
 * - 视频提取的（自动进入缓存）
 */
@Composable
private fun FrameWorkshopDialog(
  onDismiss: () -> Unit,
  onError: (String) -> Unit,
) {
  val cachedFrames by KeyFrameStore.keyFrames.collectAsState()
  val sourceLabel by KeyFrameStore.sourceLabel.collectAsState()

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("帧工坊：关键帧改造") },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (cachedFrames.isEmpty()) {
          // 空状态
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Icon(
                imageVector = Icons.Default.BrokenImage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
              )
              Spacer(modifier = Modifier.height(8.dp))
              Text(
                text = "缓存中没有关键帧",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                text = "请先在「图形」中提交关键帧，\n或在下方输入指令生成关键帧",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
              )
            }
          }
        } else {
          Text(
            text = "来源：$sourceLabel · ${cachedFrames.size} 帧",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          // 关键帧网格预览
          LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            items(cachedFrames.size) { idx ->
              Box(
                modifier = Modifier
                  .size(64.dp)
                  .clip(RoundedCornerShape(6.dp))
                  .background(MaterialTheme.colorScheme.surfaceVariant),
              ) {
                AsyncImage(
                  model = cachedFrames[idx],
                  contentDescription = "帧 ${idx + 1}",
                  modifier = Modifier.fillMaxWidth(),
                )
                // 帧号
                Text(
                  text = "${idx + 1}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurface,
                  modifier = Modifier
                    .align(Alignment.BottomStart)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                )
                // 删除按钮
                IconButton(
                  onClick = { KeyFrameStore.removeFrame(idx) },
                  modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
                ) {
                  Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "删除帧",
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(10.dp),
                  )
                }
              }
            }
          }

          Text(
            text = "提示：关闭对话框后，在输入框输入改造指令并发送，" +
              "如「把第 3 帧背景换成夜晚」「角色位置左移」",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
          )
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    dismissButton = {
      TextButton(
        onClick = {
          KeyFrameStore.clear()
          onDismiss()
        },
        enabled = cachedFrames.isNotEmpty(),
      ) { Text("清空缓存") }
    },
  )
}

// ════════════════════════════════════════════════════════
// 关键帧缓存指示器
// ════════════════════════════════════════════════════════

@Composable
private fun KeyFrameCacheIndicator(
  count: Int,
  source: String,
  frames: List<Uri>,
  onClear: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
      .padding(horizontal = 8.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = Icons.Default.Movie,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(16.dp),
    )
    Spacer(modifier = Modifier.width(6.dp))
    Text(
      text = "关键帧缓存：$count 帧（$source）",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onPrimaryContainer,
      modifier = Modifier.weight(1f),
    )
    IconButton(
      onClick = onClear,
      modifier = Modifier.size(20.dp),
    ) {
      Icon(
        imageVector = Icons.Default.Close,
        contentDescription = "清空关键帧缓存",
        tint = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(14.dp),
      )
    }
  }
}

// ════════════════════════════════════════════════════════
// 通用组件
// ════════════════════════════════════════════════════════

@Composable
private fun ElementSlot(
  label: String,
  subLabel: String,
  uri: Uri?,
  count: Int = 0,
  onClick: () -> Unit,
  onRemove: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      modifier = Modifier
        .aspectRatio(1f)
        .fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .clickable(
          indication = null,
          interactionSource = remember { MutableInteractionSource() },
        ) { onClick() },
    ) {
      if (uri != null) {
        AsyncImage(
          model = uri,
          contentDescription = label,
          modifier = Modifier.fillMaxWidth(),
        )
        IconButton(
          onClick = onRemove,
          modifier = Modifier
            .align(Alignment.TopEnd)
            .size(20.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error),
        ) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "移除",
            tint = MaterialTheme.colorScheme.onError,
            modifier = Modifier.size(12.dp),
          )
        }
        if (count > 1) {
          Text(
            text = "×$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
              .align(Alignment.BottomEnd)
              .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
              .padding(horizontal = 4.dp, vertical = 1.dp),
          )
        }
      } else {
        Column(
          modifier = Modifier.fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          Icon(
            imageVector = Icons.Default.Add,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
          )
        }
      }
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      text = subLabel,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      fontSize = 10.sp,
    )
  }
}

@Composable
private fun AttachmentChipRow(
  images: List<Uri>,
  video: Uri?,
  onRemoveImage: (Int) -> Unit,
  onClearVideo: () -> Unit,
) {
  LazyRow(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 4.dp),
  ) {
    items(images.size) { idx ->
      AttachmentChip(
        uri = images[idx],
        label = "图 ${idx + 1}",
        onRemove = { onRemoveImage(idx) },
      )
    }
    video?.let {
      item {
        AttachmentChip(
          uri = it,
          label = "视频",
          onRemove = onClearVideo,
        )
      }
    }
  }
}

@Composable
private fun AttachmentChip(
  uri: Uri,
  label: String,
  onRemove: () -> Unit,
) {
  Box(
    modifier = Modifier
      .size(72.dp)
      .clip(RoundedCornerShape(8.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant),
  ) {
    AsyncImage(
      model = uri,
      contentDescription = label,
      modifier = Modifier.fillMaxWidth(),
    )
    IconButton(
      onClick = onRemove,
      modifier = Modifier
        .align(Alignment.TopEnd)
        .size(20.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.error),
    ) {
      Icon(
        imageVector = Icons.Default.Close,
        contentDescription = "移除",
        tint = MaterialTheme.colorScheme.onError,
        modifier = Modifier.size(14.dp),
      )
    }
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier
        .align(Alignment.BottomStart)
        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
        .padding(horizontal = 4.dp, vertical = 1.dp),
    )
  }
}

@Composable
private fun SheetOption(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  description: String,
  onClick: () -> Unit,
  enabled: Boolean = true,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 24.dp, vertical = 14.dp)
      .clickable(
        indication = null,
        interactionSource = remember { MutableInteractionSource() },
      ) { if (enabled) onClick() },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(48.dp)
        .clip(CircleShape)
        .background(
          if (enabled) MaterialTheme.colorScheme.surfaceVariant
          else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = label,
        tint = if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.size(24.dp),
      )
    }
    Spacer(modifier = Modifier.width(16.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
      )
      Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    IconButton(onClick = onClick, enabled = enabled) {
      Icon(
        imageVector = Icons.Default.Add,
        contentDescription = null,
        tint = if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

// ════════════════════════════════════════════════════════
// 校验
// ════════════════════════════════════════════════════════

private const val MAX_KEYFRAME_COUNT = 12
private const val MAX_TOTAL_SIZE_BYTES = 50L * 1024 * 1024
private const val MAX_VIDEO_SIZE_BYTES = 50L * 1024 * 1024

private data class ValidationResult(
  val accepted: List<Uri> = emptyList(),
  val error: String? = null,
)

private suspend fun validateKeyFrames(
  context: Context,
  uris: List<Uri>,
  currentCount: Int,
): ValidationResult = withContext(Dispatchers.IO) {
  val remain = (MAX_KEYFRAME_COUNT - currentCount).coerceAtLeast(0)
  if (remain == 0) {
    return@withContext ValidationResult(
      error = "关键帧最多 $MAX_KEYFRAME_COUNT 张，已选 $currentCount 张，无法继续添加",
    )
  }
  val toAccept = if (uris.size > remain) uris.take(remain) else uris
  val truncated = uris.size > remain

  val accepted = mutableListOf<Uri>()
  var totalSize = 0L
  for (uri in toAccept) {
    val size = getFileSize(context, uri)
    if (size <= 0) {
      Log.w("ChatInputBar", "Cannot get size for $uri, skipping")
      continue
    }
    if (totalSize + size > MAX_TOTAL_SIZE_BYTES) {
      val msg = if (accepted.isEmpty()) "关键帧总大小超过 50MB 限制"
      else "关键帧总大小超过 50MB 限制，仅添加前 ${accepted.size} 张"
      return@withContext ValidationResult(accepted = accepted, error = msg)
    }
    totalSize += size
    accepted.add(uri)
  }
  val error = if (truncated) "关键帧最多 $MAX_KEYFRAME_COUNT 张，仅添加前 ${accepted.size} 张" else null
  ValidationResult(accepted = accepted, error = error)
}

private fun getFileSize(context: Context, uri: Uri): Long {
  return try {
    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
  } catch (e: Exception) {
    Log.w("ChatInputBar", "getFileSize failed: ${e.message}")
    -1
  }
}
