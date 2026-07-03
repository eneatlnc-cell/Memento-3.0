package com.myagent.app.multimodal

import android.app.Application
import android.graphics.Bitmap
import com.myagent.app.multimodal.dreamlite.DreamLiteImageGenerator
import com.myagent.app.multimodal.hyperframes.HyperFramesRenderer
import java.io.File

/**
 * 多模态统一调度器 — 所有端侧能力的唯一入口。
 *
 * 当前模块（纯本地执行，零网络请求）：
 * - 图像生成：HTML 渲染（WebView + CSS 图形）
 * - 视频渲染：HyperFrames（WebView + MediaCodec）
 *
 * 注：语音合成（Kokoro-TTS）已移除，第一阶段不提供音频支持。
 * 未来恢复音频能力时，在此处重新接入 TTS 引擎即可。
 *
 * 使用方式：
 * ```kotlin
 * // Application.onCreate() 中初始化
 * MultiModalDispatcher.init(application)
 *
 * // 生成图片
 * val bitmap = MultiModalDispatcher.generateImage("一只戴帽子的猫", "warm")
 *
 * // 渲染视频
 * val video = MultiModalDispatcher.renderVideo("生日快乐", config = VideoConfig.LOW)
 * ```
 */
object MultiModalDispatcher {

  private var imageGenerator: DreamLiteImageGenerator? = null
  private var videoRenderer: HyperFramesRenderer? = null

  @Volatile private var initialized = false

  /**
   * 初始化多模态引擎。
   *
   * @param app Application 实例
   */
  fun init(app: Application) {
    if (initialized) return
    synchronized(this) {
      if (initialized) return

      imageGenerator = DreamLiteImageGenerator(app)
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
      videoRenderer?.close()
      imageGenerator = null
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
