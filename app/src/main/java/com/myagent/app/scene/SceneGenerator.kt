package com.myagent.app.scene

import android.content.Context
import android.util.Log
import com.myagent.app.model.LocalModelLoader

/**
 * 场景生成调度器 —— 整合 grammar 事前约束 + 程序化兜底。
 *
 * 流程（无循环，单次生成）：
 * 1. 读取 GBNF grammar 文件（assets/grammars/comic_scene.gbnf）
 * 2. 构造 system prompt：人格 + 可用 assetRef 词典注入
 * 3. 调用 LlamaEngine.generateScene（采样阶段 grammar 约束）
 * 4. 流式收集完整 JSON 输出
 * 5. SceneValidator 程序化兜底（不触发模型重试）
 * 6. 返回合法 ComicScene 或错误
 *
 * 设计原则：一次生成，零重试。GBNF 保证结构合法，兜底只补缺省值。
 */
class SceneGenerator(
  private val context: Context,
  private val modelLoader: LocalModelLoader,
) {
  companion object {
    private const val TAG = "SceneGenerator"
    private const val GRAMMAR_FILE = "grammars/comic_scene.gbnf"
    private const val SYSTEM_PROMPT = """你是一位漫剧导演。根据用户描述生成漫剧场景 JSON。
你必须严格输出 JSON，不要输出任何解释或额外文本。
场景必须包含：layout（画面布局）、characters（角色列表）、beats（时序节点）、dialogue（对话气泡）。
beats 的 target 格式：char_id.expression（角色表情）/ dialogue.bubble（气泡弹出）/ scene.transition（场景切换）。
beats 的 t 是时间秒数，按时间顺序排列。"""
  }

  private val assetRegistry = AssetRegistry(context)
  private val grammarCache: String? by lazy { loadGrammar() }

  /** 生成结果：合法场景 / 错误信息 */
  sealed class Result {
    data class Success(val scene: ComicScene) : Result()
    data class Failure(val reason: String) : Result()
  }

  /**
   * 流式生成漫剧场景。
   *
   * @param userPrompt 用户描述（如"哈士奇看到橘猫推杯子，惊讶地叫起来"）
   * @param onPartial  流式回调，每收到一段 JSON 片段就触发（用于 UI 进度展示）
   */
  suspend fun generate(
    userPrompt: String,
    onPartial: (String) -> Unit = {},
  ): Result {
    val grammar = grammarCache ?: return Result.Failure("grammar 文件加载失败")

    // 构造 system prompt：基础指令 + 资源词典注入（第二刀：受控词汇表）
    val systemPrompt = buildString {
      append(SYSTEM_PROMPT)
      append('\n')
      append(assetRegistry.buildPromptDictionary())
    }

    // 收集流式输出
    val rawBuilder = StringBuilder()
    try {
      modelLoader.generateScene(systemPrompt, userPrompt, grammar).collect { chunk ->
        rawBuilder.append(chunk)
        onPartial(chunk)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Scene generation failed: ${e.message}", e)
      return Result.Failure("场景生成失败：${e.message ?: "未知错误"}")
    }

    val rawOutput = rawBuilder.toString()
    if (rawOutput.isBlank()) {
      return Result.Failure("模型未输出任何内容")
    }

    // 程序化兜底校验（不循环，不重试）
    val scene = SceneValidator.parseAndFix(rawOutput, assetRegistry)
      ?: return Result.Failure("输出格式无法解析，请换一种描述再试")

    Log.i(TAG, "Scene generated: layout=${scene.layout}, chars=${scene.characters.size}, beats=${scene.beats.size}")
    return Result.Success(scene)
  }

  private fun loadGrammar(): String? {
    return try {
      context.assets.open(GRAMMAR_FILE).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to load grammar: ${e.message}", e)
      null
    }
  }
}
