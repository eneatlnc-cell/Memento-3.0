package com.myagent.app.model

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从函数计算（FC）获取 OSS 预签名 URL。
 *
 * 背景：OSS bucket 为私有，匿名访问返回 403 AccessDenied。
 * FC 函数持有 AccessKey（配置在环境变量），生成临时预签名 URL
 * 返回给客户端。客户端用预签名 URL 直接 HTTP 下载，支持 Range
 * 断点续传，无需引入 OSS SDK（避免 APK 体积增大与凭证硬编码风险）。
 *
 * FC 接口规范（需在 FC 控制台实现）：
 *   GET {FC_PRESIGN_ENDPOINT}
 *   响应 200 JSON: {"model_url":"https://...","mmproj_url":"https://..."}
 *   预签名 URL 有效期建议 >= 1 小时（大文件下载耗时长）。
 *
 * URL 过期处理：下载中途 URL 过期会收到 403，downloadModelWithRetry
 * 重试时会重新调 fetch() 获取新 URL，从断点续传。
 */
object PresignUrlProvider {
  private const val TAG = "PresignUrlProvider"
  private const val TIMEOUT_MS = 10_000

  data class PresignUrls(val modelUrl: String, val mmprojUrl: String)

  /**
   * 从 FC 获取预签名 URL。失败返回 null（调用方回退到其他下载策略）。
   */
  fun fetch(endpoint: String): PresignUrls? {
    var conn: HttpURLConnection? = null
    return try {
      conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
      }
      val code = conn.responseCode
      if (code != 200) {
        val errBody = try {
          conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200)
        } catch (_: Exception) { null }
        Log.e(TAG, "FC presign failed: HTTP $code, body=$errBody")
        return null
      }
      val body = conn.inputStream.bufferedReader().use { it.readText() }
      val json = JSONObject(body)
      val modelUrl = json.optString("model_url").takeIf { it.isNotBlank() }
      val mmprojUrl = json.optString("mmproj_url").takeIf { it.isNotBlank() }
      if (modelUrl == null || mmprojUrl == null) {
        Log.e(TAG, "FC presign response missing fields: $body")
        return null
      }
      Log.i(TAG, "Got presign URLs from FC")
      PresignUrls(modelUrl, mmprojUrl)
    } catch (e: Exception) {
      Log.e(TAG, "FC presign error: ${e.message}")
      null
    } finally {
      conn?.disconnect()
    }
  }
}
