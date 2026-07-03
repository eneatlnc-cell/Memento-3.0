package com.myagent.app.scene

/**
 * 动态素材注册表 —— 运行时把用户输入的图片/视频关键帧注册为临时素材。
 *
 * 架构定位（Memento 是引擎，不是素材库）：
 * - Memento 不内置任何角色素材，不知道"哈士奇"长什么样
 * - 用户发送的图片/视频采样后注册为 user_media_* 资源
 * - LLM 在场景 JSON 中引用 assetRef，渲染器据此查找本地文件
 *
 * Skill 市场上线后，第三方素材包会注册到各自的 Skill 目录下，
 * Memento 只需扩展本接口的实现，渲染层无需改动。
 */
class AssetRegistry {
  /** 单个素材条目 */
  data class Asset(
    val ref: String,        // assetRef，如 "user_media_0"
    val filePath: String,   // 本地绝对路径
    val description: String, // 语义描述，注入 prompt 让 LLM 理解素材内容
  )

  private val assets = mutableListOf<Asset>()
  private var nextId = 0

  /** 注册一个素材，返回分配的 assetRef */
  fun register(filePath: String, description: String): String {
    val ref = "user_media_$nextId"
    nextId++
    assets.add(Asset(ref, filePath, description))
    return ref
  }

  /** 按 assetRef 查找素材文件路径（渲染器调用） */
  fun resolve(assetRef: String): Asset? = assets.firstOrNull { it.ref == assetRef }

  /** 清空所有注册的素材（场景生成完成后调用，释放引用） */
  fun clear() {
    assets.clear()
    nextId = 0
  }

  /**
   * 构造注入 prompt 的素材描述文本。
   * 让 LLM 知道每个 assetRef 对应什么内容，零额外推理成本。
   */
  fun buildPromptDictionary(): String {
    if (assets.isEmpty()) {
      return "（本次无素材，请根据用户描述用纯文字构思场景）"
    }
    return buildString {
      append("可用素材（assetRef 只能从以下列表选）：\n")
      assets.forEach { a ->
        append("- ${a.ref}（${a.description}）\n")
      }
    }
  }

  /** 是否已注册素材 */
  fun hasAssets(): Boolean = assets.isNotEmpty()
}
