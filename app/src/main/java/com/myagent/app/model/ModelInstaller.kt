package com.myagent.app.model

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 模型安装器 — 从阿里云 OSS 下载 .litertlm 模型到内部存储，支持断点续传和 SHA256 校验。
 *
 * v2.0：端侧推理模型（~3.66 GB），.litertlm 格式，LiteRT-LM 引擎。
 *
 * 策略：
 * - 从阿里云 OSS 下载（国内高速，稳定可靠）
 * - 支持 HTTP Range 断点续传
 * - 下载完成后 SHA256 校验
 * - 模型不存在或校验失败时自动降级为 Mock
 */
class ModelInstaller(private val context: Context) {
  companion object {
    const val MODEL_FILE_NAME = "mynagent-v1-it.litertlm"

    private const val DOWNLOAD_URL =
      "https://ljsour.oss-cn-beijing.aliyuncs.com/myaengt-v1-it.litertlm"

    /** SHA256 校验值 */
    private const val EXPECTED_SHA256 =
      "0B2A8980CE155FD97673D8E820B4D29D9C7D99B8FA6806F425D969B145BD52E0"

    private const val BUFFER_SIZE = 8192
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000 // 大文件下载需要更长的超时
  }

  /**
   * 获取模型文件的内部存储路径
   */
  fun getModelPath(): File = File(context.filesDir, "models/$MODEL_FILE_NAME")

  /**
   * 检查模型是否已安装且 SHA256 校验通过。
   */
  fun isModelReady(): Boolean {
    val file = getModelPath()
    return file.exists() && file.length() > 0 && verifyChecksum(file)
  }

  /**
   * 下载模型文件，返回进度 Flow。
   *
   * 流程：
   * 1. 已存在 + 校验通过 → 直接 Completed
   * 2. 发起 HTTP 下载（Range 续传）
   * 3. SHA256 校验（如果已配置）
   * 4. 失败则清理并返回 Failed
   */
  fun downloadModel(): Flow<ModelDownloadState> = flow {
    val modelFile = getModelPath()

    // 确保目录存在
    modelFile.parentFile?.mkdirs()

    // 已存在且校验通过
    if (modelFile.exists() && modelFile.length() > 0 && isModelReady()) {
      emit(ModelDownloadState.Completed)
      return@flow
    }

    // 保留已下载的部分文件用于断点续传
    // 仅在文件为空（0 字节）时删除，避免丢失已下载的进度
    if (modelFile.exists() && modelFile.length() == 0L) {
      modelFile.delete()
    }

    emit(ModelDownloadState.Downloading(0, 0, 0, 0))

    try {
      // —— 第 1 步：获取文件大小 ——
      val totalSize = fetchContentLength(DOWNLOAD_URL)
      if (totalSize <= 0) {
        emit(ModelDownloadState.Failed("无法获取模型文件信息，请检查网络连接"))
        return@flow
      }

      // —— 第 2 步：下载 ——
      val existingBytes = modelFile.length()

      // Channel 桥接：downloadFile 的回调是普通 lambda，无法直接调用 suspend 的 emit。
      // 通过 channel 把回调数据 push 到协程侧，再由协程 collect 后 emit 到 Flow。
      coroutineScope {
        val progressChannel = Channel<Pair<Long, Long>>(Channel.CONFLATED)

        val downloadJob = launch(Dispatchers.IO) {
          withContext(NonCancellable) {
            try {
              downloadFile(DOWNLOAD_URL, modelFile, existingBytes, totalSize) { downloaded, speed ->
                progressChannel.trySend(downloaded to speed)
              }
            } finally {
              progressChannel.close()
            }
          }
        }

        for ((downloaded, speed) in progressChannel) {
          val pct = if (totalSize > 0) (downloaded * 100 / totalSize).toInt().coerceIn(0, 100) else 0
          emit(ModelDownloadState.Downloading(pct, downloaded, totalSize, speed))
        }
      }

      // —— 第 3 步：校验 ——
      emit(ModelDownloadState.Verifying)
      if (!isModelReady()) {
        modelFile.delete()
        emit(ModelDownloadState.Failed("模型文件校验失败，请重试"))
        return@flow
      }

      emit(ModelDownloadState.Completed)
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      // 网络错误，保留已下载部分供下次续传
      emit(ModelDownloadState.Failed("下载中断：${e.message ?: "网络错误"}，已下载部分已保留"))
    } catch (e: Exception) {
      emit(ModelDownloadState.Failed("下载失败：${e.message ?: "未知错误"}"))
    }
  }.flowOn(Dispatchers.IO)

  // ── HEAD 请求获取 Content-Length ──
  private fun fetchContentLength(urlStr: String): Long {
    var connection: HttpURLConnection? = null
    try {
      connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
        requestMethod = "HEAD"
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        setRequestProperty("User-Agent", "Lingji/2.0")
      }
      val length = connection.contentLengthLong
      return if (length > 0) length else -1
    } catch (_: Exception) {
      return -1
    } finally {
      connection?.disconnect()
    }
  }

  // ── 流式下载，支持 Range 断点续传 ──
  private fun downloadFile(
    urlStr: String,
    target: File,
    existingBytes: Long,
    totalSize: Long,
    onProgress: (downloadedBytes: Long, speedBytesPerSec: Long) -> Unit,
  ) {
    var connection: HttpURLConnection? = null
    var input: InputStream? = null
    var output: FileOutputStream? = null

    try {
      connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        setRequestProperty("User-Agent", "Lingji/2.0")
        // 断点续传：如果已有部分数据，从断点处继续
        if (existingBytes > 0) {
          setRequestProperty("Range", "bytes=$existingBytes-")
        }
      }

      val responseCode = connection.responseCode
      if (responseCode != HttpURLConnection.HTTP_OK &&
        responseCode != HttpURLConnection.HTTP_PARTIAL
      ) {
        throw IOException("服务器返回错误：$responseCode")
      }

      input = connection.inputStream
      output = FileOutputStream(target, existingBytes > 0) // append 模式

      val buffer = ByteArray(BUFFER_SIZE)
      var bytesRead: Int
      var downloaded = existingBytes
      var lastReportTime = System.currentTimeMillis()
      var lastReportBytes = downloaded

      while (input.read(buffer).also { bytesRead = it } != -1) {
        output.write(buffer, 0, bytesRead)
        downloaded += bytesRead

        // 每 200ms 报告一次进度
        val now = System.currentTimeMillis()
        if (now - lastReportTime >= 200) {
          val elapsed = (now - lastReportTime).coerceAtLeast(1)
          val speed = (downloaded - lastReportBytes) * 1000 / elapsed
          onProgress(downloaded, speed)
          lastReportTime = now
          lastReportBytes = downloaded
        }
      }

      // 最终报告
      onProgress(downloaded, 0)
    } finally {
      output?.close()
      input?.close()
      connection?.disconnect()
    }
  }

  // ── SHA256 校验 ──
  private fun verifyChecksum(file: File): Boolean {
    if (!file.exists()) return false
    return try {
      val digest = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
          digest.update(buffer, 0, bytesRead)
        }
      }
      val hash = digest.digest().joinToString("") { "%02X".format(it) }
      hash == EXPECTED_SHA256
    } catch (_: Exception) {
      false
    }
  }
}