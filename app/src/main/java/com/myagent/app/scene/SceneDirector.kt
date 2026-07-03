package com.myagent.app.scene

import android.content.Context
import android.util.Log
import com.myagent.app.model.LocalModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 场景导演 —— 场景生成 + 渲染的统一入口。
 *
 * 整合 SceneGenerator（LLM 推理）+ SceneRenderer（HTML 渲染录制），
 * 对上层（ChatController）暴露一个简单接口：输入描述 + 素材 → 输出 MP4。
 *
 * 使用流程：
 * 1. registerUserAsset() 注册用户素材
 * 2. direct() 生成并渲染场景
 * 3. clear() 清理
 *
 * Memento 架构原则：本类只做调度，不持有素材库。
 * 素材来源由上层决定（前期是用户上传，后期是 Skill 市场包）。
 */
class SceneDirector(
  context: Context,
  private val modelLoader: LocalModelLoader,
) {
  companion object {
    private const val TAG = "SceneDirector"
    private const val GRAMMAR_FILE = "grammars/comic_scene.gbnf"
  }

  private val appContext = context.applicationContext
  private val assetRegistry = AssetRegistry()
  private val generator = SceneGenerator(modelLoader) { loadGrammar() }
  private val renderer = SceneRenderer(appContext)

  /** 注册用户素材，返回分配的 assetRef */
  fun registerUserAsset(filePath: String, description: String): String {
    return assetRegistry.register(filePath, description)
  }

  /** 导演一场戏：生成场景 JSON → 渲染为 MP4 */
  suspend fun direct(userPrompt: String, outputDir: File): DirectorResult {
    // 1. LLM 生成场景
    val genResult = generator.generate(userPrompt, assetRegistry)
    if (genResult is SceneGenerator.Result.Failure) {
      return DirectorResult.Failure(genResult.reason)
    }
    val scene = (genResult as SceneGenerator.Result.Success).scene

    // 2. HTML 渲染 + 录制
    return withContext(Dispatchers.Main) {
      // WebView 必须在主线程创建
      when (val renderResult = renderer.render(scene, assetRegistry, outputDir)) {
        is SceneRenderer.Result.Success -> {
          Log.i(TAG, "Scene directed: ${renderResult.videoFile.absolutePath}")
          DirectorResult.Success(renderResult.videoFile, scene)
        }
        is SceneRenderer.Result.Failure -> DirectorResult.Failure(renderResult.reason)
      }
    }
  }

  /** 清理本次场景的素材注册 */
  fun clear() = assetRegistry.clear()

  private fun loadGrammar(): String? {
    return try {
      appContext.assets.open(GRAMMAR_FILE).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to load grammar: ${e.message}", e)
      null
    }
  }

  /** 导演结果 */
  sealed class DirectorResult {
    data class Success(val videoFile: File, val scene: ComicScene) : DirectorResult()
    data class Failure(val reason: String) : DirectorResult()
  }
}
