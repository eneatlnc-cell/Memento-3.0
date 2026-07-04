package com.myagent.app.model

import android.util.Log
import com.myagent.app.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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
 * FC 接口规范：
 *   GET {FC_PRESIGN_ENDPOINT}?file=<文件名>
 *   Header: Authorization: ACS3-HMAC-SHA256 ...
 *   Header: Date: <RFC1123 GMT>
 *   响应 200 JSON: {"url":"https://oss预签名链接..."}
 *   预签名 URL 有效期建议 >= 1 小时（大文件下载耗时长）。
 *
 * 调用方式：分别对 model 和 mmproj 调用一次 fetch()，各拿一个 URL。
 *
 * URL 过期处理：下载中途 URL 过期会收到 403，downloadModelWithRetry
 * 重试时会重新调 fetch() 获取新 URL，从断点续传。
 */
object PresignUrlProvider {
  private const val TAG = "PresignUrlProvider"
  private const val TIMEOUT_MS = 10_000

  /** FC 预签名结果：成功带 url，失败带原因（供 UI 透传定位问题） */
  sealed class PresignResult {
    data class Success(val url: String) : PresignResult()
    data class Failure(val reason: String) : PresignResult()
  }

  /**
   * 从 FC 获取单个文件的预签名 URL。
   *
   * @param endpoint FC 根端点（如 https://xxx.fcapp.run，不含 path/query）
   * @param fileName OSS 对象名（如 Qwen3.5-0.8B-Q4_K_M.gguf）
   * @return PresignResult：成功返回 Success(url)，失败返回 Failure(reason)
   *         失败原因会透传到下载错误信息，帮助定位是凭证/签名/FC 服务/网络问题
   */
  fun fetch(endpoint: String, fileName: String): PresignResult {
    // 凭证未配置（local.properties 缺失）
    if (BuildConfig.FC_ACCESS_KEY_ID.isBlank() || BuildConfig.FC_ACCESS_KEY_SECRET.isBlank()) {
      val reason = "FC 凭证未配置（local.properties 缺少 fc.accessKeyId/accessKeySecret）"
      Log.w(TAG, reason)
      return PresignResult.Failure(reason)
    }

    // 构造完整请求 URL：endpoint?file=<URL编码的文件名>
    val encodedFile = URLEncoder.encode(fileName, "UTF-8")
      .replace("+", "%20")
      .replace("*", "%2A")
      .replace("%7E", "~")
    val fullUrl = if (endpoint.contains("?")) {
      "$endpoint&file=$encodedFile"
    } else {
      "$endpoint?file=$encodedFile"
    }
    Log.i(TAG, "Fetching presign URL for: $fileName")

    var conn: HttpURLConnection? = null
    return try {
      // 构造签名 headers（Acs3Signer 会原地补充 host、x-acs-date）
      val headers = mutableMapOf<String, String>()
      val authorization = Acs3Signer.sign(
        method = "GET",
        url = fullUrl,
        headers = headers,
        accessKeyId = BuildConfig.FC_ACCESS_KEY_ID,
        accessKeySecret = BuildConfig.FC_ACCESS_KEY_SECRET,
      )
      headers["Authorization"] = authorization

      conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        instanceFollowRedirects = true
        // FC 网关强制要求 Date header（RFC1123 格式），缺失会返回
        // MissingRequiredHeader: required HTTP header Date was not specified。
        // Date 不参与 ACS3 签名计算（只 x-acs-date 参与），仅用于网关防重放校验。
        setRequestProperty(
          "Date",
          DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now()),
        )
        // 写入签名 headers，跳过 host（HttpURLConnection 受限 header，自动设置）。
        // host 已参与签名计算（与 HttpURLConnection 自动设置的值一致），无需手动设置。
        headers.forEach { (k, v) ->
          if (k.lowercase() != "host") setRequestProperty(k, v)
        }
      }

      val code = conn.responseCode
      if (code != 200) {
        val errBody = try {
          conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(500)
        } catch (_: Exception) { null }
        val reason = when (code) {
          401, 403 -> "FC 签名认证失败（HTTP $code），检查 AccessKey/签名算法。body=$errBody"
          404 -> "FC 端点不存在（HTTP 404），检查 endpoint=$endpoint"
          else -> "FC 服务异常（HTTP $code）。body=$errBody"
        }
        Log.e(TAG, "FC presign failed for $fileName: $reason")
        return PresignResult.Failure(reason)
      }
      val body = conn.inputStream.bufferedReader().use { it.readText() }
      val json = JSONObject(body)
      val url = json.optString("url").takeIf { it.isNotBlank() }
      if (url == null) {
        val reason = "FC 响应缺少 url 字段，body=$body"
        Log.e(TAG, reason)
        return PresignResult.Failure(reason)
      }
      Log.i(TAG, "Got presign URL for $fileName (length=${url.length})")
      PresignResult.Success(url)
    } catch (e: Exception) {
      val reason = "FC 连接失败：${e.javaClass.simpleName}: ${e.message}"
      Log.e(TAG, reason, e)
      PresignResult.Failure(reason)
    } finally {
      conn?.disconnect()
    }
  }
}
