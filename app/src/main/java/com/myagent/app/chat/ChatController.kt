package com.myagent.app.chat

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.myagent.app.memory.MemoryManager
import com.myagent.app.model.LocalModelLoader
import com.myagent.app.model.PersonaManager
import com.myagent.app.multimodal.MultiModalDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
   * 图片传给 LiteRT-LM 需要绝对路径（Content.ImageFile）。
   * 压缩至最大 1024x1024，JPEG 质量 80%，避免 E4B 视觉编码器处理失败。
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
      contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(tmpFile).use { output ->
          val buffer = ByteArray(8192)
          var bytesRead: Int
          while (input.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
          }
        }
      }
      val result = compressImage(tmpFile.absolutePath)
      tmpFile.delete() // 删除原始临时文件
      result
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
      if (finalBitmap != bitmap) finalBitmap.recycle()

      Log.i(TAG, "Compressed image: ${srcW}x${srcH} → ${finalBitmap.width}x${finalBitmap.height} (${outFile.length() / 1024}KB)")
      outFile.absolutePath
    } catch (e: Exception) {
      Log.e(TAG, "Image compression failed: ${e.message}")
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

    currentStreamJob?.cancel()

    val userMessage = ChatMessage(
      id = UUID.randomUUID().toString(),
      role = "user",
      content = trimmed,
    )
    _messages.value = _messages.value + userMessage

    val memoryLabel = when {
      imagePaths.isNotEmpty() -> "[图片]"
      trimmed.isEmpty() -> "[图片]"
      else -> trimmed
    }
    memoryManager.saveMemory(role = "user", content = memoryLabel)

    _errorText.value = null
    _isLoading.value = true
    _streamingText.value = ""

    currentStreamJob = scope.launch {
      try {
        val systemPrompt = PersonaManager.getSystemPrompt()
        val memoryContext = memoryManager.getFullContext()
        val promptText = trimmed.ifEmpty { "请描述这张图片" }

        val fullPrompt = buildString {
          append(systemPrompt)
          if (memoryContext.isNotEmpty()) {
            append("\n\n")
            append(memoryContext)
            append("\n--- 当前对话 ---\n")
          }
          append("用户: $promptText\n")
          append("Memento: ")
        }

        // 流式推理 — 有图片时走多模态路径
        val assistantId = UUID.randomUUID().toString()
        val assistantMessage = ChatMessage(
          id = assistantId,
          role = "assistant",
          content = "",
        )
        _messages.value = _messages.value + assistantMessage

        val fullResponse = StringBuilder()
        val inferenceFlow = if (imagePaths.isNotEmpty()) {
          Log.i(TAG, "Multimodal inference: text + ${imagePaths.size} image(s)")
          modelLoader.generateWithImages(fullPrompt, imagePaths)
        } else {
          modelLoader.generate(fullPrompt)
        }
        // 流式输出节流：每 50ms 最多更新一次 StateFlow
        // 但首 token 不节流，确保用户立即看到反馈
        var lastStreamUpdate = 0L
        var isFirstToken = true
        inferenceFlow.collect { chunk ->
          fullResponse.append(chunk)
          val now = System.currentTimeMillis()
          if (isFirstToken || now - lastStreamUpdate >= 50) {
            _streamingText.value = fullResponse.toString()
            lastStreamUpdate = now
            isFirstToken = false
          }
        }
        // 确保最终文本被刷新（最后一个 chunk 可能被节流跳过）
        _streamingText.value = fullResponse.toString()

        val rawContent = fullResponse.toString()

        // 解析多模态意图标记
        val (cleanContent, genAction) = parseMultimodalTag(rawContent)

        // 更新文字消息（去掉标记）
        _messages.value = _messages.value.map {
          if (it.id == assistantId) it.copy(content = cleanContent) else it
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
      } catch (e: Exception) {
        _errorText.value = e.message ?: "发送失败，请重试"
        _streamingText.value = null
        _isLoading.value = false
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
          val file = File(cacheDir, "gen_${UUID.randomUUID()}.png")
          FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
          Log.i(TAG, "Generated image saved: ${file.absolutePath} (${file.length() / 1024}KB)")
          val imageMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = "assistant",
            content = action.prompt,
            type = "image",
            attachmentUri = Uri.fromFile(file).toString(),
          )
          _messages.value = _messages.value + imageMsg
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
        _messages.value = _messages.value + progressMsg
        try {
          val videoFile = MultiModalDispatcher.renderVideo(action.prompt)
          if (videoFile.length() == 0L) {
            throw Exception("视频文件为空，渲染可能超时")
          }
          Log.i(TAG, "Generated video saved: ${videoFile.absolutePath} (${videoFile.length() / 1024}KB)")
          val videoMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = "assistant",
            content = action.prompt,
            type = "video",
            attachmentUri = Uri.fromFile(videoFile).toString(),
          )
          _messages.value = _messages.value.map {
            if (it.id == progressId) videoMsg else it
          }
        } catch (e: Exception) {
          Log.e(TAG, "Video generation failed: ${e.message}", e)
          _messages.value = _messages.value.map {
            if (it.id == progressId) it.copy(content = "视频生成失败: ${e.message}") else it
          }
        }
      }
    }
  }

  fun sendImage(imageUri: String, caption: String = "") {
    val message = ChatMessage(
      id = UUID.randomUUID().toString(),
      role = "user",
      content = caption.ifEmpty { "图片" },
      type = "image",
      attachmentUri = imageUri,
    )
    _messages.value = _messages.value + message

    // 将 content:// URI 转换为文件路径，传给多模态引擎
    val imagePath = resolveImagePath(Uri.parse(imageUri))
    val imagePaths = listOfNotNull(imagePath)
    sendMessage(
      message = caption.ifEmpty { "请描述这张图片" },
      imagePaths = imagePaths,
    )
  }

  fun sendVoice(audioUri: String, transcript: String = "") {
    val message = ChatMessage(
      id = UUID.randomUUID().toString(),
      role = "user",
      content = transcript.ifEmpty { "语音消息" },
      type = "voice",
      attachmentUri = audioUri,
    )
    _messages.value = _messages.value + message
    if (transcript.isNotEmpty()) {
      sendMessage(transcript)
    }
  }

  fun abort() {
    currentStreamJob?.cancel()
    currentStreamJob = null
    _streamingText.value = null
    _isLoading.value = false
  }

  fun clearMessages() {
    currentStreamJob?.cancel()
    currentStreamJob = null
    _messages.value = emptyList()
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
    _messages.value = _messages.value + msg
  }

  private fun isLoopOutput(text: String): Boolean {
    if (text.length < 3) return false
    val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.size < 3) return false
    val maxCount = tokens.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
    return maxCount > tokens.size / 2
  }
}