package com.myagent.app.multimodal

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bitmap 帧序列 → MP4 视频编码器（MediaCodec 硬件编码）。
 *
 * 状态机：UNINITIALIZED → CONFIGURED → STARTED → STOPPED → RELEASED
 * 防御式设计：每个 release 步骤独立 try-catch，避免单步失败导致资源泄漏。
 *
 * v3.2：从 HyperFramesRenderer.kt 提取为独立文件，供 StructuredRenderer 复用。
 * 这是经过验证的稳定编码器，MediaCodec H.264 硬件编码 + MediaMuxer MP4 封装。
 */
class BitmapToVideoEncoder(
  private val outputFile: File,
  private val width: Int,
  private val height: Int,
  private val fps: Int = 24,
) {
  private enum class State { UNINITIALIZED, CONFIGURED, STARTED, STOPPED, RELEASED }

  private var mediaCodec: MediaCodec? = null
  private var mediaMuxer: MediaMuxer? = null
  private var trackIndex: Int = -1
  private var muxerStarted = false
  private var muxerStartFailed = false
  private var frameIndex = 0L
  private var state = State.UNINITIALIZED
  private var codecName: String = "unknown"

  fun start() {
    if (state != State.UNINITIALIZED) {
      Log.w("BitmapEncoder", "Already started, state=$state")
      return
    }

    val mime = MediaFormat.MIMETYPE_VIDEO_AVC
    val codecInfo = findAvailableCodec(mime, isEncoder = true)
    if (codecInfo == null) {
      Log.e("BitmapEncoder", "No available AVC encoder found on this device")
      throw IllegalStateException("设备不支持 H.264 编码，无法生成视频")
    }
    codecName = codecInfo.name
    Log.i("BitmapEncoder", "Using codec: $codecName")

    val format = MediaFormat.createVideoFormat(mime, width, height).apply {
      // NV12（SemiPlanar）颜色格式，与 bitmapToYuv420SemiPlanar() 直接写入字节缓冲匹配
      setInteger(MediaFormat.KEY_COLOR_FORMAT,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
      setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
      setInteger(MediaFormat.KEY_FRAME_RATE, fps)
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }

    try {
      mediaCodec = MediaCodec.createByCodecName(codecName)
      mediaCodec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      state = State.CONFIGURED
      mediaCodec!!.start()
      state = State.STARTED
      mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    } catch (e: Exception) {
      Log.e("BitmapEncoder", "Failed to start encoder: ${e.message}", e)
      safeRelease()
      throw e
    }
  }

  fun encodeFrame(bitmap: Bitmap) {
    if (state != State.STARTED) return
    if (muxerStartFailed) {
      throw IllegalStateException("Muxer 未成功启动，无法继续编码")
    }
    val codec = mediaCodec ?: return
    try {
      val inputBufferIndex = codec.dequeueInputBuffer(10_000)
      if (inputBufferIndex < 0) return
      val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: return
      val yuvData = bitmapToYuv420SemiPlanar(bitmap)
      inputBuffer.clear()
      inputBuffer.put(yuvData)
      codec.queueInputBuffer(inputBufferIndex, 0, yuvData.size, frameIndex * 1_000_000 / fps, 0)
      frameIndex++
      drainEncoder()
    } catch (e: Exception) {
      Log.e("BitmapEncoder", "encodeFrame failed: ${e.message}", e)
    }
  }

  private fun drainEncoder(): Boolean {
    val codec = mediaCodec ?: return true
    val bufferInfo = MediaCodec.BufferInfo()
    try {
      while (true) {
        val idx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
        when {
          idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            try {
              if (muxerStarted) {
                Log.w("BitmapEncoder", "Unexpected format change after muxer started")
              }
              trackIndex = mediaMuxer!!.addTrack(codec.outputFormat)
              mediaMuxer!!.start()
              muxerStarted = true
            } catch (e: Exception) {
              Log.e("BitmapEncoder", "Muxer addTrack/start failed: ${e.message}", e)
              muxerStartFailed = true
            }
          }
          idx >= 0 -> {
            try {
              val buf = codec.getOutputBuffer(idx) ?: continue
              if (bufferInfo.flags and MediaCodec.BufferInfo.FLAG_CODEC_CONFIG == 0
                && bufferInfo.size > 0 && muxerStarted
              ) {
                buf.position(bufferInfo.offset)
                buf.limit(bufferInfo.offset + bufferInfo.size)
                mediaMuxer!!.writeSampleData(trackIndex, buf, bufferInfo)
              }
            } catch (e: Exception) {
              Log.e("BitmapEncoder", "writeSampleData failed: ${e.message}", e)
            }
            val isEos = (bufferInfo.flags and MediaCodec.BufferInfo.FLAG_END_OF_STREAM) != 0
            codec.releaseOutputBuffer(idx, false)
            if (isEos) {
              Log.i("BitmapEncoder", "EOS received, total frames: $frameIndex")
              return true
            }
          }
          idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
        }
      }
    } catch (e: Exception) {
      Log.e("BitmapEncoder", "drainEncoder failed: ${e.message}", e)
    }
    return false
  }

  suspend fun stop() {
    if (state != State.STARTED) {
      safeRelease()
      return
    }

    withContext(Dispatchers.IO) {
      try {
        val codec = mediaCodec
        if (codec != null) {
          try {
            val inputBufferIndex = codec.dequeueInputBuffer(10_000)
            if (inputBufferIndex >= 0) {
              codec.queueInputBuffer(
                inputBufferIndex, 0, 0, 0,
                MediaCodec.BufferInfo.FLAG_END_OF_STREAM
              )
            }
          } catch (e: Exception) {
            Log.e("BitmapEncoder", "EOS signal failed: ${e.message}", e)
          }
          val deadline = System.currentTimeMillis() + 2000
          var eosReceived = false
          while (!eosReceived && System.currentTimeMillis() < deadline) {
            eosReceived = drainEncoder()
          }
          if (!eosReceived) {
            Log.w("BitmapEncoder", "EOS not received within deadline, stopping anyway")
          }
        }
      } catch (e: Exception) {
        Log.e("BitmapEncoder", "stop drain failed: ${e.message}", e)
      } finally {
        state = State.STOPPED
        safeRelease()
      }
    }
  }

  private fun safeRelease() {
    if (state == State.RELEASED) return

    try { mediaCodec?.stop() } catch (e: IllegalStateException) {
      Log.w("BitmapEncoder", "Codec stop: already released")
    } catch (e: Exception) {
      Log.e("BitmapEncoder", "Codec stop failed: ${e.message}", e)
    }

    try { mediaCodec?.release() } catch (e: Exception) {
      Log.e("BitmapEncoder", "Codec release failed: ${e.message}", e)
    }
    mediaCodec = null

    try { if (muxerStarted) mediaMuxer?.stop() } catch (e: Exception) {
      Log.e("BitmapEncoder", "Muxer stop failed: ${e.message}", e)
    }

    try { mediaMuxer?.release() } catch (e: Exception) {
      Log.e("BitmapEncoder", "Muxer release failed: ${e.message}", e)
    }
    mediaMuxer = null

    state = State.RELEASED
    Log.i("BitmapEncoder", "Encoder released (codec=$codecName, frames=$frameIndex)")
  }

  private fun findAvailableCodec(mime: String, isEncoder: Boolean): MediaCodecInfo? {
    return try {
      val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
      val hardware = codecList.codecInfos.filter { info ->
        info.isEncoder == isEncoder
          && info.supportedTypes.contains(mime)
          && !info.name.startsWith("OMX.google.")
          && !info.name.startsWith("c2.android.")
      }
      val software = codecList.codecInfos.filter { info ->
        info.isEncoder == isEncoder
          && info.supportedTypes.contains(mime)
          && (info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android."))
      }
      (hardware.firstOrNull() ?: software.firstOrNull())?.also {
        Log.i("BitmapEncoder", "Selected codec: ${it.name} (${if (hardware.contains(it)) "HW" else "SW"})")
      }
    } catch (e: Exception) {
      Log.e("BitmapEncoder", "Codec discovery failed: ${e.message}", e)
      null
    }
  }

  private fun bitmapToYuv420SemiPlanar(bitmap: Bitmap): ByteArray {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val ySize = w * h
    val nv12 = ByteArray(ySize + w * h / 2)
    var yi = 0
    var uvi = ySize
    for (j in 0 until h) {
      for (i in 0 until w) {
        val p = pixels[j * w + i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        nv12[yi++] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255).toByte()
        if (j % 2 == 0 && i % 2 == 0) {
          nv12[uvi++] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
          nv12[uvi++] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
        }
      }
    }
    return nv12
  }
}
