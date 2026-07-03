package com.myagent.app.scene

import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.resume
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 场景渲染器 —— 把 ComicScene 渲染为 MP4 视频。
 *
 * 管线：
 * 1. 加载 fullscreen HTML 模板到 WebView
 * 2. 注入场景数据（JSON + 素材路径映射）
 * 3. WebView 执行 JS timeline，CSS 动画播放
 * 4. MediaRecorder 通过 VirtualDisplay 录制 WebView 画面
 * 5. timeline 结束后停止录制，输出 MP4
 *
 * 技术选型：WebView + MediaRecorder + VirtualDisplay
 * - 保持 HTML 渲染路线，模板可由前端工程师扩展
 * - Skill 市场上线后，Skill 作者提供 HTML 模板即可接入
 */
class SceneRenderer(private val context: Context) {
  companion object {
    private const val TAG = "SceneRenderer"
    private const val TEMPLATE_FILE = "templates/fullscreen.html"
    private const val VIDEO_WIDTH = 720
    private const val VIDEO_HEIGHT = 1280
    private const val VIDEO_BITRATE = 4_000_000
    private const val VIDEO_FPS = 30
    private const val MAX_DURATION_MS = 15_000L // 安全上限 15 秒
  }

  /** 渲染结果 */
  sealed class Result {
    data class Success(val videoFile: File) : Result()
    data class Failure(val reason: String) : Result()
  }

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val mainHandler = Handler(Looper.getMainLooper())

  /**
   * 渲染场景为 MP4。
   *
   * @param scene 已校验的 ComicScene
   * @param assetRegistry 素材注册表（提供 assetRef → 文件路径映射）
   * @param outputDir 视频输出目录
   * @return 渲染结果
   *
   * 注意：本方法涉及 WebView 操作，必须在主线程调用。
   * 内部用 suspendCancellableCoroutine 包装为协程友好接口时，需切到主线程。
   */
  suspend fun render(scene: ComicScene, assetRegistry: AssetRegistry, outputDir: File): Result {
    if (scene.layout != "fullscreen") {
      Log.w(TAG, "Layout '${scene.layout}' not supported yet, falling back to fullscreen")
    }

    val outputFile = File(outputDir, "scene_${System.currentTimeMillis()}.mp4")
    outputDir.mkdirs()

    // 构造素材路径映射（assetRef → file:// URI）
    val assetPaths = mutableMapOf<String, String>()
    scene.characters.forEach { c ->
      if (c.assetRef.isNotEmpty()) {
        val asset = assetRegistry.resolve(c.assetRef)
        if (asset != null) {
          assetPaths[c.assetRef] = Uri.fromFile(File(asset.filePath)).toString()
        } else {
          Log.w(TAG, "assetRef not found: ${c.assetRef}")
        }
      }
    }

    return try {
      renderInternal(scene, assetPaths, outputFile)
    } catch (e: Exception) {
      Log.e(TAG, "Render failed: ${e.message}", e)
      Result.Failure("渲染失败：${e.message ?: "未知错误"}")
    }
  }

  private suspend fun renderInternal(
    scene: ComicScene,
    assetPaths: Map<String, String>,
    outputFile: File,
  ): Result = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
    // 必须在主线程操作 WebView
    mainHandler.post {
      try {
        val webView = WebView(context).apply {
          settings.javaScriptEnabled = true
          settings.domStorageEnabled = true
          settings.mediaPlaybackRequiresUserGesture = false
          layoutParams = ViewGroup.LayoutParams(VIDEO_WIDTH, VIDEO_HEIGHT)
        }

        // MediaRecorder 配置
        val recorder = MediaRecorder().apply {
          setVideoSource(MediaRecorder.VideoSource.SURFACE)
          setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
          setOutputFile(outputFile.absolutePath)
          setVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT)
          setVideoEncoder(MediaRecorder.VideoEncoder.H264)
          setVideoEncodingBitRate(VIDEO_BITRATE)
          setVideoFrameRate(VIDEO_FPS)
          prepare()
        }

        val surface = recorder.surface
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val virtualDisplay = displayManager.createVirtualDisplay(
          "MementoSceneDisplay",
          VIDEO_WIDTH, VIDEO_HEIGHT, 1,
          surface,
          DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        )

        // 注入场景数据
        val sceneJson = json.encodeToString(ComicScene.serializer(), scene)
        val assetsJson = buildJsonObject {
          assetPaths.forEach { (ref, path) -> put(ref, path) }
        }.toString()

        webView.webViewClient = object : WebViewClient() {
          override fun onPageFinished(view: WebView?, url: String?) {
            Log.i(TAG, "Template loaded, injecting scene data")
            // 注入数据并启动渲染
            val js = """
              window.__SCENE__ = $sceneJson;
              window.__ASSET_PATHS__ = $assetsJson;
              window.__ON_RENDER_READY = function(durationMs) {
                console.log('RENDER_READY,duration=' + durationMs);
              };
            """.trimIndent()
            view?.evaluateJavascript(js, null)

            // 启动录制
            recorder.start()
            Log.i(TAG, "Recording started")

            // 估算录制时长：从 JS 读取 RENDER_DURATION_MS，超时则取 MAX
            val checkReady = object : Runnable {
              override fun run() {
                view?.evaluateJavascript("window.__RENDER_DURATION_MS || 0") { result ->
                  val durationMs = result?.removeSurrounding("\"")?.toLongOrNull() ?: 0L
                  val actualDuration = if (durationMs > 0) {
                    minOf(durationMs + 500, MAX_DURATION_MS) // 留 500ms 尾巴
                  } else {
                    MAX_DURATION_MS
                  }
                  Log.i(TAG, "Recording for ${actualDuration}ms")
                  mainHandler.postDelayed({
                    stopRecording(recorder, virtualDisplay, webView, outputFile, cont)
                  }, actualDuration)
                }
              }
            }
            // 等 500ms 让 JS 计算完 duration
            mainHandler.postDelayed(checkReady, 500)
          }
        }

        // 加载模板（从 assets）
        webView.loadUrl("file:///android_asset/$TEMPLATE_FILE")

        // 协程取消时清理
        cont.invokeOnCancellation {
          mainHandler.post {
            try { recorder.stop() } catch (_: Exception) {}
            try { recorder.release() } catch (_: Exception) {}
            try { virtualDisplay.release() } catch (_: Exception) {}
            try { webView.destroy() } catch (_: Exception) {}
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Setup failed: ${e.message}", e)
        if (cont.isActive) cont.resume(Result.Failure("渲染初始化失败：${e.message}")) {}
      }
    }
  }

  private fun stopRecording(
    recorder: MediaRecorder,
    virtualDisplay: VirtualDisplay,
    webView: WebView,
    outputFile: File,
    cont: kotlinx.coroutines.CancellableContinuation<Result>,
  ) {
    try {
      recorder.stop()
      Log.i(TAG, "Recording stopped, file: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
    } catch (e: Exception) {
      Log.e(TAG, "Stop recording failed: ${e.message}", e)
    }

    try { recorder.release() } catch (_: Exception) {}
    try { virtualDisplay.release() } catch (_: Exception) {}
    try { webView.destroy() } catch (_: Exception) {}

    if (outputFile.exists() && outputFile.length() > 0) {
      if (cont.isActive) cont.resume(Result.Success(outputFile)) {}
    } else {
      if (cont.isActive) cont.resume(Result.Failure("录制的视频文件为空")) {}
    }
  }

  /** 预览用的静态画面（可选，用于快速预览不录制） */
  fun renderPreviewBitmap(scene: ComicScene, assetRegistry: AssetRegistry): Bitmap? {
    // TODO: 后续可加静态截图预览，当前只做视频录制
    return null
  }
}
