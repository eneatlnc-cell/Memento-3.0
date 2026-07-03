package com.myagent.app.proactive

import java.util.Calendar

/**
 * 主动搭话引擎 — 根据时间、场景自动发起 AI 搭话。
 *
 * v3.0: 移除人格依赖。搭话内容不再按人格区分，Memento 的表达
 * 风格由对话历史自然塑造。
 *
 * 触发规则：
 * - 早间问候（6:00-10:00）
 * - 晚间问候（20:00-23:00）
 * - 空闲搭话（用户 10 分钟无操作）
 * - 启动搭话（App 冷启动时，30% 概率）
 */
class ProactiveTrigger {

  // H-M7 修复：记录上次触发时间，用于最小触发间隔去抖
  @Volatile private var lastTriggerMs = 0L

  /**
   * 检查是否应该主动搭话。
   */
  fun shouldTrigger(
    lastInteractionMs: Long,
    isAppLaunch: Boolean,
  ): Boolean {
    val now = System.currentTimeMillis()

    // H-M7 修复：最小触发间隔去抖，避免早晚高峰窗口每分钟重复触发导致 spam + 耗电
    if (lastTriggerMs > 0 && now - lastTriggerMs < MIN_TRIGGER_INTERVAL_MS) {
      return false
    }

    if (isTimeTrigger()) {
      lastTriggerMs = now
      return true
    }

    if (lastInteractionMs > 0 && (now - lastInteractionMs) > IDLE_THRESHOLD_MS) {
      lastTriggerMs = now
      return true
    }

    // H-M8 修复：原条件 isAppLaunch && lastInteractionMs > 0 在冷启动时（lastInteractionMs==0）恒为 false，分支不可达；
    // 按文档意图"App 冷启动时 30% 概率"主动搭话，去掉 lastInteractionMs > 0 约束
    if (isAppLaunch && Math.random() < 0.3) {
      lastTriggerMs = now
      return true
    }

    return false
  }

  /**
   * 获取主动搭话内容。
   */
  fun getProactiveMessage(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val messages = if (hour in 6..17) {
      morningMessages
    } else {
      eveningMessages
    }
    return messages[Math.floor(Math.random() * messages.size).toInt()]
  }

  // ── 内部 ──

  private val IDLE_THRESHOLD_MS = 10 * 60 * 1000L
  // H-M7 修复：最小触发间隔 30 分钟，防止频繁触发
  private val MIN_TRIGGER_INTERVAL_MS = 30 * 60 * 1000L

  private fun isTimeTrigger(): Boolean {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return hour in 6..9 || hour in 20..22
  }

  private val morningMessages = listOf(
    "早啊！今天有什么计划？",
    "早上好！新的一天开始了 ✨",
    "嘿，醒了吗？今天天气不错",
    "早！今天想聊点什么？",
  )

  private val eveningMessages = listOf(
    "晚上好！今天过得怎么样？",
    "夜深了，来聊聊？🌙",
    "一天的忙碌结束了，放松一下",
    "晚上好，有什么想聊的吗？",
  )
}