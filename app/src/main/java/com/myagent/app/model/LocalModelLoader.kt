package com.myagent.app.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 本地模型加载器 — 使用 llama.cpp 真实推理，模型必须下载完成后才能使用。
 *
 * v3.1：llama.cpp 替换 LiteRT-LM，双文件结构（主模型 + mmproj）。
 * - 主模型：qwen3.5-0.8b-q4_k_m.gguf
 * - 视觉投影器：mmproj-BF16.gguf（多模态必需）
 *
 * 业务层接口（generate / generateWithImages / unload）保持稳定，
 * 引擎切换对 ChatController 等上层透明。
 */
class LocalModelLoader(
  private val context: Context,
  private var modelPath: String?,
  private var mmprojPath: String? = null,
) {
  companion object {
    private const val TAG = "LocalModelLoader"
    private const val INFERENCE_TIMEOUT_MS = 120_000L
  }

  private val engine = LlamaEngine(context)
  @Volatile private var initialized = false

  /**
   * 初始化引擎。modelPath 为 null 时跳过，等待下载完成后 reload。
   */
  fun init() {
    if (modelPath == null) {
      Log.i(TAG, "Model not yet downloaded, waiting for download")
      return
    }
    doInitialize(modelPath!!, mmprojPath)
  }

  /**
   * 下载完成后重新加载模型。
   *
   * @param newModelPath  主模型 GGUF 路径
   * @param newMmprojPath mmproj GGUF 路径（null 则纯文本模式）
   */
  fun reload(newModelPath: String, newMmprojPath: String? = null) {
    modelPath = newModelPath
    mmprojPath = newMmprojPath
    doInitialize(newModelPath, newMmprojPath)
  }

  private fun doInitialize(path: String, mmproj: String?) {
    try {
      if (!engine.init(path, mmproj)) {
        Log.e(TAG, "Engine init failed")
        return
      }
      initialized = true
      Log.i(TAG, "LlamaEngine ready: $path, mmproj=${mmproj ?: "null"}")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Engine init failed: ${e.message}")
      initialized = false
    }
  }

  /**
   * 尝试自动恢复：如果模型文件存在但引擎未初始化，尝试重新初始化。
   */
  private fun tryAutoRecover(): Boolean {
    if (initialized) return true
    if (modelPath == null) return false
    Log.i(TAG, "Auto-recovering: re-initializing engine from $modelPath, mmproj=${mmprojPath ?: "null"}")
    doInitialize(modelPath!!, mmprojPath)
    return initialized
  }

  /**
   * 流式生成回复。模型未就绪时尝试自动恢复，仍失败则返回提示。
   *
   * @param systemPrompt 系统提示词 + 记忆上下文（已由上层拼好），为空则省略 system 段
   * @param userPrompt   用户本轮输入的纯文本（不含 chat template 标记，由 LlamaEngine 包装）
   */
  fun generate(systemPrompt: String, userPrompt: String): Flow<String> {
    if (!tryAutoRecover()) {
      Log.w(TAG, "Model not ready, cannot generate")
      return callbackFlow {
        trySend("模型尚未下载完成，请等待下载结束后再试。")
        close()
      }
    }
    return callbackFlow {
      val inferenceScope = CoroutineScope(Dispatchers.IO)

      val inferenceJob = inferenceScope.launch {
        try {
          engine.generate(systemPrompt, userPrompt).collect { chunk ->
            trySend(chunk)
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "Inference error: ${e.message}")
        }
        close()
      }

      val finished = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
        inferenceJob.join()
        true
      }

      if (finished != true) {
        inferenceScope.cancel()
        Log.e(TAG, "Inference timed out after ${INFERENCE_TIMEOUT_MS}ms")
        trySend("抱歉，模型推理超时了。可能是手机内存不足，请尝试重启 App")
        // 超时后必须关闭引擎释放内存，否则模型权重持续占用
        engine.close()
        initialized = false
        close()
      }

      awaitClose { inferenceScope.cancel() }
    }
  }

  /**
   * 多模态流式生成（文本 + 图片）。
   * 图片路径传给 llama.cpp mtmd，Qwen3.5 视觉编码器解析。
   *
   * @param systemPrompt 系统提示词 + 记忆上下文（已由上层拼好），为空则省略 system 段
   * @param userPrompt   用户本轮输入的纯文本（不含 chat template 标记）
   * @param imagePaths   图片绝对路径列表
   */
  fun generateWithImages(systemPrompt: String, userPrompt: String, imagePaths: List<String>): Flow<String> {
    if (!tryAutoRecover()) {
      Log.w(TAG, "Model not ready, cannot generate")
      return callbackFlow {
        trySend("模型尚未下载完成，请等待下载结束后再试。")
        close()
      }
    }
    return callbackFlow {
      val inferenceScope = CoroutineScope(Dispatchers.IO)

      val inferenceJob = inferenceScope.launch {
        try {
          engine.generateWithImages(systemPrompt, userPrompt, imagePaths).collect { chunk ->
            trySend(chunk)
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "Inference with images error: ${e.message}")
        }
        close()
      }

      val finished = withTimeoutOrNull(INFERENCE_TIMEOUT_MS * 2) { // 图片推理给更多时间
        inferenceJob.join()
        true
      }

      if (finished != true) {
        inferenceScope.cancel()
        Log.e(TAG, "Inference with images timed out")
        trySend("抱歉，模型推理超时了。可能是手机内存不足，请尝试重启 App")
        // 超时后必须关闭引擎释放内存，否则模型权重持续占用
        engine.close()
        initialized = false
        close()
      }

      awaitClose { inferenceScope.cancel() }
    }
  }

  /**
   * 带 GBNF grammar 约束的结构化生成（漫剧场景 JSON）。
   * 采样阶段强制约束，无需事后校验循环。
   *
   * @param systemPrompt 系统提示词（含可用 assetRef 词典注入）
   * @param userPrompt   用户本轮输入
   * @param grammar      GBNF 语法定义（root 规则）
   */
  fun generateScene(systemPrompt: String, userPrompt: String, grammar: String): Flow<String> {
    if (!tryAutoRecover()) {
      Log.w(TAG, "Model not ready, cannot generate scene")
      return callbackFlow {
        trySend("模型尚未下载完成，请等待下载结束后再试。")
        close()
      }
    }
    return callbackFlow {
      val inferenceScope = CoroutineScope(Dispatchers.IO)

      val inferenceJob = inferenceScope.launch {
        try {
          engine.generateScene(systemPrompt, userPrompt, grammar).collect { chunk ->
            trySend(chunk)
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "Scene generation error: ${e.message}")
        }
        close()
      }

      val finished = withTimeoutOrNull(INFERENCE_TIMEOUT_MS * 2) {
        inferenceJob.join()
        true
      }

      if (finished != true) {
        inferenceScope.cancel()
        Log.e(TAG, "Scene generation timed out")
        trySend("抱歉，场景生成超时了。可能是手机内存不足，请尝试重启 App")
        engine.close()
        initialized = false
        close()
      }

      awaitClose { inferenceScope.cancel() }
    }
  }

  fun isRealModelAvailable(): Boolean = initialized && modelPath != null

  fun unload() {
    if (initialized) {
      engine.close()
      initialized = false
    }
  }
}