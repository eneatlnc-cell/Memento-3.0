package com.myagent.app

import com.myagent.app.chat.ChatMessage
import com.myagent.app.chat.OutgoingAttachment
import com.myagent.app.model.PersonaType
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI 桥接层 — 将 NodeRuntime 状态暴露为 Compose 友好的 StateFlow。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(
  app: Application,
) : AndroidViewModel(app) {
  private val nodeApp = app as NodeApp
  private val prefs = nodeApp.prefs

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
      runCatching { ensureRuntime() }
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

  // --- 人格 ---
  val currentPersona: StateFlow<PersonaType> = runtimeState(PersonaType.FUNNY) { it.currentPersona }

  // --- 外观 ---
  val appearanceThemeMode: StateFlow<AppearanceThemeMode> = prefs.appearanceThemeMode

  // --- 偏好 ---
  val onboardingCompleted: StateFlow<Boolean> = prefs.onboardingCompleted

  /**
   * 前台/后台切换时启动 runtime
   */
  fun setForeground(value: Boolean) {
    foreground = value
    if (value && prefs.onboardingCompleted.value) {
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

  // --- 聊天操作 ---
  fun sendChat(message: String, attachments: List<OutgoingAttachment> = emptyList()) {
    ensureRuntime().sendChat(message, attachments)
  }

  fun abortChat() {
    ensureRuntime().abortChat()
  }

  fun clearChat() {
    ensureRuntime().clearChat()
  }

  // --- 人格操作 ---
  fun setPersona(type: PersonaType) {
    ensureRuntime().setPersona(type)
  }

  // --- 外观 ---
  fun setAppearanceThemeMode(mode: AppearanceThemeMode) {
    ensureRuntime().setAppearanceThemeMode(mode)
  }
}