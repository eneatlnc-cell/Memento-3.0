package com.myagent.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.StrictMode
import com.myagent.app.activation.ActivationManager
import com.myagent.app.memory.MemoryManager
import com.myagent.app.model.ModelInstaller
import com.myagent.app.multimodal.MultiModalDispatcher

/**
 * Android Application 单例 — 持有全局 SecurePrefs、MemoryManager、ActivationManager。
 *
 * 内存管理：
 * - 注册 ComponentCallbacks2 监听系统内存压力
 * - onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL) 时卸载模型，释放 5.5GB RAM
 * - 不主动保活，遵循 Android 原生生命周期
 */
class NodeApp : Application() {
  val prefs: SecurePrefs by lazy { SecurePrefs(this) }
  val memoryManager: MemoryManager by lazy { MemoryManager(this) }
  val activationManager: ActivationManager by lazy { ActivationManager(this) }
  val modelInstaller: ModelInstaller by lazy { ModelInstaller(this, activationManager) }

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
    MultiModalDispatcher.init(this)

    // 注册内存压力回调
    registerComponentCallbacks(object : ComponentCallbacks2 {
      override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
          // 系统内存严重不足，立即卸载模型释放 ~5.5GB
          runtimeInstance?.unloadModel()
        }
      }

      override fun onLowMemory() {
        // 系统级低内存警告，卸载模型
        runtimeInstance?.unloadModel()
      }

      override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
    })

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