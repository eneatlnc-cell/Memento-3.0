package com.myagent.app.model

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * llama.cpp 推理引擎 — 替换 LiteRT-LM，对齐业务接口。
 *
 * 核心改进（相对 LiteRT-LM）：
 * - 用 llama.cpp + libmtmd，骁龙平台支持 Hexagon NPU + Adreno OpenCL 双后端
 * - 多模态走 libmtmd（取代 LiteRT-LM 的 Content.ImageFile），在非骁龙平台稳定
 * - mmproj 强制 CPU（CVPR 2026 实测：OpenCL 跑 ViT 抖动大）
 *
 * 硬件适配：
 * - 骁龙 SM8450+ (8 Gen 1+) → n_gpu_layers=99，HTP/OpenCL 自动选择
 * - 其他平台 → n_gpu_layers=0，纯 CPU 多线程
 *
 * 线程安全：与 LiteRtEngine 相同，用 synchronized 保护 init/close/generate。
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
   * @param maxTokens   预留参数（由 n_ctx 控制，此处保留兼容 LiteRtEngine 接口）
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
        // KV Cache 根据 RAM 动态调整（与 LiteRtEngine 一致）
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
   * 在 synchronized 块中快照 ctx 引用，避免与 close() 竞态。
   */
  fun generate(prompt: String): Flow<String> {
    val ctxSnapshot = synchronized(this) { ctx }
    if (ctxSnapshot == 0L) {
      Log.e(TAG, "Context not initialized — cannot generate")
      return callbackFlow { close() }
    }
    return callbackFlow {
      try {
        LlamaNative.completion(
          ctx = ctxSnapshot,
          prompt = prompt,
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
   * 自动在 prompt 中插入 <__media__> 占位符（每张图一个），
   * 并包装为 Qwen chat template 格式。
   * 如果 mmproj 未加载，回退为纯文本（不附带图片）。
   */
  fun generateWithImages(text: String, imagePaths: List<String>): Flow<String> {
    val ctxSnapshot = synchronized(this) { ctx }
    val mctxSnapshot = synchronized(this) { mctx }
    if (ctxSnapshot == 0L) {
      Log.e(TAG, "Context not initialized — cannot generate")
      return callbackFlow { close() }
    }
    if (mctxSnapshot == 0L) {
      Log.w(TAG, "mmproj not loaded, falling back to text-only")
      return generate(text)
    }

    // 构造含 <__media__> 占位符的 prompt
    // Qwen chat template: <|im_start|>user\n{text}\n<__media__>\n<__media__>\n...<|im_end|>\n<|im_start|>assistant\n
    val mediaMarkers = imagePaths.joinToString("\n") { MMPROJ_MARKER }
    val fullPrompt = buildString {
      append("<|im_start|>user\n")
      append(text)
      if (imagePaths.isNotEmpty()) {
        append("\n")
        append(mediaMarkers)
      }
      append("<|im_end|>\n<|im_start|>assistant\n")
    }

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
