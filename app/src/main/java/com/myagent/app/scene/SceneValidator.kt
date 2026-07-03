package com.myagent.app.scene

import android.util.Log
import kotlinx.serialization.json.Json

/**
 * 场景校验器 —— 纯程序化兜底，不调用模型，不循环。
 *
 * 设计哲学：
 * - GBNF grammar 已在采样阶段约束结构，正常情况下输出必然合法
 * - 本类只做防御性兜底：JSON 解析失败、字段缺失、值非法时填默认值
 * - 不触发模型重试（端侧 0.8B 重试成本过高，且 grammar 已保证基本合法）
 *
 * 兜底策略：
 * - JSON 解析失败 → 返回 null（上层走错误提示，不渲染）
 * - layout 非法 → 降级 fullscreen
 * - assetRef 非法 → 降级角色默认资源
 * - animation 非法 → 降级 pop
 * - beats 时序倒挂 → 按 t 排序
 */
object SceneValidator {
  private const val TAG = "SceneValidator"

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true // null/缺失字段用默认值
  }

  /**
   * 解析并兜底校验 LLM 输出。
   *
   * @param rawOutput LLM 流式生成的完整 JSON 字符串
   * @param registry  资源词典（用于 assetRef 兜底降级）
   * @return 合法的 ComicScene，或 null（JSON 完全无法解析时）
   */
  fun parseAndFix(rawOutput: String, registry: AssetRegistry): ComicScene? {
    // 1) 提取 JSON 段（模型可能输出前导文本，grammar 通常会阻止，但兜底）
    val jsonStr = extractJsonBlock(rawOutput) ?: run {
      Log.w(TAG, "No JSON block found in output")
      return null
    }

    // 2) 解析
    val scene = try {
      json.decodeFromString(ComicScene.serializer(), jsonStr)
    } catch (e: Exception) {
      Log.w(TAG, "JSON parse failed: ${e.message}")
      return null
    }

    // 3) 程序化兜底
    return applyDefaults(scene, registry)
  }

  /** 提取第一个完整的 JSON 对象（{ ... }） */
  private fun extractJsonBlock(text: String): String? {
    val start = text.indexOf('{')
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escape = false
    for (i in start until text.length) {
      val c = text[i]
      when {
        escape -> escape = false
        c == '\\' && inString -> escape = true
        c == '"' -> inString = !inString
        !inString && c == '{' -> depth++
        !inString && c == '}' -> {
          depth--
          if (depth == 0) return text.substring(start, i + 1)
        }
      }
    }
    return null // 括号不匹配
  }

  private fun applyDefaults(scene: ComicScene, registry: AssetRegistry): ComicScene {
    // layout 兜底
    val fixedLayout = if (scene.layout in registry.validLayouts()) scene.layout else "fullscreen"

    // characters 兜底
    val fixedChars = scene.characters.map { c ->
      val fixedAsset = registry.resolveAssetRef(c.name, c.assetRef)
      if (fixedAsset != c.assetRef) {
        Log.w(TAG, "assetRef fixed: ${c.assetRef} → $fixedAsset (char=${c.name})")
      }
      c.copy(assetRef = fixedAsset)
    }

    // dialogue animation 兜底
    val fixedDialogue = if (scene.dialogue.animation in registry.validAnimations()) {
      scene.dialogue
    } else {
      Log.w(TAG, "animation fixed: ${scene.dialogue.animation} → pop")
      scene.dialogue.copy(animation = "pop")
    }

    // beats 按 t 排序（防时序倒挂）
    val fixedBeats = scene.beats.sortedBy { it.t }

    return scene.copy(
      layout = fixedLayout,
      characters = fixedChars,
      beats = fixedBeats,
      dialogue = fixedDialogue,
    )
  }
}
