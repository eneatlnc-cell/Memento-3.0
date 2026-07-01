package com.myagent.app.multimodal

/**
 * 视频渲染配置 — 用户可选画质预设 + 时长调整。
 *
 * v3.0：视频输出时长默认 5 秒，用户可调 5-15 秒。
 * 视频输入固定截取前 5 秒（帧采样方案，见 VideoFrameExtractor）。
 */
data class VideoConfig(
  val width: Int = 854,
  val height: Int = 480,
  val fps: Int = 24,
  val maxDuration: Int = 5,
) {
  companion object {
    val LOW = VideoConfig(854, 480, 24, 5)
    val MEDIUM = VideoConfig(1280, 720, 30, 10)
    val HIGH = VideoConfig(1920, 1080, 30, 15)

    val PRESETS = listOf(LOW, MEDIUM, HIGH)
    val PRESET_LABELS = listOf("低画质 (480p · 省电)", "标准画质 (720p)", "高画质 (1080p)")

    /** 视频输出时长范围（秒） */
    const val DURATION_MIN = 5
    const val DURATION_MAX = 15
    const val DURATION_STEP = 5

    fun fromPresetIndex(index: Int): VideoConfig =
      PRESETS.getOrElse(index) { LOW }

    /** 从 SharedPreferences 字符串反序列化，格式："width,height,fps,maxDuration" */
    fun fromString(raw: String?): VideoConfig {
      if (raw.isNullOrBlank()) return LOW
      val parts = raw.split(",").map { it.trim() }
      return try {
        VideoConfig(
          width = parts.getOrElse(0) { "854" }.toInt().coerceIn(320, 1920),
          height = parts.getOrElse(1) { "480" }.toInt().coerceIn(240, 1080),
          fps = parts.getOrElse(2) { "24" }.toInt().coerceIn(12, 60),
          maxDuration = parts.getOrElse(3) { "5" }.toInt().coerceIn(DURATION_MIN, DURATION_MAX),
        )
      } catch (_: Exception) {
        LOW
      }
    }

    /** 序列化为 SharedPreferences 字符串 */
    fun toString(config: VideoConfig): String =
      "${config.width},${config.height},${config.fps},${config.maxDuration}"
  }
}