package com.myagent.app

import android.graphics.Bitmap
import android.util.Log
import com.myagent.app.chat.ChatController
import com.myagent.app.chat.ChatMessage
import com.myagent.app.chat.OutgoingAttachment
import com.myagent.app.memory.MemoryManager
import com.myagent.app.model.LocalModelLoader
import com.myagent.app.model.ModelDownloadState
import com.myagent.app.multimodal.MultiModalDispatcher
import com.myagent.app.multimodal.VideoConfig
import com.myagent.app.proactive.ProactiveTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Memento v3.1 运行时 — 管理 UI 状态、聊天控制器、模型加载器、下载状态。
 *
 * v3.1：llama.cpp 推理引擎 + 双文件模型（主模型 + mmproj），骁龙 Hexagon NPU 加速。
 */
class NodeRuntime(
  private val app: NodeApp,
  private val prefs: SecurePrefs,
  private val memoryManager: MemoryManager,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  companion object {
    private const val KEY_VIDEO_CONFIG = "video.config"
  }

  // 模型安装器 — 共享 NodeApp 单例，确保全 App 一致
  val modelInstaller = app.modelInstaller

  // 本地模型加载器 — lazy 初始化。
  // 注意：lazy 块只创建对象，不调 init()（JNI 模型加载耗时数秒）。
  // init() 由 ensureModelLoaded() 在后台线程显式触发，避免主线程抢跑导致 ANR/崩溃。
  val modelLoader: LocalModelLoader by lazy {
    val modelFile = modelInstaller.getModelPath()
    val mmprojFile = modelInstaller.getMmprojPath()
    val path = if (modelInstaller.isModelFileExists()) modelFile.absolutePath else null
    val mmproj = if (modelInstaller.isModelFileExists()) mmprojFile.absolutePath else null
    LocalModelLoader(app, path, mmproj)
  }

  // C-N5 修复：用 AtomicBoolean 替代 @Volatile，确保 check-and-set 原子性。
  // 原 @Volatile 只保证可见性，不保证 CAS，两个线程可同时通过检查。
  private val modelLoaderInitialized = AtomicBoolean(false)

  // 正在初始化中（由 startModelDownload 协程设置），防止 ensureModelLoaded 并行触发第二次 init。
  private val modelLoaderInitializing = AtomicBoolean(false)

  // 聊天控制器
  val chatController = ChatController(scope, modelLoader, memoryManager, app.cacheDir, app.contentResolver, app)

  // 主动搭话引擎
  private val proactiveTrigger = ProactiveTrigger()
  private var lastInteractionMs: Long = 0L

  // --- 模型下载状态 ---

  private val _downloadState = MutableStateFlow<ModelDownloadState>(
    if (modelInstaller.isModelFileExists()) ModelDownloadState.Completed
    else ModelDownloadState.Idle
  )
  val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

  private var downloadJob: Job? = null

  /**
   * 触发模型下载。顺序：
   * ① 下载 mmproj.gguf（~200MB）
   * ② 下载主模型 Qwen.gguf（~500MB）
   * ③ 同时加载主模型 + mmproj → 引擎一次性初始化完成
   *
   * 重要：中间步骤不 emit Completed，防止 OnboardingFlow 提前完成导航。
   * 只有引擎加载成功后 _downloadState 才变为 Completed。
   */
  fun startModelDownload() {
    if (_downloadState.value is ModelDownloadState.Completed ||
      _downloadState.value is ModelDownloadState.Downloading
    ) return

    downloadJob?.cancel()
    downloadJob = scope.launch {
      // 设置初始化标志，防止 ensureModelLoaded() 在下载期间竞态加载部分文件
      modelLoaderInitializing.set(true)
      try {
        // ═══════════════ ① 下载 mmproj ═══════════════
        var mmprojDone = false
        modelInstaller.downloadMmprojFileOnly().collect { state ->
          // 只透传 Downloading/Failed，不透传 Completed（防止 OnboardingFlow 提前完成）
          when (state) {
            is ModelDownloadState.Downloading -> _downloadState.value = state
            is ModelDownloadState.Failed -> {
              _downloadState.value = state
              mmprojDone = false
            }
            is ModelDownloadState.Verifying -> _downloadState.value = state
            is ModelDownloadState.Completed -> mmprojDone = true
            else -> {}
          }
        }
        if (!mmprojDone) {
          modelLoaderInitializing.set(false)
          return@launch
        }

        // ═══════════════ ② 下载主模型 ═══════════════
        var modelDone = false
        modelInstaller.downloadModelFileOnly().collect { state ->
          when (state) {
            is ModelDownloadState.Downloading -> _downloadState.value = state
            is ModelDownloadState.Failed -> {
              _downloadState.value = state
              modelDone = false
            }
            is ModelDownloadState.Verifying -> _downloadState.value = state
            is ModelDownloadState.Completed -> modelDone = true
            else -> {}
          }
        }
        if (!modelDone) {
          modelLoaderInitializing.set(false)
          return@launch
        }

        // ═══════════════ ③ 一次性加载主模型 + mmproj ═══════════════
        val modelPath = modelInstaller.getModelPath().absolutePath
        val mmprojPath = modelInstaller.getMmprojPath().absolutePath
        Log.i("NodeRuntime", "Loading engine with model + mmproj...")
        withContext(Dispatchers.IO) {
          modelLoader.reload(modelPath, mmprojPath)
        }
        modelLoaderInitialized.set(true)
        _downloadState.value = ModelDownloadState.Completed
        Log.i("NodeRuntime", "Engine ready: model + mmproj loaded")
      } catch (e: Exception) {
        Log.e("NodeRuntime", "Download/init crashed", e)
        _downloadState.value = ModelDownloadState.Failed("模型加载失败：${e.message}")
      } finally {
        modelLoaderInitializing.set(false)
      }
    }
  }

  fun resetAndStartDownload() {
    downloadJob?.cancel()
    downloadJob = null
    _downloadState.value = ModelDownloadState.Idle
    startModelDownload()
  }

  /**
   * 卸载模型释放内存 — 供系统内存压力回调调用。
   * 卸载后下次推理会自动重新加载。
   */
  fun unloadModel() {
    modelLoader.unload()
    modelLoaderInitialized.set(false)
  }

  /**
   * 在后台线程触发模型加载（JNI init）。
   * 若模型已加载或路径为空则跳过；加载失败由 doInitialize 内部 catch 记录。
   * 必须在非主线程调用（JNI 模型加载耗时数秒）。
   *
   * C-N5 修复：用 AtomicBoolean CAS 确保只有一个线程能触发 init。
   * 如果 startModelDownload 协程正在初始化，则直接返回（它完成后会设置 initialized 标志）。
   */
  suspend fun ensureModelLoaded() {
    if (modelLoaderInitialized.get()) return
    if (modelLoaderInitializing.get()) return
    val path = modelInstaller.getModelPath().absolutePath.takeIf { modelInstaller.isModelFileExists() }
    if (path == null) {
      Log.i("NodeRuntime", "Model not downloaded yet, skip init")
      return
    }
    // 防御：模型文件至少 300MB 才算完整（Q4_K_XL 实际 ~500MB+）
    val modelSize = modelInstaller.getModelPath().length()
    if (modelSize < ModelInstaller.MIN_MODEL_SIZE) {
      Log.w("NodeRuntime", "Model file too small ($modelSize bytes), likely partial download, skip init")
      return
    }
    if (!modelLoaderInitializing.compareAndSet(false, true)) return
    try {
      if (modelLoaderInitialized.get()) return
      val mmproj = modelInstaller.getMmprojPath().absolutePath
      Log.i("NodeRuntime", "Background model init: $path")
      withContext(Dispatchers.IO) {
        modelLoader.reload(path, mmproj)
      }
      modelLoaderInitialized.set(true)
      Log.i("NodeRuntime", "Background model init complete")
    } catch (e: Exception) {
      Log.e("NodeRuntime", "Background model init failed", e)
    } finally {
      modelLoaderInitializing.set(false)
    }
  }

  val isModelReady: Boolean
    get() = modelInstaller.isModelFileExists()

  // --- UI 状态 ---

  private val _isConnected = MutableStateFlow(true)
  val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

  val chatMessages: StateFlow<List<ChatMessage>> = chatController.messages
  val chatStreamingText: StateFlow<String?> = chatController.streamingText
  val chatLoading: StateFlow<Boolean> = chatController.isLoading
  val chatError: StateFlow<String?> = chatController.errorText

  // 外观
  val appearanceThemeMode: StateFlow<AppearanceThemeMode> = prefs.appearanceThemeMode

  // --- 视频画质配置 ---

  private val _videoConfig = MutableStateFlow(loadVideoConfig())
  val videoConfig: StateFlow<VideoConfig> = _videoConfig.asStateFlow()

  private fun loadVideoConfig(): VideoConfig {
    val raw = app.getSharedPreferences("lingji.v2", android.content.Context.MODE_PRIVATE)
      .getString(KEY_VIDEO_CONFIG, null)
    return VideoConfig.fromString(raw)
  }

  fun setVideoConfig(config: VideoConfig) {
    app.getSharedPreferences("lingji.v2", android.content.Context.MODE_PRIVATE)
      .edit()
      .putString(KEY_VIDEO_CONFIG, VideoConfig.toString(config))
      .apply()
    _videoConfig.value = config
  }

  // --- 操作 ---

  fun setForeground(value: Boolean) {
    // v3.1 本地推理，无需特殊处理
  }

  fun sendChat(message: String, attachments: List<OutgoingAttachment> = emptyList()) {
    chatController.sendMessage(message, attachments)
  }

  fun sendImage(imageUri: String, caption: String = "") {
    chatController.sendImage(imageUri, caption)
  }

  /** 多图输入：用户可一次发送 ≤10 张图片 */
  fun sendImages(imageUris: List<String>, caption: String = "") {
    chatController.sendImages(imageUris, caption)
  }

  fun sendVideo(videoUri: String, caption: String = "") {
    chatController.sendVideo(videoUri, caption)
  }

  fun abortChat() {
    chatController.abort()
  }

  fun clearChat() {
    chatController.clearMessages()
  }

  fun setAppearanceThemeMode(mode: AppearanceThemeMode) {
    prefs.setAppearanceThemeMode(mode)
  }

  // --- 多模态调度 ---

  /**
   * 生成图片
   */
  suspend fun generateImage(prompt: String, style: String? = null): Bitmap {
    return MultiModalDispatcher.generateImage(prompt, style)
  }

  // --- 主动搭话 ---

  /** 标记用户交互时间，每次发送消息时调用 */
  fun markInteraction() {
    lastInteractionMs = System.currentTimeMillis()
  }

  /** 检查是否需要主动搭话（App 启动时调用） */
  fun checkProactive(isAppLaunch: Boolean = false): String? {
    if (!proactiveTrigger.shouldTrigger(lastInteractionMs, isAppLaunch)) return null
    val message = proactiveTrigger.getProactiveMessage()
    // 搭话前更新交互时间，避免短时间内重复触发
    lastInteractionMs = System.currentTimeMillis()
    return message
  }

  // --- 数据管理 ---

  /** 清除聊天记录 */
  fun clearChatHistory() {
    chatController.clearMessages()
  }

  /** 清除所有记忆 */
  fun clearAllMemories() {
    memoryManager.clearAllMemories()
  }

  /** 插入系统消息（主动搭话用） */
  fun insertSystemMessage(text: String) {
    chatController.addSystemMessage(text)
  }
}