package com.myagent.app.multimodal.hyperframes

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
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
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * HyperFrames 端侧视频渲染器 — WebView + MediaCodec。
 *
 * 核心流程：
 * 1. WebView 加载 HTML 模板（Web Animations API）
 * 2. 逐帧 Seek 动画 timeline → WebView 截图
 * 3. MediaCodec 硬件编码为 MP4
 *
 * 完全本地执行，零外部依赖，纯 Android 系统 API。
 *
 * v2.1 关键修复：Android 12+ WebView 必须通过 WindowManager.addView()
 * 挂载到实际窗口才能获得 ViewRootImpl。没有 ViewRootImpl 的 WebView
 * 只能渲染 CSS 背景，文字/图形/动画帧全部丢失。这是视频只有 84KB
 * 且帧内容是黑底+噪声的根本原因。
 */
class HyperFramesRenderer(
  private val app: Application,
) {
  companion object {
    private const val TAG = "HyperFrames"
    private const val DEFAULT_WIDTH = 854
    private const val DEFAULT_HEIGHT = 480
    private const val DEFAULT_FPS = 24
    private const val WEBVIEW_TIMEOUT_SEC = 30L
  }

  private var webView: WebView? = null
  private var container: FrameLayout? = null
  private var windowAttached = false
  private val mainHandler = Handler(Looper.getMainLooper())
  private val wm: WindowManager by lazy {
    app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  }

  /**
   * 渲染视频。
   *
   * @param prompt 视频主题
   * @param duration 视频时长（秒），默认 5
   * @param width 输出宽度（默认 854）
   * @param height 输出高度（默认 480）
   * @param fps 帧率（默认 24）
   * @param onProgress 进度回调（0.0 ~ 1.0）
   */
  suspend fun render(
    prompt: String,
    duration: Int = 5,
    width: Int = DEFAULT_WIDTH,
    height: Int = DEFAULT_HEIGHT,
    fps: Int = DEFAULT_FPS,
    onProgress: ((Float) -> Unit)? = null,
  ): File = withContext(Dispatchers.Main) {
    val videoDir = File(app.getExternalFilesDir(null) ?: app.cacheDir, "hyperframes").also { it.mkdirs() }
    val outputFile = File(videoDir, "hf_${System.currentTimeMillis()}.mp4")

    // 1. 创建 WebView（WindowManager 挂窗）
    val wv = createWebView(width, height)

    // 2. 加载 HTML
    val html = generateHtmlTemplate(prompt, duration, width, height)
    val loaded = loadHtmlAndWait(wv, html)
    if (!loaded) {
      Log.e(TAG, "WebView 加载超时")
      return@withContext outputFile
    }

    // 等 WebView 完成首次布局和绘制（挂窗后需要更长时间完成首帧）
    delay(1000)

    // 3. 逐帧渲染
    val totalFrames = duration * fps
    val encoder = BitmapToVideoEncoder(outputFile, width, height, fps)

    try {
      encoder.start()

      for (frameIndex in 0 until totalFrames) {
        // 同步 seek：等待 JS evaluateJavascript 回调完成
        seekAnimation(wv, frameIndex, fps)
        // JS 已执行完毕，给 WebView 一帧时间完成布局
        delay(33)
        val bitmap = captureFrame(wv, width, height)
        if (bitmap != null) {
          withContext(Dispatchers.Default) {
            encoder.encodeFrame(bitmap)
          }
          bitmap.recycle()
        } else {
          Log.w(TAG, "captureFrame returned null at frame $frameIndex")
        }
        onProgress?.invoke(frameIndex.toFloat() / totalFrames)
      }

      onProgress?.invoke(1.0f)
      Log.i(TAG, "渲染完成: ${outputFile.absolutePath} (${outputFile.length() / 1024}KB, ${width}x${height}@${fps}fps)")
    } catch (e: Exception) {
      Log.e(TAG, "渲染失败: ${e.message}", e)
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

  fun close() {
    mainHandler.post {
      cleanupWebView()
    }
  }

  // ── WebView 管理（WindowManager 挂窗方案） ──

  /**
   * 创建 WebView 并通过 WindowManager 挂载到窗口。
   *
   * 根因：Android 12+ 硬件加速 WebView 未 attach 到窗口时，draw(Canvas)
   * 只输出背景色，不渲染 CSS/文字/图形/动画。LAYER_TYPE_SOFTWARE +
   * 容器 attach 只是逻辑 attach，无法获得 ViewRootImpl。
   *
   * 修复：WindowManager.addView() 挂载到实际窗口 → WebView 获得
   * ViewRootImpl → 完整的 measure/layout/draw 渲染管线 → 所有 CSS
   * 内容（文字、图形、动画帧）正确渲染。
   */
  @Suppress("DEPRECATION")
  private fun createWebView(width: Int, height: Int): WebView {
    cleanupWebView()

    // 1. 创建容器
    val c = FrameLayout(app).apply {
      layoutParams = ViewGroup.LayoutParams(width, height)
    }
    container = c

    // 2. 创建 WebView（硬件加速，JS 必须开启用于动画）
    val wv = WebView(app).apply {
      layoutParams = ViewGroup.LayoutParams(width, height)

      settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = true
        blockNetworkLoads = true
      }
      webViewClient = WebViewClient()
    }

    c.addView(wv)
    webView = wv

    // 3. 关键：WindowManager 挂载到窗口
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
      Log.d(TAG, "WebView attached to window")
      // 挂窗后切软件层：硬件加速 WebView 的 draw(Canvas) 无法捕获文字/图形，
      // 必须先挂窗获得 ViewRootImpl，再切软件层，两者缺一不可
      wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    } catch (e: SecurityException) {
      Log.w(TAG, "WindowManager.addView denied: ${e.message}, fallback to software")
      windowAttached = false
      wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
      wv.layout(0, 0, width, height)
      wv.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
      )
    } catch (e: Exception) {
      Log.w(TAG, "WindowManager.addView failed: ${e.message}, fallback to software")
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
   * 清理 WebView 和容器，从窗口移除。
   */
  private fun cleanupWebView() {
    if (windowAttached) {
      try {
        container?.let { wm.removeView(it) }
      } catch (e: Exception) {
        Log.w(TAG, "WindowManager.removeView failed: ${e.message}")
      }
      windowAttached = false
    }

    try {
      container?.removeAllViews()
    } catch (_: Exception) {}

    try {
      webView?.destroy()
    } catch (_: Exception) {
      Log.w(TAG, "WebView destroy failed, continuing")
    }
    webView = null
    container = null
  }

  private suspend fun loadHtmlAndWait(wv: WebView, html: String): Boolean {
    return try {
      withTimeout(WEBVIEW_TIMEOUT_SEC * 1000L) {
        suspendCancellableCoroutine<Boolean> { cont ->
          val handler = Handler(Looper.getMainLooper())
          var resumed = false
          wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
              if (!resumed) {
                resumed = true
                cont.resume(true)
              }
            }
          }
          wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
          cont.invokeOnCancellation {
            if (!resumed) {
              resumed = true
              handler.post { wv.stopLoading() }
            }
          }
        }
      }
    } catch (_: Exception) {
      Log.e(TAG, "WebView 加载超时或取消")
      false
    }
  }

  /**
   * 同步 seek 动画到指定帧 — 等待 evaluateJavascript 回调完成后返回。
   *
   * 修复 v2.2：evaluateJavascript 是异步的，之前用 delay(16) 猜测等待时间，
   * 在低端设备上 JS 未执行完就截图，导致每帧都是 opacity:0 的初始状态。
   * 现在用 suspendCancellableCoroutine 等待回调，确保 JS 执行完毕。
   */
  private suspend fun seekAnimation(wv: WebView, frameIndex: Int, fps: Int) {
    val timeInSeconds = frameIndex.toFloat() / fps
    suspendCoroutine<Unit> { cont ->
      wv.evaluateJavascript("""
        (function() {
          if (window.__timelines) {
            window.__timelines.forEach(function(tl) {
              tl.pause();
              tl.seek($timeInSeconds);
            });
          }
        })();
      """.trimIndent()) { _ ->
        cont.resume(Unit)
      }
    }
  }

  /**
   * 捕获 WebView 当前帧为 Bitmap。
   * 窗口挂载成功时 WebView 有完整渲染管线，draw(Canvas) 即可正确输出。
   */
  private fun captureFrame(wv: WebView, targetWidth: Int, targetHeight: Int): Bitmap? {
    if (wv.width == 0 || wv.height == 0) return null
    val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.scale(
      targetWidth.toFloat() / wv.width.toFloat(),
      targetHeight.toFloat() / wv.height.toFloat()
    )
    wv.draw(canvas)
    return bitmap
  }

  // ── HTML 模板 ──

  private fun generateHtmlTemplate(
    prompt: String,
    duration: Int,
    width: Int,
    height: Int,
  ): String {
    val title = TextUtils.htmlEncode(prompt.take(20).replace("\n", " "))

    return """
<!DOCTYPE html>
<html><head><meta charset="UTF-8">
<meta name="viewport" content="width=$width,height=$height">
<style>
* { margin:0; padding:0; box-sizing:border-box; }
body {
  width:${width}px; height:${height}px;
  background: linear-gradient(135deg, #1a1a2e 0%, #16213e 50%, #0f3460 100%);
  overflow:hidden;
  font-family: sans-serif;
}
#stage { width:${width}px; height:${height}px; position:relative; display:flex;
  align-items:center; justify-content:center; flex-direction:column; }
#title {
  font-size:72px; font-weight:bold; color:white;
  text-align:center; text-shadow:0 4px 20px rgba(0,0,0,0.5);
  padding:0 60px; opacity:0;
}
#subtitle {
  font-size:36px; color:rgba(255,255,255,0.7);
  margin-top:30px; opacity:0;
}
.accent { position:absolute; border-radius:50%; opacity:0; }
#a1 { width:400px; height:400px; top:-100px; right:-100px;
  background:radial-gradient(circle, rgba(233,69,96,0.3), transparent); }
#a2 { width:300px; height:300px; bottom:-50px; left:-50px;
  background:radial-gradient(circle, rgba(72,149,239,0.3), transparent); }
</style>
</head><body>
<div id="stage">
  <div id="a1" class="accent"></div>
  <div id="a2" class="accent"></div>
  <h1 id="title">$title</h1>
  <p id="subtitle">Memento</p>
</div>
<script>
window.__timelines = [];
var tAnim = document.getElementById('title').animate([
  { opacity:0, transform:'translateY(40px) scale(0.8)' },
  { opacity:1, transform:'translateY(0) scale(1)', offset:0.15 },
  { opacity:1, transform:'translateY(0) scale(1.05)', offset:0.5 },
  { opacity:0, transform:'translateY(-20px) scale(1)', offset:0.85 },
  { opacity:0, transform:'translateY(-20px) scale(0.9)' }
], { duration:${duration * 1000}, fill:'both' });
tAnim.pause();
var sAnim = document.getElementById('subtitle').animate([
  { opacity:0, transform:'translateY(20px)' },
  { opacity:1, transform:'translateY(0)', offset:0.2 },
  { opacity:1, transform:'translateY(0)', offset:0.7 },
  { opacity:0, transform:'translateY(-10px)' }
], { duration:${duration * 1000}, fill:'both' });
sAnim.pause();
var a1Anim = document.getElementById('a1').animate([
  { opacity:0 }, { opacity:0.6, offset:0.1 }, { opacity:0.4, offset:0.5 }, { opacity:0 }
], { duration:${duration * 1000}, fill:'both' });
a1Anim.pause();
var a2Anim = document.getElementById('a2').animate([
  { opacity:0 }, { opacity:0.5, offset:0.15 }, { opacity:0.3, offset:0.6 }, { opacity:0 }
], { duration:${duration * 1000}, fill:'both' });
a2Anim.pause();
window.__timelines = [
  { seek:function(t){ tAnim.currentTime = t*1000; } },
  { seek:function(t){ sAnim.currentTime = t*1000; } },
  { seek:function(t){ a1Anim.currentTime = t*1000; } },
  { seek:function(t){ a2Anim.currentTime = t*1000; } },
];
</script>
</body></html>
    """.trimIndent()
  }
}

/**
 * Bitmap 帧序列 → MP4 视频编码器（MediaCodec 硬件编码）。
 *
 * 状态机：UNINITIALIZED → CONFIGURED → STARTED → STOPPED → RELEASED
 * 防御式设计：每个 release 步骤独立 try-catch，避免单步失败导致资源泄漏。
 */
class BitmapToVideoEncoder(
  private val outputFile: File,
  private val width: Int,
  private val height: Int,
  private val fps: Int = 24,
) {
  private enum class State { UNINITIALIZED, CONFIGURED, STARTED, STOPPED, RELEASED }

  private var mediaCodec: MediaCodec? = null
  private var mediaMuxer: MediaMuxer? = null
  private var trackIndex: Int = -1
  private var muxerStarted = false
  private var frameIndex = 0L
  private var state = State.UNINITIALIZED
  private var codecName: String = "unknown"

  fun start() {
    if (state != State.UNINITIALIZED) {
      Log.w("BitmapEncoder", "Already started, state=$state")
      return
    }

    val mime = MediaFormat.MIMETYPE_VIDEO_AVC
    val codecInfo = findAvailableCodec(mime, isEncoder = true)
    if (codecInfo == null) {
      Log.e("BitmapEncoder", "No available AVC encoder found on this device")
      throw IllegalStateException("设备不支持 H.264 编码，无法生成视频")
    }
    codecName = codecInfo.name
    Log.i("BitmapEncoder", "Using codec: $codecName")

    val format = MediaFormat.createVideoFormat(mime, width, height).apply {
      setInteger(MediaFormat.KEY_COLOR_FORMAT,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
      setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
      setInteger(MediaFormat.KEY_FRAME_RATE, fps)
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }

    try {
      mediaCodec = MediaCodec.createByCodecName(codecName)
      mediaCodec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      state = State.CONFIGURED
      mediaCodec!!.start()
      state = State.STARTED
      mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    } catch (e: Exception) {
      Log.e("BitmapEncoder", "Failed to start encoder: ${e.message}", e)
      safeRelease()
      throw e
    }
  }

  fun encodeFrame(bitmap: Bitmap) {
    if (state != State.STARTED) return
    val codec = mediaCodec ?: return
    try {
      val inputBufferIndex = codec.dequeueInputBuffer(10_000)
      if (inputBufferIndex < 0) return
      val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: return
      val yuvData = bitmapToYuv420SemiPlanar(bitmap)
      inputBuffer.clear()
      inputBuffer.put(yuvData)
      codec.queueInputBuffer(inputBufferIndex, 0, yuvData.size, frameIndex * 1_000_000 / fps, 0)
      frameIndex++
      drainEncoder()
    } catch (e: Exception) {
      Log.e("BitmapEncoder", "encodeFrame failed: ${e.message}", e)
    }
  }

  private fun drainEncoder(): Boolean {
    val codec = mediaCodec ?: return true
    val bufferInfo = MediaCodec.BufferInfo()
    try {
      while (true) {
        val idx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
        when {
          idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            try {
              if (muxerStarted) {
                Log.w("BitmapEncoder", "Unexpected format change after muxer started")
              }
              trackIndex = mediaMuxer!!.addTrack(codec.outputFormat)
              mediaMuxer!!.start()
              muxerStarted = true
            } catch (e: Exception) {
              Log.e("BitmapEncoder", "Muxer addTrack/start failed: ${e.message}", e)
            }
          }
          idx >= 0 -> {
            try {
              val buf = codec.getOutputBuffer(idx) ?: continue
              if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                && bufferInfo.size > 0 && muxerStarted
              ) {
                buf.position(bufferInfo.offset)
                buf.limit(bufferInfo.offset + bufferInfo.size)
                mediaMuxer!!.writeSampleData(trackIndex, buf, bufferInfo)
              }
            } catch (e: Exception) {
              Log.e("BitmapEncoder", "writeSampleData failed: ${e.message}", e)
            }
            val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
            codec.releaseOutputBuffer(idx, false)
            if (isEos) {
              Log.i("BitmapEncoder", "EOS received, total frames: $frameIndex")
              return true
            }
          }
          idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
        }
      }
    } catch (e: Exception) {
      Log.e("BitmapEncoder", "drainEncoder failed: ${e.message}", e)
    }
    return false
  }

  fun stop() {
    if (state != State.STARTED) {
      safeRelease()
      return
    }

    try {
      val codec = mediaCodec
      if (codec != null) {
        try {
          val inputBufferIndex = codec.dequeueInputBuffer(10_000)
          if (inputBufferIndex >= 0) {
            codec.queueInputBuffer(
              inputBufferIndex, 0, 0, 0,
              MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
          }
        } catch (e: Exception) {
          Log.e("BitmapEncoder", "EOS signal failed: ${e.message}", e)
        }
        val deadline = System.currentTimeMillis() + 2000
        var eosReceived = false
        while (!eosReceived && System.currentTimeMillis() < deadline) {
          eosReceived = drainEncoder()
        }
        if (!eosReceived) {
          Log.w("BitmapEncoder", "EOS not received within deadline, stopping anyway")
        }
      }
    } catch (e: Exception) {
      Log.e("BitmapEncoder", "stop drain failed: ${e.message}", e)
    } finally {
      state = State.STOPPED
      safeRelease()
    }
  }

  private fun safeRelease() {
    if (state == State.RELEASED) return

    try {
      mediaCodec?.stop()
    } catch (e: IllegalStateException) {
      Log.w("BitmapEncoder", "Codec stop: already released")
    } catch (e: Exception) {
      Log.e("BitmapEncoder", "Codec stop failed: ${e.message}", e)
    }

    try {
      mediaCodec?.release()
    } catch (e: Exception) {
      Log.e("BitmapEncoder", "Codec release failed: ${e.message}", e)
    }
    mediaCodec = null

    try {
      if (muxerStarted) {
        mediaMuxer?.stop()
      }
    } catch (e: Exception) {
      Log.e("BitmapEncoder", "Muxer stop failed: ${e.message}", e)
    }

    try {
      mediaMuxer?.release()
    } catch (e: Exception) {
      Log.e("BitmapEncoder", "Muxer release failed: ${e.message}", e)
    }
    mediaMuxer = null

    state = State.RELEASED
    Log.i("BitmapEncoder", "Encoder released (codec=$codecName, frames=$frameIndex)")
  }

  private fun findAvailableCodec(mime: String, isEncoder: Boolean): MediaCodecInfo? {
    return try {
      val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
      val hardware = codecList.codecInfos.filter { info ->
        info.isEncoder == isEncoder
          && info.supportedTypes.contains(mime)
          && !info.name.startsWith("OMX.google.")
          && !info.name.startsWith("c2.android.")
      }
      val software = codecList.codecInfos.filter { info ->
        info.isEncoder == isEncoder
          && info.supportedTypes.contains(mime)
          && (info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android."))
      }
      (hardware.firstOrNull() ?: software.firstOrNull())?.also {
        Log.i("BitmapEncoder", "Selected codec: ${it.name} (${if (hardware.contains(it)) "HW" else "SW"})")
      }
    } catch (e: Exception) {
      Log.e("BitmapEncoder", "Codec discovery failed: ${e.message}", e)
      null
    }
  }

  private fun bitmapToYuv420SemiPlanar(bitmap: Bitmap): ByteArray {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val ySize = w * h
    val nv12 = ByteArray(ySize + w * h / 2)
    var yi = 0
    var uvi = ySize
    for (j in 0 until h) {
      for (i in 0 until w) {
        val p = pixels[j * w + i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        nv12[yi++] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255).toByte()
        if (j % 2 == 0 && i % 2 == 0) {
          nv12[uvi++] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
          nv12[uvi++] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
        }
      }
    }
    return nv12
  }
}