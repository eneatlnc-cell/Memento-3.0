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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Videocam
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 多模态输入栏 — 加号折叠菜单 + 元素替换对话框 + 视频选择 + 文本 caption。
 *
 * v3.2 交互流程：
 * - 图片：点击「+」→ 图片 → 弹出「元素到元素替换和关键帧提交」对话框
 *   - 左侧两个入口：用户元素（1张）+ 原图元素（1张）
 *   - 右侧入口：关键帧（4-12张，自动缩略预览）
 *   - 提交后合并为图片列表，caption 标记各类素材
 * - 视频：点击「+」→ 视频 → 直接选择器 → 50MB 校验 → chip 显示
 *
 * 布局：
 *   [附件 chip 列表]
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
  modifier: Modifier = Modifier,
) {
  var inputText by remember { mutableStateOf("") }
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current
  val scope = rememberCoroutineScope()
  val context = LocalContext.current

  // 附件状态
  // 图片：从对话框提交后合并到 pendingImages
  val pendingImages = remember { mutableStateListOf<Uri>() }
  // 用于构造 caption 的元数据（标记哪些是元素、哪些是关键帧）
  var pendingHasUserElement by remember { mutableStateOf(false) }
  var pendingHasOriginalElement by remember { mutableStateOf(false) }
  var pendingKeyFrameCount by remember { mutableStateOf(0) }
  var pendingVideo by remember { mutableStateOf<Uri?>(null) }

  // 超限提示
  var errorMessage by remember { mutableStateOf<String?>(null) }

  // 加号浮层
  var showSheet by remember { mutableStateOf(false) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  // 图片复合对话框
  var showImageDialog by remember { mutableStateOf(false) }

  // 视频选择器
  val videoPicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent(),
  ) { uri: Uri? ->
    if (uri != null) {
      scope.launch {
        val valid = validateVideo(context, uri)
        if (valid.error != null) {
          errorMessage = valid.error
        } else {
          pendingVideo = uri
          // 选视频后清空图片（互斥）
          pendingImages.clear()
          pendingHasUserElement = false
          pendingHasOriginalElement = false
          pendingKeyFrameCount = 0
        }
      }
    }
  }

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
        // 构造 caption：标记元素/关键帧结构，帮助模型理解
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

    // 清空
    inputText = ""
    pendingImages.clear()
    pendingHasUserElement = false
    pendingHasOriginalElement = false
    pendingKeyFrameCount = 0
    pendingVideo = null
    focusManager.clearFocus()
    keyboardController?.hide()
  }

  // ── 错误提示弹窗 ──
  errorMessage?.let { msg ->
    AlertDialog(
      onDismissRequest = { errorMessage = null },
      title = { Text("无法添加附件") },
      text = { Text(msg) },
      confirmButton = {
        TextButton(onClick = { errorMessage = null }) { Text("知道了") }
      },
    )
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
        // 图片 → 打开元素替换对话框
        SheetOption(
          icon = Icons.Default.Image,
          label = "图片",
          description = "元素到元素替换和关键帧提交",
          onClick = {
            launchAfterSheetClose { showImageDialog = true }
          },
        )
        // 视频 → 直接打开选择器
        SheetOption(
          icon = Icons.Default.Videocam,
          label = "视频",
          description = "1s 视频 ≤ 50MB，自动采样 24 帧关键帧",
          onClick = {
            launchAfterSheetClose { videoPicker.launch("video/*") }
          },
        )
      }
    }
  }

  // ── 图片复合对话框 ──
  if (showImageDialog) {
    ImageCompositeDialog(
      onDismiss = { showImageDialog = false },
      onSubmit = { userElement, originalElement, keyFrames ->
        // 合并到 pendingImages
        pendingImages.clear()
        userElement?.let { pendingImages.add(it) }
        originalElement?.let { pendingImages.add(it) }
        pendingImages.addAll(keyFrames)
        // 记录元数据
        pendingHasUserElement = userElement != null
        pendingHasOriginalElement = originalElement != null
        pendingKeyFrameCount = keyFrames.size
        // 清空视频（互斥）
        pendingVideo = null
        showImageDialog = false
      },
      onError = { msg -> errorMessage = msg },
    )
  }

  // ── 主输入栏 ──
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp, vertical = 6.dp),
  ) {
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

      // 文本输入
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

// ── 图片复合对话框：元素到元素替换和关键帧提交 ──

/**
 * 元素到元素替换和关键帧提交对话框。
 *
 * 布局：（+ + - +）
 * - 左侧两个入口：用户元素（1张）+ 原图元素（1张）
 * - 右侧入口：关键帧（4-12张）
 *
 * 用户可选择：
 * 1. 自己的元素（要替换进去的素材）
 * 2. 原图中的元素（要被替换的部分）
 * 3. 关键帧（4-12张，作为动画基础）
 *
 * 提交后合并为图片列表，由上层构造 caption 标记各类素材。
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

  // 用户元素选择器（单选）
  val userElementPicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent(),
  ) { uri: Uri? ->
    if (uri != null) userElement = uri
  }

  // 原图元素选择器（单选）
  val originalElementPicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent(),
  ) { uri: Uri? ->
    if (uri != null) originalElement = uri
  }

  // 关键帧选择器（多选，4-12张）
  val keyFramePicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetMultipleContents(),
  ) { uris: List<Uri> ->
    if (uris.isNotEmpty()) {
      scope.launch {
        val valid = validateKeyFrames(context, uris, keyFrames.size)
        if (valid.error != null) {
          onError(valid.error)
        }
        keyFrames.clear()
        keyFrames.addAll(valid.accepted)
      }
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("元素到元素替换和关键帧提交") },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        // 三个入口横向排列：[+ 用户元素] [+ 原图元素] → [+ 关键帧]
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // 用户元素
          ElementSlot(
            label = "用户元素",
            subLabel = "1 张",
            uri = userElement,
            onClick = { userElementPicker.launch("image/*") },
            onRemove = { userElement = null },
            modifier = Modifier.weight(1f),
          )

          Spacer(modifier = Modifier.width(4.dp))

          // 原图元素
          ElementSlot(
            label = "原图元素",
            subLabel = "1 张",
            uri = originalElement,
            onClick = { originalElementPicker.launch("image/*") },
            onRemove = { originalElement = null },
            modifier = Modifier.weight(1f),
          )

          // 替换箭头
          Icon(
            imageVector = Icons.Default.SwapHoriz,
            contentDescription = "替换",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
          )

          // 关键帧
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

        // 关键帧缩略图列表
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

        // 提示
        Text(
          text = "选择你的元素和原图中要替换的元素，以及 4-12 张关键帧。" +
            "系统将执行元素替换并基于关键帧生成动画。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          fontSize = 12.sp,
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          // 校验：关键帧不为空时至少 4 张
          if (keyFrames.isNotEmpty() && keyFrames.size < 4) {
            onError("关键帧至少需要 4 张（当前 ${keyFrames.size} 张）")
            return@TextButton
          }
          onSubmit(userElement, originalElement, keyFrames.toList())
        },
        enabled = userElement != null || originalElement != null || keyFrames.isNotEmpty(),
      ) { Text("提交") }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("取消") }
    },
  )
}

/**
 * 元素选择槽位 — 可点击的方块，显示已选图片缩略图或加号占位符。
 */
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
        // 删除按钮
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
        // 数量标记（关键帧多选时）
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
        // 占位符
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

// ── 附件 chip 列表 ──

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
    // 图片 chips
    items(images.size) { idx ->
      AttachmentChip(
        uri = images[idx],
        label = "图 ${idx + 1}",
        onRemove = { onRemoveImage(idx) },
      )
    }
    // 视频 chip
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
    // 删除按钮
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
    // 标签
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

// ── 附件大小校验 ──

private const val MAX_KEYFRAME_COUNT = 12
private const val MIN_KEYFRAME_COUNT = 4
private const val MAX_TOTAL_SIZE_BYTES = 50L * 1024 * 1024  // 50MB
private const val MAX_VIDEO_SIZE_BYTES = 50L * 1024 * 1024  // 50MB

private data class ValidationResult(
  val accepted: List<Uri> = emptyList(),
  val error: String? = null,
)

/**
 * 校验关键帧：数量 4-12，总大小 ≤50MB。
 * 超过 12 张时只接受前 N 张（N = 12 - 已选数）。
 */
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
      val msg = if (accepted.isEmpty()) {
        "关键帧总大小超过 50MB 限制"
      } else {
        "关键帧总大小超过 50MB 限制，仅添加前 ${accepted.size} 张"
      }
      return@withContext ValidationResult(
        accepted = accepted,
        error = msg,
      )
    }
    totalSize += size
    accepted.add(uri)
  }

  val error = when {
    truncated -> "关键帧最多 $MAX_KEYFRAME_COUNT 张，仅添加前 ${accepted.size} 张"
    else -> null
  }
  ValidationResult(accepted = accepted, error = error)
}

/**
 * 校验视频：单个 ≤50MB。
 */
private suspend fun validateVideo(
  context: Context,
  uri: Uri,
): ValidationResult = withContext(Dispatchers.IO) {
  val size = getFileSize(context, uri)
  if (size <= 0) {
    return@withContext ValidationResult(error = "无法获取视频大小，可能已损坏")
  }
  if (size > MAX_VIDEO_SIZE_BYTES) {
    val sizeMB = size / (1024 * 1024)
    return@withContext ValidationResult(
      error = "视频大小 ${sizeMB}MB 超过 50MB 限制，请选择更小的视频",
    )
  }
  ValidationResult(accepted = listOf(uri))
}

/** 获取文件大小（字节），-1 表示获取失败 */
private fun getFileSize(context: Context, uri: Uri): Long {
  return try {
    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
  } catch (e: Exception) {
    Log.w("ChatInputBar", "getFileSize failed: ${e.message}")
    -1
  }
}
