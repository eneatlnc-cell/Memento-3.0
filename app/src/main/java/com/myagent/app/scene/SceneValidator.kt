package com.myagent.app.scene

import android.util.Log
import kotlinx.serialization.json.Json

/**
 * 场景校验器 —— 纯程序化兜底，不调用模型，不循环。
 *
 * GBNF grammar 已在采样阶段约束结构，正常情况下输出必然合法。
 * 本类只做防御性兜底：JSON 解析失败、字段缺失、值非法时填默认值。
 *
 * assetRef 不做白名单校验（前期素材由用户动态注册，无固定词典）。
 * 渲染器在解析 ComicScene 时若 resolve(assetRef) 返回 null，自行降级处理。
 */
object SceneValidator {
  private const val TAG = "SceneValidator"

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true // null/缺失字段用默认值
  }

  private val VALID_LAYOUTS = setOf("fullscreen", "split", "overlay")
  private val VALID_ANIMATIONS = setOf("pop", "fade", "typewriter", "slide")

  /**
   * 解析并兜底校验 LLM 输出。
   *
   * @param rawOutput LLM 流式生成的完整 JSON 字符串
   * @return 合法的 ComicScene，或 null（JSON 完全无法解析时）
   */
  fun parseAndFix(rawOutput: String): ComicScene? {
    val jsonStr = extractJsonBlock(rawOutput) ?: run {
      Log.w(TAG, "No JSON block found in output")
      return null
    }

    val scene = try {
      json.decodeFromString(ComicScene.serializer(), jsonStr)
    } catch (e: Exception) {
      Log.w(TAG, "JSON parse failed: ${e.message}")
      return null
    }

    return applyDefaults(scene)
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
    return null
  }

  private fun applyDefaults(scene: ComicScene): ComicScene {
    val fixedLayout = if (scene.layout in VALID_LAYOUTS) scene.layout else "fullscreen"

    val fixedDialogue = if (scene.dialogue.animation in VALID_ANIMATIONS) {
      scene.dialogue
    } else {
      scene.dialogue.copy(animation = "pop")
    }

    val fixedBeats = scene.beats.sortedBy { it.t }

    return scene.copy(
      layout = fixedLayout,
      characters = scene.characters,
      beats = fixedBeats,
      dialogue = fixedDialogue,
    )
  }
}
