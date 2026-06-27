package com.myagent.app.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 本地模型加载器 — 优先使用 LiteRT-LM 真实推理，模型不可用时降级为 Mock。
 *
 * v2.0：LiteRT-LM 替代 llama.cpp，纯 Kotlin 实现，无需 JNI/NDK。
 */
class LocalModelLoader(
  private val context: Context,
  private var modelPath: String?,
) {
  companion object {
    private const val TAG = "LocalModelLoader"
    private const val INFERENCE_TIMEOUT_MS = 120_000L // LiteRT-LM 首次推理较慢，放宽到 120s
  }

  private val engine = LiteRtEngine(context)
  @Volatile private var initialized = false

  /**
   * 初始化引擎。如果 modelPath 为 null 则跳过（降级 Mock）。
   */
  fun init() {
    if (modelPath == null) {
      Log.i(TAG, "No model available, using Mock mode")
      return
    }
    doInitialize(modelPath!!)
  }

  /**
   * 下载完成后重新加载模型。
   */
  fun reload(newModelPath: String) {
    modelPath = newModelPath
    doInitialize(newModelPath)
  }

  private fun doInitialize(path: String) {
    try {
      if (!engine.init(path)) {
        Log.e(TAG, "Engine init failed, falling back to Mock")
        return
      }
      initialized = true
      Log.i(TAG, "LiteRT-LM engine ready: $path")
    } catch (e: Exception) {
      Log.e(TAG, "Engine init failed: ${e.message}, falling back to Mock")
      initialized = false
    }
  }

  /**
   * 流式生成回复。
   *
   * 使用 callbackFlow 将 LiteRT-LM 回调桥接到 Flow，
   * 用 withTimeoutOrNull 在超时后停止收集（不阻塞 UI）。
   */
  fun generate(prompt: String): Flow<String> {
    if (!initialized || modelPath == null) {
      return mockGenerate(prompt)
    }
    return callbackFlow {
      val inferenceScope = CoroutineScope(Dispatchers.IO)

      val inferenceJob = inferenceScope.launch {
        try {
          engine.generate(prompt).collect { chunk ->
            trySend(chunk)
          }
        } catch (e: Exception) {
          Log.e(TAG, "Inference error: ${e.message}")
        }
        close()
      }

      // 等待推理完成或超时
      val finished = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
        inferenceJob.join()
        true
      }

      if (finished != true) {
        inferenceScope.cancel()
        Log.e(TAG, "Inference timed out after ${INFERENCE_TIMEOUT_MS}ms")
        trySend("抱歉，模型推理超时了。可能是手机内存不足，请尝试：\n1. 重启 App\n2. 在设置中切换到 Mock 模式")
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

  // ── Mock 回复 ──

  private fun mockGenerate(prompt: String): Flow<String> = flow {
    val response = mockResponse(prompt)
    var i = 0
    while (i < response.length) {
      val chunkSize = if (i % 3 == 0) 2 else 1
      val end = minOf(i + chunkSize, response.length)
      emit(response.substring(i, end))
      delay(30)
      i = end
    }
  }

  private fun mockResponse(prompt: String): String {
    val input = prompt.trim().lowercase()

    return when {
      input.contains("你好") || input.contains("嗨") || input.contains("hello") || input.contains("hi") ->
        "嗨宝！今天想聊点啥？我随时在线~ 😎"

      input.contains("名字") || input.contains("你是谁") || input.contains("你叫什么") ->
        "我叫灵机！你的专属 AI 搭子，24 小时不下线的那种！"

      input.contains("天气") ->
        "宝，我现在还看不到天气数据，不过你可以看看窗外嘛！要是下雨记得带伞，别淋感冒了~"

      input.contains("谢谢") || input.contains("感谢") || input.contains("thank") ->
        "跟我客气啥！咱俩谁跟谁啊 😏"

      input.contains("再见") || input.contains("拜拜") || input.contains("bye") ->
        "拜拜宝！随时找我，我永远在~ 👋"

      input.contains("笑话") || input.contains("搞笑") || input.contains("段子") ->
        "为什么程序员总是分不清万圣节和圣诞节？因为 Oct 31 == Dec 25！😂"

      input.contains("无聊") || input.contains("没意思") || input.contains("好闲") ->
        "无聊的时候最适合来找我聊天了！"

      input.contains("emo") || input.contains("难过") || input.contains("不开心") || input.contains("伤心") ->
        "抱抱！不管发生什么，我都在这里。想吐槽就尽情吐槽，我听着呢 ❤️"

      input.contains("学习") || input.contains("考试") || input.contains("作业") ->
        "学霸模式启动！哪里卡住了？"

      input.contains("推荐") || input.contains("安利") ->
        "你想让我推荐哪方面的？音乐、电影、游戏还是学习资料？"

      input.length < 5 ->
        "嗯？宝你说啥？我没太听清，再说一遍呗~"

      else ->
        "哈哈哈哈这个有意思！宝你继续说，我听着呢~"
    }
  }
}