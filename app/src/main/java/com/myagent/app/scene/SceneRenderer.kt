package com.myagent.app.scene

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
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
 * 2. onPageFinished 后注入场景数据 + 调用 __mementoStartRender__
 * 3. WebView 执行 JS timeline，CSS 动画播放
 * 4. MediaRecorder 通过 VirtualDisplay 录制 WebView 画面
 * 5. timeline 结束后停止录制，输出 MP4
 *
 * 技术选型：WebView + MediaRecorder + VirtualDisplay + Presentation
 * - 保持 HTML 渲染路线，模板可由前端工程师扩展
 * - Skill 市场上线后，Skill 作者提供 HTML 模板即可接入
 *
 * C-M1 修复：通过 Presentation 将 WebView 挂载到 VirtualDisplay，
 * 录制的才是 WebView 画面而非默认屏幕镜像。
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
    // 必须在主线程操作 WebView / Presentation
    mainHandler.post {
      // 局部引用，确保 catch 块能访问到已创建的资源做清理
      var webView: WebView? = null
      var recorder: MediaRecorder? = null
      var virtualDisplay: VirtualDisplay? = null
      var presentation: Presentation? = null

      try {
        webView = WebView(context).apply {
          settings.javaScriptEnabled = true
          settings.domStorageEnabled = true
          settings.mediaPlaybackRequiresUserGesture = false
          layoutParams = ViewGroup.LayoutParams(VIDEO_WIDTH, VIDEO_HEIGHT)
        }

        // MediaRecorder 配置
        recorder = MediaRecorder().apply {
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
        // C-M1 修复：移除 AUTO_MIRROR（会镜像默认屏幕），改用 OWN_CONTENT_ONLY
        // 配合 Presentation 只把 WebView 内容渲染到 VirtualDisplay 的 surface
        virtualDisplay = displayManager.createVirtualDisplay(
          "MementoSceneDisplay",
          VIDEO_WIDTH, VIDEO_HEIGHT, 1,
          surface,
          DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        if (virtualDisplay == null) {
          throw Exception("VirtualDisplay 创建失败")
        }

        // C-M1 修复：通过 Presentation 将 WebView 挂载到 VirtualDisplay，
        // 使 WebView 渲染内容输出到 MediaRecorder 消费的 surface
        val display = virtualDisplay!!.display
          ?: throw Exception("VirtualDisplay 无可用 Display")
        presentation = object : Presentation(context, display) {
          override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(webView)
          }
        }
        presentation.show()

        // 注入场景数据
        val sceneJson = json.encodeToString(ComicScene.serializer(), scene)
        val assetsJson = buildJsonObject {
          assetPaths.forEach { (ref, path) -> put(ref, path) }
        }.toString()

        val webViewRef = webView
        val recorderRef = recorder
        val virtualDisplayRef = virtualDisplay
        val presentationRef = presentation

        webView.webViewClient = object : WebViewClient() {
          override fun onPageFinished(view: WebView?, url: String?) {
            Log.i(TAG, "Template loaded, injecting scene data and starting render")
            // 注入数据 + 调用渲染入口（同步执行，RENDER_DURATION_MS 在回调触发时已设置）
            val injectJs = """
              window.__SCENE__ = $sceneJson;
              window.__ASSET_PATHS__ = $assetsJson;
              window.__mementoStartRender__(window.__SCENE__, window.__ASSET_PATHS__);
            """.trimIndent()
            view?.evaluateJavascript(injectJs) { _ ->
              // JS 同步执行完毕，读取渲染时长
              view?.evaluateJavascript("window.__RENDER_DURATION_MS || 0") { result ->
                val durationMs = result?.removeSurrounding("\"")?.toLongOrNull() ?: 0L
                val actualDuration = if (durationMs > 0) {
                  minOf(durationMs + 500, MAX_DURATION_MS) // 留 500ms 尾巴
                } else {
                  MAX_DURATION_MS
                }
                Log.i(TAG, "Recording for ${actualDuration}ms")
                // 启动录制
                try { recorderRef.start() } catch (e: Exception) {
                  Log.e(TAG, "recorder.start() failed: ${e.message}", e)
                }
                mainHandler.postDelayed({
                  stopRecording(recorderRef, virtualDisplayRef, webViewRef, presentationRef, outputFile, cont)
                }, actualDuration)
              }
            }
          }
        }

        // 加载模板（从 assets）— Presentation 已 show，WebView 有 surface 可渲染
        webView.loadUrl("file:///android_asset/$TEMPLATE_FILE")

        // 协程取消时清理
        cont.invokeOnCancellation {
          mainHandler.post {
            cleanupResources(recorderRef, virtualDisplayRef, webViewRef, presentationRef)
          }
        }
      } catch (e: Exception) {
        // H-M3 修复：setup 阶段抛异常时清理已创建的资源
        Log.e(TAG, "Setup failed: ${e.message}", e)
        cleanupResources(recorder, virtualDisplay, webView, presentation)
        if (cont.isActive) cont.resume(Result.Failure("渲染初始化失败：${e.message}")) {}
      }
    }
  }

  /**
   * 统一资源清理：dismiss Presentation、停止 + 释放 recorder、释放 virtualDisplay、销毁 webView。
   * 所有操作都 try-catch，确保一个失败不影响其他清理。
   */
  private fun cleanupResources(
    recorder: MediaRecorder?,
    virtualDisplay: VirtualDisplay?,
    webView: WebView?,
    presentation: Presentation?,
  ) {
    try { presentation?.dismiss() } catch (_: Exception) {}
    try { recorder?.stop() } catch (_: Exception) {}
    try { recorder?.release() } catch (_: Exception) {}
    try { virtualDisplay?.release() } catch (_: Exception) {}
    try { webView?.destroy() } catch (_: Exception) {}
  }

  private fun stopRecording(
    recorder: MediaRecorder,
    virtualDisplay: VirtualDisplay,
    webView: WebView,
    presentation: Presentation,
    outputFile: File,
    cont: kotlinx.coroutines.CancellableContinuation<Result>,
  ) {
    Log.i(TAG, "Stopping recording")
    cleanupResources(recorder, virtualDisplay, webView, presentation)

    if (outputFile.exists() && outputFile.length() > 0) {
      Log.i(TAG, "Recording done: ${outputFile.absolutePath} (${outputFile.length() / 1024}KB)")
      if (cont.isActive) cont.resume(Result.Success(outputFile)) {}
    } else {
      // F2 修复：失败时删除空文件，避免累积垃圾
      if (outputFile.exists() && outputFile.length() == 0L) outputFile.delete()
      if (cont.isActive) cont.resume(Result.Failure("录制的视频文件为空")) {}
    }
  }

  /** 预览用的静态画面（可选，用于快速预览不录制） */
  fun renderPreviewBitmap(scene: ComicScene, assetRegistry: AssetRegistry): Bitmap? {
    // TODO: 后续可加静态截图预览，当前只做视频录制
    return null
  }
}
