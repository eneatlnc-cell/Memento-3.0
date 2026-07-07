package com.myagent.app

import android.app.Application
import android.os.StrictMode
import com.myagent.app.activation.ActivationManager
import com.myagent.app.cloud.CloudInferenceClient
import com.myagent.app.memory.MemoryManager
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.util.Date

/**
 * Android Application 单例 — 持有全局 SecurePrefs、MemoryManager、ActivationManager、CloudInferenceClient。
 *
 * v4.0 云端架构：移除端侧模型加载，所有智能能力由 CloudInferenceClient 提供。
 */
class NodeApp : Application() {
  val prefs: SecurePrefs by lazy { SecurePrefs(this) }
  val memoryManager: MemoryManager by lazy { MemoryManager(this) }
  val activationManager: ActivationManager by lazy { ActivationManager(this) }

  /** v4.0 云端推理客户端（GPT-4o + DALL-E 3 + Kling） */
  val cloudClient: CloudInferenceClient by lazy { CloudInferenceClient(this) }

  @Volatile private var runtimeInstance: NodeRuntime? = null

  /**
   * 返回进程唯一的 NodeRuntime，首次使用时创建。
   */
  fun ensureRuntime(): NodeRuntime {
    runtimeInstance?.let { return it }
    return synchronized(this) {
      runtimeInstance ?: NodeRuntime(this, prefs, memoryManager).also { runtimeInstance = it }
    }
  }

  /**
   * 读取 runtime 但不触发启动，供生命周期探测和服务使用。
   */
  fun peekRuntime(): NodeRuntime? = runtimeInstance

  override fun onCreate() {
    super.onCreate()

    // ═══════════════════════════════════════════════════════════════
    // Java 层未捕获异常处理器，写入崩溃日志文件
    // ═══════════════════════════════════════════════════════════════
    val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
      try {
        val logDir = File(getExternalFilesDir(null), "logs")
        logDir.mkdirs()
        val logFile = File(logDir, "crash.log")
        val writer = PrintWriter(FileWriter(logFile, true))
        writer.println("╔════════════════════════════════════════════╗")
        writer.println("║  JAVA CRASH — ${Date()}                   ║")
        writer.println("╚════════════════════════════════════════════╝")
        writer.println("Thread: ${t?.name ?: "unknown"}")
        e.printStackTrace(writer)
        writer.flush()
        writer.close()
      } catch (_: Exception) {}
      defaultHandler?.uncaughtException(t, e)
    }

    if (BuildConfig.DEBUG) {
      StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy
          .Builder()
          .detectAll()
          .penaltyLog()
          .build(),
      )
      StrictMode.setVmPolicy(
        StrictMode.VmPolicy
          .Builder()
          .detectAll()
          .penaltyLog()
          .build(),
      )
    }
  }
}
