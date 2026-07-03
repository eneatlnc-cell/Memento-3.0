package com.myagent.app.multimodal.dreamlite

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.*

/**
 * 图像生成器 — HTML 渲染方案（与 HyperFrames 共用 WebView 渲染管线）。
 *
 * 用 HTML/CSS 根据文本描述生成视觉图像，WebView 渲染后截图输出。
 * 零依赖、零模型文件、零网络请求，完全端侧运行。
 *
 * 核心流程：
 * 1. 解析 prompt 关键词 → 选择视觉主题（色彩、形状、布局）
 * 2. 生成自包含 HTML 页面（内联 CSS + 渐变/阴影/图形）
 * 3. WebView 加载 HTML → WindowManager 临时挂窗 → PixelCopy 截图
 * 4. 返回 Bitmap
 *
 * 关键修复 v2.1：Android 12+ WebView 必须通过 WindowManager.addView()
 * 挂载到实际窗口才能获得 ViewRootImpl，否则 CSS 背景能渲染但文字/图形
 * 全部丢失。LAYER_TYPE_SOFTWARE + 容器 attach 只是逻辑 attach，不够。
 */
class DreamLiteImageGenerator(
  private val app: Application,
) {
  companion object {
    private const val TAG = "DreamLiteGen"
    private const val DEFAULT_WIDTH = 1024
    private const val DEFAULT_HEIGHT = 1024
    private const val WEBVIEW_TIMEOUT_SEC = 15L
  }

  private var webView: WebView? = null
  private var container: FrameLayout? = null
  private var windowAttached = false
  private val mainHandler = Handler(Looper.getMainLooper())
  private val wm: WindowManager by lazy {
    app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  }

  /**
   * 文生图。根据 prompt 生成 HTML 视觉图像并截图。
   *
   * @param prompt 文本描述
   * @param style 风格（如 "minimal", "vibrant", "dark", "warm"）
   * @return 生成的 Bitmap
   */
  suspend fun generate(
    prompt: String,
    style: String? = null,
  ): Bitmap = withContext(Dispatchers.Main) {
    val html = generateHtmlForImage(prompt, style, DEFAULT_WIDTH, DEFAULT_HEIGHT)
    val wv = getOrCreateWebView(DEFAULT_WIDTH, DEFAULT_HEIGHT)
    loadHtmlAndWait(wv, html)
    // onPageFinished 后仍需等 WebView 完成布局 + 首次绘制（挂窗后需要更长时间）
    delay(1200)
    val bitmap = captureFrame(wv, DEFAULT_WIDTH, DEFAULT_HEIGHT)
      ?: createFallbackBitmap(prompt)
    cleanupWebView()
    bitmap
  }

  /**
   * 图片编辑。将原始图片嵌入 HTML 做滤镜/叠加效果。
   */
  suspend fun edit(
    prompt: String,
    sourceImage: Bitmap,
  ): Bitmap = withContext(Dispatchers.Main) {
    val html = generateEditHtml(prompt, DEFAULT_WIDTH, DEFAULT_HEIGHT)
    val wv = getOrCreateWebView(DEFAULT_WIDTH, DEFAULT_HEIGHT)
    loadHtmlAndWait(wv, html)
    delay(1200)
    val result = captureFrame(wv, DEFAULT_WIDTH, DEFAULT_HEIGHT)
      ?: sourceImage
    cleanupWebView()
    result
  }

  fun close() {
    mainHandler.post {
      cleanupWebView()
    }
  }

  // ── WebView 管理（WindowManager 挂窗方案） ──

  /**
   * 创建 WebView 并通过 WindowManager 临时挂载到窗口。
   *
   * 根因：Android 12+ 硬件加速在未 attach 到窗口的 WebView 上调用 draw(Canvas)
   * 只输出背景色，不渲染 CSS/文字/图形。LAYER_TYPE_SOFTWARE + 容器 attach
   * 也只是逻辑 attach，WebView 没有 ViewRootImpl，无法完成完整渲染管线。
   *
   * 修复：通过 WindowManager.addView() 将 WebView 容器挂载到实际窗口，
   * WebView 获得 ViewRootImpl → 触发完整的 measure/layout/draw 循环 →
   * 文字/图形/阴影全部正确渲染。
   *
   * 使用 TYPE_APPLICATION_PANEL（API<30）或 TYPE_APPLICATION（API≥30），
   * 不需要 SYSTEM_ALERT_WINDOW 权限。
   */
  @Suppress("DEPRECATION")
  private fun getOrCreateWebView(width: Int, height: Int): WebView {
    cleanupWebView()

    // 1. 创建隐藏容器
    val c = FrameLayout(app).apply {
      layoutParams = ViewGroup.LayoutParams(width, height)
    }
    container = c

    // 2. 创建 WebView
    val wv = WebView(app).apply {
      layoutParams = ViewGroup.LayoutParams(width, height)

      settings.apply {
        javaScriptEnabled = false // 图片生成不需要 JS
        domStorageEnabled = false
        allowFileAccess = false
        blockNetworkLoads = true
      }
      webViewClient = WebViewClient()
    }

    c.addView(wv)
    webView = wv

    // 3. 关键：通过 WindowManager 挂载到实际窗口
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
      Log.d(TAG, "WebView attached to window via WindowManager")
      // 挂窗后切软件层：硬件加速 WebView 的 draw(Canvas) 无法捕获文字/图形
      wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    } catch (e: SecurityException) {
      Log.w(TAG, "WindowManager.addView denied: ${e.message}, falling back to software layer")
      windowAttached = false
      // 回退方案：软件层 + 强制 layout（效果有限但至少不崩溃）
      wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
      wv.layout(0, 0, width, height)
      wv.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
      )
    } catch (e: Exception) {
      Log.w(TAG, "WindowManager.addView failed: ${e.message}, falling back to software layer")
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

  /**
   * 清理 WebView 和容器，从窗口移除，防止内存泄漏。
   */
  private fun cleanupWebView() {
    // 1. 从 WindowManager 移除容器
    if (windowAttached) {
      try {
        container?.let { wm.removeView(it) }
      } catch (e: Exception) {
        Log.w(TAG, "WindowManager.removeView failed: ${e.message}")
      }
      windowAttached = false
    }

    // 2. 清理容器内子视图
    try {
      container?.removeAllViews()
    } catch (_: Exception) {}

    // 3. 销毁 WebView
    try {
      webView?.destroy()
    } catch (_: Exception) {
      Log.w(TAG, "WebView destroy failed, continuing")
    }
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
      while (!loaded) {
        delay(100)
      }
    }
  }

  /**
   * 捕获 WebView 内容为 Bitmap。
   *
   * 修复 v2.1：WebView 通过 WindowManager 挂载到窗口后有完整 ViewRootImpl，
   * draw(Canvas) 即可正确输出所有 CSS 内容（文字、图形、阴影等）。
   */
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

  // ── HTML 模板生成 ──

  /**
   * 根据 prompt 关键词选择视觉主题并生成 HTML。
   */
  private fun generateHtmlForImage(
    prompt: String,
    style: String?,
    width: Int,
    height: Int,
  ): String {
    val theme = pickTheme(prompt, style)
    // H-M6 修复：prompt 来自 LLM 输出，未 HTML 转义可注入 </h1><style>...</style>
    val title = TextUtils.htmlEncode(formatTitle(prompt))

    return """
<!DOCTYPE html>
<html><head><meta charset="UTF-8">
<meta name="viewport" content="width=$width,height=$height">
<style>
* { margin:0; padding:0; box-sizing:border-box; }
body {
  width:${width}px; height:${height}px;
  font-family: sans-serif;
  overflow:hidden;
  display:flex; align-items:center; justify-content:center;
}
#canvas {
  width:${width}px; height:${height}px;
  position:relative;
  background: ${theme.bg};
  display:flex; flex-direction:column;
  align-items:center; justify-content:center;
}
#title {
  font-size:${theme.titleSize}px; font-weight:${theme.titleWeight};
  color:${theme.textColor};
  text-align:center; padding:0 80px;
  line-height:1.3;
  ${theme.textShadow}
}
#subtitle {
  font-size:${theme.subSize}px; color:${theme.subColor};
  margin-top:${theme.gap}px;
  opacity:0.8;
}
.ornament {
  position:absolute;
  border-radius:50%;
  ${theme.ornamentStyle}
}
#o1 { width:${(width * 0.45).toInt()}px; height:${(width * 0.45).toInt()}px;
      top:${(height * -0.12).toInt()}px; right:${(width * -0.12).toInt()}px; }
#o2 { width:${(width * 0.28).toInt()}px; height:${(width * 0.28).toInt()}px;
      bottom:${(height * -0.06).toInt()}px; left:${(width * -0.06).toInt()}px; }
</style>
</head><body>
<div id="canvas">
  <div id="o1" class="ornament"></div>
  <div id="o2" class="ornament"></div>
  <h1 id="title">$title</h1>
  <p id="subtitle">Memento</p>
</div>
</body></html>
    """.trimIndent()
  }

  private fun generateEditHtml(
    prompt: String,
    width: Int,
    height: Int,
  ): String = generateHtmlForImage(prompt, "edit", width, height)

  // ── 主题引擎 ──

  private data class Theme(
    val bg: String,
    val textColor: String,
    val titleSize: Int,
    val titleWeight: Int,
    val subSize: Int,
    val subColor: String,
    val gap: Int,
    val textShadow: String,
    val ornamentStyle: String,
  )

  private fun pickTheme(prompt: String, style: String?): Theme {
    val lower = prompt.lowercase()

    if (style == "minimal" || style == "edit") {
      return Theme(
        bg = "#ffffff",
        textColor = "#1a1a2e",
        titleSize = 64, titleWeight = 300, subSize = 28,
        subColor = "#888888", gap = 24,
        textShadow = "",
        ornamentStyle = "background:radial-gradient(circle, rgba(0,0,0,0.04),transparent);",
      )
    }
    if (style == "dark") return darkTheme()
    if (style == "warm") return warmTheme()
    if (style == "vibrant") return vibrantTheme()

    return when {
      lower.contains("日") || lower.contains("sun") || lower.contains("光") ||
      lower.contains("黎明") || lower.contains("dawn") || lower.contains("日出") ||
      lower.contains("sunrise") || lower.contains("阳光") -> warmTheme()

      lower.contains("夜") || lower.contains("night") || lower.contains("dark") ||
      lower.contains("星") || lower.contains("star") || lower.contains("黑") ||
      lower.contains("月") || lower.contains("moon") || lower.contains("宇宙") ||
      lower.contains("space") -> darkTheme()

      lower.contains("海") || lower.contains("sea") || lower.contains("ocean") ||
      lower.contains("水") || lower.contains("water") || lower.contains("蓝") ||
      lower.contains("blue") || lower.contains("湖") || lower.contains("lake") -> oceanTheme()

      lower.contains("山") || lower.contains("mountain") || lower.contains("森林") ||
      lower.contains("forest") || lower.contains("树") || lower.contains("tree") ||
      lower.contains("自然") || lower.contains("nature") || lower.contains("绿") ||
      lower.contains("green") -> natureTheme()

      lower.contains("花") || lower.contains("flower") || lower.contains("粉") ||
      lower.contains("pink") || lower.contains("樱") || lower.contains("玫瑰") ||
      lower.contains("rose") -> pinkTheme()

      lower.contains("猫") || lower.contains("cat") || lower.contains("狗") ||
      lower.contains("dog") || lower.contains("动物") || lower.contains("animal") ||
      lower.contains("宠物") || lower.contains("pet") -> warmTheme()

      lower.contains("科技") || lower.contains("tech") || lower.contains("未来") ||
      lower.contains("future") || lower.contains("赛博") || lower.contains("cyber") ||
      lower.contains("ai") || lower.contains("数字") -> cyberTheme()

      else -> vibrantTheme()
    }
  }

  private fun darkTheme() = Theme(
    bg = "linear-gradient(135deg, #0a0a1a 0%, #1a1040 50%, #0d1b2a 100%)",
    textColor = "#e0e0ff",
    titleSize = 72, titleWeight = 700, subSize = 32,
    subColor = "rgba(200,200,255,0.6)", gap = 36,
    textShadow = "0 4px 30px rgba(100,100,255,0.3)",
    ornamentStyle = "background:radial-gradient(circle, rgba(100,80,255,0.25),transparent);",
  )

  private fun warmTheme() = Theme(
    bg = "linear-gradient(135deg, #ff6b35 0%, #f7c948 40%, #ff8c42 100%)",
    textColor = "#ffffff",
    titleSize = 68, titleWeight = 700, subSize = 30,
    subColor = "rgba(255,255,255,0.8)", gap = 32,
    textShadow = "0 4px 24px rgba(180,60,0,0.3)",
    ornamentStyle = "background:radial-gradient(circle, rgba(255,255,255,0.2),transparent);",
  )

  private fun oceanTheme() = Theme(
    bg = "linear-gradient(135deg, #0077b6 0%, #00b4d8 40%, #90e0ef 100%)",
    textColor = "#ffffff",
    titleSize = 68, titleWeight = 700, subSize = 30,
    subColor = "rgba(255,255,255,0.85)", gap = 32,
    textShadow = "0 4px 24px rgba(0,60,120,0.3)",
    ornamentStyle = "background:radial-gradient(circle, rgba(255,255,255,0.2),transparent);",
  )

  private fun natureTheme() = Theme(
    bg = "linear-gradient(135deg, #2d6a4f 0%, #52b788 40%, #95d5b2 100%)",
    textColor = "#ffffff",
    titleSize = 68, titleWeight = 700, subSize = 30,
    subColor = "rgba(255,255,255,0.85)", gap = 32,
    textShadow = "0 4px 24px rgba(20,50,30,0.3)",
    ornamentStyle = "background:radial-gradient(circle, rgba(255,255,255,0.15),transparent);",
  )

  private fun pinkTheme() = Theme(
    bg = "linear-gradient(135deg, #ff6b9d 0%, #c44d7a 40%, #f8a4c8 100%)",
    textColor = "#ffffff",
    titleSize = 66, titleWeight = 600, subSize = 30,
    subColor = "rgba(255,255,255,0.85)", gap = 32,
    textShadow = "0 4px 24px rgba(140,30,60,0.3)",
    ornamentStyle = "background:radial-gradient(circle, rgba(255,255,255,0.2),transparent);",
  )

  private fun cyberTheme() = Theme(
    bg = "linear-gradient(135deg, #0d0221 0%, #150578 30%, #3a0ca3 60%, #0d0221 100%)",
    textColor = "#00ff88",
    titleSize = 64, titleWeight = 700, subSize = 28,
    subColor = "rgba(0,255,136,0.5)", gap = 36,
    textShadow = "0 0 40px rgba(0,255,136,0.4), 0 0 80px rgba(0,255,136,0.2)",
    ornamentStyle = "background:radial-gradient(circle, rgba(0,255,136,0.15),transparent);",
  )

  private fun vibrantTheme() = Theme(
    bg = "linear-gradient(135deg, #6c3ce0 0%, #e94560 50%, #f5a623 100%)",
    textColor = "#ffffff",
    titleSize = 68, titleWeight = 700, subSize = 30,
    subColor = "rgba(255,255,255,0.85)", gap = 32,
    textShadow = "0 4px 24px rgba(60,20,100,0.3)",
    ornamentStyle = "background:radial-gradient(circle, rgba(255,255,255,0.2),transparent);",
  )

  private fun formatTitle(prompt: String): String {
    val cleaned = prompt.replace("\n", " ").replace("\r", "").trim()
    return if (cleaned.length <= 40) cleaned
    else cleaned.take(40) + "…"
  }

  private fun createFallbackBitmap(prompt: String): Bitmap {
    val bitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(0xFF1A1A2E.toInt())
    val paint = android.graphics.Paint().apply {
      color = 0xFFFFFFFF.toInt()
      textSize = 22f
      textAlign = android.graphics.Paint.Align.CENTER
      isAntiAlias = true
    }
    canvas.drawText(formatTitle(prompt), 512f, 500f, paint)
    canvas.drawText("Memento · 端侧渲染", 512f, 580f, paint)
    return bitmap
  }
}