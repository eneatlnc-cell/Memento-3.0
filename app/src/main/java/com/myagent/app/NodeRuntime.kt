package com.myagent.app

import com.myagent.app.chat.ChatController
import com.myagent.app.chat.ChatMessage
import com.myagent.app.chat.OutgoingAttachment
import com.myagent.app.memory.MemoryManager
import com.myagent.app.model.LocalModelLoader
import com.myagent.app.model.ModelInstaller
import com.myagent.app.model.PersonaManager
import com.myagent.app.model.PersonaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 灵机 v2.0 运行时 — 管理 UI 状态、聊天控制器、模型加载器。
 *
 * 不再依赖 Gateway，所有推理在本地完成。
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
    val path = if (modelFile.exists()) modelFile.absolutePath else null
    LocalModelLoader(path)
  }

  // 聊天控制器
  val chatController = ChatController(scope, modelLoader, memoryManager, personaManager)

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