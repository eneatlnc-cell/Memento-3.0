package com.myagent.app.ui.chat

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
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
import com.myagent.app.multimodal.VideoFrameExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 多模态输入栏 — 三段式工作流入口。
 *
 * v4.0 云端工作流：帧 / 图形 / 合成
 *
 *   帧   ─→ 1s 视频 → 24 帧关键帧 → 保存相册 + 进缓存
 *   图形 ─→ 元素替换对话框（用户元素+原图元素+关键帧）→ 改造后图片进缓存
 *   合成 ─→ 取缓存关键帧 → 可灵 Kling 图生视频 → MP4
 *
 * 三段式通过 KeyFrameStore 单例传递关键帧状态。
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

  // KeyFrameStore 状态
  val cachedKeyFrames by KeyFrameStore.keyFrames.collectAsState()
  val sourceLabel by KeyFrameStore.sourceLabel.collectAsState()

  var errorMessage by remember { mutableStateOf<String?>(null) }

  var showSheet by remember { mutableStateOf(false) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  var showFrameDialog by remember { mutableStateOf(false) }
  var showImageDialog by remember { mutableStateOf(false) }

  fun launchAfterSheetClose(block: () -> Unit) {
    showSheet = false
    keyboardController?.hide()
    scope.launch {
      sheetState.hide()
      delay(100)
      block()
    }
  }

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

  // 错误提示
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

  // ── 加号浮层：帧 / 图形 / 合成 ──
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
        // 帧：1s 视频 → 24 帧关键帧
        SheetOption(
          icon = Icons.Default.AutoAwesome,
          label = "帧",
          description = "选择 1s 视频 → 提取 24 帧关键帧 → 保存相册 + 进缓存",
          onClick = { launchAfterSheetClose { showFrameDialog = true } },
        )
        // 图形：元素替换改造关键帧
        SheetOption(
          icon = Icons.Default.Image,
          label = "图形",
          description = "元素替换对话框，改造关键帧输出图片（当前缓存 ${cachedKeyFrames.size} 帧）",
          onClick = { launchAfterSheetClose { showImageDialog = true } },
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
                errorMessage = "缓存中没有关键帧，请先在「帧」中提取关键帧"
              } else {
                onComposeVideo()
              }
            }
          },
        )
      }
    }
  }

  // ── 帧工坊对话框 ──
  if (showFrameDialog) {
    FrameWorkshopDialog(
      onDismiss = { showFrameDialog = false },
      onError = { msg -> errorMessage = msg },
    )
  }

  // ── 图形工坊对话框 ──
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
        if (keyFrames.isNotEmpty()) {
          KeyFrameStore.setKeyFrames(keyFrames, "图形工坊")
        }
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
    if (cachedKeyFrames.isNotEmpty()) {
      KeyFrameCacheIndicator(
        count = cachedKeyFrames.size,
        source = sourceLabel,
        frames = cachedKeyFrames,
        onClear = { KeyFrameStore.clear() },
      )
      Spacer(modifier = Modifier.height(6.dp))
    }

    if (pendingImages.isNotEmpty() || pendingVideo != null) {
      AttachmentChipRow(
        images = pendingImages,
        video = pendingVideo,
        onRemoveImage = { idx -> pendingImages.removeAt(idx) },
        onClearVideo = { pendingVideo = null },
      )
      Spacer(modifier = Modifier.height(6.dp))
    }

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
            cachedKeyFrames.isNotEmpty() -> "输入改造指令，或选「图形」/「合成」继续..."
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
// 帧工坊：1s 视频 → 24 帧关键帧 → 保存相册 + 进缓存
// ════════════════════════════════════════════════════════

/**
 * 帧工坊 — 三段式工作流的起点。
 *
 * 用户选择 1s 视频 → 系统提取 24 帧关键帧 → 保存到相册 + 进入 KeyFrameStore 缓存。
 * 提取完成后，关键帧可在「图形」中改造，「合成」为 MP4。
 */
@Composable
private fun FrameWorkshopDialog(
  onDismiss: () -> Unit,
  onError: (String) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val cachedFrames by KeyFrameStore.keyFrames.collectAsState()
  val sourceLabel by KeyFrameStore.sourceLabel.collectAsState()

  var isExtracting by remember { mutableStateOf(false) }
  var extractProgress by remember { mutableStateOf(0) }

  // 视频选择器
  val videoPicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent(),
  ) { uri: Uri? ->
    if (uri != null) {
      scope.launch {
        isExtracting = true
        extractProgress = 0
        try {
          // 校验大小
          val size = withContext(Dispatchers.IO) { getFileSize(context, uri) }
          if (size <= 0) {
            onError("无法获取视频大小，可能已损坏")
            return@launch
          }
          if (size > MAX_VIDEO_SIZE_BYTES) {
            val sizeMB = size / (1024 * 1024)
            onError("视频大小 ${sizeMB}MB 超过 50MB 限制，请选择更小的视频")
            return@launch
          }

          // 提取 24 帧
          val frameUris = withContext(Dispatchers.IO) {
            extractFramesToGallery(context, uri) { progress ->
              extractProgress = progress
            }
          }

          if (frameUris.isEmpty()) {
            onError("视频帧提取失败，请检查视频格式或时长（需 ≤1s）")
            return@launch
          }

          // 进入 KeyFrameStore 缓存
          KeyFrameStore.setKeyFrames(frameUris, "帧工坊（1s视频）")
        } catch (e: Exception) {
          Log.e("FrameWorkshop", "Extract failed: ${e.message}", e)
          onError("帧提取失败: ${e.message}")
        } finally {
          isExtracting = false
        }
      }
    }
  }

  AlertDialog(
    onDismissRequest = { if (!isExtracting) onDismiss() },
    title = { Text("帧工坊：1s 视频 → 24 帧关键帧") },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (isExtracting) {
          // 提取进度
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
          ) {
            CircularProgressIndicator(
              modifier = Modifier.size(24.dp),
              strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
              text = "正在提取关键帧... $extractProgress/24",
              style = MaterialTheme.typography.bodyMedium,
            )
          }
        } else if (cachedFrames.isNotEmpty()) {
          // 当前缓存
          Text(
            text = "来源：$sourceLabel · ${cachedFrames.size} 帧",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            items(cachedFrames.size) { idx ->
              Box(
                modifier = Modifier
                  .size(56.dp)
                  .clip(RoundedCornerShape(6.dp))
                  .background(MaterialTheme.colorScheme.surfaceVariant),
              ) {
                AsyncImage(
                  model = cachedFrames[idx],
                  contentDescription = "帧 ${idx + 1}",
                  modifier = Modifier.fillMaxWidth(),
                )
                Text(
                  text = "${idx + 1}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurface,
                  modifier = Modifier
                    .align(Alignment.BottomStart)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                )
              }
            }
          }

          Text(
            text = "关键帧已提取并保存到相册。可点「选择新视频」重新提取，" +
              "或关闭后进入「图形」改造，「合成」为 MP4。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
          )
        } else {
          // 空状态
          Text(
            text = "点击下方按钮，选择一个 1s 视频。系统将提取 24 帧关键帧，" +
              "保存到相册并进入缓存。\n\n" +
              "要求：视频 ≤ 1 秒，大小 ≤ 50MB。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
          )
        }
      }
    },
    confirmButton = {
      if (!isExtracting) {
        TextButton(onClick = { videoPicker.launch("video/*") }) {
          Text(if (cachedFrames.isEmpty()) "选择 1s 视频" else "选择新视频")
        }
      }
    },
    dismissButton = {
      if (!isExtracting) {
        TextButton(onClick = onDismiss) { Text("完成") }
      }
    },
  )
}

/**
 * 从视频提取 24 帧关键帧，保存到相册，返回 Uri 列表。
 */
private suspend fun extractFramesToGallery(
  context: Context,
  videoUri: Uri,
  onProgress: (Int) -> Unit,
): List<Uri> = withContext(Dispatchers.IO) {
  // 使用 VideoFrameExtractor 提取帧到缓存目录
  val cacheDir = File(context.cacheDir, "extracted_frames").apply { mkdirs() }
  val framePaths = VideoFrameExtractor.extractFrames(context, videoUri, cacheDir)

  if (framePaths.isEmpty()) return@withContext emptyList()

  val result = mutableListOf<Uri>()
  var index = 0
  for (path in framePaths) {
    val file = File(path)
    if (!file.exists()) continue

    // 保存到相册
    val savedUri = saveBitmapToGallery(context, file, "memento_frame_${System.currentTimeMillis()}_$index.jpg")
    if (savedUri != null) {
      result.add(savedUri)
    }
    index++
    onProgress(result.size)
  }
  result
}

/**
 * 保存图片文件到相册（MediaStore），返回 Uri。
 */
private fun saveBitmapToGallery(context: Context, file: File, displayName: String): Uri? {
  return try {
    val contentValues = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
      put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Memento")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
      }
    }
    val uri = context.contentResolver.insert(
      MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
    ) ?: return null

    context.contentResolver.openOutputStream(uri)?.use { out ->
      file.inputStream().use { it.copyTo(out) }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      contentValues.clear()
      contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
      context.contentResolver.update(uri, contentValues, null, null)
    }
    uri
  } catch (e: Exception) {
    Log.w("FrameWorkshop", "saveBitmapToGallery failed: ${e.message}")
    null
  }
}

// ════════════════════════════════════════════════════════
// 图形工坊：元素替换改造关键帧
// ════════════════════════════════════════════════════════

/**
 * 元素到元素替换和关键帧提交对话框。
 *
 * 布局：（+ + - +）
 * - 左侧两个入口：用户元素（1张）+ 原图元素（1张）
 * - 右侧入口：关键帧（4-12张）
 *
 * 提交后：
 * - 用户元素 + 原图元素 → 模型执行替换 → 输出改造后的关键帧图片 → 进缓存
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

  // 预填：若 KeyFrameStore 有关键帧，自动填入
  val cachedFrames by KeyFrameStore.keyFrames.collectAsState()
  LaunchedEffect(Unit) {
    if (keyFrames.isEmpty() && cachedFrames.isNotEmpty()) {
      keyFrames.addAll(cachedFrames)
    }
  }

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
    title = { Text("图形工坊：元素替换改造关键帧") },
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
            text = "已选关键帧 ${keyFrames.size} 张（来自帧工坊缓存或手动选择）",
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
          text = "选择用户元素和原图元素，系统将替换关键帧中的对应元素，" +
            "输出改造后的关键帧图片进入缓存，供「合成」生成 MP4。",
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
