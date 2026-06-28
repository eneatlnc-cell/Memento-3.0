package com.myagent.app.activation

import android.content.Context
import com.myagent.app.BuildConfig
import java.io.File

/**
 * 激活管理器 — 激活状态持久化到外部存储（清除数据不丢失）。
 *
 * 激活码校验：当前版本使用简单校验，后续可扩展为在线验证。
 * Debug 版本自动激活，跳过激活码输入。
 */
class ActivationManager(context: Context) {
  companion object {
    private const val ACTIVATION_DIR = "activation"
    private const val ACTIVATION_FILE = "activated.txt"

    /** 有效激活码（后续可扩展为在线验证） */
    private val VALID_CODES = setOf(
      "LINGJI2024",
      "MYAGENT-V2",
      "LINGJI-PRO",
      "MEMENTO2024",
      "MEMENTO-V1",
    )
  }

  private val appContext = context.applicationContext

  /**
   * 检查是否已激活。Debug 版本始终返回 true。
   */
  fun isActivated(): Boolean {
    if (BuildConfig.DEBUG) return true
    return getActivationFile().exists()
  }

  /**
   * 尝试验证并保存激活码。
   *
   * @return true 表示激活成功
   */
  fun activate(code: String): Boolean {
    if (BuildConfig.DEBUG) return true
    val trimmed = code.trim()
    if (trimmed.isEmpty()) return false

    if (trimmed !in VALID_CODES) return false

    val file = getActivationFile()
    file.parentFile?.mkdirs()
    try {
      file.writeText(trimmed)
      return true
    } catch (_: Exception) {
      return false
    }
  }

  private fun getActivationFile(): File {
    val dir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
    return File(dir, "$ACTIVATION_DIR/$ACTIVATION_FILE")
  }
}