package com.myagent.app.multimodal.hyperframes

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume

/**
 * HyperFrames 端侧视频渲染器 — WebView + MediaCodec。
 *
 * 核心流程：
 * 1. WebView 加载 HTML 模板（Web Animations API）
 * 2. 逐帧 Seek 动画 timeline → WebView.draw(Canvas) 截图
 * 3. MediaCodec 硬件编码为 MP4
 *
 * 完全本地执行，零外部依赖，纯 Android 系统 API。
 *
 * v2.0：默认最低画质 854x480@24fps，用户可在设置中切换。
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
  private val mainHandler = Handler(Looper.getMainLooper())

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

    // 1. 创建 WebView
    val wv = createWebView(width, height)

    // 2. 加载 HTML
    val html = generateHtmlTemplate(prompt, duration, width, height)
    val loaded = loadHtmlAndWait(wv, html)
    if (!loaded) {
      Log.e(TAG, "WebView 加载超时")
      return@withContext outputFile
    }

    // 3. 逐帧渲染
    val totalFrames = duration * fps
    val encoder = BitmapToVideoEncoder(outputFile, width, height, fps)

    try {
      encoder.start()

      for (frameIndex in 0 until totalFrames) {
        seekAnimation(wv, frameIndex, fps)
        delay(16)
        val bitmap = captureFrame(wv, width, height)
        if (bitmap != null) {
          encoder.encodeFrame(bitmap)
          bitmap.recycle()
        }
        onProgress?.invoke(frameIndex.toFloat() / totalFrames)
      }

      onProgress?.invoke(1.0f)
      Log.i(TAG, "渲染完成: ${outputFile.absolutePath} (${width}x${height}@${fps}fps)")
    } catch (e: Exception) {
      Log.e(TAG, "渲染失败: ${e.message}", e)
    } finally {
      // 确保无论成功失败都释放资源
      try {
        encoder.stop()
      } catch (e: Exception) {
        Log.e(TAG, "Encoder stop failed: ${e.message}", e)
      }
      try {
        wv.destroy()
      } catch (e: Exception) {
        Log.w(TAG, "WebView destroy failed: ${e.message}")
      }
      webView = null
    }

    outputFile
  }

  fun close() {
    mainHandler.post {
      webView?.destroy()
      webView = null
    }
  }

  // ── WebView 管理 ──

  private fun createWebView(width: Int, height: Int): WebView {
    try {
      webView?.destroy()
    } catch (_: Exception) {
      Log.w(TAG, "WebView destroy failed, continuing")
    }
    webView = null

    val wv = WebView(app).apply {
      layout(0, 0, width, height)
      measure(
        android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
        android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY)
      )
      settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = true
        blockNetworkLoads = true
        // setRenderPriority 已在 API 26 弃用、API 31 移除，minSdk=31 无需设置
      }
      webViewClient = WebViewClient()
    }
    webView = wv
    return wv
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

  private fun seekAnimation(wv: WebView, frameIndex: Int, fps: Int) {
    val timeInSeconds = frameIndex.toFloat() / fps
    wv.evaluateJavascript("""
      (function() {
        if (window.__timelines) {
          window.__timelines.forEach(function(tl) {
            tl.pause();
            tl.seek($timeInSeconds);
          });
        }
      })();
    """.trimIndent(), null)
  }

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
  font-family: -apple-system, 'Noto Sans CJK SC', 'PingFang SC', 'Microsoft YaHei', sans-serif;
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

    // 检查编解码器可用性，优先硬解，失败回退软解
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

  private fun drainEncoder() {
    val codec = mediaCodec ?: return
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
            codec.releaseOutputBuffer(idx, false)
          }
          idx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
        }
      }
    } catch (e: Exception) {
      Log.e("BitmapEncoder", "drainEncoder failed: ${e.message}", e)
    }
  }

  /**
   * 停止编码 — 发送 EOS 信号，等待 codec 完成最后帧输出，然后释放资源。
   */
  fun stop() {
    if (state != State.STARTED) {
      safeRelease()
      return
    }

    try {
      // 发送 EOS（End of Stream）信号，让 codec 完成最后帧编码
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
        // 等待 EOS 输出
        drainEncoder()
      }
    } catch (e: Exception) {
      Log.e("BitmapEncoder", "stop drain failed: ${e.message}", e)
    } finally {
      state = State.STOPPED
      safeRelease()
    }
  }

  /**
   * 防御式释放 — 每个步骤独立 try-catch，确保单步失败不影响后续释放。
   */
  private fun safeRelease() {
    if (state == State.RELEASED) return

    // 1. 停止 codec
    try {
      mediaCodec?.stop()
    } catch (e: IllegalStateException) {
      // 已处于 released 状态，忽略
      Log.w("BitmapEncoder", "Codec stop: already released")
    } catch (e: Exception) {
      Log.e("BitmapEncoder", "Codec stop failed: ${e.message}", e)
    }

    // 2. 释放 codec
    try {
      mediaCodec?.release()
    } catch (e: Exception) {
      Log.e("BitmapEncoder", "Codec release failed: ${e.message}", e)
    }
    mediaCodec = null

    // 3. 停止 muxer（仅在有数据写入时）
    try {
      if (muxerStarted) {
        mediaMuxer?.stop()
      }
    } catch (e: Exception) {
      Log.e("BitmapEncoder", "Muxer stop failed: ${e.message}", e)
    }

    // 4. 释放 muxer
    try {
      mediaMuxer?.release()
    } catch (e: Exception) {
      Log.e("BitmapEncoder", "Muxer release failed: ${e.message}", e)
    }
    mediaMuxer = null

    state = State.RELEASED
    Log.i("BitmapEncoder", "Encoder released (codec=$codecName, frames=$frameIndex)")
  }

  // ── 编解码器查询 ──

  private fun findAvailableCodec(mime: String, isEncoder: Boolean): MediaCodecInfo? {
    return try {
      val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
      // 优先硬件编解码器
      val hardware = codecList.codecInfos.filter { info ->
        info.isEncoder == isEncoder
          && info.supportedTypes.contains(mime)
          && !info.name.startsWith("OMX.google.") // 排除软解
          && !info.name.startsWith("c2.android.") // 排除 Android 软解
      }
      // 回退软件编解码器
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