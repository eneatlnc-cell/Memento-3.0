package com.myagent.app.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.FileWriter
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
    private const val CRASH_LOG_DIR = "logs"
    private const val CRASH_LOG_FILE = "llama_crash.log"
  }

  private val appContext: Context = context.applicationContext

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
  @Volatile var forceCpuOnly: Boolean = true

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
    if (!initializing.compareAndSet(false, true)) {
      Log.w(TAG, "init() already in progress on another thread, skipping")
      return false
    }

    // ── 第一步：创建诊断日志文件（纯 Kotlin FileWriter，不依赖 JNI） ──
    // 必须在任何 JNI 调用之前完成，确保即使 System.loadLibrary 崩溃也有日志。
    val externalDir = appContext.getExternalFilesDir(null)
    val logDir = if (externalDir != null) {
      java.io.File(externalDir, CRASH_LOG_DIR)
    } else {
      java.io.File(appContext.filesDir, CRASH_LOG_DIR)
    }
    logDir.mkdirs()
    val logFile = java.io.File(logDir, CRASH_LOG_FILE)
    val logWriter = try {
      java.io.FileWriter(logFile, true).also { w ->
        w.write("[${java.util.Date()}] === engine.init() called ===\n")
        w.write("[${java.util.Date()}] modelPath=$modelPath\n")
        w.write("[${java.util.Date()}] mmprojPath=${mmprojPath ?: "null"}\n")
        w.flush()
      }
    } catch (e: Exception) {
      Log.e(TAG, "Cannot create log file: ${e.message}")
      null
    }

    fun logToFile(msg: String) {
      try {
        logWriter?.write("[${java.util.Date()}] $msg\n")
        logWriter?.flush()
      } catch (_: Exception) {}
    }

    try {
      // 安全关闭旧引擎（等待推理退出），再初始化新的
      logToFile("calling safeClose()...")
      safeClose()
      logToFile("safeClose() done")

      return synchronized(this) {
        try {
          // ── 第二步：加载 native 库 ──
          logToFile("calling LlamaNative.ensureLoaded()...")
          try {
            LlamaNative.ensureLoaded()
            logToFile("ensureLoaded() OK")
          } catch (e: Throwable) {
            logToFile("ensureLoaded() FAILED: ${e.javaClass.name} — ${e.message}")
            Log.e(TAG, "ensureLoaded failed: ${e.message}", e)
            activeBackend = "failed"
            return@synchronized false
          }

          // ── 第三步：初始化 JNI 日志 + llama backend ──
          logToFile("calling LlamaNative.initLogFile(${logFile.absolutePath})")
          try {
            LlamaNative.initLogFile(logFile.absolutePath)
            logToFile("initLogFile OK")
          } catch (e: Throwable) {
            logToFile("initLogFile FAILED: ${e.javaClass.name} — ${e.message}")
          }

          logToFile("calling LlamaNative.backendInit()")
          try {
            LlamaNative.backendInit()
            logToFile("backendInit OK")
          } catch (e: Throwable) {
            logToFile("backendInit FAILED: ${e.javaClass.name} — ${e.message}")
            Log.e(TAG, "backendInit failed: ${e.message}", e)
            activeBackend = "failed"
            return@synchronized false
          }

          val caps = DeviceCapability.detect(context)

          // ── 按需加速策略（不启用不使用） ──
          // 主模型（文本推理）：永远 CPU-only。0.8B 模型在 4 核 ARM 上
          // 可达 15-20 token/s，GPU/NPU 的额外加速不明显（<2x），
          // 但 GPU 持续运行会导致手机严重发热。
          // mmproj（图像编码）：骁龙 8 NPU 设备上启用 GPU 加速。
          // 图像编码是一次性短时操作（0.5-2s），编码完成后 GPU 闲置，
          // 不会持续加热。非骁龙 8 设备用 CPU 编码。
          val mtmdUseGpu = caps.canUseNpu  // mmproj：按需启用

          activeBackend = when {
            forceCpuOnly -> "CPU-4threads (forced)"
            mtmdUseGpu  -> "CPU-4threads + NPU(mmproj)"
            caps.isSd8  -> "CPU-4threads (SD8)"
            else        -> "CPU-4threads"
          }

          logToFile("modelLoad START: $modelPath (cpu-only, mtmdUseGpu=$mtmdUseGpu)")

          // 0) 文件完整性检查
          val modelFile = java.io.File(modelPath)
          if (!modelFile.canRead()) {
            logToFile("Model file not readable: $modelPath")
            activeBackend = "failed"
            return@synchronized false
          }
          logToFile("Model file readable: ${modelFile.length()} bytes")

          // 1) 加载模型（CPU-only）
          logToFile(">>> about to call LlamaNative.modelLoad()")
          model = LlamaNative.modelLoad(modelPath, nGpuLayers = 0, useHtp = false)
          if (model == 0L) {
            logToFile("<<< modelLoad FAILED — returned 0")
            activeBackend = "failed"
            return@synchronized false
          }
          logToFile("<<< modelLoad OK — model pointer: $model")

          // 2) 创建上下文
          val nCtx = when {
            caps.totalRamGb >= 12 -> 2048
            caps.totalRamGb >= 8 -> 2048
            else -> 1024
          }
          logToFile(">>> about to call LlamaNative.contextInit(nCtx=$nCtx)")
          ctx = LlamaNative.contextInit(model, nCtx, nThreads = 4, nBatch = 256)
          if (ctx == 0L) {
            logToFile("<<< contextInit FAILED — returned 0")
            activeBackend = "failed"
            closeInternal()
            return@synchronized false
          }
          logToFile("<<< contextInit OK — ctx pointer: $ctx")

          // 3) 加载 mmproj（多模态）
          if (mmprojPath != null) {
            logToFile(">>> about to call LlamaNative.mtmdInit($mmprojPath, useGpu=$mtmdUseGpu)")
            mctx = LlamaNative.mtmdInit(model, mmprojPath, mtmdUseGpu)
            if (mctx == 0L) {
              logToFile("<<< mtmdInit FAILED — returned 0, falling back to text-only")
              Log.w(TAG, "mmproj load failed, falling back to text-only: $mmprojPath")
            } else {
              logToFile("<<< mtmdInit OK — mctx pointer: $mctx")
            }
          }

          logToFile("=== LlamaEngine init complete: $activeBackend, nCtx=$nCtx ===")
          Log.i(TAG, "LlamaEngine ready: $modelPath ($activeBackend, nCtx=$nCtx, mmproj=${mmprojPath != null})")
          return@synchronized true
        } catch (e: UnsatisfiedLinkError) {
          logToFile("UnsatisfiedLinkError: ${e.message}")
          Log.e(TAG, "Native lib not loaded: ${e.message}", e)
          activeBackend = "no-native-lib"
          return@synchronized false
        } catch (e: Exception) {
          logToFile("Exception: ${e.javaClass.name} — ${e.message}")
          Log.e(TAG, "Init failed: ${e.message}", e)
          activeBackend = "error"
          return@synchronized false
        }
      }
    } finally {
      try { logWriter?.close() } catch (_: Exception) {}
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
    // catch Throwable 而不是 Exception：native 库缺失时 UnsatifiedLinkError 属于 Error 体系
    try { LlamaNative.cancelCompletion() } catch (_: Throwable) {}

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
