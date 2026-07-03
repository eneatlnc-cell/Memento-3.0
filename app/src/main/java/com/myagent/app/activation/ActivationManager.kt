package com.myagent.app.activation

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.myagent.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 激活管理器 — 激活状态持久化 + 鉴权 Token 加密存储。
 *
 * C-S1/S2/S3 修复后的策略：
 * - 移除硬编码离线激活码（C-S1），激活必须通过服务端校验
 * - 在线模式失败时直接判定激活失败，不再静默回退（C-S3）
 * - Token 改用 EncryptedSharedPreferences 加密存储（C-S2），不再明文写入外部存储
 * - 激活标志（activated.txt）仍保留在外部存储，仅为布尔标记不含敏感信息
 *
 * Debug 版本自动激活，跳过激活码输入。
 */
class ActivationManager(context: Context) {
  companion object {
    private const val TAG = "ActivationManager"
    private const val ACTIVATION_DIR = "activation"
    private const val ACTIVATION_FILE = "activated.txt"
    private const val LEGACY_TOKEN_FILE = "auth_token.txt"  // 旧明文文件，仅用于迁移后删除
    private const val SECURE_PREFS_NAME = "activation_secure"
    private const val KEY_TOKEN = "auth.token"
    private const val KEY_TOKEN_EXPIRES_AT = "auth.token.expiresAt"
  }

  private val appContext = context.applicationContext

  // C-S2 修复：Token 用 EncryptedSharedPreferences 加密存储
  private val securePrefs: SharedPreferences by lazy {
    val masterKey = MasterKey.Builder(appContext)
      .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
      .build()
    EncryptedSharedPreferences.create(
      appContext,
      SECURE_PREFS_NAME,
      masterKey,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
  }

  /**
   * 检查是否已激活。Debug 版本始终返回 true。
   */
  fun isActivated(): Boolean {
    if (BuildConfig.DEBUG) return true
    return getActivationFile().exists()
  }

  /**
   * 验证激活码 — 仅在线模式，服务端校验。
   *
   * C-S1/S3 修复：移除硬编码离线码，在线失败不再静默回退。
   *
   * @return true 表示激活成功
   */
  suspend fun activate(code: String): Boolean = withContext(Dispatchers.IO) {
    if (BuildConfig.DEBUG) return@withContext true
    val trimmed = code.trim()
    if (trimmed.isEmpty()) return@withContext false

    // C-S3 修复：离线时直接失败，不再回退到本地常量
    if (!AuthApi.isOnline) {
      Log.w(TAG, "Cannot activate: offline (no network or AuthApi unavailable)")
      return@withContext false
    }

    val result = AuthApi.activate(trimmed)
    if (result.success && result.token.isNotBlank()) {
      saveActivation(trimmed)
      saveToken(result.token, result.expiresIn)
      Log.i(TAG, "Online activation success, token expires in ${result.expiresIn}s")
      return@withContext true
    }

    Log.w(TAG, "Activation failed: ${result.error}")
    return@withContext false
  }

  /**
   * 获取鉴权 Token（用于模型下载）。
   *
   * C-S2 修复：从 EncryptedSharedPreferences 读取，并检查过期时间。
   * 兼容旧版：若 SecurePrefs 中无 token 但旧明文文件存在，迁移后删除明文文件。
   */
  fun getToken(): String? {
    if (BuildConfig.DEBUG) return null

    val token = securePrefs.getString(KEY_TOKEN, null)?.trim()
    if (!token.isNullOrEmpty()) {
      // 检查过期时间
      val expiresAt = securePrefs.getLong(KEY_TOKEN_EXPIRES_AT, 0L)
      if (expiresAt > 0L && System.currentTimeMillis() > expiresAt) {
        Log.i(TAG, "Token expired, clearing")
        securePrefs.edit { remove(KEY_TOKEN); remove(KEY_TOKEN_EXPIRES_AT) }
        return null
      }
      return token
    }

    // 迁移：从旧明文文件读取一次，迁移到 SecurePrefs 后删除明文文件
    return migrateLegacyToken()
  }

  /** 一次性迁移：旧版明文 token → EncryptedSharedPreferences */
  private fun migrateLegacyToken(): String? {
    val file = getLegacyTokenFile()
    if (!file.exists()) return null
    return try {
      val token = file.readText().trim().takeIf { it.isNotEmpty() } ?: run {
        file.delete()
        return null
      }
      securePrefs.edit {
        putString(KEY_TOKEN, token)
        putLong(KEY_TOKEN_EXPIRES_AT, 0L)  // 旧 token 无过期信息，设为 0 表示不过期
      }
      file.delete()
      Log.i(TAG, "Migrated legacy plaintext token to SecurePrefs")
      token
    } catch (e: Exception) {
      Log.e(TAG, "Legacy token migration failed: ${e.message}")
      null
    }
  }

  private fun saveActivation(code: String) {
    val file = getActivationFile()
    file.parentFile?.mkdirs()
    try {
      file.writeText(code)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to save activation: ${e.message}")
    }
  }

  private fun saveToken(token: String, expiresInSec: Long) {
    val expiresAt = if (expiresInSec > 0) System.currentTimeMillis() + expiresInSec * 1000 else 0L
    securePrefs.edit {
      putString(KEY_TOKEN, token)
      putLong(KEY_TOKEN_EXPIRES_AT, expiresAt)
    }
    // C-S2 修复：删除旧明文文件（如果存在），避免明文残留
    getLegacyTokenFile().delete()
  }

  private fun getActivationFile(): File {
    val dir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
    return File(dir, "$ACTIVATION_DIR/$ACTIVATION_FILE")
  }

  private fun getLegacyTokenFile(): File {
    val dir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
    return File(dir, "$ACTIVATION_DIR/$LEGACY_TOKEN_FILE")
  }
}
