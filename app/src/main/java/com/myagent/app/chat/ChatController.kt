package com.myagent.app.chat

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import com.myagent.app.memory.MemoryManager
import com.myagent.app.model.LocalModelLoader
import com.myagent.app.model.PersonaManager
import com.myagent.app.multimodal.MultiModalDispatcher
import com.myagent.app.multimodal.VideoFrameExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 聊天控制器 — 协调 LocalModelLoader、MemoryManager、MultiModalDispatcher。
 *
 * v3.0 移除人格框架：原始记忆由 PersonaManager 单例提供，不再注入。
 */
class ChatController(
  private val scope: CoroutineScope,
  private val modelLoader: LocalModelLoader,
  private val memoryManager: MemoryManager,
  private val cacheDir: File,
  private val contentResolver: ContentResolver,
  private val context: Context,
) {
  companion object {
    private const val TAG = "ChatController"
  }

  private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
  val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

  private val _streamingText = MutableStateFlow<String?>(null)
  val streamingText: StateFlow<String?> = _streamingText.asStateFlow()

  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  private val _errorText = MutableStateFlow<String?>(null)
  val errorText: StateFlow<String?> = _errorText.asStateFlow()

  private var currentStreamJob: Job? = null
  private var currentAssistantId: String? = null

  // ── 多模态标记解析 ──

  private val imageTag = Regex("""^\[GEN_IMAGE:(.+?)]\s*""")
  private val videoTag = Regex("""^\[GEN_VIDEO:(.+?)]\s*""")

  private data class GenAction(val type: String, val prompt: String)

  private fun parseMultimodalTag(text: String): Pair<String, GenAction?> {
    imageTag.find(text)?.let { match ->
      val prompt = match.groupValues[1].trim()
      val clean = text.removeRange(match.range).trimStart()
      return clean to GenAction("image", prompt)
    }
    videoTag.find(text)?.let { match ->
      val prompt = match.groupValues[1].trim()
      val clean = text.removeRange(match.range).trimStart()
      return clean to GenAction("video", prompt)
    }
    return text to null
  }

  // ── URI → 文件路径 ──

  /**
   * 将 content:// URI 复制到缓存目录，压缩后返回绝对文件路径。
   * 图片传给 llama.cpp mtmd 需要绝对路径（mtmd_helper_bitmap_init_from_file）。
   * 压缩至最大 1024x1024，JPEG 质量 80%，避免 Qwen3.5 视觉编码器处理失败。
   * 限制单张图片最大 50MB，防止 OOM。
   */
  private fun resolveImagePath(uri: Uri): String? {
    return try {
      if (uri.scheme == "file") {
        return compressImage(uri.path ?: return null)
      }

      val size = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
      if (size > 50 * 1024 * 1024) {
        Log.w(TAG, "Image too large: ${size / 1024 / 1024}MB, max 50MB")
        return null
      }

      // 先复制到临时文件
      val tmpFile = File(cacheDir, "img_raw_${UUID.randomUUID()}")
      try {
        contentResolver.openInputStream(uri)?.use { input ->
          FileOutputStream(tmpFile).use { output ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
              output.write(buffer, 0, bytesRead)
            }
          }
        } ?: run {
          tmpFile.delete()
          return null
        }
        val result = compressImage(tmpFile.absolutePath)
        tmpFile.delete()
        result
      } catch (e: Exception) {
        tmpFile.delete()
        throw e
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to resolve image URI: ${e.message}")
      null
    }
  }

  /**
   * 压缩图片至最大 1024x1024，JPEG 质量 80%。
   * 返回压缩后文件的绝对路径。
   */
  private fun compressImage(inputPath: String): String? {
    return try {
      val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(inputPath, options)
      val srcW = options.outWidth
      val srcH = options.outHeight
      if (srcW <= 0 || srcH <= 0) return null

      val maxDim = 1024
      // inSampleSize 必须是 2 的幂，取不小于所需缩放倍数的 2 的幂
      val sampleSize = if (srcW > maxDim || srcH > maxDim) {
        var s = 1
        val scale = maxOf(srcW.toFloat() / maxDim, srcH.toFloat() / maxDim)
        while (s < scale) s *= 2
        s
      } else 1

      val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
      val bitmap = BitmapFactory.decodeFile(inputPath, opts) ?: return null

      // 如果解码后尺寸仍超过 1024，再等比缩放
      val finalBitmap = if (bitmap.width > maxDim || bitmap.height > maxDim) {
        val ratio = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
        Bitmap.createScaledBitmap(
          bitmap,
          (bitmap.width * ratio).toInt(),
          (bitmap.height * ratio).toInt(),
          true,
        ).also { if (it != bitmap) bitmap.recycle() }
      } else bitmap

      val outFile = File(cacheDir, "img_${UUID.randomUUID()}.jpg")
      FileOutputStream(outFile).use { out ->
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
      }
      // 保存尺寸信息后再回收，避免访问已回收 Bitmap 的属性
      val fw = finalBitmap.width
      val fh = finalBitmap.height
      if (finalBitmap != bitmap) finalBitmap.recycle() else bitmap.recycle()

      Log.i(TAG, "Compressed image: ${srcW}x${srcH} → ${fw}x${fh} (${outFile.length() / 1024}KB)")
      outFile.absolutePath
    } catch (e: Throwable) {
      Log.e(TAG, "Image compression failed: ${e.message}")
      null
    }
  }

  /**
   * 将 OutgoingAttachment（base64）解码为临时文件，供多模态推理使用。
   */
  private fun decodeAttachmentToTempFile(att: OutgoingAttachment): String? {
    return try {
      val bytes = Base64.decode(att.base64, Base64.DEFAULT)
      val ext = when {
        att.mimeType.contains("png", ignoreCase = true) -> "png"
        att.mimeType.contains("webp", ignoreCase = true) -> "webp"
        else -> "jpg"
      }
      val file = File(cacheDir, "att_${UUID.randomUUID()}.$ext")
      FileOutputStream(file).use { it.write(bytes) }
      file.absolutePath
    } catch (e: Exception) {
      Log.e(TAG, "Failed to decode attachment: ${e.message}")
      null
    }
  }

  private fun Uri.getExtension(): String? {
    val name = contentResolver.query(this, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
      ?.use { cursor ->
        if (cursor.moveToFirst()) {
          cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        } else null
      }
    return name?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
  }

  // ── 发送消息 ──

  fun sendMessage(
    message: String,
    attachments: List<OutgoingAttachment> = emptyList(),
    imagePaths: List<String> = emptyList(),
  ) {
    val trimmed = message.trim()
    if (trimmed.isEmpty() && attachments.isEmpty() && imagePaths.isEmpty()) return

    val userMessage = ChatMessage(
      id = UUID.randomUUID().toString(),
      role = "user",
      content = trimmed,
    )
    _messages.update { it + userMessage }

    // M-7: 转发图片附件到推理（base64 → 临时文件），避免静默丢弃
    val attachmentImagePaths = attachments.mapNotNull { att ->
      if (att.type == "image" || att.mimeType.startsWith("image/", ignoreCase = true)) {
        decodeAttachmentToTempFile(att)
      } else {
        Log.w(TAG, "Unsupported attachment type=${att.type} mime=${att.mimeType}, ignored")
        null
      }
    }
    val allImagePaths = imagePaths + attachmentImagePaths

    val memoryLabel = when {
      allImagePaths.isNotEmpty() -> "[图片]"
      trimmed.isEmpty() -> "[图片]"
      else -> trimmed
    }
    memoryManager.saveMemory(role = "user", content = memoryLabel)

    startInference(
      promptText = trimmed.ifEmpty { "请描述这张图片" },
      imagePaths = allImagePaths,
    )
  }

  /**
   * 启动推理流程 — 不添加用户消息（由调用方负责）。
   * sendImage / sendVideo 已自行添加用户消息，直接调用此方法进入推理。
   */
  private fun startInference(
    promptText: String,
    imagePaths: List<String>,
  ) {
    currentStreamJob?.cancel()
    _errorText.value = null
    _isLoading.value = true
    // streamingText 保持 null，直到首 token 到达才设置，避免空字符串导致三个气泡同时出现

    currentStreamJob = scope.launch {
      try {
        val systemPrompt = PersonaManager.getSystemPrompt()
        val memoryContext = memoryManager.getFullContext()

        // system 段：人格 + 记忆（为空则 LlamaEngine 省略 system 段）
        val systemBlock = buildString {
          append(systemPrompt)
          if (memoryContext.isNotEmpty()) {
            append("\n\n")
            append(memoryContext)
          }
        }

        // 多模态推理前校验图片有效性，避免损坏图片导致原生崩溃
        val validPaths = if (imagePaths.isNotEmpty()) {
          imagePaths.filter { path ->
            val file = File(path)
            if (!file.exists() || file.length() == 0L) {
              Log.w(TAG, "Skipping invalid image: $path")
              false
            } else {
              // 预解码校验：BitmapFactory 解码失败则说明图片损坏/格式不支持，
              // 避免传入 llama.cpp mtmd 原生层触发 SIGSEGV
              try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, opts)
                val valid = opts.outWidth > 0 && opts.outHeight > 0
                if (!valid) Log.w(TAG, "Corrupted/unsupported image: $path")
                valid
              } catch (t: Throwable) {
                Log.w(TAG, "Image validation failed: $path — ${t.message}")
                false
              }
            }
          }
        } else emptyList()

        if (imagePaths.isNotEmpty() && validPaths.isEmpty()) {
          _errorText.value = "图片格式不支持或已损坏，请重试"
          _isLoading.value = false
          return@launch
        }

        // 流式推理 — 有图片时走多模态路径
        // Qwen chat template 由 LlamaEngine 统一构造，这里只传语义内容
        val assistantId = UUID.randomUUID().toString()
        currentAssistantId = assistantId
        // 延迟添加助手消息：只有首 token 到达后才插入，避免 cancel 时残留空气泡
        var assistantAdded = false

        val fullResponse = StringBuilder()
        val inferenceFlow = if (validPaths.isNotEmpty()) {
          Log.i(TAG, "Multimodal inference: text + ${validPaths.size} image(s)")
          modelLoader.generateWithImages(systemBlock, promptText, validPaths)
        } else {
          modelLoader.generate(systemBlock, promptText)
        }

        // 流式输出节流：每 50ms 最多更新一次 StateFlow
        var lastStreamUpdate = 0L
        var isFirstToken = true
        inferenceFlow.collect { chunk ->
          fullResponse.append(chunk)
          if (!assistantAdded) {
            _messages.update { it + ChatMessage(id = assistantId, role = "assistant", content = "") }
            assistantAdded = true
          }
          val now = System.currentTimeMillis()
          if (isFirstToken || now - lastStreamUpdate >= 50) {
            _streamingText.value = fullResponse.toString()
            lastStreamUpdate = now
            isFirstToken = false
          }
        }
        // 确保最终文本被刷新
        _streamingText.value = fullResponse.toString()

        val rawContent = fullResponse.toString()

        // 如果推理无任何输出，添加错误消息
        if (!assistantAdded) {
          _messages.update { it + ChatMessage(
            id = assistantId, role = "assistant",
            content = "抱歉，推理未产生任何输出，请稍后重试"
          )}
          _isLoading.value = false
          return@launch
        }

        // 解析多模态意图标记
        val (cleanContent, genAction) = parseMultimodalTag(rawContent)

        // 更新文字消息（去掉标记）
        _messages.update { list ->
          list.map { if (it.id == assistantId) it.copy(content = cleanContent) else it }
        }

        // 保存助手回复到记忆
        val cleaned = cleanContent.trim()
        if (cleaned.isNotEmpty() && !isLoopOutput(cleaned)) {
          memoryManager.saveMemory(role = "assistant", content = cleaned)
        }

        _streamingText.value = null
        _isLoading.value = false

        // 多模态生成
        if (genAction != null) {
          dispatchGeneration(genAction)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: OutOfMemoryError) {
        Log.e(TAG, "Inference OOM: ${e.message}")
        _errorText.value = "内存不足，请稍后重试"
        _streamingText.value = null
        _isLoading.value = false
      } catch (e: Exception) {
        _errorText.value = e.message ?: "发送失败，请重试"
        _streamingText.value = null
        _isLoading.value = false
      } catch (t: Throwable) {
        // 兜底：捕获原生层异常（如 llama.cpp 的 SIGSEGV 被转换为 Java 异常）
        Log.e(TAG, "Fatal inference error: ${t.javaClass.name} — ${t.message}")
        _errorText.value = "模型推理遇到严重错误，请重启应用"
        _streamingText.value = null
        _isLoading.value = false
      } finally {
        // M-6: 确保所有路径（含外部取消）都复位加载/流式状态
        _streamingText.value = null
        _isLoading.value = false
        // M-5: 清除 assistantId 并移除残留的空助手气泡
        val aid = currentAssistantId
        currentAssistantId = null
        if (aid != null) {
          _messages.update { msgs -> msgs.filterNot { it.id == aid && it.content.isEmpty() } }
        }
      }
    }
  }

  /**
   * 调度多模态生成 — 图片/视频。
   */
  private suspend fun dispatchGeneration(action: GenAction) {
    when (action.type) {
      "image" -> {
        try {
          val bitmap = MultiModalDispatcher.generateImage(action.prompt)
          // H-M4 修复：bitmap 必须回收，否则每张 ~4MB native 内存泄漏
          try {
            // 保存到 cacheDir/images/ 子目录，匹配 FileProvider 的路径映射
            val imagesDir = File(cacheDir, "images").also { it.mkdirs() }
            val file = File(imagesDir, "gen_${UUID.randomUUID()}.png")
            val ok = FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!ok || file.length() == 0L) {
              throw Exception("图片写入失败，可能是磁盘空间不足")
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            Log.i(TAG, "Generated image saved: ${file.absolutePath} (${file.length() / 1024}KB)")
            val imageMsg = ChatMessage(
              id = UUID.randomUUID().toString(),
              role = "assistant",
              content = action.prompt,
              type = "image",
              attachmentUri = uri.toString(),
              attachmentMimeType = "image/png",
              localPath = file.absolutePath,
            )
            _messages.update { it + imageMsg }
          } finally {
            bitmap.recycle()
          }
        } catch (e: Exception) {
          Log.e(TAG, "Image generation failed: ${e.message}", e)
          _errorText.value = "图片生成失败: ${e.message}"
        }
      }
      "video" -> {
        val progressId = UUID.randomUUID().toString()
        val progressMsg = ChatMessage(
          id = progressId,
          role = "assistant",
          content = "正在渲染视频「${action.prompt}」，请稍候...",
        )
        _messages.update { it + progressMsg }
        try {
          val videoFile = MultiModalDispatcher.renderVideo(action.prompt)
          if (videoFile.length() == 0L) {
            throw Exception("视频文件为空，渲染可能超时")
          }
          // 基本完整性检查：MP4 文件至少需要 ftyp + moov 原子（最小 ~1KB）
          if (videoFile.length() < 1024) {
            throw Exception("视频文件过小，可能已损坏")
          }
          val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", videoFile)
          Log.i(TAG, "Generated video saved: ${videoFile.absolutePath} (${videoFile.length() / 1024}KB)")
          val videoMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = "assistant",
            content = action.prompt,
            type = "video",
            attachmentUri = uri.toString(),
            attachmentMimeType = "video/mp4",
            localPath = videoFile.absolutePath,
          )
          _messages.update { it.map { m -> if (m.id == progressId) videoMsg else m } }
        } catch (e: Exception) {
          Log.e(TAG, "Video generation failed: ${e.message}", e)
          _messages.update { it.map { m ->
            if (m.id == progressId) m.copy(content = "视频生成失败: ${e.message}") else m
          } }
        }
      }
    }
  }

  fun sendImage(imageUri: String, caption: String = "") {
    sendImages(listOf(imageUri), caption)
  }

  /**
   * 多图输入 — 用户可一次发送 ≤10 张图片，作为多模态上下文一起推理。
   *
   * 典型用法：
   * - 多角度拍摄同一物体 → LLM 综合理解
   * - 用户手动挑选视频关键帧 → 比系统采样更可控
   * - 前后对比图 → 变化检测
   *
   * Qwen3.5-VL 原生支持多图输入，所有图片路径一起传入 [imagePaths]。
   */
  fun sendImages(imageUris: List<String>, caption: String = "") {
    if (imageUris.isEmpty()) return

    val displayContent = caption.ifEmpty { "图片 ×${imageUris.size}" }
    val firstUri = imageUris.first()
    val message = ChatMessage(
      id = UUID.randomUUID().toString(),
      role = "user",
      content = displayContent,
      type = "image",
      attachmentUri = firstUri,
    )
    _messages.update { it + message }

    // 图片解析（文件 I/O + Bitmap 解码）放到 IO 线程，避免主线程 ANR
    scope.launch {
      try {
        val imagePaths = withContext(Dispatchers.IO) {
          imageUris.mapNotNull { resolveImagePath(Uri.parse(it)) }
        }
        if (imagePaths.isEmpty()) {
          _errorText.value = "图片处理失败，请检查图片是否过大或格式不支持"
          return@launch
        }
        memoryManager.saveMemory(role = "user", content = "[图片 ×${imagePaths.size}]")
        startInference(
          promptText = caption.ifEmpty { "请描述这些图片" },
          imagePaths = imagePaths,
        )
      } catch (e: OutOfMemoryError) {
        Log.e(TAG, "sendImages OOM: ${e.message}")
        _errorText.value = "图片过大，内存不足，请选择较小的图片"
      } catch (e: Exception) {
        Log.e(TAG, "sendImages failed: ${e.message}", e)
        _errorText.value = "图片处理失败: ${e.message}"
      } catch (t: Throwable) {
        // 兜底：捕获 BitmapFactory 等原生层异常
        Log.e(TAG, "sendImages fatal: ${t.javaClass.name} — ${t.message}")
        _errorText.value = "图片处理遇到严重错误，请尝试其他图片"
      }
    }
  }

  /**
   * 视频输入 — 帧采样后作为多张图片传给 Qwen3.5。
   *
   * llama.cpp libmtmd 当前不直接接受视频输入，采用帧采样替代方案：
   * MediaMetadataRetriever 提取前 5 秒的关键帧（每秒 3 帧），
   * 压缩为 JPEG 后作为 imagePaths 列表传给多模态引擎。
   */
  fun sendVideo(videoUri: String, caption: String = "") {
    val message = ChatMessage(
      id = UUID.randomUUID().toString(),
      role = "user",
      content = caption.ifEmpty { "视频" },
      type = "video",
      attachmentUri = videoUri,
    )
    _messages.update { it + message }

    _errorText.value = null

    // 文件大小检查 + 帧采样全部放到 IO 线程，避免主线程 ANR
    scope.launch {
      try {
        val uri = Uri.parse(videoUri)

        // 视频预校验：检查 URI 是否可访问，避免 MediaMetadataRetriever 原生崩溃
        val fileSize = withContext(Dispatchers.IO) {
          try {
            VideoFrameExtractor.getFileSize(context, uri)
          } catch (t: Throwable) {
            Log.e(TAG, "Video file size check failed: ${t.javaClass.name} — ${t.message}")
            -1L
          }
        }
        if (fileSize <= 0) {
          _errorText.value = "视频文件无法访问，可能已被删除或格式不支持"
          return@launch
        }
        if (fileSize > VideoFrameExtractor.MAX_FILE_SIZE) {
          val sizeMB = fileSize / (1024 * 1024)
          _errorText.value = "视频文件过大（当前 ${sizeMB} MB，限制 50MB），请选择较短的视频"
          return@launch
        }

        val frames = withContext(Dispatchers.IO) {
          VideoFrameExtractor.extractFrames(context, uri, cacheDir)
        }
        if (frames.isEmpty()) {
          _errorText.value = "视频帧提取失败，请尝试其他视频"
          return@launch
        }

        Log.i(TAG, "Video input: extracted ${frames.size} frames")
        memoryManager.saveMemory(role = "user", content = "[视频]")
        startInference(
          promptText = caption.ifEmpty { "请描述这个视频的内容" },
          imagePaths = frames,
        )
      } catch (e: OutOfMemoryError) {
        Log.e(TAG, "Video frame extraction OOM: ${e.message}")
        _errorText.value = "视频处理内存不足，请选择较短的视频"
      } catch (e: Exception) {
        Log.e(TAG, "Video send failed: ${e.message}", e)
        _errorText.value = "视频处理失败: ${e.message}"
      } catch (t: Throwable) {
        // 兜底：捕获 MediaMetadataRetriever 等原生层异常
        Log.e(TAG, "Video send fatal: ${t.javaClass.name} — ${t.message}")
        _errorText.value = "视频处理遇到严重错误，请尝试其他视频"
      }
    }
  }

  fun abort() {
    currentStreamJob?.cancel()
    currentStreamJob = null
    _streamingText.value = null
    _isLoading.value = false
    // M-5: 清除 assistantId 并移除残留的空助手气泡
    val aid = currentAssistantId
    currentAssistantId = null
    if (aid != null) {
      _messages.update { msgs -> msgs.filterNot { it.id == aid && it.content.isEmpty() } }
    }
  }

  fun clearMessages() {
    currentStreamJob?.cancel()
    currentStreamJob = null
    _messages.update { emptyList() }
    _streamingText.value = null
    _isLoading.value = false
    _errorText.value = null
  }

  /** 插入系统消息（主动搭话用），不触发模型推理 */
  fun addSystemMessage(text: String) {
    val msg = ChatMessage(
      id = UUID.randomUUID().toString(),
      role = "assistant",
      content = text,
      timestampMs = System.currentTimeMillis(),
    )
    _messages.update { it + msg }
  }

  private fun isLoopOutput(text: String): Boolean {
    if (text.length < 3) return false
    val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.size < 3) return false
    val maxCount = tokens.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
    return maxCount > tokens.size / 2
  }
}