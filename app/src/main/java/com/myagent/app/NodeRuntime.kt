package com.myagent.app

import com.myagent.app.chat.ChatController
import com.myagent.app.chat.ChatMessage
import com.myagent.app.chat.OutgoingAttachment
import com.myagent.app.memory.MemoryManager
import com.myagent.app.model.LocalModelLoader
import com.myagent.app.model.ModelDownloadState
import com.myagent.app.model.ModelInstaller
import com.myagent.app.model.PersonaManager
import com.myagent.app.model.PersonaType
import com.myagent.app.multimodal.VideoConfig
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
  private val personaManager: PersonaManager,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  companion object {
    private const val KEY_VIDEO_CONFIG = "video.config"
  }

  // 模型安装器
  val modelInstaller = ModelInstaller(app)

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
  val chatController = ChatController(scope, modelLoader, memoryManager, personaManager)

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

  // 人格
  val currentPersona: StateFlow<PersonaType> = personaManager.currentPersona
  private val _personaSelected = MutableStateFlow(personaManager.isPersonaSelected)
  val personaSelected: StateFlow<Boolean> = _personaSelected.asStateFlow()

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

  fun lockPersona(type: PersonaType): Boolean {
    val result = personaManager.lockPersona(type)
    if (result) {
      _personaSelected.value = true
    }
    return result
  }

  fun sendChat(message: String, attachments: List<OutgoingAttachment> = emptyList()) {
    chatController.sendMessage(message, attachments)
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
}