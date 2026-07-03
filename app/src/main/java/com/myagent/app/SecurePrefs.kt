package com.myagent.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Memento v2.0 偏好设置 — 外观、onboarding 等简单配置。
 */
class SecurePrefs(context: Context) {
  companion object {
    private const val plainPrefsName = "lingji.v2"
    private const val displayNameKey = "node.displayName"
    private const val appearanceThemeModeKey = "appearance.themeMode"
    private const val onboardingCompletedKey = "onboarding.completed"
    private const val welcomeCompletedKey = "welcome.completed"
    private const val instanceIdKey = "node.instanceId"
  }

  private val appContext = context.applicationContext

  private val plainPrefs: SharedPreferences =
    appContext.getSharedPreferences(plainPrefsName, Context.MODE_PRIVATE)

  private val masterKey by lazy {
    MasterKey.Builder(appContext)
      .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
      .build()
  }
  private val securePrefs: SharedPreferences by lazy {
    EncryptedSharedPreferences.create(
      appContext,
      "lingji.v2.secure",
      masterKey,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
  }

  // --- Instance ID ---
  private val _instanceId = MutableStateFlow(loadOrCreateInstanceId())
  val instanceId: StateFlow<String> = _instanceId

  // --- Display Name ---
  private val _displayName = MutableStateFlow(
    plainPrefs.getString(displayNameKey, null)?.trim().orEmpty()
      .ifEmpty { DeviceNames.bestDefaultNodeName(appContext) },
  )
  val displayName: StateFlow<String> = _displayName

  fun setDisplayName(value: String) {
    val trimmed = value.trim()
    plainPrefs.edit { putString(displayNameKey, trimmed) }
    _displayName.value = trimmed
  }

  // --- Appearance Theme ---
  private val _appearanceThemeMode = MutableStateFlow(
    AppearanceThemeMode.fromRawValue(plainPrefs.getString(appearanceThemeModeKey, null)),
  )
  val appearanceThemeMode: StateFlow<AppearanceThemeMode> = _appearanceThemeMode

  fun setAppearanceThemeMode(mode: AppearanceThemeMode) {
    plainPrefs.edit { putString(appearanceThemeModeKey, mode.rawValue) }
    _appearanceThemeMode.value = mode
  }

  // --- Onboarding ---
  private val _onboardingCompleted = MutableStateFlow(
    plainPrefs.getBoolean(onboardingCompletedKey, false),
  )
  val onboardingCompleted: StateFlow<Boolean> = _onboardingCompleted

  fun setOnboardingCompleted(value: Boolean) {
    plainPrefs.edit { putBoolean(onboardingCompletedKey, value) }
    _onboardingCompleted.value = value
  }

  // --- Welcome ---
  private val _welcomeCompleted = MutableStateFlow(
    plainPrefs.getBoolean(welcomeCompletedKey, false),
  )
  val welcomeCompleted: StateFlow<Boolean> = _welcomeCompleted

  fun setWelcomeCompleted() {
    plainPrefs.edit { putBoolean(welcomeCompletedKey, true) }
    _welcomeCompleted.value = true
  }

  // --- Secure Storage ---
  fun getString(key: String): String? = securePrefs.getString(key, null)

  fun putString(key: String, value: String) {
    securePrefs.edit { putString(key, value) }
  }

  fun remove(key: String) {
    securePrefs.edit { remove(key) }
  }

  private fun loadOrCreateInstanceId(): String {
    getString(instanceIdKey)?.trim()?.let { if (it.isNotBlank()) return it }
    plainPrefs.getString(instanceIdKey, null)?.trim()?.let { legacy ->
      if (legacy.isNotBlank()) {
        putString(instanceIdKey, legacy)
        plainPrefs.edit { remove(instanceIdKey) }
        return legacy
      }
    }
    val fresh = UUID.randomUUID().toString()
    putString(instanceIdKey, fresh)
    return fresh
  }
}