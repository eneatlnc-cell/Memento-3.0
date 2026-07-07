package com.myagent.app.cloud

import android.content.Context
import android.util.Log

/**
 * API 密钥管理 — 支持两种模式：
 *
 * 1. 代理模式（默认）：用户无需填 Key，所有请求走代理端点。
 *    代理服务端持有真实 Key，统一管理。
 *
 * 2. 自填模式（高级用户）：用户在设置页填入自己的 OpenAI/Kling Key。
 *    请求直连官方 API。
 *
 * 优先级：自填 Key > 代理端点。
 */
class ApiKeyManager(private val context: Context) {

  companion object {
    private const val TAG = "ApiKeyManager"
    private const val PREFS_NAME = "cloud_api_keys"
    private const val KEY_MODE = "mode"
    private const val KEY_OPENAI = "openai_api_key"
    private const val KEY_KLING = "kling_api_key"
    private const val KEY_PROXY_ENDPOINT = "proxy_endpoint"

    // 默认代理端点（用户可改）。留空则要求用户配置。
    private const val DEFAULT_PROXY_ENDPOINT = ""
  }

  enum class Mode {
    PROXY,   // 代理模式（默认）
    DIRECT,  // 自填 Key 直连
  }

  private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  /** 当前模式 */
  var mode: Mode
    get() = Mode.valueOf(prefs.getString(KEY_MODE, Mode.PROXY.name) ?: Mode.PROXY.name)
    set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

  /** 用户自填的 OpenAI API Key */
  var openaiApiKey: String
    get() = prefs.getString(KEY_OPENAI, "") ?: ""
    set(value) = prefs.edit().putString(KEY_OPENAI, value).apply()

  /** 用户自填的 Kling API Key */
  var klingApiKey: String
    get() = prefs.getString(KEY_KLING, "") ?: ""
    set(value) = prefs.edit().putString(KEY_KLING, value).apply()

  /** 代理端点 URL（如 https://your-proxy.example.com） */
  var proxyEndpoint: String
    get() = prefs.getString(KEY_PROXY_ENDPOINT, DEFAULT_PROXY_ENDPOINT) ?: DEFAULT_PROXY_ENDPOINT
    set(value) = prefs.edit().putString(KEY_PROXY_ENDPOINT, value).apply()

  /** 是否已配置可用 */
  fun isConfigured(): Boolean {
    return when (mode) {
      Mode.DIRECT -> openaiApiKey.isNotEmpty()
      Mode.PROXY -> proxyEndpoint.isNotEmpty()
    }
  }

  /**
   * 获取对话/图片生成的 Base URL。
   * - DIRECT: 官方 OpenAI 端点
   * - PROXY: 用户配置的代理端点
   */
  fun getChatBaseUrl(): String {
    return when (mode) {
      Mode.DIRECT -> "https://api.openai.com/v1"
      Mode.PROXY -> {
        val ep = proxyEndpoint.trimEnd('/')
        if (ep.isEmpty()) {
          Log.w(TAG, "Proxy endpoint not configured, falling back to OpenAI direct")
          "https://api.openai.com/v1"
        } else {
          "$ep/v1"
        }
      }
    }
  }

  /**
   * 获取视频生成的 Base URL（Kling）。
   * - DIRECT: 官方 Kling 端点
   * - PROXY: 代理端点下的 /kling 路径
   */
  fun getKlingBaseUrl(): String {
    return when (mode) {
      Mode.DIRECT -> "https://api.klingai.com/v1"
      Mode.PROXY -> {
        val ep = proxyEndpoint.trimEnd('/')
        if (ep.isEmpty()) {
          Log.w(TAG, "Proxy endpoint not configured, falling back to Kling direct")
          "https://api.klingai.com/v1"
        } else {
          "$ep/kling/v1"
        }
      }
    }
  }

  /** 获取认证 header 值 */
  fun getAuthHeader(service: Service): String {
    return when (service) {
      Service.OPENAI -> {
        when (mode) {
          Mode.DIRECT -> "Bearer ${openaiApiKey}"
          Mode.PROXY -> "Bearer proxy"  // 代理服务端忽略此值，用自有 Key
        }
      }
      Service.KLING -> {
        when (mode) {
          Mode.DIRECT -> "Bearer ${klingApiKey}"
          Mode.PROXY -> "Bearer proxy"
        }
      }
    }
  }

  enum class Service { OPENAI, KLING }
}
