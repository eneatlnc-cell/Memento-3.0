package com.myagent.app.multimodal

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * StructuredRenderer — v3.2 多模态生成架构的核心渲染管线。
 *
 * 架构定位：
 *   模型输出 SVG（结构化描述）→ 本管线渲染为 Bitmap/MP4
 *
 * 与旧 DreamLite/HyperFrames 的根本区别：
 *   - 旧：模型输出主题词 → 关键词匹配选预设模板 → 截图（伪多模态）
 *   - 新：模型输出 SVG → 直接渲染该 SVG → 截图（真·结构化渲染）
 *
 * 复用 HyperFrames 已验证的技术：
 *   - WebView + WindowManager.addView 挂窗（解决 Android 12+ 渲染问题）
 *   - LAYER_TYPE_SOFTWARE 切换（draw(Canvas) 可正确输出）
 *   - captureFrame 截图机制
 *   - BitmapToVideoEncoder（MediaCodec H.264 编码）
 *
 * 渲染能力：
 *   - SVG 支持所有基础元素：rect/circle/ellipse/path/polygon/line/linearGradient/radialGradient/text
 *   - WebView 原生 SVG 渲染，支持高精细高细腻图形
 *   - 输出尺寸：512×512（图片）/ 512×512@24fps（视频）
 */
class StructuredRenderer(
  private val app: Application,
) {
  companion object {
    private const val TAG = "StructuredRenderer"
    private const val IMAGE_SIZE = 512
    private const val VIDEO_FPS = 24
    private const val WEBVIEW_TIMEOUT_SEC = 15L
  }

  private val mainHandler = Handler(Looper.getMainLooper())
  private val wm: WindowManager by lazy {
    app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  }

  /**
   * 渲染单张图片：SVG → WebView → Bitmap。
   *
   * @param svg 模型输出的合法 SVG 字符串
   * @return 渲染后的 Bitmap（IMAGE_SIZE × IMAGE_SIZE）
   * @throws IllegalArgumentException SVG 非法或为空
   */
  suspend fun renderImage(svg: String): Bitmap = withContext(Dispatchers.Main) {
    if (svg.isBlank() || !svg.contains("<svg", ignoreCase = true)) {
      throw IllegalArgumentException("SVG 内容为空或非法")
    }

    val html = wrapSvgInHtml(svg, IMAGE_SIZE, IMAGE_SIZE)
    val wv = createWebView(IMAGE_SIZE, IMAGE_SIZE)
    try {
      loadHtmlAndWait(wv, html)
      // 等 WebView 完成布局 + 首次绘制
      delay(800)
      captureFrame(wv, IMAGE_SIZE, IMAGE_SIZE)
        ?: throw IllegalStateException("WebView 截图失败，SVG 可能包含不支持的元素")
    } finally {
      cleanupWebView()
    }
  }

  /**
   * 渲染视频：SVG 帧序列 → 逐帧渲染 → MediaCodec → MP4。
   *
   * @param frames SVG 帧列表（每帧一个 SVG 字符串）
   * @param onProgress 进度回调（0.0 ~ 1.0）
   * @return 生成的 MP4 文件
   */
  suspend fun renderVideo(
    frames: List<String>,
    onProgress: ((Float) -> Unit)? = null,
  ): File = withContext(Dispatchers.Main) {
    if (frames.isEmpty()) {
      throw IllegalArgumentException("帧列表为空")
    }

    val videoDir = File(app.getExternalFilesDir(null) ?: app.cacheDir, "structured").also { it.mkdirs() }
    val outputFile = File(videoDir, "vid_${System.currentTimeMillis()}.mp4")
    val encoder = BitmapToVideoEncoder(outputFile, IMAGE_SIZE, IMAGE_SIZE, VIDEO_FPS)

    val wv = createWebView(IMAGE_SIZE, IMAGE_SIZE)
    try {
      encoder.start()
      var renderedCount = 0

      for ((index, svg) in frames.withIndex()) {
        if (svg.isBlank()) {
          Log.w(TAG, "Frame $index SVG is blank, skipping")
          continue
        }

        val html = wrapSvgInHtml(svg, IMAGE_SIZE, IMAGE_SIZE)
        loadHtmlAndWait(wv, html)
        delay(150) // 给 WebView 时间完成 SVG 渲染

        val bitmap = captureFrame(wv, IMAGE_SIZE, IMAGE_SIZE)
        if (bitmap != null) {
          withContext(Dispatchers.Default) {
            encoder.encodeFrame(bitmap)
          }
          bitmap.recycle()
          renderedCount++
        } else {
          Log.w(TAG, "Frame $index capture failed, using blank frame")
          // 用黑帧填充，保证帧数一致（视频时序不乱）
          val blank = Bitmap.createBitmap(IMAGE_SIZE, IMAGE_SIZE, Bitmap.Config.ARGB_8888)
          val canvas = Canvas(blank)
          canvas.drawColor(0xFF000000.toInt())
          withContext(Dispatchers.Default) {
            encoder.encodeFrame(blank)
          }
          blank.recycle()
          renderedCount++
        }

        onProgress?.invoke((index + 1).toFloat() / frames.size)
      }

      Log.i(TAG, "Video rendered: $renderedCount/${frames.size} frames → ${outputFile.absolutePath}")
      if (renderedCount == 0) {
        throw IllegalStateException("所有帧渲染失败，未生成有效视频")
      }
    } finally {
      try {
        encoder.stop()
      } catch (e: Exception) {
        Log.e(TAG, "Encoder stop failed: ${e.message}", e)
      }
      cleanupWebView()
    }

    outputFile
  }

  // ── WebView 管理（复用 HyperFrames 已验证方案） ──

  private var webView: WebView? = null
  private var container: FrameLayout? = null
  private var windowAttached = false

  @Suppress("DEPRECATION")
  private fun createWebView(width: Int, height: Int): WebView {
    cleanupWebView()

    val c = FrameLayout(app).apply {
      layoutParams = ViewGroup.LayoutParams(width, height)
    }
    container = c

    val wv = WebView(app).apply {
      layoutParams = ViewGroup.LayoutParams(width, height)
      settings.apply {
        javaScriptEnabled = false // SVG 渲染不需要 JS
        domStorageEnabled = false
        allowFileAccess = false
        blockNetworkLoads = true
      }
      webViewClient = WebViewClient()
    }

    c.addView(wv)
    webView = wv

    // 关键：WindowManager 挂载到窗口（Android 12+ 必需，否则 draw(Canvas) 只输出背景）
    try {
      val type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
      val params = WindowManager.LayoutParams(
        width, height,
        type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
          or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
          or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
      ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        x = 0
        y = 0
      }
      wm.addView(c, params)
      windowAttached = true
      // 挂窗后切软件层：硬件加速 WebView draw(Canvas) 无法捕获内容
      wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    } catch (e: SecurityException) {
      Log.w(TAG, "WindowManager denied: ${e.message}, fallback to software layer")
      windowAttached = false
      wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
      wv.layout(0, 0, width, height)
      wv.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
      )
    } catch (e: Exception) {
      Log.w(TAG, "WindowManager failed: ${e.message}, fallback to software layer")
      windowAttached = false
      wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
      wv.layout(0, 0, width, height)
      wv.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
      )
    }

    return wv
  }

  private fun cleanupWebView() {
    if (windowAttached) {
      try { container?.let { wm.removeView(it) } } catch (e: Exception) {
        Log.w(TAG, "removeView failed: ${e.message}")
      }
      windowAttached = false
    }
    try { container?.removeAllViews() } catch (_: Exception) {}
    try { webView?.destroy() } catch (_: Exception) {}
    webView = null
    container = null
  }

  private suspend fun loadHtmlAndWait(wv: WebView, html: String) {
    var loaded = false
    wv.webViewClient = object : WebViewClient() {
      override fun onPageFinished(view: WebView?, url: String?) {
        loaded = true
      }
    }
    wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    withTimeout(WEBVIEW_TIMEOUT_SEC * 1000L) {
      while (!loaded) delay(50)
    }
  }

  private fun captureFrame(wv: WebView, targetWidth: Int, targetHeight: Int): Bitmap? {
    if (wv.width == 0 || wv.height == 0) {
      wv.layout(0, 0, targetWidth, targetHeight)
      wv.measure(
        View.MeasureSpec.makeMeasureSpec(targetWidth, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(targetHeight, View.MeasureSpec.EXACTLY),
      )
    }
    val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.scale(
      targetWidth.toFloat() / wv.width.coerceAtLeast(1).toFloat(),
      targetHeight.toFloat() / wv.height.coerceAtLeast(1).toFloat(),
    )
    wv.draw(canvas)
    return bitmap
  }

  /**
   * 将 SVG 包装为完整 HTML 页面。
   *
   * 关键：SVG 直接内嵌，不通过 <img> 加载，确保 draw(Canvas) 能捕获所有内容。
   * body 设为 0 margin + 隐藏 overflow，避免滚动条干扰截图。
   */
  private fun wrapSvgInHtml(svg: String, width: Int, height: Int): String {
    return """
<!DOCTYPE html>
<html><head><meta charset="UTF-8">
<meta name="viewport" content="width=$width,height=$height">
<style>
* { margin:0; padding:0; box-sizing:border-box; }
body {
  width:${width}px; height:${height}px;
  overflow:hidden;
  background:#FFFFFF;
}
svg {
  width:${width}px;
  height:${height}px;
  display:block;
}
</style>
</head><body>
$svg
</body></html>
    """.trimIndent()
  }

  fun close() {
    mainHandler.post { cleanupWebView() }
  }
}
