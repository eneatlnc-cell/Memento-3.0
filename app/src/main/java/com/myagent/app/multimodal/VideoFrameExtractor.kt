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
 * 由于 LiteRT-LM 未暴露 Content.VideoFile 类型，采用帧采样替代方案：
 * MediaMetadataRetriever 提取帧 → 压缩为 JPEG → 作为多张图片传给 E4B。
 *
 * 限制：
 * - 视频最大 100MB
 * - 截取前 5 秒（不足则全部）
 * - 每秒采样 2-3 帧
 * - 每帧压缩至 1024 像素宽
 */
object VideoFrameExtractor {
  private const val TAG = "VideoFrameExtractor"

  /** 最大输入视频时长（秒） */
  const val MAX_DURATION_SEC = 5

  /** 每秒采样帧数 */
  const val FPS_SAMPLE = 3

  /** 最大文件大小 */
  private const val MAX_FILE_SIZE = 100L * 1024 * 1024

  /**
   * 从视频 URI 提取帧列表。
   *
   * @return 帧文件路径列表，失败返回空列表
   */
  fun extractFrames(
    context: Context,
    videoUri: Uri,
    cacheDir: File,
  ): List<String> {
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
      val intervalUs = (1_000_000L / FPS_SAMPLE).coerceAtLeast(100_000L)

      Log.i(TAG, "Extracting frames: duration=${actualDurationSec}s, sampling=${sampleDurationMs}ms, frames=$totalFrames")

      val frames = mutableListOf<String>()
      for (i in 0 until totalFrames) {
        val timeUs = (i * intervalUs).coerceAtMost(sampleDurationMs * 1000L - 1)
        val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
          ?: continue

        // 缩放至最大 1024 宽
        val scaled = if (bitmap.width > 1024) {
          val ratio = 1024f / bitmap.width
          Bitmap.createScaledBitmap(bitmap, 1024, (bitmap.height * ratio).toInt(), true)
            .also { if (it != bitmap) bitmap.recycle() }
        } else bitmap

        val frameFile = File(cacheDir, "vf_${System.currentTimeMillis()}_${i}.jpg")
        FileOutputStream(frameFile).use { out ->
          scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        frames.add(frameFile.absolutePath)

        if (scaled != bitmap) scaled.recycle()
      }

      Log.i(TAG, "Extracted ${frames.size} frames from video")
      frames
    } catch (e: Exception) {
      Log.e(TAG, "Frame extraction failed: ${e.message}", e)
      emptyList()
    } finally {
      try { retriever.release() } catch (_: Exception) {}
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
}