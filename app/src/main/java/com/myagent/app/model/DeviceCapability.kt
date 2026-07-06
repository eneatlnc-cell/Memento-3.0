package com.myagent.app.model

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.FileReader

/**
 * 设备能力检测 — SOC 型号 + 芯片平台分级 + 内存容量。
 *
 * 芯片平台分级（决定原生库加载策略和后端选择）：
 *
 *  TIER_SD8_NPU  — 骁龙 8 系列（SM8450+），有 Hexagon NPU + Adreno OpenCL
 *                   → 加载: opencl_stub + cdsprpc_stub + llama + llama_jni
 *                   → 后端: Hexagon NPU (n_gpu_layers=99) 或 OpenCL 回退
 *  TIER_SD8_CPU  — 骁龙 8 系列但 RAM < 12GB
 *                   → 加载: opencl_stub + cdsprpc_stub + llama + llama_jni
 *                   → 后端: CPU-only (n_gpu_layers=0)
 *  TIER_SD7      — 骁龙 7 系列，有 Adreno OpenCL，无 Hexagon NPU
 *                   → 加载: opencl_stub + cdsprpc_stub + llama + llama_jni
 *                   → 后端: CPU-only (n_gpu_layers=0)
 *  TIER_QCOM     — 其他高通平台（骁龙 6/4/2 系列）
 *                   → 加载: opencl_stub + cdsprpc_stub + llama + llama_jni
 *                   → 后端: CPU-only
 *  TIER_OTHER    — 麒麟 / 天玑 / 三星 Exynos / 鸿蒙 / 其他
 *                   → 加载: opencl_stub + cdsprpc_stub + llama + llama_jni
 *                   → 后端: CPU-only（nGpuLayers=0，最保守）
 *
 * 所有平台都需要加载 opencl_stub + cdsprpc_stub，因为 libllama.so
 * 来自 llama.rn 0.12.5 预编译，硬编码了 OpenCL + Hexagon 的 DT_NEEDED。
 * 这些 stub 在 CPU-only 模式下不会被实际调用。
 */
object DeviceCapability {
  private const val TAG = "DeviceCapability"

  /** 芯片平台分级 */
  enum class Tier {
    /** 骁龙 8 系列 + RAM ≥12GB → NPU 加速 */
    SD8_NPU,
    /** 骁龙 8 系列但 RAM 不足 → CPU-only */
    SD8_CPU,
    /** 骁龙 7 系列 → CPU-only */
    SD7,
    /** 其他高通 → CPU-only */
    QCOM,
    /** 麒麟 / 天玑 / Exynos / 鸿蒙 / 其他 → CPU-only */
    OTHER,
  }

  /** NPU 加速所需的最小内存（GB） */
  const val NPU_MIN_RAM_GB = 12

  /** 骁龙 8 系列平台代号（SM8450+） */
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

  /** 骁龙 7 系列平台代号 */
  private val SD7_PLATFORMS = setOf(
    "yupik",     // SD7 Gen1
    "holi",      // SD780G
    "lito",      // SD765G
    "atoll",     // SD720G
    "bengal",    // SD680
    "khaje",     // SD680
    "trinket",   // SD665
    "sdm845",    // SD845
  )

  private val QCOM_HARDWARE_KEYWORDS = listOf(
    "qcom", "qualcomm", "snapdragon",
  )

  data class Info(
    val tier: Tier,
    val isSd8: Boolean,
    val platform: String,
    val hardware: String,
    val socManufacturer: String,
    val totalRamGb: Int,
    val canUseNpu: Boolean,
  )

  @Volatile private var cached: Info? = null

  fun detect(context: Context): Info {
    cached?.let { return it }
    return detectInternal(context).also { cached = it }
  }

  private fun detectInternal(context: Context): Info {
    val hardware = readCpuinfoHardware()
    val platform = readSystemProperty("ro.board.platform")
    val socMfr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      Build.SOC_MANUFACTURER ?: ""
    } else ""
    val totalRamGb = getTotalRamGb(context)

    val isQcom = isQualcommPlatform(platform, hardware, socMfr)
    val isSd8 = isQcom && isSnapdragon8Platform(platform)
    val isSd7 = isQcom && !isSd8 && isSnapdragon7Platform(platform)

    val tier = when {
      isSd8 && totalRamGb >= NPU_MIN_RAM_GB -> Tier.SD8_NPU
      isSd8 -> Tier.SD8_CPU
      isSd7 -> Tier.SD7
      isQcom -> Tier.QCOM
      else -> Tier.OTHER
    }

    val info = Info(
      tier = tier,
      isSd8 = isSd8,
      platform = platform,
      hardware = hardware,
      socManufacturer = socMfr,
      totalRamGb = totalRamGb,
      canUseNpu = tier == Tier.SD8_NPU,
    )

    Log.i(TAG, "Device: tier=$tier, platform=$platform, hardware=$hardware, " +
      "soc=$socMfr, ram=${totalRamGb}GB, npu=${info.canUseNpu}")

    return info
  }

  // ── 芯片平台检测 ──

  private fun isQualcommPlatform(platform: String, hardware: String, socMfr: String): Boolean {
    if (platform.isNotBlank() && platform.lowercase().trim() in SD8_PLATFORMS + SD7_PLATFORMS) return true
    if (hardware.isNotBlank() && QCOM_HARDWARE_KEYWORDS.any { hardware.lowercase().contains(it) }) return true
    if (socMfr.isNotBlank() && socMfr.lowercase().contains("qualcomm")) return true
    return false
  }

  private fun isSnapdragon8Platform(platform: String): Boolean {
    if (platform.isBlank()) return false
    val normalized = platform.lowercase().trim()
    return normalized in SD8_PLATFORMS
  }

  private fun isSnapdragon7Platform(platform: String): Boolean {
    if (platform.isBlank()) return false
    val normalized = platform.lowercase().trim()
    return normalized in SD7_PLATFORMS
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
      8
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