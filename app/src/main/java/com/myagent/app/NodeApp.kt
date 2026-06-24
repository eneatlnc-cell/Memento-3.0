package com.myagent.app

import android.app.Application
import android.os.StrictMode
import com.myagent.app.memory.MemoryManager
import com.myagent.app.model.PersonaManager

/**
 * Android Application 单例 — 持有全局 SecurePrefs、MemoryManager、PersonaManager。
 */
class NodeApp : Application() {
  val prefs: SecurePrefs by lazy { SecurePrefs(this) }
  val memoryManager: MemoryManager by lazy { MemoryManager(this) }
  val personaManager: PersonaManager by lazy { PersonaManager(this) }

  @Volatile private var runtimeInstance: NodeRuntime? = null

  /**
   * 返回进程唯一的 NodeRuntime，首次使用时创建。
   */
  fun ensureRuntime(): NodeRuntime {
    runtimeInstance?.let { return it }
    return synchronized(this) {
      runtimeInstance ?: NodeRuntime(this, prefs, memoryManager, personaManager).also { runtimeInstance = it }
    }
  }

  /**
   * 读取 runtime 但不触发启动，供生命周期探测和服务使用。
   */
  fun peekRuntime(): NodeRuntime? = runtimeInstance

  override fun onCreate() {
    super.onCreate()
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