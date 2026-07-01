package com.myagent.app

import com.myagent.app.activation.ActivationManager
import com.myagent.app.chat.ChatMessage
import com.myagent.app.chat.OutgoingAttachment
import com.myagent.app.model.ModelDownloadState
import com.myagent.app.multimodal.VideoConfig
import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI 桥接层 — 将 NodeRuntime 状态暴露为 Compose 友好的 StateFlow。
 *
 * v2.0：新增仪式感人格选择状态 + 视频画质配置。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(
  app: Application,
) : AndroidViewModel(app) {
  private val nodeApp = app as NodeApp
  private val prefs = nodeApp.prefs
  private val activationManager = nodeApp.activationManager

  private val runtimeRef = MutableStateFlow<NodeRuntime?>(null)
  @Volatile private var foreground = false
  @Volatile private var runtimeStartupQueued = false

  private fun ensureRuntime(): NodeRuntime {
    runtimeRef.value?.let { return it }
    val runtime = nodeApp.ensureRuntime()
    runtime.setForeground(foreground)
    runtimeRef.value = runtime
    return runtime
  }

  private fun queueRuntimeStartup() {
    if (runtimeRef.value != null || runtimeStartupQueued) return
    runtimeStartupQueued = true
    viewModelScope.launch(Dispatchers.Default) {
      try {
        ensureRuntime()
      } catch (e: Exception) {
        Log.e("MainViewModel", "Runtime startup failed", e)
      }
      runtimeStartupQueued = false
    }
  }

  private fun <T> runtimeState(initial: T, selector: (NodeRuntime) -> StateFlow<T>): StateFlow<T> =
    runtimeRef
      .flatMapLatest { runtime -> runtime?.let(selector) ?: flowOf(initial) }
      .stateIn(viewModelScope, SharingStarted.Eagerly, initial)

  val runtimeInitialized: StateFlow<Boolean> =
    runtimeRef
      .flatMapLatest { runtime -> flowOf(runtime != null) }
      .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  // --- 聊天 ---
  val chatMessages: StateFlow<List<ChatMessage>> = runtimeState(emptyList()) { it.chatMessages }
  val chatStreamingText: StateFlow<String?> = runtimeState(null) { it.chatStreamingText }
  val chatLoading: StateFlow<Boolean> = runtimeState(false) { it.chatLoading }
  val chatError: StateFlow<String?> = runtimeState(null) { it.chatError }

  // --- 外观 ---
  val appearanceThemeMode: StateFlow<AppearanceThemeMode> = prefs.appearanceThemeMode

  // --- 偏好 ---
  val onboardingCompleted: StateFlow<Boolean> = prefs.onboardingCompleted
  val welcomeCompleted: StateFlow<Boolean> = prefs.welcomeCompleted

  fun setWelcomeCompleted() {
    prefs.setWelcomeCompleted()
  }

  // --- 激活 ---
  private val _isActivated = MutableStateFlow(activationManager.isActivated())
  val isActivated: StateFlow<Boolean> = _isActivated.asStateFlow()

  fun activate(code: String, onResult: (Boolean) -> Unit) {
    viewModelScope.launch(Dispatchers.IO) {
      val success = activationManager.activate(code)
      if (success) {
        _isActivated.value = true
      }
      onResult(success)
    }
  }

  // --- 视频画质 ---
  val videoConfig: StateFlow<VideoConfig> = runtimeState(VideoConfig.LOW) { it.videoConfig }

  // --- 模型下载 ---
  val downloadState: StateFlow<ModelDownloadState> =
    runtimeState(ModelDownloadState.Idle) { it.downloadState }

  /**
   * 前台/后台切换时启动 runtime
   */
  fun setForeground(value: Boolean) {
    foreground = value
    if (value) {
      queueRuntimeStartup()
    }
    runtimeRef.value?.setForeground(value)
  }

  fun setOnboardingCompleted(value: Boolean) {
    if (value) {
      ensureRuntime()
    }
    prefs.setOnboardingCompleted(value)
  }

  // --- 模型下载操作 ---
  private val _downloadRetryCount = MutableStateFlow(0)
  val downloadRetryCount: StateFlow<Int> = _downloadRetryCount.asStateFlow()

  fun startModelDownload() {
    downloadJob?.cancel()
    _downloadRetryCount.value = 0
    downloadJob = viewModelScope.launch(Dispatchers.Default) {
      ensureRuntime().startModelDownload()
    }
  }

  fun resetModelDownload() {
    downloadJob?.cancel()
    _downloadRetryCount.value = _downloadRetryCount.value + 1
    downloadJob = viewModelScope.launch(Dispatchers.Default) {
      ensureRuntime().resetAndStartDownload()
    }
  }

  private var downloadJob: kotlinx.coroutines.Job? = null

  // --- 聊天操作 ---
  fun sendChat(message: String, attachments: List<OutgoingAttachment> = emptyList()) {
    ensureRuntime().markInteraction()
    ensureRuntime().sendChat(message, attachments)
  }

  fun sendImage(uri: Uri, caption: String = "") {
    ensureRuntime().sendImage(uri.toString(), caption)
  }

  fun sendVideo(uri: Uri, caption: String = "") {
    ensureRuntime().sendVideo(uri.toString(), caption)
  }

  fun sendVoice(uri: Uri, transcript: String = "") {
    ensureRuntime().sendVoice(uri.toString(), transcript)
  }

  fun abortChat() {
    ensureRuntime().abortChat()
  }

  fun clearChat() {
    ensureRuntime().clearChat()
  }

  // --- 视频画质 ---
  fun setVideoConfig(config: VideoConfig) {
    ensureRuntime().setVideoConfig(config)
  }

  // --- 外观 ---
  fun setAppearanceThemeMode(mode: AppearanceThemeMode) {
    ensureRuntime().setAppearanceThemeMode(mode)
  }

  // --- 主动搭话 ---

  /**
   * 检查是否需要主动搭话，返回搭话内容（null 表示不需要）
   */
  fun checkProactive(isAppLaunch: Boolean = false): String? {
    return runtimeRef.value?.checkProactive(isAppLaunch)
  }

  /** 插入系统消息（主动搭话用） */
  fun insertSystemMessage(text: String) {
    ensureRuntime().insertSystemMessage(text)
  }

  // --- 数据管理 ---

  fun clearChatHistory() {
    ensureRuntime().clearChatHistory()
  }

  fun clearAllMemories() {
    ensureRuntime().clearAllMemories()
  }

  // --- 多模态操作 ---

  private val _ttsPlaying = MutableStateFlow(false)
  val ttsPlaying: StateFlow<Boolean> = _ttsPlaying.asStateFlow()

  suspend fun synthesizeSpeech(text: String): ByteArray {
    return ensureRuntime().synthesizeSpeech(text)
  }

  suspend fun generateImage(prompt: String): Bitmap {
    return ensureRuntime().generateImage(prompt)
  }
}