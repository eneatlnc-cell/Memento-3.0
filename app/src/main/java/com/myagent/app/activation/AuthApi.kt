package com.myagent.app.activation

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 后台鉴权 API — 火山引擎函数计算通信层。
 *
 * 当前阶段：ServerlessEndpoint 为空，自动走本地离线模式。
 * 正式版上线后，只需填入端点地址即可切换为在线校验。
 *
 * 两个 API：
 * - POST /api/activate  →  { code: "xxx" }  →  { token: "eyJ...", expires_in: 86400 }
 * - POST /api/download   →  { token: "xxx" }  →  { url: "https://tos...?sign=...", expires_in: 3600 }
 */
object AuthApi {
  private const val TAG = "AuthApi"
  private const val CONNECT_TIMEOUT_MS = 10_000
  private const val READ_TIMEOUT_MS = 10_000

  /**
   * 后台端点地址。正式上线时替换为函数计算 HTTPS 地址。
   * 留空 → 自动使用离线模式（硬编码激活码 + 公读 CDN下载）。
   */
  private var ServerlessEndpoint: String? = null

  // ── 公开 API ──

  /** 是否已配置后台端点 */
  val isOnline: Boolean get() = !ServerlessEndpoint.isNullOrBlank()

  /** 设置后台端点，供外部初始化时调用 */
  fun setEndpoint(url: String) {
    ServerlessEndpoint = url.trimEnd('/')
    Log.i(TAG, "Auth endpoint set: ${hostOf(ServerlessEndpoint)}")
  }

  /**
   * 在线激活码校验。
   *
   * @return [ActivateResult] 成功时含 token 和过期时间
   */
  fun activate(code: String): ActivateResult {
    if (!isOnline) {
      Log.w(TAG, "ServerlessEndpoint not configured, skip online activation")
      return ActivateResult(false, "后台未配置")
    }
    return try {
      val json = JSONObject().apply { put("code", code) }
      val response = post("${ServerlessEndpoint}/api/activate", json.toString())
      if (response == null) {
        ActivateResult(false, "网络错误，请稍后重试")
      } else {
        val ok = response.optBoolean("ok", false)
        if (ok) {
          ActivateResult(
            success = true,
            token = response.optString("token", ""),
            expiresIn = response.optLong("expires_in", 86400),
          )
        } else {
          ActivateResult(false, response.optString("error", "激活码无效"))
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Online activation failed: ${e.message}", e)
      ActivateResult(false, "网络错误: ${e.message}")
    }
  }

  /**
   * 获取模型下载预签名 URL。
   *
   * @return 带签名的下载链接，或 null 表示失败
   */
  fun getDownloadUrl(token: String): String? {
    if (!isOnline) {
      Log.w(TAG, "ServerlessEndpoint not configured, cannot get download URL")
      return null
    }
    return try {
      val json = JSONObject().apply { put("token", token) }
      val response = post("${ServerlessEndpoint}/api/download", json.toString())
      response?.optString("url", null)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
      Log.e(TAG, "Get download URL failed: ${e.message}", e)
      null
    }
  }

  // ── 内部 HTTP ──

  private fun hostOf(urlStr: String?): String =
    if (urlStr.isNullOrEmpty()) "unknown"
    else runCatching { URL(urlStr).host }.getOrNull() ?: "unknown"

  private fun post(urlStr: String, body: String): JSONObject? {
    var connection: HttpURLConnection? = null
    try {
      connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        setRequestProperty("User-Agent", "Memento/2.0")
      }
      OutputStreamWriter(connection.outputStream).use { it.write(body) }
      val code = connection.responseCode
      if (code !in 200..299) {
        Log.w(TAG, "POST ${hostOf(urlStr)} → HTTP $code")
        return null
      }
      val text = BufferedReader(InputStreamReader(connection.inputStream)).readText()
      return JSONObject(text)
    } catch (e: Exception) {
      Log.e(TAG, "POST ${hostOf(urlStr)} failed: ${e.message}")
      return null
    } finally {
      connection?.disconnect()
    }
  }
}

/**
 * 在线激活结果。
 */
data class ActivateResult(
  val success: Boolean,
  val error: String = "",
  val token: String = "",
  val expiresIn: Long = 0,
)