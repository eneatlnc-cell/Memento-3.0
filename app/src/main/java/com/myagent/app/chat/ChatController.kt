package com.myagent.app.chat

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import com.myagent.app.cloud.CloudChatMessage
import com.myagent.app.cloud.CloudInferenceClient
import com.myagent.app.memory.MemoryManager
import com.myagent.app.model.PersonaManager
import com.myagent.app.multimodal.KeyFrameStore
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
 * 聊天控制器 — 协调 CloudInferenceClient、MemoryManager、KeyFrameStore。
 *
 * v4.0 架构转向云端 API：
 * - 对话（含图片理解）→ GPT-4o 流式
 * - 图片生成           → DALL-E 3
 * - 视频合成           → 可灵 Kling
 *
 * 模型在对话中通过标记触发媒体生成：
 *   [GEN_IMAGE]\n{图片描述 prompt}\n[/GEN_IMAGE]
 *   [EDIT_IMAGE]\n{编辑描述 prompt}\n[/EDIT_IMAGE]
 *   [GEN_VIDEO]\n{视频描述 prompt}\n[/GEN_VIDEO]
 *
 * 应用解析标记 → 调用对应云端 API → 插入媒体消息。
 * GPT-4o 负责"理解+翻译为生成 prompt"，DALL-E 3/Kling 负责"渲染"。
 */
class ChatController(
  private val scope: CoroutineScope,
  private val cloudClient: CloudInferenceClient,
  private val memoryManager: MemoryManager,
  private val cacheDir: File,
  private val contentResolver: ContentResolver,
  private val context: Context,
) {
  companion object {
    private const val TAG = "ChatController"
    private const val HISTORY_LIMIT = 10  // 传给云端 API 的历史消息条数上限
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

  // 编辑模式：缓存最近一次生成的图片本地路径，供 EDIT_IMAGE 引用
  @Volatile
  private var lastGeneratedImagePath: String? = null

  // ── 媒体生成标记解析 ──
  private val genImageBlock = Regex("""\[GEN_IMAGE]\s*(.*?)\s*\[/GEN_IMAGE]""", RegexOption.DOT_MATCHES_ALL)
  private val genVideoBlock = Regex("""\[GEN_VIDEO[^\]]*]\s*(.*?)\s*\[/GEN_VIDEO]""", RegexOption.DOT_MATCHES_ALL)
  private val editImageBlock = Regex("""\[EDIT_IMAGE]\s*(.*?)\s*\[/EDIT_IMAGE]""", RegexOption.DOT_MATCHES_ALL)

  private data class GenAction(
    val type: String,         // "image" | "video" | "edit"
    val prompt: String,       // 媒体生成 prompt（送入 DALL-E 3 / Kling）
    val description: String,  // 模型在标记外的说明文字
  )

  /**
   * 解析媒体生成标记，返回 (干净文字, 动作)。
   * 动作为 null 表示纯文本回复。
   */
  private fun parseMultimodalTag(text: String): Pair<String, GenAction?> {
    editImageBlock.find(text)?.let { match ->
      val prompt = match.groupValues[1].trim()
      val clean = text.removeRange(match.range).trim()
      if (prompt.isEmpty()) return clean to null
      return clean to GenAction("edit", prompt = prompt, description = clean)
    }
    genImageBlock.find(text)?.let { match ->
      val prompt = match.groupValues[1].trim()
      val clean = text.removeRange(match.range).trim()
      if (prompt.isEmpty()) return clean to null
      return clean to GenAction("image", prompt = prompt, description = clean)
    }
    genVideoBlock.find(text)?.let { match ->
      val prompt = match.groupValues[1].trim()
      val clean = text.removeRange(match.range).trim()
      if (prompt.isEmpty()) return clean to null
      return clean to GenAction("video", prompt = prompt, description = clean)
    }
    return text to null
  }

  /** 判断用户是否请求编辑已生成的图片 */
  private fun isEditRequest(text: String): Boolean {
    val lower = text.lowercase()
    val keywords = listOf("编辑", "修改", "替换", "去掉", "换成", "改成", "调整", "edit", "modify", "replace", "remove")
    return keywords.any { it in lower } && lastGeneratedImagePath != null
  }

  // ── URI → 文件路径 ──

  /**
   * 将 content:// URI 复制到缓存目录，压缩后返回绝对文件路径。
   * 图片传给云端 API 需要本地路径（GPT-4o Vision 转 data URI / DALL-E 不需要）。
   * 压缩至最大 1024x1024，JPEG 质量 80%，避免上传过大。
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
      val sampleSize = if (srcW > maxDim || srcH > maxDim) {
        var s = 1
        val scale = maxOf(srcW.toFloat() / maxDim, srcH.toFloat() / maxDim)
        while (s < scale) s *= 2
        s
      } else 1

      val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
      val bitmap = BitmapFactory.decodeFile(inputPath, opts) ?: return null

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

    currentStreamJob = scope.launch {
      try {
        val systemPrompt = PersonaManager.getSystemPrompt()
        val memoryContext = memoryManager.getFullContext()

        val systemBlock = buildString {
          append(systemPrompt)
          if (memoryContext.isNotEmpty()) {
            append("\n\n")
            append(memoryContext)
          }
        }

        // 多模态推理前校验图片有效性
        val validPaths = if (imagePaths.isNotEmpty()) {
          imagePaths.filter { path ->
            val file = File(path)
            if (!file.exists() || file.length() == 0L) {
              Log.w(TAG, "Skipping invalid image: $path")
              false
            } else {
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

        val assistantId = UUID.randomUUID().toString()
        currentAssistantId = assistantId
        var assistantAdded = false

        // 编辑模式：若用户请求编辑且有缓存图片，把编辑上下文注入 prompt
        val effectivePrompt = if (isEditRequest(promptText) && lastGeneratedImagePath != null) {
          buildString {
            append(promptText)
            append("\n\n[参考：上一次生成的图片路径 — ")
            append(lastGeneratedImagePath)
            append("，请基于此图片生成 EDIT_IMAGE 标记描述编辑后的画面]")
          }
        } else {
          promptText
        }

        // 构造云端对话历史：UI 消息 → CloudChatMessage
        val history = _messages.value
          .filter { it.content.isNotBlank() }
          .takeLast(HISTORY_LIMIT)
          .map { CloudChatMessage(role = it.role, content = it.content) }
        val currentMessages = history + CloudChatMessage(role = "user", content = effectivePrompt)

        val fullResponse = StringBuilder()
        Log.i(TAG, "Cloud inference: text + ${validPaths.size} image(s), history=${history.size}")
        val inferenceFlow = cloudClient.chat(systemBlock, currentMessages, validPaths)

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
        _streamingText.value = fullResponse.toString()

        val rawContent = fullResponse.toString()

        if (!assistantAdded) {
          _messages.update { it + ChatMessage(
            id = assistantId, role = "assistant",
            content = "抱歉，推理未产生任何输出，请稍后重试"
          )}
          _isLoading.value = false
          return@launch
        }

        val (cleanContent, genAction) = parseMultimodalTag(rawContent)

        _messages.update { list ->
          list.map { if (it.id == assistantId) it.copy(content = cleanContent) else it }
        }

        val cleaned = cleanContent.trim()
        if (cleaned.isNotEmpty() && !isLoopOutput(cleaned)) {
          memoryManager.saveMemory(role = "assistant", content = cleaned)
        }

        _streamingText.value = null
        _isLoading.value = false

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
        Log.e(TAG, "Fatal inference error: ${t.javaClass.name} — ${t.message}")
        _errorText.value = "推理遇到严重错误，请重试"
        _streamingText.value = null
        _isLoading.value = false
      } finally {
        _streamingText.value = null
        _isLoading.value = false
        val aid = currentAssistantId
        currentAssistantId = null
        if (aid != null) {
          _messages.update { msgs -> msgs.filterNot { it.id == aid && it.content.isEmpty() } }
        }
      }
    }
  }

  /**
   * 调度媒体生成 — v4.0 云端 API。
   *
   * - image/edit：调 DALL-E 3 生成图片
   * - video：调可灵 Kling 合成视频（关键帧来自 KeyFrameStore）
   */
  private suspend fun dispatchGeneration(action: GenAction) {
    when (action.type) {
      "image", "edit" -> dispatchImage(action)
      "video" -> dispatchVideo(action)
    }
  }

  /** 调 DALL-E 3 生成图片，插入图片消息 */
  private suspend fun dispatchImage(action: GenAction) {
    val progressId = UUID.randomUUID().toString()
    _messages.update {
      it + ChatMessage(id = progressId, role = "assistant", content = "正在生成图片…")
    }
    try {
      // 编辑模式：把原图片描述融入新 prompt
      val finalPrompt = if (action.type == "edit" && lastGeneratedImagePath != null) {
        // DALL-E 3 不支持图片输入编辑，只能基于文字描述重新生成
        action.prompt
      } else {
        action.prompt
      }

      val imagePath = withContext(Dispatchers.IO) {
        cloudClient.generateImage(finalPrompt)
      }
      if (imagePath == null) {
        _messages.update { list ->
          list.map { m -> if (m.id == progressId) m.copy(content = "图片生成失败，请检查 API 配置后重试") else m }
        }
        return
      }

      val file = File(imagePath)
      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      Log.i(TAG, "DALL-E 3 image saved: ${file.absolutePath} (${file.length() / 1024}KB)")

      // 缓存最近生成图片路径，供编辑模式引用
      lastGeneratedImagePath = imagePath

      val imageMsg = ChatMessage(
        id = UUID.randomUUID().toString(),
        role = "assistant",
        content = action.description.ifEmpty { "已生成图片" },
        type = "image",
        attachmentUri = uri.toString(),
        attachmentMimeType = "image/png",
        localPath = file.absolutePath,
      )
      _messages.update { list -> list.map { m -> if (m.id == progressId) imageMsg else m } }
    } catch (e: Exception) {
      Log.e(TAG, "Image generation failed: ${e.message}", e)
      _messages.update { list ->
        list.map { m -> if (m.id == progressId) m.copy(content = "图片生成失败: ${e.message}") else m }
      }
    }
  }

  /** 调可灵 Kling 合成视频，插入视频消息 */
  private suspend fun dispatchVideo(action: GenAction) {
    val progressId = UUID.randomUUID().toString()
    _messages.update {
      it + ChatMessage(id = progressId, role = "assistant", content = "正在合成视频…")
    }
    try {
      // 优先用 KeyFrameStore 缓存的关键帧做图生视频；无缓存则文生视频
      val keyFrameUris = KeyFrameStore.keyFrames.value
      val keyFramePaths = if (keyFrameUris.isNotEmpty()) {
        keyFrameUris.mapNotNull { withContext(Dispatchers.IO) { resolveImagePath(it) } }
      } else emptyList()

      val videoPath = if (keyFramePaths.isNotEmpty()) {
        cloudClient.imageToVideo(keyFramePaths, action.prompt, duration = 5) { progress ->
          _messages.update { list ->
            list.map { m ->
              if (m.id == progressId) m.copy(content = "正在合成视频… $progress%") else m
            }
          }
        }
      } else {
        cloudClient.textToVideo(action.prompt, duration = 5) { progress ->
          _messages.update { list ->
            list.map { m ->
              if (m.id == progressId) m.copy(content = "正在合成视频… $progress%") else m
            }
          }
        }
      }

      if (videoPath == null) {
        _messages.update { list ->
          list.map { m -> if (m.id == progressId) m.copy(content = "视频合成失败，请检查 API 配置后重试") else m }
        }
        return
      }

      val file = File(videoPath)
      if (file.length() < 1024) {
        throw Exception("视频文件过小，可能已损坏")
      }
      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      Log.i(TAG, "Kling video saved: ${file.absolutePath} (${file.length() / 1024}KB)")

      val videoMsg = ChatMessage(
        id = UUID.randomUUID().toString(),
        role = "assistant",
        content = action.description.ifEmpty { "已生成视频" },
        type = "video",
        attachmentUri = uri.toString(),
        attachmentMimeType = "video/mp4",
        localPath = file.absolutePath,
      )
      _messages.update { list -> list.map { m -> if (m.id == progressId) videoMsg else m } }
    } catch (e: Exception) {
      Log.e(TAG, "Video generation failed: ${e.message}", e)
      _messages.update { list ->
        list.map { m -> if (m.id == progressId) m.copy(content = "视频合成失败: ${e.message}") else m }
      }
    }
  }

  fun sendImage(imageUri: String, caption: String = "") {
    sendImages(listOf(imageUri), caption)
  }

  /**
   * 多图输入 — 用户可一次发送 ≤10 张图片，作为多模态上下文一起推理。
   *
   * GPT-4o Vision 原生支持多图输入，所有图片路径一起传入。
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
        Log.e(TAG, "sendImages fatal: ${t.javaClass.name} — ${t.message}")
        _errorText.value = "图片处理遇到严重错误，请尝试其他图片"
      }
    }
  }

  /**
   * 视频输入 — 帧采样后作为多张图片传给 GPT-4o Vision。
   *
   * GPT-4o 不直接接受视频，采用帧采样：
   * MediaMetadataRetriever 提取关键帧，压缩为 JPEG 后作为 imagePaths 列表传入。
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

    scope.launch {
      try {
        val uri = Uri.parse(videoUri)

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
        Log.e(TAG, "Video send fatal: ${t.javaClass.name} — ${t.message}")
        _errorText.value = "视频处理遇到严重错误，请尝试其他视频"
      }
    }
  }

  /**
   * v4.0 三段式工作流：合成 MP4。
   *
   * 取 KeyFrameStore 中缓存的关键帧，直接调可灵 Kling 图生视频。
   * 不走对话流程，避免 GPT-4o 中转（关键帧已就绪，Kling 直接接收）。
   */
  fun composeVideoFromKeyFrames() {
    val keyFrameUris = KeyFrameStore.keyFrames.value
    if (keyFrameUris.isEmpty()) {
      _errorText.value = "缓存中没有关键帧，请先在「图形」或「帧」中准备"
      return
    }

    val sourceLabel = KeyFrameStore.sourceLabel.value
    val userMessage = ChatMessage(
      id = UUID.randomUUID().toString(),
      role = "user",
      content = "[合成视频] 基于 $sourceLabel 的 ${keyFrameUris.size} 帧关键帧",
    )
    _messages.update { it + userMessage }
    _errorText.value = null

    val progressId = UUID.randomUUID().toString()
    _messages.update {
      it + ChatMessage(id = progressId, role = "assistant", content = "正在合成视频…")
    }

    scope.launch {
      try {
        val imagePaths = keyFrameUris.mapNotNull { uri ->
          withContext(Dispatchers.IO) { resolveImagePath(uri) }
        }
        if (imagePaths.isEmpty()) {
          _messages.update { list ->
            list.map { m -> if (m.id == progressId) m.copy(content = "关键帧解析失败，请重新选择") else m }
          }
          return@launch
        }

        Log.i(TAG, "Compose video: ${imagePaths.size} key frames")
        memoryManager.saveMemory(role = "user", content = "[合成视频 ${imagePaths.size}帧]")

        val prompt = "基于 ${imagePaths.size} 张关键帧生成流畅过渡的视频动画，保持角色和场景一致。"
        val videoPath = cloudClient.imageToVideo(imagePaths, prompt, duration = 5) { progress ->
          _messages.update { list ->
            list.map { m ->
              if (m.id == progressId) m.copy(content = "正在合成视频… $progress%") else m
            }
          }
        }

        if (videoPath == null) {
          _messages.update { list ->
            list.map { m -> if (m.id == progressId) m.copy(content = "视频合成失败，请检查 API 配置后重试") else m }
          }
          return@launch
        }

        val file = File(videoPath)
        if (file.length() < 1024) {
          throw Exception("视频文件过小，可能已损坏")
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        Log.i(TAG, "Kling video saved: ${file.absolutePath} (${file.length() / 1024}KB)")

        val videoMsg = ChatMessage(
          id = UUID.randomUUID().toString(),
          role = "assistant",
          content = "已合成视频（基于 ${imagePaths.size} 帧关键帧）",
          type = "video",
          attachmentUri = uri.toString(),
          attachmentMimeType = "video/mp4",
          localPath = file.absolutePath,
        )
        _messages.update { list -> list.map { m -> if (m.id == progressId) videoMsg else m } }
      } catch (e: Exception) {
        Log.e(TAG, "Compose video failed: ${e.message}", e)
        _messages.update { list ->
          list.map { m -> if (m.id == progressId) m.copy(content = "视频合成失败: ${e.message}") else m }
        }
      }
    }
  }

  fun abort() {
    currentStreamJob?.cancel()
    currentStreamJob = null
    _streamingText.value = null
    _isLoading.value = false
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
