package com.myagent.app

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
 * Memento v2.0 运行时 — 管理 UI 状态、聊天控制器、模型加载器、下载状态。
 *
 * v2.0：LiteRT-LM 替代 llama.cpp，纯 Kotlin 推理，无需 nativeLibDir。
 * 新增：仪式感人格锁定 + 视频画质可配置。
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

  // 本地模型加载器 — 模型下载完成后才初始化推理引擎
  val modelLoader: LocalModelLoader = run {
    val modelFile = modelInstaller.getModelPath()
    val path = if (modelInstaller.isModelReady()) modelFile.absolutePath else null
    LocalModelLoader(app, path).also {
      if (path != null) {
        it.init()
      }
    }
  }

  // 聊天控制器
  val chatController = ChatController(scope, modelLoader, memoryManager, app.cacheDir, app.contentResolver)

  // 主动搭话引擎
  private val proactiveTrigger = ProactiveTrigger()
  private var lastInteractionMs: Long = 0L

  // --- 模型下载状态 ---

  private val _downloadState = MutableStateFlow<ModelDownloadState>(
    if (modelInstaller.isModelReady()) ModelDownloadState.Completed
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
      modelInstaller.downloadModel().collect { state ->
        _downloadState.value = state
        if (state is ModelDownloadState.Completed && modelInstaller.isModelReady()) {
          val modelPath = modelInstaller.getModelPath().absolutePath
          modelLoader.reload(modelPath)
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
    modelLoader.unload()
  }

  val isModelReady: Boolean
    get() = _downloadState.value is ModelDownloadState.Completed ||
      modelInstaller.isModelReady()

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
    // v2.0 本地推理，无需特殊处理
  }

  fun sendChat(message: String, attachments: List<OutgoingAttachment> = emptyList()) {
    chatController.sendMessage(message, attachments)
  }

  fun sendImage(imageUri: String, caption: String = "") {
    chatController.sendImage(imageUri, caption)
  }

  fun sendVoice(audioUri: String, transcript: String = "") {
    chatController.sendVoice(audioUri, transcript)
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
   * 合成语音并返回 WAV 数据
   */
  suspend fun synthesizeSpeech(text: String, voice: String = "af_heart"): ByteArray {
    return MultiModalDispatcher.synthesizeSpeech(text, voice)
  }

  /**
   * 生成图片
   */
  suspend fun generateImage(prompt: String, style: String? = null): android.graphics.Bitmap {
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