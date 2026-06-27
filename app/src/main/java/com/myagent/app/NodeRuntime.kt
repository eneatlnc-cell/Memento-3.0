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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 灵机 v2.0 运行时 — 管理 UI 状态、聊天控制器、模型加载器、下载状态。
 *
 * v2.0：LiteRT-LM 替代 llama.cpp，纯 Kotlin 推理，无需 nativeLibDir。
 */
class NodeRuntime(
  private val app: NodeApp,
  private val prefs: SecurePrefs,
  private val memoryManager: MemoryManager,
  private val personaManager: PersonaManager,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  // 模型安装器
  val modelInstaller = ModelInstaller(app)

  // 本地模型加载器（Mock 模式：modelPath 为 null 时自动降级）
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

  private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
  val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

  private var downloadJob: Job? = null

  /** 是否已经运行过下载流程（无论成功失败） */
  private val modelDownloadFinished: Boolean
    get() = _downloadState.value is ModelDownloadState.Completed ||
      _downloadState.value is ModelDownloadState.Failed

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
        // 下载完成后，初始化 LiteRT-LM 引擎
        if (state is ModelDownloadState.Completed && modelInstaller.isModelReady()) {
          val modelPath = modelInstaller.getModelPath().absolutePath
          modelLoader.reload(modelPath)
        }
      }
    }
  }

  /**
   * 跳过下载，继续使用 Mock 模式。
   */
  fun skipModelDownload() {
    downloadJob?.cancel()
    downloadJob = null
    _downloadState.value = ModelDownloadState.Completed
  }

  /**
   * 重置下载状态，允许重新触发下载（从设置页调用）。
   */
  fun resetAndStartDownload() {
    downloadJob?.cancel()
    downloadJob = null
    _downloadState.value = ModelDownloadState.Idle
    startModelDownload()
  }

  /**
   * 模型是否已就绪（已下载校验通过，或用户选择跳过）
   */
  val isModelReady: Boolean
    get() = _downloadState.value is ModelDownloadState.Completed ||
      modelInstaller.isModelReady()

  // --- UI 状态 ---

  private val _isConnected = MutableStateFlow(true) // v2.0 本地推理，始终"在线"
  val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

  val chatMessages: StateFlow<List<ChatMessage>> = chatController.messages
  val chatStreamingText: StateFlow<String?> = chatController.streamingText
  val chatLoading: StateFlow<Boolean> = chatController.isLoading
  val chatError: StateFlow<String?> = chatController.errorText

  // 人格
  val currentPersona: StateFlow<PersonaType> = personaManager.currentPersona

  // 外观
  val appearanceThemeMode: StateFlow<AppearanceThemeMode> = prefs.appearanceThemeMode

  fun setForeground(value: Boolean) {
    // v2.0 本地推理，无需特殊处理
  }

  fun setPersona(type: PersonaType) {
    personaManager.setPersona(type)
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