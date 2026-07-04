package com.myagent.app.model

import android.util.Log
import com.myagent.app.BuildConfig
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
 * FC 触发器鉴权：ACS3-HMAC-SHA256 签名认证。客户端用 BuildConfig
 * 中的 AccessKey（来自 local.properties，不进 git）对请求做签名。
 *
 * FC 接口规范（需在 FC 控制台实现）：
 *   GET {FC_PRESIGN_ENDPOINT}
 *   Header: Authorization: ACS3-HMAC-SHA256 ...
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
    // 凭证未配置（local.properties 缺失）→ 直接返回 null
    if (BuildConfig.FC_ACCESS_KEY_ID.isBlank() || BuildConfig.FC_ACCESS_KEY_SECRET.isBlank()) {
      Log.w(TAG, "FC credentials not configured (local.properties missing fc.accessKeyId/Secret), skip presign")
      return null
    }

    var conn: HttpURLConnection? = null
    return try {
      // 构造签名 headers
      val headers = mutableMapOf<String, String>()
      val authorization = Acs3Signer.sign(
        method = "GET",
        url = endpoint,
        headers = headers,
        accessKeyId = BuildConfig.FC_ACCESS_KEY_ID,
        accessKeySecret = BuildConfig.FC_ACCESS_KEY_SECRET,
      )
      headers["Authorization"] = authorization

      conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        // 写入签名 headers，跳过 host（HttpURLConnection 受限 header，自动设置）。
        // host 已参与签名计算（与 HttpURLConnection 自动设置的值一致），无需手动设置。
        headers.forEach { (k, v) ->
          if (k.lowercase() != "host") setRequestProperty(k, v)
        }
      }

      val code = conn.responseCode
      if (code != 200) {
        val errBody = try {
          conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(300)
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
