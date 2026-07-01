package com.myagent.app.model

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * LiteRT-LM 推理引擎 — 纯 Kotlin 封装，替代 llama.cpp JNI 桥接。
 *
 * 使用 Google LiteRT-LM 官方 Kotlin API：
 * - Engine：模型加载与生命周期管理
 * - Conversation：有状态对话，sendMessageAsync 返回 Flow<Message> 逐 token 流式输出
 *
 * v3.0 硬件适配：
 * - 骁龙 8 系列 + ≥12GB RAM → QNN NPU 后端（Hexagon 加速）
 * - 其他平台或 <12GB → CPU 多线程（4 线程）
 * - KV Cache 根据 RAM 动态调整：≥12GB → 4096，≥8GB → 2048，<8GB → 1024
 *
 * v2.1：新增 generateWithImages()，支持多模态输入（文本 + 图片）。
 * 图片通过 Content.ImageFile 直接传给 Conversation，利用 Gemma 4 原生视觉编码器。
 *
 * 线程安全：LiteRT-LM 内部管理推理线程，callbackFlow 负责桥接到协程。
 */
class LiteRtEngine(private val context: Context) {
  companion object {
    private const val TAG = "LiteRtEngine"
  }

  private var engine: Engine? = null
  private var conversation: Conversation? = null

  /** 当前使用的后端（用于日志/诊断） */
  var activeBackend: String = "unknown"
    private set

  /**
   * 初始化引擎并加载模型。
   *
   * 自动检测设备能力，选择最优后端：
   * - 骁龙 8 + ≥12GB → QNN NPU
   * - 其他平台 → CPU 多线程
   *
   * @param modelPath  .litertlm 模型文件的绝对路径
   * @param maxTokens  每次推理最大 token 数（由 ConversationConfig 控制，此处保留参数兼容）
   * @return true 表示初始化成功
   */
  fun init(modelPath: String, maxTokens: Int = 512): Boolean {
    try {
      // 防止重复初始化导致原生资源泄漏
      if (engine != null) {
        Log.w(TAG, "Engine already initialized, closing old instance first")
        close()
      }

      val caps = DeviceCapability.detect(context)
      val kvCacheTokens = when {
        caps.totalRamGb >= 12 -> 4096
        caps.totalRamGb >= 8 -> 2048
        else -> 1024
      }

      val backend = if (caps.canUseNpu) {
        // 骁龙 8 + ≥12GB → QNN NPU
        Log.i(TAG, "SD8 detected (${caps.platform}), ${caps.totalRamGb}GB RAM → NPU mode")
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        Backend.NPU(nativeLibraryDir)
      } else {
        // 其他平台 → CPU 多线程
        val reason = if (!caps.isSd8) "non-SD8 platform (${caps.platform})"
        else "RAM ${caps.totalRamGb}GB < ${DeviceCapability.NPU_MIN_RAM_GB}GB"
        Log.i(TAG, "Fallback to CPU: $reason")
        Backend.CPU(numOfThreads = 4)
      }

      activeBackend = if (caps.canUseNpu) "NPU-QNN" else "CPU"

      val engineConfig = EngineConfig(
        modelPath = modelPath,
        backend = backend,
        maxNumTokens = kvCacheTokens,
        cacheDir = context.cacheDir.absolutePath,
      )
      engine = Engine(engineConfig).also { it.initialize() }

      val conversationConfig = ConversationConfig(
        samplerConfig = SamplerConfig(
          topK = 40,
          topP = 0.95,
          temperature = 0.7,
        ),
      )
      conversation = engine!!.createConversation(conversationConfig)
      Log.i(TAG, "LiteRT-LM engine ready: $modelPath ($activeBackend, kvCache=$kvCacheTokens)")
      return true
    } catch (e: Exception) {
      Log.e(TAG, "Init failed: ${e.message}", e)
      return false
    }
  }

  /**
   * 流式生成回复（纯文本）。
   */
  fun generate(prompt: String): Flow<String> = callbackFlow {
    val conv = conversation ?: run {
      Log.e(TAG, "Conversation not initialized — cannot generate")
      close()
      return@callbackFlow
    }

    try {
      conv.sendMessageAsync(prompt).collect { message ->
        trySend(message.toString())
      }
      close()
    } catch (e: Exception) {
      Log.e(TAG, "Generate error: ${e.message}", e)
      close(e)
    }

    awaitClose {}
  }

  /**
   * 多模态流式生成（文本 + 图片）。
   *
   * 构建 Content 列表，包含 TextPart 和 ImageFile，通过 Message 传给 Conversation。
   * Gemma 4 原生视觉编码器会解析图片像素，结合文本 Prompt 一起推理。
   */
  fun generateWithImages(text: String, imagePaths: List<String>): Flow<String> = callbackFlow {
    val conv = conversation ?: run {
      Log.e(TAG, "Conversation not initialized — cannot generate")
      close()
      return@callbackFlow
    }

    try {
      val contents = mutableListOf<Content>()
      if (text.isNotEmpty()) {
        contents.add(Content.Text(text))
      }
      for (path in imagePaths) {
        if (path.isNotBlank()) {
          contents.add(Content.ImageFile(path))
          Log.i(TAG, "Attached image: $path")
        }
      }
      val message = Message.user(Contents.of(contents))
      conv.sendMessageAsync(message).collect { chunk ->
        trySend(chunk.toString())
      }
      close()
    } catch (e: Exception) {
      Log.e(TAG, "Generate with images error: ${e.message}", e)
      close(e)
    }

    awaitClose {}
  }

  /**
   * 关闭引擎，释放所有资源。
   */
  fun close() {
    try {
      conversation?.close()
      engine?.close()
    } catch (_: Exception) {}
    conversation = null
    engine = null
    Log.i(TAG, "Engine closed")
  }
}