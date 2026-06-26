package com.myagent.app.chat

import com.myagent.app.memory.MemoryManager
import com.myagent.app.model.LocalModelLoader
import com.myagent.app.model.PersonaManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 聊天控制器 — 协调 LocalModelLoader、MemoryManager、PersonaManager。
 *
 * 数据流：
 * 用户输入 → PersonaManager.getSystemPrompt()
 *          → MemoryManager.getRecentMemories(5)
 *          → 组装 Prompt
 *          → LocalModelLoader.generate() [流式]
 *          → UI 逐字显示
 *          → MemoryManager.saveMemory()
 */
class ChatController(
  private val scope: CoroutineScope,
  private val modelLoader: LocalModelLoader,
  private val memoryManager: MemoryManager,
  private val personaManager: PersonaManager,
) {
  private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
  val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

  private val _streamingText = MutableStateFlow<String?>(null)
  val streamingText: StateFlow<String?> = _streamingText.asStateFlow()

  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  private val _errorText = MutableStateFlow<String?>(null)
  val errorText: StateFlow<String?> = _errorText.asStateFlow()

  private var currentStreamJob: Job? = null

  /**
   * 发送消息并获取流式回复
   */
  fun sendMessage(message: String, attachments: List<OutgoingAttachment> = emptyList()) {
    val trimmed = message.trim()
    if (trimmed.isEmpty() && attachments.isEmpty()) return

    // 取消正在进行的流式输出
    currentStreamJob?.cancel()

    // 添加用户消息
    val userMessage = ChatMessage(
      id = UUID.randomUUID().toString(),
      role = "user",
      content = trimmed,
    )
    _messages.value = _messages.value + userMessage

    // 保存用户消息到记忆
    memoryManager.saveMemory(role = "user", content = trimmed)

    _errorText.value = null
    _isLoading.value = true
    _streamingText.value = ""

    currentStreamJob = scope.launch {
      try {
        // 1. 获取人格 System Prompt
        val systemPrompt = personaManager.getSystemPrompt()

        // 2. 获取最近记忆
        val recentMemories = memoryManager.getRecentMemories(5)
        val memoryContext = if (recentMemories.isNotEmpty()) {
          recentMemories.joinToString("\n") { "${it.role}: ${it.content}" }
        } else {
          ""
        }

        // 3. 组装 Prompt
        val fullPrompt = buildString {
          append(systemPrompt)
          if (memoryContext.isNotEmpty()) {
            append("\n\n--- 最近的对话 ---\n")
            append(memoryContext)
            append("\n--- 当前对话 ---\n")
          }
          append("用户: $trimmed\n")
          append("灵机: ")
        }

        // 4. 流式推理
        val assistantId = UUID.randomUUID().toString()
        val assistantMessage = ChatMessage(
          id = assistantId,
          role = "assistant",
          content = "",
        )
        _messages.value = _messages.value + assistantMessage

        val fullResponse = StringBuilder()
        modelLoader.generate(fullPrompt).collect { chunk ->
          fullResponse.append(chunk)
          _streamingText.value = fullResponse.toString()
        }

        // 5. 更新最终消息
        val finalContent = fullResponse.toString()
        _messages.value = _messages.value.map {
          if (it.id == assistantId) it.copy(content = finalContent) else it
        }

        // 6. 保存助手回复到记忆（过滤掉循环输出）
        val cleaned = finalContent.trim()
        if (cleaned.isNotEmpty() && !isLoopOutput(cleaned)) {
          memoryManager.saveMemory(role = "assistant", content = cleaned)
        }

        _streamingText.value = null
        _isLoading.value = false
      } catch (e: Exception) {
        _errorText.value = e.message ?: "发送失败，请重试"
        _streamingText.value = null
        _isLoading.value = false
      }
    }
  }

  /**
   * 中止当前正在进行的流式输出
   */
  fun abort() {
    currentStreamJob?.cancel()
    currentStreamJob = null
    _streamingText.value = null
    _isLoading.value = false
  }

  /**
   * 清空聊天记录
   */
  fun clearMessages() {
    currentStreamJob?.cancel()
    currentStreamJob = null
    _messages.value = emptyList()
    _streamingText.value = null
    _isLoading.value = false
    _errorText.value = null
  }

  /**
   * 检测是否为循环输出（如 "灵机: 灵机: 灵机:"）
   */
  private fun isLoopOutput(text: String): Boolean {
    if (text.length < 3) return false
    // 按空格/换行分割
    val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.size < 3) return false
    // 检查是否有超过一半的 token 相同
    val maxCount = tokens.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
    return maxCount > tokens.size / 2
  }
}