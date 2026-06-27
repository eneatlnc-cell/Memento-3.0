package com.myagent.app.model

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * LiteRT-LM 推理引擎 — 纯 Kotlin 封装，替代 llama.cpp JNI 桥接。
 *
 * 使用 Google LiteRT-LM 官方 Kotlin API：
 * - Engine：模型加载与生命周期管理
 * - Conversation：有状态对话，sendMessageAsync 返回 Flow<String> 逐 token 流式输出
 *
 * 线程安全：LiteRT-LM 内部管理推理线程，callbackFlow 负责桥接到协程。
 */
class LiteRtEngine(private val context: Context) {
  companion object {
    private const val TAG = "LiteRtEngine"
  }

  private var engine: Engine? = null
  private var conversation: Conversation? = null

  /**
   * 初始化引擎并加载模型。
   *
   * @param modelPath  .litertlm 模型文件的绝对路径
   * @param maxTokens  每次推理最大 token 数（由 ConversationConfig 控制，此处保留参数兼容）
   * @return true 表示初始化成功
   */
  fun init(modelPath: String, maxTokens: Int = 512): Boolean {
    try {
      val engineConfig = EngineConfig(modelPath = modelPath)
      engine = Engine(engineConfig).also { it.initialize() }
      conversation = engine!!.createConversation()
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
   * 使用 callbackFlow 将 LiteRT-LM 的 sendMessageAsync Flow 桥接到外部 Flow。
   * sendMessageAsync 返回 Flow<Message>，每个 Message 是增量 token 文本。
   *
   * @param prompt 完整的输入 Prompt
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

    awaitClose {
      // 流收集取消时的清理
    }
  }

  /**
   * 关闭引擎，释放所有资源。
   */
  fun close() {
    try {
      conversation?.close()
      engine?.close()
    } catch (_: Exception) {
      // 忽略关闭时的异常
    }
    conversation = null
    engine = null
    Log.i(TAG, "Engine closed")
  }
}