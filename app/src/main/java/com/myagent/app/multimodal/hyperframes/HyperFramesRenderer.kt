package com.myagent.app.multimodal.hyperframes

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * HyperFrames 端侧视频渲染器 — WebView + MediaCodec。
 *
 * 核心流程：
 * 1. WebView 加载 HTML 模板（Web Animations API）
 * 2. 逐帧 Seek 动画 timeline → WebView.draw(Canvas) 截图
 * 3. MediaCodec 硬件编码为 MP4
 *
 * 完全本地执行，零外部依赖，纯 Android 系统 API。
 */
class HyperFramesRenderer(
  private val app: Application,
) {
  companion object {
    private const val TAG = "HyperFrames"
    private const val DEFAULT_FPS = 30
    private const val DEFAULT_WIDTH = 1080
    private const val DEFAULT_HEIGHT = 1920
    private const val WEBVIEW_TIMEOUT_SEC = 30L
  }

  private var webView: WebView? = null
  private val mainHandler = Handler(Looper.getMainLooper())

  /**
   * 渲染视频。
   */
  suspend fun render(
    prompt: String,
    duration: Int = 5,
    width: Int = DEFAULT_WIDTH,
    height: Int = DEFAULT_HEIGHT,
    onProgress: ((Float) -> Unit)? = null,
  ): File = withContext(Dispatchers.Main) {
    val cacheDir = File(app.cacheDir, "hyperframes").also { it.mkdirs() }
    val outputFile = File(cacheDir, "hf_${System.currentTimeMillis()}.mp4")

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
    val totalFrames = duration * DEFAULT_FPS
    val encoder = BitmapToVideoEncoder(outputFile, width, height, DEFAULT_FPS)
    encoder.start()

    for (frameIndex in 0 until totalFrames) {
      seekAnimation(wv, frameIndex, DEFAULT_FPS)
      delay(16)
      val bitmap = captureFrame(wv, width, height)
      if (bitmap != null) {
        encoder.encodeFrame(bitmap)
        bitmap.recycle()
      }
      onProgress?.invoke(frameIndex.toFloat() / totalFrames)
    }

    encoder.stop()
    webView = null
    onProgress?.invoke(1.0f)

    Log.i(TAG, "渲染完成: ${outputFile.absolutePath}")
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
    webView?.destroy()
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
        setRenderPriority(android.webkit.WebSettings.RenderPriority.HIGH)
      }
      webViewClient = WebViewClient()
    }
    webView = wv
    return wv
  }

  private fun loadHtmlAndWait(wv: WebView, html: String): Boolean {
    val latch = CountDownLatch(1)
    var loaded = false
    wv.webViewClient = object : WebViewClient() {
      override fun onPageFinished(view: WebView?, url: String?) {
        loaded = true
        latch.countDown()
      }
    }
    wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    return try {
      latch.await(WEBVIEW_TIMEOUT_SEC, TimeUnit.SECONDS)
      loaded
    } catch (_: InterruptedException) {
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
    val title = prompt.take(20).replace("\n", " ")

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
  <p id="subtitle">灵机 · MyAgent</p>
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
 */
class BitmapToVideoEncoder(
  private val outputFile: File,
  private val width: Int,
  private val height: Int,
  private val fps: Int = 30,
) {
  private var mediaCodec: MediaCodec? = null
  private var mediaMuxer: MediaMuxer? = null
  private var trackIndex: Int = -1
  private var muxerStarted = false
  private var frameIndex = 0L

  fun start() {
    val format = MediaFormat.createVideoFormat(
      MediaFormat.MIMETYPE_VIDEO_AVC, width, height
    ).apply {
      setInteger(MediaFormat.KEY_COLOR_FORMAT,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
      setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
      setInteger(MediaFormat.KEY_FRAME_RATE, fps)
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }
    mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    mediaCodec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    mediaCodec!!.start()
    mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
  }

  fun encodeFrame(bitmap: Bitmap) {
    val codec = mediaCodec ?: return
    val inputBufferIndex = codec.dequeueInputBuffer(10_000)
    if (inputBufferIndex < 0) return
    val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
    val yuvData = bitmapToYuv420SemiPlanar(bitmap)
    inputBuffer.clear()
    inputBuffer.put(yuvData)
    codec.queueInputBuffer(inputBufferIndex, 0, yuvData.size, frameIndex * 1_000_000 / fps, 0)
    frameIndex++
    drainEncoder()
  }

  private fun drainEncoder() {
    val codec = mediaCodec ?: return
    val bufferInfo = MediaCodec.BufferInfo()
    while (true) {
      val idx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
      when {
        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          trackIndex = mediaMuxer!!.addTrack(codec.outputFormat)
          mediaMuxer!!.start()
          muxerStarted = true
        }
        idx >= 0 -> {
          val buf = codec.getOutputBuffer(idx)!!
          if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
            && bufferInfo.size > 0 && muxerStarted
          ) {
            buf.position(bufferInfo.offset)
            buf.limit(bufferInfo.offset + bufferInfo.size)
            mediaMuxer!!.writeSampleData(trackIndex, buf, bufferInfo)
          }
          codec.releaseOutputBuffer(idx, false)
        }
        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
      }
    }
  }

  fun stop() {
    try {
      mediaCodec?.stop()
      mediaCodec?.release()
      mediaMuxer?.stop()
      mediaMuxer?.release()
    } catch (_: Exception) {}
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