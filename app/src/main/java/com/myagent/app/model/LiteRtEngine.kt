package com.myagent.app.model

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.LlmInferenceEngine
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * LiteRT-LM 推理引擎 — 纯 Kotlin 封装，替代 llama.cpp JNI 桥接。
 *
 * 使用 Google LiteRT-LM 的 Engine/Session 模型：
 * - Engine：单例，加载模型文件
 * - Session：每次对话创建一个，管理 KV-cache
 *
 * 线程安全：LiteRT-LM 内部管理推理线程，callbackFlow 负责桥接到协程。
 */
class LiteRtEngine(private val context: Context) {
  companion object {
    private const val TAG = "LiteRtEngine"
  }

  private var engine: LlmInferenceEngine? = null
  private var session: LlmInferenceEngine.Session? = null

  /**
   * 初始化引擎并加载模型。
   *
   * @param modelPath  .litertlm 模型文件的绝对路径
   * @param maxTokens  每次推理最大 token 数
   * @return true 表示初始化成功
   */
  fun init(modelPath: String, maxTokens: Int = 512): Boolean {
    try {
      val options = LlmInferenceEngine.Options.builder()
        .setModelPath(modelPath)
        .setMaxTokens(maxTokens)
        .build()

      engine = LlmInferenceEngine.create(context, options)
      session = engine!!.createSession()
      Log.i(TAG, "LiteRT-LM engine ready: $modelPath")
      return true
    } catch (e: Exception) {
      Log.e(TAG, "Init failed: ${e.message}", e)
      return false
    }
  }

  /**
   * 流式生成回复。
   *
   * 使用 callbackFlow 将 LiteRT-LM 的异步回调桥接到 Kotlin Flow。
   * generateResponseAsync 的回调中 partial 是累积文本，这里计算增量再发射。
   *
   * @param prompt 完整的输入 Prompt
   */
  fun generate(prompt: String): Flow<String> = callbackFlow {
    val s = session ?: run {
      Log.e(TAG, "Session not initialized — cannot generate")
      close()
      return@callbackFlow
    }

    var lastText = ""

    try {
      s.addQueryChunk(prompt)
      s.generateResponseAsync { partial, done ->
        // 计算增量：当前累积文本去掉上次的部分
        val delta = partial.removePrefix(lastText)
        if (delta.isNotEmpty()) {
          trySend(delta)
          lastText = partial
        }
        if (done) {
          close()
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Generate error: ${e.message}", e)
      close(e)
    }

    awaitClose {
      // 流收集取消时的清理
    }
  }

  /**
   * 关闭引擎，释放所有资源。
   */
  fun close() {
    try {
      session?.close()
      engine?.close()
    } catch (_: Exception) {
      // 忽略关闭时的异常
    }
    session = null
    engine = null
    Log.i(TAG, "Engine closed")
  }
}