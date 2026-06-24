package com.myagent.app.chat

import java.util.UUID

/**
 * 聊天消息数据模型
 */
data class ChatMessage(
  val id: String,
  val role: String, // "user" 或 "assistant"
  val content: String,
  val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * 发送中的消息附件
 */
data class OutgoingAttachment(
  val type: String,
  val mimeType: String,
  val fileName: String,
  val base64: String,
)