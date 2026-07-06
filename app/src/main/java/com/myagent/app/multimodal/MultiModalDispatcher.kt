package com.myagent.app.multimodal

import android.app.Application
import android.graphics.Bitmap
import java.io.File

/**
 * 多模态统一调度器 — v3.2 重构。
 *
 * v3.2 架构变更：
 *   - 移除 DreamLiteImageGenerator（7 个预设模板的伪多模态）
 *   - 移除 HyperFramesRenderer（固定动画模板的伪逐帧）
 *   - 新增 StructuredRenderer（模型输出 SVG → 真正结构化渲染）
 *
 * 新架构：
 *   模型输出 SVG（结构化描述）→ StructuredRenderer 渲染为 Bitmap/MP4
 *
 * 接口语义变更：
 *   - renderSvgImage(svg)：接收 SVG 字符串，渲染为 Bitmap
 *   - renderSvgVideo(frames, onProgress)：接收 SVG 帧序列，渲染为 MP4
 *
 * 旧接口 generateImage/renderVideo（接收 prompt 字符串）已废弃，
 * 因为 v3.2 不再让渲染器自己选模板，而是模型直接输出 SVG。
 */
object MultiModalDispatcher {

  private var structuredRenderer: StructuredRenderer? = null

  @Volatile private var initialized = false

  fun init(app: Application) {
    if (initialized) return
    synchronized(this) {
      if (initialized) return
      structuredRenderer = StructuredRenderer(app)
      initialized = true
    }
  }

  /**
   * 渲染 SVG 为 Bitmap。
   *
   * @param svg 模型输出的合法 SVG 字符串
   * @return 渲染后的 Bitmap（512×512）
   */
  suspend fun renderSvgImage(svg: String): Bitmap {
    checkInitialized()
    val renderer = structuredRenderer ?: throw IllegalStateException("StructuredRenderer not initialized")
    return renderer.renderImage(svg)
  }

  /**
   * 渲染 SVG 帧序列为 MP4 视频。
   *
   * @param frames SVG 帧列表（每帧一个 SVG 字符串）
   * @param onProgress 进度回调（0.0 ~ 1.0）
   * @return 生成的 MP4 文件
   */
  suspend fun renderSvgVideo(
    frames: List<String>,
    onProgress: ((Float) -> Unit)? = null,
  ): File {
    checkInitialized()
    val renderer = structuredRenderer ?: throw IllegalStateException("StructuredRenderer not initialized")
    return renderer.renderVideo(frames, onProgress)
  }

  private fun checkInitialized() {
    check(initialized) { "MultiModalDispatcher not initialized, call init() first" }
  }

  fun close() {
    structuredRenderer?.close()
  }
}
