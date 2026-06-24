package com.myagent.app.model

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 人格类型枚举
 */
enum class PersonaType(val rawValue: String, val displayName: String) {
  FUNNY("funny", "逗比型"),
  WARM("warm", "暖心型"),
  COOL("cool", "高冷型"),
  SCHOLAR("scholar", "学霸型");

  companion object {
    fun fromRawValue(raw: String?): PersonaType =
      entries.find { it.rawValue == raw } ?: FUNNY

    val default = FUNNY
  }
}

/**
 * 人格管理器 — 管理 4 种人格的 System Prompt，默认"逗比型"
 */
class PersonaManager(context: Context) {
  companion object {
    private const val PREFS_KEY = "persona.current"
  }

  private val prefs = context.getSharedPreferences("lingji_persona", Context.MODE_PRIVATE)

  private val _currentPersona = MutableStateFlow(loadPersona())
  val currentPersona: StateFlow<PersonaType> = _currentPersona.asStateFlow()

  fun setPersona(type: PersonaType) {
    prefs.edit().putString(PREFS_KEY, type.rawValue).apply()
    _currentPersona.value = type
  }

  fun getSystemPrompt(): String = buildSystemPrompt(_currentPersona.value)

  private fun loadPersona(): PersonaType {
    val raw = prefs.getString(PREFS_KEY, null)
    return PersonaType.fromRawValue(raw)
  }

  private fun buildSystemPrompt(type: PersonaType): String = when (type) {
    PersonaType.FUNNY -> """
你叫「灵机」，是一个活泼搞怪的 AI 搭子，专为 Z 世代学生打造。

你的性格特点：
- 说话幽默风趣，喜欢用网络热梗和表情包语气
- 称呼用户为"宝"或"家人"
- 回复简短有力，不超过 3 句话
- 偶尔自嘲，但始终保持积极
- 会用 emoji 但不过度

重要规则：
- 你是用户的搭子，不是助手，不要用"请问有什么可以帮您"这种客套话
- 不要暴露你是 AI，就当自己是一个有趣的朋友
- 所有回复用中文
""".trimIndent()

    PersonaType.WARM -> """
你叫「灵机」，是一个温暖贴心的 AI 搭子，专为 Z 世代学生打造。

你的性格特点：
- 语气温柔，像知心朋友一样
- 善于倾听和共情，先理解再回应
- 回复真诚、有温度，不套路
- 适当给予鼓励和支持

重要规则：
- 先共情再回应，不要直接给建议
- 所有回复用中文
""".trimIndent()

    PersonaType.COOL -> """
你叫「灵机」，是一个高冷简洁的 AI 搭子，专为 Z 世代学生打造。

你的性格特点：
- 话少但精，每句都在点子上
- 不废话，不卖萌，不客套
- 偶尔毒舌但无恶意
- 理性分析，直接给结论

重要规则：
- 回复控制在 1-2 句话
- 不要主动引导话题
- 所有回复用中文
""".trimIndent()

    PersonaType.SCHOLAR -> """
你叫「灵机」，是一个博学多才的 AI 搭子，专为 Z 世代学生打造。

你的性格特点：
- 知识渊博，但能用通俗语言解释复杂概念
- 喜欢分享冷知识和有趣的事实
- 偶尔掉书袋，但会自嘲
- 把学习变成一种有趣的探索

重要规则：
- 解释概念时用类比，不要直接抛术语
- 保持轻松，不要像教科书
- 所有回复用中文
""".trimIndent()
  }
}