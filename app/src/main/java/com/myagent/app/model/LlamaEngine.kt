package com.myagent.app.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * llama.cpp 推理引擎 — 业务层与原生 C API 之间的桥接。
 *
 * 核心能力：
 * - 用 llama.cpp + libmtmd，骁龙平台支持 Hexagon NPU + Adreno OpenCL 双后端
 * - 多模态走 libmtmd，<__media__> 占位符由本类统一注入
 * - mmproj 强制 CPU（CVPR 2026 实测：OpenCL 跑 ViT 抖动大）
 * - Qwen3.5 chat template 由本类的 buildQwenChatPrompt() 唯一构造
 *
 * 硬件适配：
 * - 骁龙 SM8450+ (8 Gen 1+) → n_gpu_layers=99，HTP/OpenCL 自动选择
 * - 其他平台 → n_gpu_layers=0，纯 CPU 多线程
 *
 * 线程安全：用 synchronized 保护 init/close/generate。
 */
class LlamaEngine(private val context: Context) {
  companion object {
    private const val TAG = "LlamaEngine"
    private const val MMPROJ_MARKER = "<__media__>"  // mtmd 默认占位符
  }

  @Volatile private var model: Long = 0L
  @Volatile private var ctx: Long = 0L
  @Volatile private var mctx: Long = 0L  // mtmd 上下文（0 表示无多模态）

  /** 当前使用的后端（用于日志/诊断） */
  var activeBackend: String = "unknown"
    private set

  /**
   * 初始化引擎并加载模型。
   *
   * @param modelPath   主模型 GGUF 文件路径
   * @param mmprojPath  视觉投影器 GGUF 文件路径（多模态必需，null 则纯文本）
   * @param maxTokens   预留参数（由 n_ctx 控制，当前未使用）
   * @return true 表示初始化成功
   */
  fun init(modelPath: String, mmprojPath: String? = null, maxTokens: Int = 512): Boolean =
    synchronized(this) {
      try {
        // 防止重复初始化
        closeInternal()

        LlamaNative.ensureLoaded()
        LlamaNative.backendInit()

        val caps = DeviceCapability.detect(context)
        val useHtp = caps.canUseNpu  // 骁龙 8 + ≥12GB
        val nGpuLayers = if (useHtp) 99 else 0

        activeBackend = if (useHtp) "Hexagon-NPU+OpenCL" else "CPU-4threads"

        // 1) 加载模型
        model = LlamaNative.modelLoad(modelPath, nGpuLayers, useHtp)
        if (model == 0L) {
          Log.e(TAG, "Model load failed: $modelPath")
          activeBackend = "failed"
          return false
        }

        // 2) 创建上下文
        // KV Cache 根据 RAM 动态调整
        val nCtx = when {
          caps.totalRamGb >= 12 -> 4096
          caps.totalRamGb >= 8 -> 2048
          else -> 1024
        }
        ctx = LlamaNative.contextInit(model, nCtx, nThreads = 4, nBatch = 512)
        if (ctx == 0L) {
          Log.e(TAG, "Context init failed")
          activeBackend = "failed"
          closeInternal()
          return false
        }

        // 3) 加载 mmproj（多模态）
        if (mmprojPath != null) {
          mctx = LlamaNative.mtmdInit(model, mmprojPath)
          if (mctx == 0L) {
            Log.w(TAG, "mmproj load failed, falling back to text-only: $mmprojPath")
            // 不 return false——纯文本仍可用
          }
        }

        Log.i(TAG, "LlamaEngine ready: $modelPath ($activeBackend, nCtx=$nCtx, mmproj=${mmprojPath != null})")
        return true
      } catch (e: UnsatisfiedLinkError) {
        Log.e(TAG, "Native lib not loaded: ${e.message}", e)
        activeBackend = "no-native-lib"
        return false
      } catch (e: Exception) {
        Log.e(TAG, "Init failed: ${e.message}", e)
        activeBackend = "error"
        return false
      }
    }

  /**
   * 流式生成回复（纯文本）。
   *
   * @param systemPrompt 系统提示词 + 记忆上下文（已由上层拼好），为空则省略 system 段
   * @param userPrompt   用户本轮输入的纯文本（不含 chat template 标记）
   *
   * LlamaEngine 是 Qwen chat template 的唯一权威：所有 <|im_start|>/<|im_end|>
   * 在这里构造，上层（ChatController/LocalModelLoader）只传语义内容。
   */
  fun generate(systemPrompt: String, userPrompt: String): Flow<String> {
    val ctxSnapshot = synchronized(this) { ctx }
    if (ctxSnapshot == 0L) {
      Log.e(TAG, "Context not initialized — cannot generate")
      return callbackFlow { close() }
    }
    val fullPrompt = buildQwenChatPrompt(systemPrompt, userPrompt, imageCount = 0)
    return callbackFlow {
      try {
        LlamaNative.completion(
          ctx = ctxSnapshot,
          prompt = fullPrompt,
          maxTokens = 512,
          temperature = 0.7f,
          topP = 0.8f,
          topK = 20,
          callback = object : LlamaNative.TokenCallback {
            override fun onToken(piece: String, isEos: Boolean) {
              if (piece.isNotEmpty()) trySend(piece)
              if (isEos) close()
            }
          },
        )
        if (!isClosedForSend) close()
      } catch (e: Exception) {
        Log.e(TAG, "Generate error: ${e.message}", e)
        close(e)
      }
      awaitClose {}
    }
  }

  /**
   * 多模态流式生成（文本 + 图片）。
   *
   * @param systemPrompt 系统提示词 + 记忆上下文（已由上层拼好），为空则省略 system 段
   * @param userPrompt   用户本轮输入的纯文本（不含 chat template 标记）
   * @param imagePaths   图片绝对路径列表；每张图会在 user 段末尾插入一个 <__media__> 占位符
   *
   * 如果 mmproj 未加载，回退为纯文本（不附带图片）。
   */
  fun generateWithImages(systemPrompt: String, userPrompt: String, imagePaths: List<String>): Flow<String> {
    val ctxSnapshot = synchronized(this) { ctx }
    val mctxSnapshot = synchronized(this) { mctx }
    if (ctxSnapshot == 0L) {
      Log.e(TAG, "Context not initialized — cannot generate")
      return callbackFlow { close() }
    }
    if (mctxSnapshot == 0L || imagePaths.isEmpty()) {
      Log.w(TAG, "mmproj not loaded or no images, falling back to text-only")
      return generate(systemPrompt, userPrompt)
    }

    val fullPrompt = buildQwenChatPrompt(systemPrompt, userPrompt, imageCount = imagePaths.size)

    return callbackFlow {
      try {
        LlamaNative.completionWithImage(
          ctx = ctxSnapshot,
          mctx = mctxSnapshot,
          prompt = fullPrompt,
          imagePaths = imagePaths.toTypedArray(),
          maxTokens = 512,
          temperature = 0.7f,
          topP = 0.8f,
          topK = 20,
          callback = object : LlamaNative.TokenCallback {
            override fun onToken(piece: String, isEos: Boolean) {
              if (piece.isNotEmpty()) trySend(piece)
              if (isEos) close()
            }
          },
        )
        if (!isClosedForSend) close()
      } catch (e: Exception) {
        Log.e(TAG, "Generate with images error: ${e.message}", e)
        close(e)
      }
      awaitClose {}
    }
  }

  /**
   * 构造 Qwen3.5 chat template：
   * ```
   * <|im_start|>system
   * {systemPrompt}<|im_end|>
   * <|im_start|>user
   * {userPrompt}
   * <__media__>
   * <__media__>
   * ...<|im_end|>
   * <|im_start|>assistant
   * ```
   * systemPrompt 为空时省略 system 段。imageCount=0 时不插占位符。
   */
  private fun buildQwenChatPrompt(systemPrompt: String, userPrompt: String, imageCount: Int): String {
    return buildString {
      if (systemPrompt.isNotBlank()) {
        append("<|im_start|>system\n")
        append(systemPrompt.trim())
        append("<|im_end|>\n")
      }
      append("<|im_start|>user\n")
      append(userPrompt)
      if (imageCount > 0) {
        append("\n")
        repeat(imageCount) { append(MMPROJ_MARKER).append('\n') }
      }
      append("<|im_end|>\n")
      append("<|im_start|>assistant\n")
    }
  }

  /**
   * 带 GBNF grammar 约束的结构化生成（漫剧场景 JSON）。
   *
   * 设计：在采样阶段用 grammar 强制约束输出结构，模型物理上无法生成非法 JSON。
   * 无需事后校验循环，端侧 0.8B 也能稳定产出结构化数据。
   *
   * @param systemPrompt 系统提示词（含可用 assetRef 词典注入）
   * @param userPrompt   用户本轮输入
   * @param grammar      GBNF 语法定义（root 规则）
   * @return 流式 JSON 字符串片段
   */
  fun generateScene(systemPrompt: String, userPrompt: String, grammar: String): Flow<String> {
    val ctxSnapshot = synchronized(this) { ctx }
    if (ctxSnapshot == 0L) {
      Log.e(TAG, "Context not initialized — cannot generate scene")
      return callbackFlow { close() }
    }
    val fullPrompt = buildQwenChatPrompt(systemPrompt, userPrompt, imageCount = 0)
    return callbackFlow {
      try {
        LlamaNative.completionWithGrammar(
          ctx = ctxSnapshot,
          prompt = fullPrompt,
          grammarStr = grammar,
          maxTokens = 768,  // 结构化 JSON 需要更多 token
          temperature = 0.4f,  // 结构化输出降温度，更稳定
          topP = 0.9f,
          topK = 40,
          callback = object : LlamaNative.TokenCallback {
            override fun onToken(piece: String, isEos: Boolean) {
              if (piece.isNotEmpty()) trySend(piece)
              if (isEos) close()
            }
          },
        )
        if (!isClosedForSend) close()
      } catch (e: Exception) {
        Log.e(TAG, "Generate scene error: ${e.message}", e)
        close(e)
      }
      awaitClose {}
    }
  }

  /**
   * 关闭引擎，释放所有资源（synchronized 防止与 generate/init 并发）。
   */
  fun close() = synchronized(this) { closeInternal() }

  private fun closeInternal() {
    if (mctx != 0L) {
      try { LlamaNative.mtmdFree(mctx) } catch (_: Exception) {}
      mctx = 0L
    }
    if (ctx != 0L) {
      try { LlamaNative.contextFree(ctx) } catch (_: Exception) {}
      ctx = 0L
    }
    if (model != 0L) {
      try { LlamaNative.modelFree(model) } catch (_: Exception) {}
      model = 0L
    }
    Log.i(TAG, "Engine closed")
  }
}
