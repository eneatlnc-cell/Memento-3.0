package com.myagent.app.cloud

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.channels.awaitClose
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Kling Provider — 可灵视频生成。
 *
 * 可灵 API 是异步任务模式：
 *   1. POST 创建任务 → 返回 task_id
 *   2. GET 轮询任务状态 → 直到 succeeded
 *   3. 下载视频 URL 到本地
 *
 * 支持图生视频（关键帧→视频）和文生视频。
 *
 * 参考文档：https://docs.qingque.cn/d/home/eZQBm8vfYDKfTszpTAPlNHfin
 */
class KlingProvider(private val apiKeyManager: ApiKeyManager) {

  companion object {
    private const val TAG = "KlingProvider"
    private const val MODEL_IMAGE_TO_VIDEO = "kling-v2"  // 图生视频
    private const val MODEL_TEXT_TO_VIDEO = "kling-v2"   // 文生视频
    private const val POLL_INTERVAL_MS = 3000L
    private const val POLL_TIMEOUT_MS = 180_000L  // 3 分钟超时
  }

  private val client = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()

  /**
   * 图生视频：关键帧 → MP4。
   *
   * @param keyFrameImagePaths 关键帧图片本地路径列表（取第一张作为起始帧）
   * @param prompt             视频描述
   * @param duration           视频时长（秒）：5 | 10
   * @param onProgress         进度回调（0-100）
   * @return 下载到本地的视频文件路径，失败返回 null
   */
  suspend fun imageToVideo(
    keyFrameImagePaths: List<String>,
    prompt: String,
    duration: Int = 5,
    onProgress: (Int) -> Unit,
    outputDir: File,
  ): String? {
    if (keyFrameImagePaths.isEmpty()) {
      Log.e(TAG, "No key frame images provided")
      return null
    }

    val baseUrl = apiKeyManager.getKlingBaseUrl()
    val url = "$baseUrl/videos/image2video"

    // 将第一张关键帧转为 base64 data URI
    val firstFrame = imagePathToDataUri(keyFrameImagePaths.first())
    if (firstFrame == null) {
      Log.e(TAG, "Cannot read first key frame")
      return null
    }

    val requestBody = JSONObject().apply {
      put("model_name", MODEL_IMAGE_TO_VIDEO)
      put("image", firstFrame)
      put("prompt", prompt)
      put("duration", duration.toString())
      put("mode", "std")  // std | pro
    }

    val request = Request.Builder()
      .url(url)
      .header("Authorization", apiKeyManager.getAuthHeader(ApiKeyManager.Service.KLING))
      .header("Content-Type", "application/json")
      .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
      .build()

    // 1. 创建任务
    val taskId = try {
      val response = client.newCall(request).execute()
      if (!response.isSuccessful) {
        Log.e(TAG, "Create task failed: ${response.code} ${response.body?.string()}")
        return null
      }
      val json = JSONObject(response.body!!.string())
      json.optJSONObject("data")?.optString("task_id")
    } catch (e: Exception) {
      Log.e(TAG, "Create task error: ${e.message}", e)
      null
    }

    if (taskId.isNullOrEmpty()) {
      Log.e(TAG, "No task_id in response")
      return null
    }

    Log.i(TAG, "Kling task created: $taskId")

    // 2. 轮询任务状态
    val taskUrl = "$url/$taskId"
    val startTime = System.currentTimeMillis()

    while (System.currentTimeMillis() - startTime < POLL_TIMEOUT_MS) {
      delay(POLL_INTERVAL_MS)

      val pollRequest = Request.Builder()
        .url(taskUrl)
        .header("Authorization", apiKeyManager.getAuthHeader(ApiKeyManager.Service.KLING))
        .get()
        .build()

      try {
        val response = client.newCall(pollRequest).execute()
        if (!response.isSuccessful) {
          Log.w(TAG, "Poll failed: ${response.code}")
          continue
        }

        val json = JSONObject(response.body!!.string())
        val data = json.optJSONObject("data")
        val status = data?.optString("task_status")
        val progress = data?.optInt("task_progress", 0) ?: 0

        onProgress(progress.coerceIn(0, 100))

        when (status) {
          "succeed" -> {
            // 获取视频 URL
            val videoUrl = data?.optJSONObject("task_result")
              ?.optJSONArray("videos")
              ?.optJSONObject(0)
              ?.optString("url")
            if (videoUrl.isNullOrEmpty()) {
              Log.e(TAG, "No video URL in completed task")
              return null
            }
            Log.i(TAG, "Kling video ready: $videoUrl")
            return downloadVideo(videoUrl, outputDir)
          }
          "failed" -> {
            val failReason = data?.optString("task_status_msg") ?: "unknown"
            Log.e(TAG, "Kling task failed: $failReason")
            return null
          }
          else -> {
            // submitted / processing，继续轮询
            Log.d(TAG, "Kling status: $status ($progress%)")
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "Poll error: ${e.message}")
      }
    }

    Log.e(TAG, "Kling task timed out after ${POLL_TIMEOUT_MS}ms")
    return null
  }

  /**
   * 文生视频。
   *
   * @param prompt    视频描述
   * @param duration  视频时长（秒）：5 | 10
   * @param onProgress 进度回调
   * @return 下载到本地的视频文件路径，失败返回 null
   */
  suspend fun textToVideo(
    prompt: String,
    duration: Int = 5,
    onProgress: (Int) -> Unit,
    outputDir: File,
  ): String? {
    val baseUrl = apiKeyManager.getKlingBaseUrl()
    val url = "$baseUrl/videos/text2video"

    val requestBody = JSONObject().apply {
      put("model_name", MODEL_TEXT_TO_VIDEO)
      put("prompt", prompt)
      put("duration", duration.toString())
      put("mode", "std")
    }

    val request = Request.Builder()
      .url(url)
      .header("Authorization", apiKeyManager.getAuthHeader(ApiKeyManager.Service.KLING))
      .header("Content-Type", "application/json")
      .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
      .build()

    // 创建任务
    val taskId = try {
      val response = client.newCall(request).execute()
      if (!response.isSuccessful) {
        Log.e(TAG, "Create text2video task failed: ${response.code}")
        return null
      }
      val json = JSONObject(response.body!!.string())
      json.optJSONObject("data")?.optString("task_id")
    } catch (e: Exception) {
      Log.e(TAG, "Create text2video error: ${e.message}", e)
      null
    }

    if (taskId.isNullOrEmpty()) return null

    // 轮询（复用逻辑）
    val taskUrl = "$url/$taskId"
    val startTime = System.currentTimeMillis()

    while (System.currentTimeMillis() - startTime < POLL_TIMEOUT_MS) {
      delay(POLL_INTERVAL_MS)
      val pollRequest = Request.Builder()
        .url(taskUrl)
        .header("Authorization", apiKeyManager.getAuthHeader(ApiKeyManager.Service.KLING))
        .get()
        .build()

      try {
        val response = client.newCall(pollRequest).execute()
        if (!response.isSuccessful) continue
        val json = JSONObject(response.body!!.string())
        val data = json.optJSONObject("data")
        val status = data?.optString("task_status")
        val progress = data?.optInt("task_progress", 0) ?: 0
        onProgress(progress.coerceIn(0, 100))

        if (status == "succeed") {
          val videoUrl = data?.optJSONObject("task_result")
            ?.optJSONArray("videos")
            ?.optJSONObject(0)
            ?.optString("url")
          if (videoUrl != null) return downloadVideo(videoUrl, outputDir)
        } else if (status == "failed") return null
      } catch (e: Exception) {
        Log.w(TAG, "Poll error: ${e.message}")
      }
    }
    return null
  }

  /** 下载视频 URL 到本地文件 */
  private fun downloadVideo(url: String, outputDir: File): String? {
    return try {
      outputDir.mkdirs()
      val outputFile = File(outputDir, "kling_${System.currentTimeMillis()}.mp4")
      val request = Request.Builder().url(url).build()
      val response = client.newCall(request).execute()
      if (!response.isSuccessful) return null
      response.body!!.byteStream().use { input ->
        FileOutputStream(outputFile).use { output -> input.copyTo(output) }
      }
      outputFile.absolutePath
    } catch (e: Exception) {
      Log.e(TAG, "Download video error: ${e.message}", e)
      null
    }
  }

  /** 图片转 data URI */
  private fun imagePathToDataUri(path: String): String? {
    return try {
      val file = File(path)
      if (!file.exists()) return null
      val bytes = file.readBytes()
      val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
      val mime = when {
        path.endsWith(".png", true) -> "image/png"
        else -> "image/jpeg"
      }
      "data:$mime;base64,$base64"
    } catch (e: Exception) {
      Log.w(TAG, "imagePathToDataUri failed: ${e.message}")
      null
    }
  }
}
