package com.myagent.app.proactive

/**
 * 主动搭话引擎 — P1 阶段实现。
 *
 * 当前为骨架，预留接口：
 * - 时间触发（早/晚问候）
 * - 空闲检测（用户 10 分钟无操作）
 * - 启动触发（每次打开 App）
 */
class ProactiveTrigger {
  /**
   * 检查是否应该主动搭话。P1 阶段实现具体逻辑。
   */
  fun shouldTrigger(
    lastInteractionMs: Long,
    isAppLaunch: Boolean,
  ): Boolean {
    // P1: 实现规则引擎
    return false
  }

  /**
   * 获取主动搭话内容。P1 阶段实现。
   */
  fun getProactiveMessage(): String? {
    // P1: 根据时间/场景返回合适的搭话内容
    return null
  }
}