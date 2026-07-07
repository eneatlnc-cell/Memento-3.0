package com.myagent.app

import android.util.Log
import com.myagent.app.chat.ChatController
import com.myagent.app.chat.ChatMessage
import com.myagent.app.chat.OutgoingAttachment
import com.myagent.app.memory.MemoryManager
import com.myagent.app.multimodal.VideoConfig
import com.myagent.app.proactive.ProactiveTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Memento v4.0 运行时 — 云端 API 架构。
 *
 * 移除端侧推理（llama.cpp/LocalModelLoader），所有智能能力由 CloudInferenceClient 提供：
 * - 对话 + 图片理解 → GPT-4o
 * - 图片生成       → DALL-E 3
 * - 视频合成       → 可灵 Kling
 *
 * 不再有模型下载/加载流程，应用启动即可用（前提是已配置 API 密钥）。
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

  /** 云端推理客户端（GPT-4o + DALL-E 3 + Kling） */
  val cloudClient get() = app.cloudClient

  // 聊天控制器 — 注入 CloudInferenceClient
  val chatController = ChatController(scope, app.cloudClient, memoryManager, app.cacheDir, app.contentResolver, app)

  // 主动搭话引擎
  private val proactiveTrigger = ProactiveTrigger()
  private var lastInteractionMs: Long = 0L

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
    // 云端架构无需特殊处理
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

  /**
   * v4.0 三段式工作流：合成 MP4。
   *
   * 取 KeyFrameStore 中缓存的关键帧，调用 ChatController → 可灵 Kling 合成视频。
   */
  fun composeVideoFromKeyFrames() {
    chatController.composeVideoFromKeyFrames()
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

  // --- 主动搭话 ---

  /** 标记用户交互时间，每次发送消息时调用 */
  fun markInteraction() {
    lastInteractionMs = System.currentTimeMillis()
  }

  /** 检查是否需要主动搭话（App 启动时调用） */
  fun checkProactive(isAppLaunch: Boolean = false): String? {
    if (!proactiveTrigger.shouldTrigger(lastInteractionMs, isAppLaunch)) return null
    val message = proactiveTrigger.getProactiveMessage()
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
