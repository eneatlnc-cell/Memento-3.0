package com.myagent.app.multimodal

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 视频帧采样器 — 从视频中提取关键帧作为图片列表。
 *
 * llama.cpp libmtmd 当前不直接接受视频输入，采用帧采样替代方案：
 * MediaMetadataRetriever 提取帧 → 压缩为 JPEG → 作为多张图片传给 Qwen3.5。
 *
 * v3.2 架构升级：
 * - 输入视频时长从 5s 缩短为 1s（用户手动挑选 1s 关键片段）
 * - 采样帧率从 2fps 提升为 24fps（1s × 24fps = 24 帧，达到视频最低标准）
 * - 帧尺寸从 1024 宽降为 256×256 缩略图（减少模型 token 负担）
 * - JPEG 质量从 80 降为 60（缩略图够用，进一步减小体积）
 *
 * 安全设计保留：
 * - MIME 类型白名单 + 文件头魔数校验
 * - OPTION_CLOSEST_SYNC 防止 B 帧解码崩溃
 * - 逐帧隔离 try-catch
 * - 首帧快速失败
 *
 * 崩溃风险评估：
 * - 1s 视频通常只有 1-2 个 I 帧，OPTION_CLOSEST_SYNC 会复用最近的关键帧
 * - 实际采到的可能是重复帧，但不会崩溃
 * - 24 帧 × 256×256 × JPEG60 ≈ 1.5MB，模型推理 token 量可控
 */
object VideoFrameExtractor {
  private const val TAG = "VideoFrameExtractor"

  /** 最大输入视频时长（秒）— v3.2: 5s → 1s */
  const val MAX_DURATION_SEC = 1

  /** 每秒采样帧数 — v3.2: 2fps → 24fps（视频最低标准） */
  const val FPS_SAMPLE = 24

  /** 缩略图尺寸 — v3.2 新增：256×256，减少模型 token 负担 */
  const val THUMBNAIL_SIZE = 256

  /** 最大文件大小（字节） */
  const val MAX_FILE_SIZE = 50L * 1024 * 1024

  /** 视频 MIME 类型白名单 — 只允许经过验证的格式 */
  private val ALLOWED_MIME_TYPES = setOf(
    "video/mp4",
    "video/mpeg4",
    "video/3gpp",
    "video/3gpp2",
    "video/webm",
    "video/x-matroska",
    "video/quicktime",
    "video/x-msvideo",
    "video/avi",
    "video/x-ms-wmv",
  )

  /**
   * 从视频 URI 提取帧列表。
   *
   * 安全策略：
   * 1. MIME 类型白名单预检 — 拒绝未知格式
   * 2. 文件头魔数校验 — 拒绝损坏文件
   * 3. 首帧快速测试 — 如果第一帧就失败，立即中止
   * 4. 逐帧隔离 — 单帧失败跳过继续
   * 5. 使用 OPTION_CLOSEST_SYNC — 只提取关键帧，跳过 B 帧解码
   *
   * @return 帧文件路径列表，失败返回空列表
   */
  fun extractFrames(
    context: Context,
    videoUri: Uri,
    cacheDir: File,
  ): List<String> {
    // ── 1. MIME 类型预检 ──
    val mimeType = context.contentResolver.getType(videoUri)
    if (mimeType != null && mimeType !in ALLOWED_MIME_TYPES) {
      Log.w(TAG, "Unsupported video MIME type: $mimeType, rejecting to avoid native crash")
      return emptyList()
    }
    Log.i(TAG, "Video MIME: $mimeType")

    // ── 2. 文件头魔数校验 ──
    if (!validateVideoHeader(context, videoUri)) {
      Log.w(TAG, "Video header validation failed, file may be corrupt")
      return emptyList()
    }

    // ── 3. MediaMetadataRetriever 提取 ──
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(context, videoUri)

      // 检查时长
      val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
      val durationMs = durationStr?.toLongOrNull() ?: 0L
      val actualDurationSec = (durationMs / 1000).toInt()
      if (actualDurationSec <= 0) {
        Log.w(TAG, "Video duration is 0, cannot extract frames")
        return emptyList()
      }

      val sampleDurationMs = minOf(durationMs, MAX_DURATION_SEC * 1000L)
      val totalFrames = minOf(MAX_DURATION_SEC, actualDurationSec) * FPS_SAMPLE
      val intervalUs = (1_000_000L / FPS_SAMPLE).coerceAtLeast(200_000L)

      Log.i(TAG, "Extracting frames: duration=${actualDurationSec}s, sampling=${sampleDurationMs}ms, frames=$totalFrames")

      // ── 4. 首帧快速测试 ──
      val firstFrame = try {
        retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
      } catch (t: Throwable) {
        Log.e(TAG, "First frame extraction crashed: ${t.javaClass.name} — ${t.message}")
        null
      }
      if (firstFrame == null) {
        Log.w(TAG, "First frame extraction failed, video format likely unsupported by this device")
        return emptyList()
      }
      firstFrame.recycle()

      // ── 5. 逐帧提取（每帧独立 try-catch） ──
      val frames = mutableListOf<String>()
      for (i in 0 until totalFrames) {
        val timeUs = (i * intervalUs).coerceAtMost(sampleDurationMs * 1000L - 1)

        val bitmap = try {
          // OPTION_CLOSEST_SYNC: 只返回关键帧（I 帧），避免解码 B 帧时的解码器崩溃
          retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (t: Throwable) {
          Log.w(TAG, "Frame $i extraction crashed: ${t.javaClass.name} — ${t.message}, skipping")
          null
        } ?: continue

        var scaled: Bitmap? = null
        try {
          // v3.2: 缩放至 256×256 缩略图（原 1024 宽）
          // 缩略图用于模型理解，256×256 足够 Qwen3.5-VL 识别内容
          scaled = if (bitmap.width != THUMBNAIL_SIZE || bitmap.height != THUMBNAIL_SIZE) {
            Bitmap.createScaledBitmap(bitmap, THUMBNAIL_SIZE, THUMBNAIL_SIZE, true)
          } else bitmap

          val frameFile = File(cacheDir, "vf_${System.currentTimeMillis()}_${i}.jpg")
          FileOutputStream(frameFile).use { out ->
            // v3.2: JPEG 质量从 80 降为 60（缩略图够用）
            scaled.compress(Bitmap.CompressFormat.JPEG, 60, out)
          }
          frames.add(frameFile.absolutePath)
        } catch (t: Throwable) {
          Log.w(TAG, "Frame $i save failed: ${t.message}")
        } finally {
          // H-M5 修复：compress 抛异常时 scaled bitmap（scaled != bitmap）漏回收
          if (scaled != null && scaled != bitmap) scaled.recycle()
          bitmap.recycle()
        }
      }

      Log.i(TAG, "Extracted ${frames.size}/${totalFrames} frames from video")
      frames
    } catch (t: Throwable) {
      Log.e(TAG, "Frame extraction failed: ${t.javaClass.name} — ${t.message}", t)
      emptyList()
    } finally {
      try { retriever.release() } catch (_: Exception) {}
    }
  }

  /**
   * 验证视频文件头魔数，防止损坏文件传入 MediaMetadataRetriever 导致原生崩溃。
   *
   * 支持的格式及魔数：
   * - MP4/MOV: 偏移 4 处为 "ftyp"（ISO BMFF）
   * - WebM: 偏移 0 处为 0x1A45DFA3（EBML）
   * - 3GPP: 同 MP4
   * - AVI: 偏移 0 处为 "RIFF"
   * - MKV: 偏移 0 处为 0x1A45DFA3（同 WebM）
   * - WMV: 偏移 0 处为 0x3026B275（ASF）
   */
  private fun validateVideoHeader(context: Context, uri: Uri): Boolean {
    return try {
      context.contentResolver.openInputStream(uri)?.use { stream ->
        val header = ByteArray(12)
        val read = stream.read(header)
        if (read < 8) return@use false

        // ISO BMFF (MP4/MOV/3GPP): bytes 4-7 = "ftyp"
        if (header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte()
          && header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()
        ) return@use true

        // EBML (WebM/MKV): first 4 bytes = 0x1A 0x45 0xDF 0xA3
        if (header[0] == 0x1A.toByte() && header[1] == 0x45.toByte()
          && header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()
        ) return@use true

        // RIFF (AVI): bytes 0-3 = "RIFF"
        if (header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte()
          && header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte()
        ) return@use true

        // ASF (WMV): bytes 0-3 = 0x30 0x26 0xB2 0x75
        if (header[0] == 0x30.toByte() && header[1] == 0x26.toByte()
          && header[2] == 0xB2.toByte() && header[3] == 0x75.toByte()
        ) return@use true

        Log.w(TAG, "Unknown video header: ${header.take(read).joinToString { "%02X".format(it) }}")
        false
      } ?: false
    } catch (e: Exception) {
      Log.w(TAG, "Video header validation failed: ${e.message}")
      false
    }
  }

  /**
   * 获取视频时长（秒），用于 UI 显示。
   */
  fun getDurationSeconds(context: Context, videoUri: Uri): Int {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(context, videoUri)
      val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
      (durationStr?.toLongOrNull() ?: 0L).let { (it / 1000).toInt() }
    } catch (_: Exception) {
      0
    } finally {
      try { retriever.release() } catch (_: Exception) {}
    }
  }

  /**
   * 获取视频文件大小（字节），-1 表示获取失败。
   */
  fun getFileSize(context: Context, videoUri: Uri): Long {
    return try {
      context.contentResolver.openAssetFileDescriptor(videoUri, "r")?.use { it.length } ?: -1
    } catch (_: Exception) {
      -1
    }
  }
}