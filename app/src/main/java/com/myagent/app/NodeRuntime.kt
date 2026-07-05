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

  // 标记是否已对 modelLoader 执行过 init/reload，避免重复加载
  @Volatile private var modelLoaderInitialized = false

  // 模型加载互斥锁 — 保护 modelLoader.reload/unload 与 modelLoaderInitialized 标志，
  // 防止 startModelDownload 完成后的 reload 与 ensureModelLoaded 并发执行导致 JNI 状态混乱
  private val modelLock = Any()

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
   * 触发模型下载。如果已完成或正在下载则忽略。
   */
  fun startModelDownload() {
    if (_downloadState.value is ModelDownloadState.Completed ||
      _downloadState.value is ModelDownloadState.Downloading
    ) return

    downloadJob?.cancel()
    downloadJob = scope.launch {
      // 用 downloadModelWithRetry 而非 downloadModel：移动端公网下载大文件（~700MB）
      // 易因网络抖动中断，单次下载失败即报错体验差。WithRetry 提供 3 次自动重试 +
      // 断点续传（downloadFile 以 modelFile.length() 为 existingBytes 请求 Range）。
      modelInstaller.downloadModelWithRetry().collect { state ->
        _downloadState.value = state
        if (state is ModelDownloadState.Completed && modelInstaller.isModelReady()) {
          // 下载完成后加载模型。与 ensureModelLoaded 互斥（synchronized(modelLock)），
          // 避免并发 reload 导致 JNI 状态混乱崩溃。
          ensureModelLoadedInternal()
        }
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
    synchronized(modelLock) {
      modelLoader.unload()
      modelLoaderInitialized = false
    }
  }

  /**
   * 在后台线程触发模型加载（JNI init）。
   *
   * 关键守卫：
   * 1. 下载中（Downloading）跳过 — isModelFileExists 只检查文件存在 + length>0，
   *    下载中文件部分写入时会误判为 true，加载不完整 GGUF 会触发 JNI SIGSEGV。
   * 2. 已初始化跳过 — 避免重复 reload。
   * 3. 与 startModelDownload 的 reload 互斥（synchronized(modelLock)）— 避免并发
   *    JNI init 导致状态混乱崩溃。
   *
   * 必须在非主线程调用（JNI 模型加载耗时数秒）。
   */
  fun ensureModelLoaded() {
    ensureModelLoadedInternal()
  }

  private fun ensureModelLoadedInternal() {
    synchronized(modelLock) {
      if (modelLoaderInitialized) return
      // 下载中不加载：文件可能部分写入，isModelFileExists 会误判
      if (_downloadState.value is ModelDownloadState.Downloading) {
        Log.i("NodeRuntime", "Model still downloading, skip init")
        return
      }
      val path = modelInstaller.getModelPath().absolutePath.takeIf { modelInstaller.isModelFileExists() }
      if (path == null) {
        Log.i("NodeRuntime", "Model not downloaded yet, skip init")
        return
      }
      val mmproj = modelInstaller.getMmprojPath().absolutePath
      Log.i("NodeRuntime", "Background model init: $path")
      modelLoader.reload(path, mmproj)
      modelLoaderInitialized = true
    }
  }

  val isModelReady: Boolean
    get() = _downloadState.value is ModelDownloadState.Completed ||
      modelInstaller.isModelFileExists()

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