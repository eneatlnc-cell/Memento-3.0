package com.myagent.app.cloud

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * 云端推理客户端 — 统一门面。
 *
 * 封装 OpenAIProvider（对话 + 图片生成）和 KlingProvider（视频生成）。
 * 上层（ChatController）只与此类交互，不直接接触具体 Provider。
 *
 * 三段式工作流映射：
 * - 对话（含图片理解）→ OpenAIProvider.chat()
 * - 图形（图片生成）   → OpenAIProvider.generateImage()
 * - 合成（视频生成）   → KlingProvider.imageToVideo() / textToVideo()
 */
class CloudInferenceClient(context: Context) {

  companion object {
    private const val TAG = "CloudInferenceClient"
  }

  private val apiKeyManager = ApiKeyManager(context)
  private val openaiProvider = OpenAIProvider(apiKeyManager)
  private val klingProvider = KlingProvider(apiKeyManager)

  private val genOutputDir = File(context.cacheDir, "gen_output").apply { mkdirs() }

  /** API 密钥管理器（供设置页配置） */
  val keyManager: ApiKeyManager get() = apiKeyManager

  /** 是否已配置可用 */
  fun isConfigured(): Boolean = apiKeyManager.isConfigured()

  /**
   * 多模态对话流式输出。
   *
   * @param systemPrompt 系统提示词
   * @param messages     对话历史
   * @param imagePaths   当前轮图片路径（可选）
   * @return 流式文本 chunk
   */
  fun chat(
    systemPrompt: String,
    messages: List<CloudChatMessage>,
    imagePaths: List<String> = emptyList(),
  ): Flow<String> {
    if (!isConfigured()) {
      Log.w(TAG, "Not configured, using empty flow")
      return kotlinx.coroutines.flow.flowOf("请先在设置中配置 API 密钥或代理端点")
    }
    return openaiProvider.chat(systemPrompt, messages, imagePaths)
  }

  /**
   * 图片生成（DALL-E 3）。
   *
   * @param prompt 图片描述
   * @param size   "1024x1024" | "1792x1024" | "1024x1792"
   * @param quality "standard" | "hd"
   * @return 本地图片文件路径，失败 null
   */
  suspend fun generateImage(
    prompt: String,
    size: String = "1024x1024",
    quality: String = "hd",
  ): String? {
    if (!isConfigured()) return null
    return openaiProvider.generateImage(prompt, size, quality, genOutputDir)
  }

  /**
   * 图生视频（可灵）。
   *
   * @param keyFrameImagePaths 关键帧图片路径列表
   * @param prompt             视频描述
   * @param duration           时长（秒）：5 | 10
   * @param onProgress         进度回调
   * @return 本地视频文件路径，失败 null
   */
  suspend fun imageToVideo(
    keyFrameImagePaths: List<String>,
    prompt: String,
    duration: Int = 5,
    onProgress: (Int) -> Unit,
  ): String? {
    if (!isConfigured()) return null
    return klingProvider.imageToVideo(keyFrameImagePaths, prompt, duration, onProgress, genOutputDir)
  }

  /**
   * 文生视频（可灵）。
   *
   * @param prompt     视频描述
   * @param duration   时长（秒）：5 | 10
   * @param onProgress 进度回调
   * @return 本地视频文件路径，失败 null
   */
  suspend fun textToVideo(
    prompt: String,
    duration: Int = 5,
    onProgress: (Int) -> Unit,
  ): String? {
    if (!isConfigured()) return null
    return klingProvider.textToVideo(prompt, duration, onProgress, genOutputDir)
  }
}
