package com.myagent.app.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay

/**
 * 本地模型加载器 — 封装 llama.cpp JNI 调用，提供流式推理接口。
 *
 * 当前阶段：Mock 模式，模拟 Gemma 3 270M 的推理效果。
 * 后续接入 llama.cpp 后替换 generate() 实现即可。
 */
class LocalModelLoader(
  private val modelPath: String?,
) {
  /**
   * 流式生成回复，逐 token 发射。
   * 当前为 Mock 实现，返回预设的搭子风格回复，模拟流式输出。
   */
  fun generate(prompt: String): Flow<String> = flow {
    val response = mockResponse(prompt)
    // 模拟流式输出：每次发射 1-2 个字符
    var i = 0
    while (i < response.length) {
      val chunkSize = if (i % 3 == 0) 2 else 1
      val end = minOf(i + chunkSize, response.length)
      emit(response.substring(i, end))
      delay(30) // 模拟推理延迟
      i = end
    }
  }

  /**
   * 检查模型是否可用
   */
  fun isModelAvailable(): Boolean = modelPath != null

  /**
   * Mock 回复生成 — 根据用户输入返回搭子风格的回复。
   * 后续接入 llama.cpp 后删除此方法。
   */
  private fun mockResponse(prompt: String): String {
    val input = prompt.trim().lowercase()

    return when {
      input.contains("你好") || input.contains("嗨") || input.contains("hello") || input.contains("hi") ->
        "嗨宝！今天想聊点啥？我随时在线~ 😎"

      input.contains("名字") || input.contains("你是谁") || input.contains("你叫什么") ->
        "我叫灵机！你的专属 AI 搭子，24 小时不下线的那种！"

      input.contains("天气") ->
        "宝，我现在还看不到天气数据，不过你可以看看窗外嘛！要是下雨记得带伞，别淋感冒了~"

      input.contains("谢谢") || input.contains("感谢") || input.contains("thank") ->
        "跟我客气啥！咱俩谁跟谁啊 😏"

      input.contains("再见") || input.contains("拜拜") || input.contains("bye") ->
        "拜拜宝！随时找我，我永远在~ 👋"

      input.contains("笑话") || input.contains("搞笑") || input.contains("段子") ->
        "为什么程序员总是分不清万圣节和圣诞节？因为 Oct 31 == Dec 25！😂 好吧我知道这个有点冷..."

      input.contains("无聊") || input.contains("没意思") || input.contains("好闲") ->
        "无聊的时候最适合来找我聊天了！要不我给你讲个八卦？虽然我其实没有八卦可以讲..."

      input.contains("emo") || input.contains("难过") || input.contains("不开心") || input.contains("伤心") ->
        "抱抱！不管发生什么，我都在这里。想吐槽就尽情吐槽，我听着呢 ❤️"

      input.contains("学习") || input.contains("考试") || input.contains("作业") ->
        "学霸模式启动！虽然我有时候也不靠谱，但陪你一起学习还是可以的。哪里卡住了？"

      input.contains("推荐") || input.contains("安利") ->
        "我强烈安利...睡觉！开个玩笑，你想让我推荐哪方面的？音乐、电影、游戏还是学习资料？"

      input.length < 5 ->
        "嗯？宝你说啥？我没太听清，再说一遍呗~"

      else ->
        "哈哈哈哈这个有意思！宝你继续说，我听着呢~"
    }
  }
}