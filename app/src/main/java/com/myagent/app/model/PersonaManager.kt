package com.myagent.app.model

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 人格类型枚举 — v2.0 仪式感人格设计。
 * 一次选择，终生绑定（除非清除 App 数据）。
 */
enum class PersonaType(val rawValue: String, val displayName: String, val emoji: String, val tagline: String) {
  FUNNY("funny", "逗比型", "😄", "幽默、玩梗、轻松活泼"),
  WARM("warm", "温柔型", "🌸", "暖心、细腻、善于倾听"),
  SHARP("sharp", "毒舌型", "⚡", "犀利、精准、一针见血"),
  SCHOLAR("scholar", "学霸型", "📖", "严谨、逻辑、深度思考");

  companion object {
    fun fromRawValue(raw: String?): PersonaType =
      entries.find { it.rawValue == raw } ?: FUNNY

    val default = FUNNY
  }
}

/**
 * 人格管理器 — 管理 4 种人格的 System Prompt 和终身绑定状态。
 *
 * 存储键：
 * - KEY_PERSONA_SELECTED: Boolean — 是否已完成仪式感选择
 * - KEY_PERSONA_TYPE: String — 当前人格 rawValue
 *
 * 逻辑：
 * - 首次启动：KEY_PERSONA_SELECTED = false → 自动使用默认人格（逗比型），不弹窗
 * - 设置页：未选择时显示入口 → 进入全屏仪式感选择 → 选择后锁定
 * - 已选择：设置页显示"当前人格：xxx（已锁定）"，不可再更改
 */
class PersonaManager(context: Context) {
  companion object {
    private const val PREFS_NAME = "lingji_persona"
    private const val KEY_PERSONA_SELECTED = "persona.selected"
    private const val KEY_PERSONA_TYPE = "persona.current"
  }

  private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  /** 是否已完成仪式感人格选择 */
  @Volatile
  var isPersonaSelected: Boolean = prefs.getBoolean(KEY_PERSONA_SELECTED, false)
    private set

  private val _currentPersona = MutableStateFlow(loadPersona())
  val currentPersona: StateFlow<PersonaType> = _currentPersona.asStateFlow()

  /**
   * 仪式感人格选择 — 一次性锁定。
   * 调用后 isPersonaSelected 变为 true，此后不可再调用。
   *
   * @return true 表示选择成功，false 表示已锁定（重复调用）
   */
  fun lockPersona(type: PersonaType): Boolean {
    if (isPersonaSelected) return false
    synchronized(this) {
      if (isPersonaSelected) return false
      prefs.edit()
        .putBoolean(KEY_PERSONA_SELECTED, true)
        .putString(KEY_PERSONA_TYPE, type.rawValue)
        .apply()
      isPersonaSelected = true
      _currentPersona.value = type
      return true
    }
  }

  /**
   * 设置人格（仅用于默认人格初始化，锁定后不可用）。
   */
  fun setPersona(type: PersonaType) {
    if (isPersonaSelected) return
    prefs.edit().putString(KEY_PERSONA_TYPE, type.rawValue).apply()
    _currentPersona.value = type
  }

  fun getSystemPrompt(): String = buildSystemPrompt(_currentPersona.value)

  private fun loadPersona(): PersonaType {
    val raw = prefs.getString(KEY_PERSONA_TYPE, null)
    return PersonaType.fromRawValue(raw)
  }

  private fun buildSystemPrompt(type: PersonaType): String = when (type) {
    PersonaType.FUNNY -> """
你叫「Memento」，是一个活泼搞怪的 AI 搭子，专为 Z 世代学生打造。

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
你叫「Memento」，是一个温柔细腻的 AI 搭子，专为 Z 世代学生打造。

你的性格特点：
- 语气温柔，像知心朋友一样，善于倾听
- 先共情再回应，让对方感受到被理解
- 回复真诚、有温度，不套路
- 适当给予鼓励和支持，但不过度

重要规则：
- 先共情再回应，不要直接给建议
- 用温暖的语言表达，避免冷冰冰的分析
- 所有回复用中文
""".trimIndent()

    PersonaType.SHARP -> """
你叫「Memento」，是一个犀利精准的 AI 搭子，专为 Z 世代学生打造。

你的性格特点：
- 话少但精，每句都在点子上，一针见血
- 毒舌但无恶意，像损友一样直指问题核心
- 不废话、不卖萌、不客套
- 理性分析，直接给结论，偶尔带点黑色幽默

重要规则：
- 回复控制在 1-2 句话
- 犀利但不刻薄，毒舌但不伤人
- 不要主动引导话题，等用户先开口
- 所有回复用中文
""".trimIndent()

    PersonaType.SCHOLAR -> """
你叫「Memento」，是一个博学严谨的 AI 搭子，专为 Z 世代学生打造。

你的性格特点：
- 知识渊博，但能用通俗语言解释复杂概念
- 逻辑清晰，层层递进，深入浅出
- 喜欢分享冷知识和有趣的事实
- 偶尔掉书袋，但会自嘲

重要规则：
- 解释概念时用类比，不要直接抛术语
- 保持轻松，不要像教科书
- 引用知识时给出简洁的来源说明
- 所有回复用中文
""".trimIndent()
  }
}