package com.myagent.app.model

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.FileReader

/**
 * 设备能力检测 — SOC 型号 + 内存容量。
 *
 * 用于决定 llama.cpp 推理引擎后端选择：
 * - 骁龙 8 系列 + ≥12GB RAM → Hexagon NPU + Adreno OpenCL 加速（n_gpu_layers=99）
 * - 其他平台 → CPU 多线程（n_gpu_layers=0）
 *
 * 骁龙 8 系列检测逻辑：
 * 1. 读取 /proc/cpuinfo 中的 Hardware 字段
 * 2. 匹配 "qcom" / "Qualcomm" / "Snapdragon"
 * 3. 通过 ro.board.platform 确认平台代号
 */
object DeviceCapability {
  private const val TAG = "DeviceCapability"

  /** NPU 加速所需的最小内存（GB） */
  const val NPU_MIN_RAM_GB = 12

  /** 骁龙 8 系列平台代号前缀 */
  private val SD8_PLATFORMS = setOf(
    "lahaina",   // SD888 / SD8 Gen1
    "taro",      // SD8 Gen1
    "kalama",    // SD8 Gen2
    "pineapple", // SD8 Gen3
    "sun",       // SD8 Gen4 / SD8 Elite
    "parrot",    // SD8s Gen3
    "monaco",    // SD8 Gen2 (alternate)
    "waipio",    // SD8+ Gen1
    "diwali",    // SD8 Gen2 (alternate)
    "anjo",      // SD8s Gen3 (alternate)
  )

  /** 骁龙 8 系列 Hardware 字段关键词 */
  private val SD8_HARDWARE_KEYWORDS = listOf(
    "qcom", "qualcomm", "snapdragon",
  )

  data class Info(
    val isSd8: Boolean,
    val platform: String,
    val hardware: String,
    val totalRamGb: Int,
    val canUseNpu: Boolean,
  )

  private var cached: Info? = null

  fun detect(context: Context): Info {
    cached?.let { return it }
    return detectInternal(context).also { cached = it }
  }

  private fun detectInternal(context: Context): Info {
    val hardware = readCpuinfoHardware()
    val platform = readSystemProperty("ro.board.platform")
    val totalRamGb = getTotalRamGb(context)

    val isSd8 = isSnapdragon8(platform, hardware)
    val canUseNpu = isSd8 && totalRamGb >= NPU_MIN_RAM_GB

    val info = Info(
      isSd8 = isSd8,
      platform = platform,
      hardware = hardware,
      totalRamGb = totalRamGb,
      canUseNpu = canUseNpu,
    )

    Log.i(TAG, "Device: platform=$platform, hardware=$hardware, " +
      "ram=${totalRamGb}GB, sd8=$isSd8, npu=$canUseNpu")

    return info
  }

  // ── 骁龙 8 检测 ──

  private fun isSnapdragon8(platform: String, hardware: String): Boolean {
    // 平台代号匹配
    if (platform.isNotBlank()) {
      val normalized = platform.lowercase().trim()
      if (normalized in SD8_PLATFORMS) return true
      // 也支持 "msm" 前缀的骁龙 8 平台
      if (normalized.startsWith("msm") && normalized.any { it.isDigit() }) return true
    }

    // Hardware 字段匹配
    if (hardware.isNotBlank()) {
      val normalized = hardware.lowercase().trim()
      if (SD8_HARDWARE_KEYWORDS.any { normalized.contains(it) }) return true
    }

    // 制造商兜底：如果 SOC 制造商是 Qualcomm 且机型年份 >= 2022
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      if (Build.SOC_MANUFACTURER.lowercase().contains("qualcomm")) return true
    }

    return false
  }

  // ── 内存检测 ──

  private fun getTotalRamGb(context: Context): Int {
    return try {
      val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
      val memInfo = ActivityManager.MemoryInfo()
      am.getMemoryInfo(memInfo)
      (memInfo.totalMem / (1024 * 1024 * 1024)).toInt()
    } catch (e: Exception) {
      Log.w(TAG, "Failed to get RAM info: ${e.message}")
      // 用 /proc/meminfo 兜底
      readMemTotalGb()
    }
  }

  private fun readMemTotalGb(): Int {
    return try {
      BufferedReader(FileReader("/proc/meminfo")).use { reader ->
        var line: String?
        while (reader.readLine().also { line = it } != null) {
          line?.let {
            if (it.startsWith("MemTotal:")) {
              val kb = it.replace(Regex("[^0-9]"), "").toLongOrNull() ?: 0L
              return (kb / (1024 * 1024)).toInt()
            }
          }
        }
      }
      8 // 保守默认
    } catch (e: Exception) {
      8
    }
  }

  // ── 系统信息读取 ──

  private fun readCpuinfoHardware(): String {
    return try {
      BufferedReader(FileReader("/proc/cpuinfo")).use { reader ->
        var line: String?
        while (reader.readLine().also { line = it } != null) {
          line?.let {
            if (it.startsWith("Hardware")) {
              val parts = it.split(":").map { p -> p.trim() }
              return parts.getOrElse(1) { "" }
            }
          }
        }
      }
      ""
    } catch (e: Exception) {
      ""
    }
  }

  @Suppress("SameParameterValue")
  private fun readSystemProperty(key: String): String {
    return try {
      val clazz = Class.forName("android.os.SystemProperties")
      val method = clazz.getMethod("get", String::class.java, String::class.java)
      method.invoke(null, key, "") as String
    } catch (e: Exception) {
      ""
    }
  }
}