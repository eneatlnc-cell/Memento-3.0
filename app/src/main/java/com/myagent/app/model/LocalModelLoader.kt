package com.myagent.app.model

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 本地模型加载器 — 优先使用 llama.cpp 真实推理，模型不可用时降级为 Mock。
 *
 * 使用方式：
 * 1. 构造时传入 modelPath（非 null 表示模型已就绪）
 * 2. 调用 init(nativeLibDir) 初始化 llama.cpp 后端
 * 3. 调用 generate(prompt) 获取流式响应
 * 4. 如果模型是后续下载的，调用 reload(modelPath, nativeLibDir) 重新初始化
 */
class LocalModelLoader(
  private var modelPath: String?,
) {
  companion object {
    private const val TAG = "LocalModelLoader"
  }

  private val engine = LlamaEngine()
  @Volatile private var initialized = false

  /**
   * 初始化 llama.cpp 后端。如果模型不可用则跳过。
   */
  fun init(nativeLibDir: String) {
    if (modelPath == null) {
      Log.i(TAG, "No model available, using Mock mode")
      return
    }
    doInitialize(modelPath!!, nativeLibDir)
  }

  /**
   * 模型下载完成后重新加载引擎。
   */
  fun reload(newModelPath: String, nativeLibDir: String) {
    modelPath = newModelPath
    doInitialize(newModelPath, nativeLibDir)
  }

  private fun doInitialize(path: String, nativeLibDir: String) {
    try {
      engine.init(nativeLibDir)
      if (!engine.loadModel(path)) {
        Log.e(TAG, "Model load failed, falling back to Mock")
        return
      }
      if (!engine.prepare()) {
        Log.e(TAG, "Context prepare failed, falling back to Mock")
        return
      }
      initialized = true
      Log.i(TAG, "llama.cpp engine ready: $path")
    } catch (e: Exception) {
      Log.e(TAG, "Engine init failed: ${e.message}, falling back to Mock")
      initialized = false
    }
  }

  /**
   * 流式生成回复。
   * - 模型就绪 → llama.cpp 真实推理（带 15 秒超时，超时自动降级 Mock）
   * - 模型不可用 → Mock 降级
   */
  fun generate(prompt: String): Flow<String> {
    if (initialized && modelPath != null) {
      return flow {
        // 先尝试真实推理，15 秒内没产出就降级 Mock
        val result = withTimeoutOrNull(15_000L) {
          val sb = StringBuilder()
          engine.generate(prompt).collect { chunk ->
            sb.append(chunk)
            emit(chunk)
          }
          sb.toString()
        }
        if (result == null) {
          // 超时了，降级 Mock
          Log.w(TAG, "Model inference timed out, falling back to Mock")
          initialized = false
          mockGenerate(prompt).collect { emit(it) }
        }
      }.catch { e ->
        Log.e(TAG, "Model inference error: ${e.message}, falling back to Mock")
        initialized = false
        mockGenerate(prompt).collect { emit(it) }
      }
    }
    return mockGenerate(prompt)
  }

  /**
   * 检查真实推理是否可用
   */
  fun isRealModelAvailable(): Boolean = initialized && modelPath != null

  /**
   * 卸载模型释放内存
   */
  fun unload() {
    if (initialized) {
      engine.unload()
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
        "为什么程序员总是分不清万圣节和圣诞节？因为 Oct 31 == Dec 25！😂 好吧我知道这个有点冷..."

      input.contains("无聊") || input.contains("没意思") || input.contains("好闲") ->
        "无聊的时候最适合来找我聊天了！要不我给你讲个八卦？虽然我其实没有八卦可以讲..."

      input.contains("emo") || input.contains("难过") || input.contains("不开心") || input.contains("伤心") ->
        "抱抱！不管发生什么，我都在这里。想吐槽就尽情吐槽，我听着呢 ❤️"

      input.contains("学习") || input.contains("考试") || input.contains("作业") ->
        "学霸模式启动！虽然我有时候也不靠谱，但陪你一起学习还是可以的。哪里卡住了？"

      input.contains("推荐") || input.contains("安利") ->
        "我强烈安利...睡觉！开个玩笑，你想让我推荐哪方面的？音乐、电影、游戏还是学习资料？"

      input.length < 5 ->
        "嗯？宝你说啥？我没太听清，再说一遍呗~"

      else ->
        "哈哈哈哈这个有意思！宝你继续说，我听着呢~"
    }
  }
}