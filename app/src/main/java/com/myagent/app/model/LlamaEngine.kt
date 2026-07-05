package com.myagent.app.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

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
 * 线程安全（C-N3/C-N4 修复）：
 * - activeInferences 引用计数跟踪正在运行的 JNI 推理
 * - close() 先通过 cancelCompletion() 信号中断推理，再等待计数归零，最后 free ctx
 * - closing 标志阻止新推理在关闭过程中启动
 * - 这避免了"JNI 推理运行中 close() 释放 ctx"的 UAF
 */
class LlamaEngine(private val context: Context) {
  companion object {
    private const val TAG = "LlamaEngine"
    private const val MMPROJ_MARKER = "<__media__>"  // mtmd 默认占位符
    private const val CLOSE_WAIT_MS = 3000L  // close() 等待推理退出的最长时间
  }

  @Volatile private var model: Long = 0L
  @Volatile private var ctx: Long = 0L
  @Volatile private var mctx: Long = 0L  // mtmd 上下文（0 表示无多模态）

  // C-N3 修复：引用计数 + closing 标志
  private val activeInferences = AtomicInteger(0)
  @Volatile private var closing = false

  // C-N5 修复：防止并发 init() 调用导致 safeClose() 释放另一个线程刚加载的 JNI 资源。
  // 两个线程同时调用 init() → 线程 2 的 safeClose() 会 closeInternal() 线程 1 刚加载的 model/ctx/mctx。
  private val initializing = AtomicBoolean(false)

  // 串行化 JNI 推理：同一 ctx 上并发 llama_decode 会损坏 KV cache。
  // 场景生成（SceneDirector）与聊天回复共用同一引擎，必须串行执行。
  private val inferenceSemaphore = Semaphore(1)

  /** 当前使用的后端（用于日志/诊断） */
  var activeBackend: String = "unknown"
    private set

  /**
   * 调试开关：强制 CPU 模式（n_gpu_layers=0），用于排查 GPU/NPU 崩溃。
   * 设为 true 后需重新调用 init() 生效。
   */
  @Volatile var forceCpuOnly: Boolean = false

  /**
   * 初始化引擎并加载模型。
   *
   * C-N3 修复：init() 不再直接 closeInternal()，而是走 safeClose() 安全等待
   * 正在运行的推理退出后再释放旧 ctx，避免 reload 时的 UAF。
   *
   * @param modelPath   主模型 GGUF 文件路径
   * @param mmprojPath  视觉投影器 GGUF 文件路径（多模态必需，null 则纯文本）
   * @param maxTokens   预留参数（由 n_ctx 控制，当前未使用）
   * @return true 表示初始化成功
   */
  fun init(modelPath: String, mmprojPath: String? = null, maxTokens: Int = 512): Boolean {
    // C-N5 修复：防止并发 init() 调用。
    // 如果另一个线程正在 init() 中，直接返回 false（上层 LocalModelLoader.doInitialize
    // 已有 CAS 保护，这里是最后一道防线）。
    if (!initializing.compareAndSet(false, true)) {
      Log.w(TAG, "init() already in progress on another thread, skipping")
      return false
    }
    try {
      // 安全关闭旧引擎（等待推理退出），再初始化新的
      safeClose()

      return synchronized(this) {
        try {
          LlamaNative.ensureLoaded()
          LlamaNative.backendInit()

          val caps = DeviceCapability.detect(context)
          val useHtp = caps.canUseNpu && !forceCpuOnly
          val nGpuLayers = if (useHtp) 99 else 0

          activeBackend = if (forceCpuOnly) "CPU-4threads (forced)" 
            else if (useHtp) "Hexagon-NPU+OpenCL" 
            else "CPU-4threads"

          Log.i(TAG, "init: modelLoad START — path=$modelPath, nGpuLayers=$nGpuLayers, useHtp=$useHtp, forceCpuOnly=$forceCpuOnly")
          // 1) 加载模型
          model = LlamaNative.modelLoad(modelPath, nGpuLayers, useHtp)
          if (model == 0L) {
            Log.e(TAG, "Model load failed: $modelPath")
            activeBackend = "failed"
            return@synchronized false
          }
          Log.i(TAG, "init: modelLoad OK, now contextInit...")

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
            return@synchronized false
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
          return@synchronized true
        } catch (e: UnsatisfiedLinkError) {
          Log.e(TAG, "Native lib not loaded: ${e.message}", e)
          activeBackend = "no-native-lib"
          return@synchronized false
        } catch (e: Exception) {
          Log.e(TAG, "Init failed: ${e.message}", e)
          activeBackend = "error"
          return@synchronized false
        }
      }
    } finally {
      initializing.set(false)
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
    val fullPrompt = buildQwenChatPrompt(systemPrompt, userPrompt, imageCount = 0)
    return callbackFlow {
      // 串行化 JNI 推理 + 原子 check-and-increment，防止并发 llama_decode 损坏 KV cache
      inferenceSemaphore.withPermit {
        val ctxSnapshot = synchronized(this@LlamaEngine) {
          if (closing || ctx == 0L) 0L
          else { activeInferences.incrementAndGet(); ctx }
        }
        if (ctxSnapshot == 0L) {
          Log.e(TAG, "Engine closing or not initialized — cannot generate")
          close()
          return@withPermit
        }
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
        } finally {
          activeInferences.decrementAndGet()
        }
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
    // 早检查：无多模态或无图片时直接回退到纯文本（不占用 semaphore）
    if (mctx == 0L || imagePaths.isEmpty()) {
      Log.w(TAG, "mmproj not loaded or no images, falling back to text-only")
      return generate(systemPrompt, userPrompt)
    }
    val fullPrompt = buildQwenChatPrompt(systemPrompt, userPrompt, imageCount = imagePaths.size)
    return callbackFlow {
      inferenceSemaphore.withPermit {
        // 在锁内原子检查 closing + ctx + mctx，并递增引用计数
        val ctxSnapshot: Long
        val mctxSnapshot: Long
        synchronized(this@LlamaEngine) {
          if (closing || ctx == 0L || mctx == 0L) {
            ctxSnapshot = 0L
            mctxSnapshot = 0L
          } else {
            activeInferences.incrementAndGet()
            ctxSnapshot = ctx
            mctxSnapshot = mctx
          }
        }
        if (ctxSnapshot == 0L || mctxSnapshot == 0L) {
          Log.e(TAG, "Engine closing or not initialized — cannot generate")
          close()
          return@withPermit
        }
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
        } finally {
          activeInferences.decrementAndGet()
        }
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
    val fullPrompt = buildQwenChatPrompt(systemPrompt, userPrompt, imageCount = 0)
    return callbackFlow {
      inferenceSemaphore.withPermit {
        val ctxSnapshot = synchronized(this@LlamaEngine) {
          if (closing || ctx == 0L) 0L
          else { activeInferences.incrementAndGet(); ctx }
        }
        if (ctxSnapshot == 0L) {
          Log.e(TAG, "Engine closing or not initialized — cannot generate scene")
          close()
          return@withPermit
        }
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
        } finally {
          activeInferences.decrementAndGet()
        }
      }
      awaitClose {}
    }
  }

  /**
   * 关闭引擎，安全释放所有资源。
   *
   * C-N3/C-N4 修复流程：
   * 1. 设置 closing=true，阻止新推理启动
   * 2. 通过 cancelCompletion() 信号中断正在运行的 JNI 推理
   * 3. 等待 activeInferences 归零（推理在下一个 token 检查处退出）
   * 4. 在 synchronized 块内 closeInternal() 释放原生资源
   * 5. 重置 closing=false
   *
   * 如果推理在 CLOSE_WAIT_MS 内未退出（极罕见，如单 token decode 卡住），
   * 记录警告后仍释放——这比无限等待或引擎永久不可用更好。
   */
  fun close() = safeClose()

  private fun safeClose() {
    synchronized(this) {
      if (closing) return  // 已经在关闭中，避免重入
      closing = true
    }

    // 1. 信号中断正在运行的推理
    try { LlamaNative.cancelCompletion() } catch (_: Exception) {}

    // 2. 等待推理退出（JNI 在每个 token 迭代检查取消标志）
    val deadline = System.currentTimeMillis() + CLOSE_WAIT_MS
    while (activeInferences.get() > 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(50)
    }
    if (activeInferences.get() > 0) {
      Log.w(TAG, "safeClose: ${activeInferences.get()} inference(s) still running after ${CLOSE_WAIT_MS}ms, freeing anyway")
    }

    // 3. 在锁内释放原生资源
    synchronized(this) {
      closeInternal()
      closing = false
    }
  }

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
