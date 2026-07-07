package com.myagent.app

import com.myagent.app.activation.ActivationManager
import com.myagent.app.chat.ChatMessage
import com.myagent.app.chat.OutgoingAttachment
import com.myagent.app.multimodal.VideoConfig
import android.app.Application
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
 * v4.0 云端架构：移除模型下载/加载状态，应用启动即可用。
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

  /** 运行时未就绪时的待处理操作 */
  private val pendingActions = mutableListOf<() -> Unit>()

  private fun ensureRuntime(): NodeRuntime {
    runtimeRef.value?.let { return it }
    val runtime = nodeApp.ensureRuntime()
    runtime.setForeground(foreground)
    runtimeRef.value = runtime
    val actions = synchronized(pendingActions) {
      val list = pendingActions.toList()
      pendingActions.clear()
      list
    }
    actions.forEach { it() }
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
      queueRuntimeStartup()
    }
    prefs.setOnboardingCompleted(value)
  }

  // --- 聊天操作 ---
  fun sendChat(message: String, attachments: List<OutgoingAttachment> = emptyList()) {
    try {
      ensureRuntime().markInteraction()
      ensureRuntime().sendChat(message, attachments)
    } catch (e: Exception) {
      Log.e("MainViewModel", "sendChat failed", e)
    }
  }

  fun sendImage(uri: Uri, caption: String = "") {
    sendImages(listOf(uri), caption)
  }

  /** 多图输入：用户可一次发送 ≤10 张图片，作为多模态上下文一起推理 */
  fun sendImages(uris: List<Uri>, caption: String = "") {
    try {
      val uriStrings = uris.map { it.toString() }
      val runtime = runtimeRef.value
      if (runtime == null) {
        Log.w("MainViewModel", "Runtime not ready, queueing images for later")
        synchronized(pendingActions) { pendingActions.add { runtimeRef.value?.sendImages(uriStrings, caption) } }
        queueRuntimeStartup()
        return
      }
      runtime.sendImages(uriStrings, caption)
    } catch (e: Exception) {
      Log.e("MainViewModel", "sendImages failed", e)
    }
  }

  fun sendVideo(uri: Uri, caption: String = "") {
    try {
      val runtime = runtimeRef.value
      if (runtime == null) {
        Log.w("MainViewModel", "Runtime not ready, queueing video for later")
        synchronized(pendingActions) { pendingActions.add { runtimeRef.value?.sendVideo(uri.toString(), caption) } }
        queueRuntimeStartup()
        return
      }
      runtime.sendVideo(uri.toString(), caption)
    } catch (e: Exception) {
      Log.e("MainViewModel", "sendVideo failed", e)
    }
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
    val runtime = runtimeRef.value
    if (runtime != null) {
      runtime.insertSystemMessage(text)
    } else {
      synchronized(pendingActions) {
        pendingActions.add { runtimeRef.value?.insertSystemMessage(text) }
      }
      queueRuntimeStartup()
    }
  }

  // --- 数据管理 ---

  fun clearChatHistory() {
    ensureRuntime().clearChatHistory()
  }

  fun clearAllMemories() {
    ensureRuntime().clearAllMemories()
  }

  // --- 多模态操作 ---

  /**
   * v4.0 三段式工作流：合成 MP4。
   *
   * 取 KeyFrameStore 中缓存的关键帧，调用 ChatController → 可灵 Kling 合成视频。
   */
  fun composeVideoFromKeyFrames() {
    val runtime = runtimeRef.value ?: run {
      pendingActions.add { runtimeRef.value?.composeVideoFromKeyFrames() }
      return
    }
    runtime.composeVideoFromKeyFrames()
  }
}
