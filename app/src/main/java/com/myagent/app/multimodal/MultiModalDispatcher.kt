package com.myagent.app.multimodal

import android.app.Application
import android.graphics.Bitmap
import com.myagent.app.multimodal.dreamlite.DreamLiteImageGenerator
import com.myagent.app.multimodal.hyperframes.HyperFramesRenderer
import com.myagent.app.multimodal.kokoro.KokoroTtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 多模态统一调度器 — 所有端侧能力的唯一入口。
 *
 * 三大多模态模块，零网络请求，纯本地执行：
 * - 图像生成：HTML 渲染（WebView + CSS 图形）
 * - 语音合成：Kokoro-TTS（ONNX Runtime，模型内嵌 APK）
 * - 视频渲染：HyperFrames（WebView + MediaCodec）
 *
 * 使用方式：
 * ```kotlin
 * // Application.onCreate() 中初始化
 * MultiModalDispatcher.init(application)
 *
 * // 生成图片
 * val bitmap = MultiModalDispatcher.generateImage("一只戴帽子的猫", "warm")
 *
 * // 合成语音
 * val audio = MultiModalDispatcher.synthesizeSpeech("你好世界", "af_bella")
 *
 * // 渲染视频（可传入 VideoConfig 覆盖默认参数）
 * val video = MultiModalDispatcher.renderVideo("生日快乐", config = VideoConfig.LOW)
 * ```
 */
object MultiModalDispatcher {

  private var imageGenerator: DreamLiteImageGenerator? = null
  private var ttsEngine: KokoroTtsEngine? = null
  private var videoRenderer: HyperFramesRenderer? = null

  @Volatile private var initialized = false

  /**
   * 初始化所有多模态引擎。
   * Kokoro-TTS 模型已内嵌在 APK assets 中，无需额外参数。
   *
   * @param app Application 实例
   */
  fun init(app: Application) {
    if (initialized) return
    synchronized(this) {
      if (initialized) return

      imageGenerator = DreamLiteImageGenerator(app)
      ttsEngine = KokoroTtsEngine(app)
      videoRenderer = HyperFramesRenderer(app)

      initialized = true
    }
  }

  /**
   * 生成图片（HTML 渲染方案）。
   *
   * @param prompt 文本描述
   * @param style 风格参数（"minimal"、"dark"、"warm"、"vibrant" 等）
   * @return 生成的 Bitmap（1024×1024）
   */
  suspend fun generateImage(prompt: String, style: String? = null): Bitmap {
    checkInitialized()
    val gen = imageGenerator ?: throw IllegalStateException("ImageGenerator not initialized")
    return gen.generate(prompt, style)
  }

  /**
   * 编辑图片（HTML 叠加效果）。
   */
  suspend fun editImage(prompt: String, sourceImage: Bitmap): Bitmap {
    checkInitialized()
    val gen = imageGenerator ?: throw IllegalStateException("ImageGenerator not initialized")
    return gen.edit(prompt, sourceImage)
  }

  /**
   * 合成语音。
   *
   * @param text 要合成的文本
   * @param voice 音色名称（从 getAvailableVoices() 获取）
   * @param speed 语速，默认 1.0
   * @return 24kHz 16-bit PCM WAV 字节数组
   */
  suspend fun synthesizeSpeech(
    text: String,
    voice: String = "af_heart",
    speed: Float = 1.0f,
  ): ByteArray {
    checkInitialized()
    val tts = ttsEngine ?: throw IllegalStateException("TTS engine not initialized")
    return withContext(Dispatchers.Default) {
      tts.synthesize(text, voice, speed)
    }
  }

  /**
   * 流式合成语音。
   */
  suspend fun synthesizeSpeechStreaming(
    text: String,
    voice: String = "af_heart",
    speed: Float = 1.0f,
    onChunk: (ByteArray) -> Unit,
  ) {
    checkInitialized()
    val tts = ttsEngine ?: throw IllegalStateException("TTS engine not initialized")
    withContext(Dispatchers.Default) {
      tts.synthesizeStreaming(text, voice, speed, onChunk)
    }
  }

  /**
   * 获取所有可用音色。
   */
  fun getAvailableVoices(): List<String> = ttsEngine?.getAvailableVoices() ?: emptyList()

  /**
   * 渲染视频（HTML 模板 + Web Animations → MP4）。
   *
   * @param prompt 视频主题
   * @param config 视频配置（分辨率、帧率、时长），默认使用 VideoConfig.LOW (854x480@24fps)
   * @param onProgress 进度回调（0.0 ~ 1.0）
   * @return 生成的 MP4 文件
   */
  suspend fun renderVideo(
    prompt: String,
    config: VideoConfig = VideoConfig.LOW,
    onProgress: ((Float) -> Unit)? = null,
  ): File {
    checkInitialized()
    val renderer = videoRenderer ?: throw IllegalStateException("VideoRenderer not initialized")
    return renderer.render(
      prompt = prompt,
      duration = config.maxDuration,
      width = config.width,
      height = config.height,
      fps = config.fps,
      onProgress = onProgress,
    )
  }

  /**
   * 渲染视频（显式参数，兼容旧接口）。
   */
  suspend fun renderVideo(
    prompt: String,
    duration: Int,
    width: Int,
    height: Int,
    fps: Int = 24,
    onProgress: ((Float) -> Unit)? = null,
  ): File {
    checkInitialized()
    val renderer = videoRenderer ?: throw IllegalStateException("VideoRenderer not initialized")
    return renderer.render(prompt, duration, width, height, fps, onProgress)
  }

  /**
   * 释放所有引擎资源。
   */
  fun shutdown() {
    synchronized(this) {
      imageGenerator?.close()
      ttsEngine?.close()
      videoRenderer?.close()
      imageGenerator = null
      ttsEngine = null
      videoRenderer = null
      initialized = false
    }
  }

  private fun checkInitialized() {
    if (!initialized) {
      throw IllegalStateException(
        "MultiModalDispatcher 未初始化，请在 Application.onCreate() 中调用 MultiModalDispatcher.init(app)"
      )
    }
  }
}