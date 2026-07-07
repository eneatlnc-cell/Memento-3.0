package com.myagent.app.cloud

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.channels.awaitClose
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * OpenAI Provider — GPT-4o 对话（流式 + Vision）+ DALL-E 3 图片生成。
 *
 * - chat(): 多模态对话流式输出，支持图片输入（data URI）
 * - generateImage(): DALL-E 3 图片生成，返回本地文件路径
 *
 * 认证：直连用 OpenAI Key，代理模式走代理端点。
 */
class OpenAIProvider(private val apiKeyManager: ApiKeyManager) {

  companion object {
    private const val TAG = "OpenAIProvider"
    private const val MODEL_CHAT = "gpt-4o"
    private const val MODEL_IMAGE = "dall-e-3"
  }

  private val client = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()

  /**
   * 多模态对话流式输出。
   *
   * @param systemPrompt 系统提示词
   * @param messages     对话历史 [{role, content}]
   * @param imagePaths   图片本地路径列表（可选，送入 GPT-4o Vision）
   * @return 流式文本 chunk
   */
  fun chat(
    systemPrompt: String,
    messages: List<CloudChatMessage>,
    imagePaths: List<String> = emptyList(),
  ): Flow<String> = callbackFlow {
    val baseUrl = apiKeyManager.getChatBaseUrl()
    val url = "$baseUrl/chat/completions"

    // 构建请求体
    val messagesArray = JSONArray()

    // system message
    if (systemPrompt.isNotEmpty()) {
      messagesArray.put(JSONObject().apply {
        put("role", "system")
        put("content", systemPrompt)
      })
    }

    // 历史消息 + 当前消息
    for (msg in messages) {
      messagesArray.put(JSONObject().apply {
        put("role", msg.role)
        if (msg.imagePaths.isNotEmpty()) {
          // 多模态消息：content 是数组
          val contentArray = JSONArray()
          // 图片
          for (imgPath in msg.imagePaths) {
            val dataUri = imagePathToDataUri(imgPath)
            if (dataUri != null) {
              contentArray.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                  put("url", dataUri)
                })
              })
            }
          }
          // 文字
          if (msg.content.isNotEmpty()) {
            contentArray.put(JSONObject().apply {
              put("type", "text")
              put("text", msg.content)
            })
          }
          put("content", contentArray)
        } else {
          put("content", msg.content)
        }
      })
    }

    // 若有当前轮图片但没在 messages 里，附加到最后一条 user 消息
    if (imagePaths.isNotEmpty() && (messages.isEmpty() || messages.last().imagePaths.isEmpty())) {
      // 移除最后一条纯文本 user 消息，重建为多模态
      if (messages.isNotEmpty() && messages.last().role == "user") {
        messagesArray.remove(messagesArray.length() - 1)
      }
      val contentArray = JSONArray()
      for (imgPath in imagePaths) {
        val dataUri = imagePathToDataUri(imgPath)
        if (dataUri != null) {
          contentArray.put(JSONObject().apply {
            put("type", "image_url")
            put("image_url", JSONObject().apply {
              put("url", dataUri)
            })
          })
        }
      }
      val lastText = if (messages.isNotEmpty()) messages.last().content else ""
      if (lastText.isNotEmpty()) {
        contentArray.put(JSONObject().apply {
          put("type", "text")
          put("text", lastText)
        })
      }
      messagesArray.put(JSONObject().apply {
        put("role", "user")
        put("content", contentArray)
      })
    }

    val requestBody = JSONObject().apply {
      put("model", MODEL_CHAT)
      put("messages", messagesArray)
      put("stream", true)
      put("max_tokens", 2048)
    }

    val request = Request.Builder()
      .url(url)
      .header("Authorization", apiKeyManager.getAuthHeader(ApiKeyManager.Service.OPENAI))
      .header("Content-Type", "application/json")
      .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
      .build()

    try {
      val response = client.newCall(request).execute()
      if (!response.isSuccessful) {
        val errBody = response.body?.string() ?: ""
        trySend("API 错误 ${response.code}: $errBody")
        close()
        return@callbackFlow
      }

      val reader = BufferedReader(response.body!!.charStream())
      var line: String?
      while (reader.readLine().also { line = it } != null) {
        if (line.isNullOrEmpty()) continue
        if (!line.startsWith("data: ")) continue
        val data = line.removePrefix("data: ").trim()
        if (data == "[DONE]") break

        try {
          val json = JSONObject(data)
          val delta = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("delta")
            ?.optString("content")
          if (!delta.isNullOrEmpty()) {
            trySend(delta)
          }
        } catch (e: Exception) {
          // 跳过无法解析的行
        }
      }
      reader.close()
    } catch (e: Exception) {
      Log.e(TAG, "Chat stream error: ${e.message}", e)
      trySend("[错误] ${e.message}")
    }
    close()

    awaitClose { }
  }.flowOn(Dispatchers.IO)

  /**
   * DALL-E 3 图片生成。
   *
   * @param prompt 图片描述
   * @param size   "1024x1024" | "1792x1024" | "1024x1792"
   * @param quality "standard" | "hd"
   * @return 下载到本地的图片文件路径，失败返回 null
   */
  suspend fun generateImage(
    prompt: String,
    size: String = "1024x1024",
    quality: String = "hd",
    outputDir: File,
  ): String? {
    val baseUrl = apiKeyManager.getChatBaseUrl()
    val url = "$baseUrl/images/generations"

    val requestBody = JSONObject().apply {
      put("model", MODEL_IMAGE)
      put("prompt", prompt)
      put("n", 1)
      put("size", size)
      put("quality", quality)
      put("response_format", "url")
    }

    val request = Request.Builder()
      .url(url)
      .header("Authorization", apiKeyManager.getAuthHeader(ApiKeyManager.Service.OPENAI))
      .header("Content-Type", "application/json")
      .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
      .build()

    return try {
      val response = client.newCall(request).execute()
      if (!response.isSuccessful) {
        Log.e(TAG, "Image gen failed: ${response.code} ${response.body?.string()}")
        return null
      }

      val json = JSONObject(response.body!!.string())
      val imageUrl = json.optJSONArray("data")
        ?.optJSONObject(0)
        ?.optString("url")
      if (imageUrl.isNullOrEmpty()) {
        Log.e(TAG, "No image URL in response")
        return null
      }

      // 下载图片到本地
      downloadImage(imageUrl, outputDir)
    } catch (e: Exception) {
      Log.e(TAG, "Image gen error: ${e.message}", e)
      null
    }
  }

  /** 下载图片 URL 到本地文件，返回路径 */
  private fun downloadImage(url: String, outputDir: File): String? {
    return try {
      outputDir.mkdirs()
      val outputFile = File(outputDir, "gen_${System.currentTimeMillis()}.png")

      val request = Request.Builder().url(url).build()
      val response = client.newCall(request).execute()
      if (!response.isSuccessful) return null

      response.body!!.byteStream().use { input ->
        FileOutputStream(outputFile).use { output ->
          input.copyTo(output)
        }
      }
      outputFile.absolutePath
    } catch (e: Exception) {
      Log.e(TAG, "Download image error: ${e.message}", e)
      null
    }
  }

  /** 将本地图片转为 data URI（base64） */
  private fun imagePathToDataUri(path: String): String? {
    return try {
      val file = File(path)
      if (!file.exists()) return null
      val bytes = file.readBytes()
      val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
      // 根据扩展名判断 mime type
      val mime = when {
        path.endsWith(".png", true) -> "image/png"
        path.endsWith(".jpg", true) || path.endsWith(".jpeg", true) -> "image/jpeg"
        path.endsWith(".webp", true) -> "image/webp"
        else -> "image/jpeg"
      }
      "data:$mime;base64,$base64"
    } catch (e: Exception) {
      Log.w(TAG, "imagePathToDataUri failed: ${e.message}")
      null
    }
  }
}

/** 对话消息（含可选图片路径）— 供云端 API 使用，区别于 UI 的 chat.ChatMessage */
data class CloudChatMessage(
  val role: String,        // "user" | "assistant" | "system"
  val content: String,
  val imagePaths: List<String> = emptyList(),
)
