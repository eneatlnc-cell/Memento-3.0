package com.myagent.app.scene

import android.util.Log
import com.myagent.app.model.LocalModelLoader

/**
 * 场景生成调度器 —— 整合 grammar 事前约束 + 程序化兜底。
 *
 * 流程（无循环，单次生成）：
 * 1. 读取 GBNF grammar 文件（assets/grammars/comic_scene.gbnf）
 * 2. 构造 system prompt：人格 + 用户素材描述注入
 * 3. 调用 LlamaEngine.generateScene（采样阶段 grammar 约束）
 * 4. 流式收集完整 JSON 输出
 * 5. SceneValidator 程序化兜底（不触发模型重试）
 * 6. 返回合法 ComicScene 或错误
 *
 * 素材来源（前期）：
 * - 用户上传的图片/视频关键帧，由上层注册到 AssetRegistry
 * - LLM 在 prompt 中看到素材描述，在 JSON 中引用 assetRef
 * - 不依赖任何内置素材库
 */
class SceneGenerator(
  private val modelLoader: LocalModelLoader,
  private val grammarLoader: () -> String?,
) {
  companion object {
    private const val TAG = "SceneGenerator"
    private const val SYSTEM_PROMPT = """你是一位漫剧导演。根据用户描述生成漫剧场景 JSON。
你必须严格输出 JSON，不要输出任何解释或额外文本。
场景必须包含：layout（画面布局，只能用 fullscreen）、characters（角色列表）、beats（时序节点）、dialogue（对话气泡）。
每个 character 的 assetRef 必须从用户提供的素材列表中选取；若无素材则留空。
beats 的 target 格式：char_id.expression（角色表情）/ dialogue.bubble（气泡弹出）/ scene.transition（场景切换）。
beats 的 t 是时间秒数，按时间顺序排列。"""
  }

  /** 生成结果：合法场景 / 错误信息 */
  sealed class Result {
    data class Success(val scene: ComicScene) : Result()
    data class Failure(val reason: String) : Result()
  }

  /**
   * 流式生成漫剧场景。
   *
   * @param userPrompt 用户的描述关键词（如"让画面里的哈士奇惊讶地叫起来"）
   * @param assetRegistry 已注册的用户素材（用于 prompt 注入）
   * @param onPartial 流式回调，每收到一段 JSON 片段就触发（用于 UI 进度展示）
   */
  suspend fun generate(
    userPrompt: String,
    assetRegistry: AssetRegistry,
    onPartial: (String) -> Unit = {},
  ): Result {
    val grammar = grammarLoader() ?: return Result.Failure("grammar 文件加载失败")

    val systemPrompt = buildString {
      append(SYSTEM_PROMPT)
      append('\n')
      append(assetRegistry.buildPromptDictionary())
    }

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

    val scene = SceneValidator.parseAndFix(rawOutput)
      ?: return Result.Failure("输出格式无法解析，请换一种描述再试")

    Log.i(TAG, "Scene generated: layout=${scene.layout}, chars=${scene.characters.size}, beats=${scene.beats.size}")
    return Result.Success(scene)
  }
}
