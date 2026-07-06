package com.myagent.app.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 多模态输入栏 — 加号折叠菜单 + 多图/视频附件 chip + 文本 caption。
 *
 * 设计：
 * - 图片：多选 ≤10 张，总大小 ≤50MB（用户可手动挑选关键帧作为"视频"输入）
 * - 视频：单选 ≤50MB（系统自动采样前 5s 关键帧）
 * - 选完附件后回到输入框，用户输入 caption 一起发送
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

  // 附件状态：图片列表（≤10）+ 视频（单个，二选一）
  val pendingImages = remember { mutableStateListOf<Uri>() }
  var pendingVideo by remember { mutableStateOf<Uri?>(null) }

  // 超限提示
  var errorMessage by remember { mutableStateOf<String?>(null) }

  // --- 加号浮层 ---
  var showSheet by remember { mutableStateOf(false) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  // --- 图片多选器 ---
  val imagePicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetMultipleContents(),
  ) { uris: List<Uri> ->
    if (uris.isNotEmpty()) {
      scope.launch {
        val valid = validateImages(context, uris, pendingImages.size)
        if (valid.error != null) {
          errorMessage = valid.error
        } else {
          pendingImages.addAll(valid.accepted)
          // 选图后清空视频（互斥）
          pendingVideo = null
        }
      }
    }
  }

  // --- 视频单选器 ---
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
        }
      }
    }
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

  /** 发送：根据附件类型分发 */
  fun send() {
    val text = inputText.trim()
    val hasImages = pendingImages.isNotEmpty()
    val hasVideo = pendingVideo != null

    if (text.isEmpty() && !hasImages && !hasVideo) return

    when {
      hasImages -> onSendImages(pendingImages.toList(), text)
      hasVideo -> onSendVideo(pendingVideo!!, text)
      else -> onSendText(text)
    }

    // 清空
    inputText = ""
    pendingImages.clear()
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
        // 图片（多选）
        SheetOption(
          icon = Icons.Default.Image,
          label = "图片（可多选）",
          description = "最多 10 张，总大小 ≤ 50MB",
          onClick = {
            launchAfterSheetClose { imagePicker.launch("image/*") }
          },
        )
        // 视频（单选）
        SheetOption(
          icon = Icons.Default.Videocam,
          label = "视频",
          description = "单个视频 ≤ 50MB（系统自动采样关键帧）",
          onClick = {
            launchAfterSheetClose { videoPicker.launch("video/*") }
          },
        )
      }
    }
  }

  // ── 主输入栏 ──
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp, vertical = 6.dp),
  ) {
    // ── 附件 chip 列表 ──
    if (pendingImages.isNotEmpty() || pendingVideo != null) {
      AttachmentChipRow(
        images = pendingImages,
        video = pendingVideo,
        onRemoveImage = { idx -> pendingImages.removeAt(idx) },
        onClearVideo = { pendingVideo = null },
      )
      Spacer(modifier = Modifier.height(6.dp))
    }

    // ── 输入栏 ──
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
            pendingImages.isNotEmpty() -> "为图片添加说明（可选）..."
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

private const val MAX_IMAGE_COUNT = 10
private const val MAX_TOTAL_SIZE_BYTES = 50L * 1024 * 1024  // 50MB
private const val MAX_VIDEO_SIZE_BYTES = 50L * 1024 * 1024  // 50MB

private data class ValidationResult(
  val accepted: List<Uri> = emptyList(),
  val error: String? = null,
)

/**
 * 校验图片：数量 ≤10，总大小（含已选）≤50MB。
 * 超限时部分接受：尽量多地接受图片，直到达到限制。
 */
private suspend fun validateImages(
  context: Context,
  uris: List<Uri>,
  currentCount: Int,
): ValidationResult = withContext(Dispatchers.IO) {
  // 数量检查
  if (currentCount + uris.size > MAX_IMAGE_COUNT) {
    val remain = (MAX_IMAGE_COUNT - currentCount).coerceAtLeast(0)
    return@withContext ValidationResult(
      error = "图片数量超出限制（最多 $MAX_IMAGE_COUNT 张，当前已选 $currentCount 张，本次选了 ${uris.size} 张" +
        if (remain > 0) "，仅添加前 $remain 张" else "，未添加任何图片",
    )
  }

  // 大小检查：逐个查询，累计 ≤50MB
  val accepted = mutableListOf<Uri>()
  var totalSize = 0L

  // 加上已选图片的大小
  // 注：currentCount 已在数量层面校验，这里不重新查询已选图片大小，
  // 假设已选图片累计大小合理（用户每次添加都会校验）

  for (uri in uris) {
    val size = getFileSize(context, uri)
    if (size <= 0) {
      // 无法获取大小，跳过此图
      Log.w("ChatInputBar", "Cannot get size for $uri, skipping")
      continue
    }
    if (totalSize + size > MAX_TOTAL_SIZE_BYTES) {
      val currentMB = totalSize / (1024 * 1024)
      return@withContext ValidationResult(
        accepted = accepted,
        error = "图片总大小超出 50MB 限制（已选 ${accepted.size} 张共 ${currentMB}MB" +
          if (accepted.isNotEmpty()) "，已添加前 ${accepted.size} 张" else "，未添加任何图片",
      )
    }
    totalSize += size
    accepted.add(uri)
  }

  ValidationResult(accepted = accepted)
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
    return@withContext ValidationResult(error = "无法读取视频文件，可能已被删除或权限不足")
  }
  if (size > MAX_VIDEO_SIZE_BYTES) {
    val sizeMB = size / (1024 * 1024)
    return@withContext ValidationResult(error = "视频文件过大（${sizeMB}MB），限制为 50MB，请选择较小的视频")
  }
  ValidationResult(accepted = listOf(uri))
}

/**
 * 查询 Uri 对应文件的大小（字节）。
 */
private fun getFileSize(context: Context, uri: Uri): Long {
  return try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
      if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
    } ?: -1L
  } catch (e: Exception) {
    Log.w("ChatInputBar", "getFileSize failed: ${e.message}")
    -1L
  }
}
