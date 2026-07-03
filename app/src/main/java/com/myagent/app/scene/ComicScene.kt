package com.myagent.app.scene

import kotlinx.serialization.Serializable

/**
 * 漫剧场景数据模型 —— 与 GBNF grammar (comic_scene.gbnf) 一一对应。
 *
 * 结构由 grammar 在采样阶段强制约束，模型物理上无法生成非法字段。
 * SceneValidator 只做防御性程序化兜底（缺字段填默认/非法值降级），不触发模型重试。
 *
 * 三刀流落地：
 * - 第一刀：layout 受 grammar enum 锁死，只能是 fullscreen/split/overlay
 * - 第二刀：assetRef 由 prompt 注入的词典约束 + SceneValidator 兜底校验
 * - 第三刀：beats 简化时序（LLM 只写剧本），复杂节奏由渲染器推导
 */
@Serializable
data class ComicScene(
  val layout: String = "fullscreen",
  val characters: List<Character> = emptyList(),
  val beats: List<Beat> = emptyList(),
  val dialogue: Dialogue = Dialogue(),
)

@Serializable
data class Character(
  val id: String = "",
  val name: String = "",
  val assetRef: String = "",
  val expression: String = "neutral",
  val transform: Transform = Transform(),
)

@Serializable
data class Transform(
  val x: Float = 0.5f,
  val y: Float = 0.5f,
  val scale: Float = 1.0f,
  val rotation: Float = 0.0f,
)

/** 简化时序节点：什么时候、对谁、做什么。节奏细节由渲染器推导。 */
@Serializable
data class Beat(
  val t: Float = 0.0f,
  val target: String = "",
  val value: String = "",
)

@Serializable
data class Dialogue(
  val speaker: String = "",
  val text: String = "",
  val animation: String = "pop",
)
