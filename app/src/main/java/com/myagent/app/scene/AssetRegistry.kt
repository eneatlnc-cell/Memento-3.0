package com.myagent.app.scene

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 资源词典加载器 —— 从 assets/asset_registry.json 读取可用 assetRef 集合。
 *
 * 作用：
 * - 构造 prompt 时注入合法 assetRef 列表，让 LLM 只能从词典里选（第二刀）
 * - SceneValidator 兜底校验时，把非法 assetRef 降级为角色默认资源
 *
 * 词典格式见 assets/asset_registry.json。角色与资源扩展时改 JSON 即可，无需改代码。
 */
class AssetRegistry(private val context: Context) {
  companion object {
    private const val TAG = "AssetRegistry"
    private const val ASSET_FILE = "asset_registry.json"
  }

  @Serializable
  private data class RegistryFile(
    val version: String = "1.0",
    val characters: Map<String, CharacterEntry> = emptyMap(),
    val layouts: List<String> = listOf("fullscreen", "split", "overlay"),
    val animations: List<String> = listOf("pop", "fade", "typewriter", "slide"),
  )

  @Serializable
  private data class CharacterEntry(
    val name: String = "",
    val defaultAsset: String = "",
    val assets: List<AssetEntry> = emptyList(),
  )

  @Serializable
  private data class AssetEntry(
    val ref: String = "",
    val desc: String = "",
  )

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  private val registry: RegistryFile by lazy { loadRegistry() }

  private fun loadRegistry(): RegistryFile {
    return try {
      context.assets.open(ASSET_FILE).bufferedReader().use { reader ->
        json.decodeFromString(RegistryFile.serializer(), reader.readText())
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to load asset registry, using empty: ${e.message}")
      RegistryFile()
    }
  }

  /** 合法 layout 集合（第一刀 enum 的运行时镜像） */
  fun validLayouts(): Set<String> = registry.layouts.toSet()

  /** 合法 animation 集合 */
  fun validAnimations(): Set<String> = registry.animations.toSet()

  /**
   * 构造注入 prompt 的资源词典文本，让 LLM 知道可选 assetRef。
   * 只列出当前注册的角色及其资源，避免 prompt 过长撑爆 0.8B 的 context。
   */
  fun buildPromptDictionary(): String {
    if (registry.characters.isEmpty()) {
      return "（资源词典为空，请让角色使用默认描述）"
    }
    return buildString {
      append("可用角色与资源（assetRef 只能从以下列表选）：\n")
      registry.characters.forEach { (charId, entry) ->
        append("- 角色 $charId（${entry.name}）：")
        append(entry.assets.joinToString("、") { "${it.ref}（${it.desc}）" })
        append('\n')
      }
    }
  }

  /**
   * 兜底校验：assetRef 非法时降级为角色默认资源。
   * 返回修正后的 assetRef（合法原值 / 角色默认 / 空字符串）。
   */
  fun resolveAssetRef(characterName: String, assetRef: String): String {
    // 直接命中
    val charEntry = registry.characters.values.firstOrNull { it.name == characterName }
      ?: return assetRef // 未注册的角色，保持原值（grammar 已约束结构）
    if (charEntry.assets.any { it.ref == assetRef }) return assetRef
    // 降级到默认
    return charEntry.defaultAsset
  }
}
