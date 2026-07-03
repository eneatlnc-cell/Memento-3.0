package com.myagent.app.model

import android.content.Context
import android.util.Log
import com.myagent.app.activation.ActivationManager
import com.myagent.app.activation.AuthApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 模型安装器 — 从 TOS 下载 GGUF 模型到外部存储，支持断点续传和 SHA256 校验。
 *
 * v3.1：llama.cpp 替换 LiteRT-LM，双文件结构：
 * - 主模型：qwen3.5-0.8b-q4_k_m.gguf（~500MB）
 * - 视觉投影器：mmproj-BF16.gguf（~200MB）
 *
 * 下载策略（两层）：
 * 1. 鉴权下载（正式版）：通过 activationManager 获取 token → AuthApi 换预签名 URL → 私有读
 * 2. 公读下载（测试版）：直接用公读 CDN URL，无需 token
 *
 * 策略：
 * - 支持 HTTP Range 断点续传
 * - 下载完成后 SHA256 校验（可空，未配置则跳过）
 * - 模型文件存储在外部存储（getExternalFilesDir），清除数据/缓存不会删除
 */
class ModelInstaller(
  private val context: Context,
  private val activationManager: ActivationManager? = null,
) {
  companion object {
    /** 主模型文件名（Qwen3.5-0.8B Q4_K_M） */
    const val MODEL_FILE_NAME = "qwen3.5-0.8b-q4_k_m.gguf"

    /** 视觉投影器文件名（mmproj BF16） */
    const val MMPROJ_FILE_NAME = "mmproj-BF16.gguf"

    /**
     * SHA256 校验值。
     * 按设计：保留校验流程（Verifying 状态 + 通知文案），但默认 null → isFileReady 直接 return true，
     * 不真算 hash（避免大文件 SHA 计算导致 OOM/ANR）。需要时填入即自动启用真校验。
     */
    @Volatile var EXPECTED_MODEL_SHA256: String? = null

    @Volatile var EXPECTED_MMPROJ_SHA256: String? = null

    /** 主模型公读直链（cstcloud 对象存储，已验证支持 HEAD + Accept-Ranges） */
    @Volatile var MODEL_DOWNLOAD_URL: String =
      "https://data.cstcloud.cn/api/file/download/file/a75e0442-38a2-4466-bb03-0a1554be4ef6-26e850cd938dc4760280749733031e8b"

    /** mmproj 公读直链 */
    @Volatile var MMPROJ_DOWNLOAD_URL: String =
      "https://data.cstcloud.cn/api/file/download/file/deb10d1c-592f-4c07-b06f-866966342770-d62a75fa19bed916d83954056eaba447"

    private const val BUFFER_SIZE = 8192
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 120_000
    const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 2000L
  }

  /**
   * 获取主模型文件的存储路径（外部存储，清除数据后不会删除）。
   */
  fun getModelPath(): File = getModelFile(MODEL_FILE_NAME)

  /**
   * 获取 mmproj 文件的存储路径。
   */
  fun getMmprojPath(): File = getModelFile(MMPROJ_FILE_NAME)

  private fun getModelFile(fileName: String): File {
    val appContext = context.applicationContext
    val externalDir = appContext.getExternalFilesDir(null)
    val targetFile = if (externalDir != null) {
      File(externalDir, "models/$fileName")
    } else {
      File(appContext.filesDir, "models/$fileName")
    }

    // 自动迁移：如果内部存储有旧模型文件，移动到外部存储
    if (externalDir != null) {
      val legacyFile = File(appContext.filesDir, "models/$fileName")
      if (legacyFile.exists() && legacyFile.length() > 0 && !targetFile.exists()) {
        targetFile.parentFile?.mkdirs()
        try {
          legacyFile.copyTo(targetFile)
          legacyFile.delete()
        } catch (_: Exception) {
          return legacyFile
        }
      }
    }

    return targetFile
  }

  /**
   * 解析下载 URL。鉴权优先，公读兜底。
   */
  private fun resolveDownloadUrl(defaultUrl: String): String {
    val token = activationManager?.getToken()
    if (token != null && AuthApi.isOnline) {
      val signedUrl = AuthApi.getDownloadUrl(token)
      if (signedUrl != null) {
        Log.i("ModelInstaller", "Using authenticated download URL")
        return signedUrl
      }
    }
    return defaultUrl
  }

  /**
   * 检查主模型 + mmproj 是否都已安装且校验通过。
   */
  fun isModelReady(): Boolean {
    return isFileReady(getModelPath(), EXPECTED_MODEL_SHA256) &&
      isFileReady(getMmprojPath(), EXPECTED_MMPROJ_SHA256)
  }

  /**
   * 轻量级检查：仅判断两个文件是否存在且大小 > 0。
   */
  fun isModelFileExists(): Boolean {
    val model = getModelPath()
    val mmproj = getMmprojPath()
    return model.exists() && model.length() > 0 &&
      mmproj.exists() && mmproj.length() > 0
  }

  private fun isFileReady(file: File, expectedSha256: String?): Boolean {
    if (!file.exists() || file.length() == 0L) return false
    if (expectedSha256 == null) return true  // 未配置校验值则跳过
    return verifyChecksum(file, expectedSha256)
  }

  /**
   * 下载主模型 + mmproj，返回进度 Flow。
   * 进度百分比基于两个文件总大小合并计算。
   */
  fun downloadModel(): Flow<ModelDownloadState> = flow {
    val modelFile = getModelPath()
    val mmprojFile = getMmprojPath()
    modelFile.parentFile?.mkdirs()

    // 已存在且校验通过
    if (isModelReady()) {
      emit(ModelDownloadState.Completed)
      return@flow
    }

    if (modelFile.exists() && modelFile.length() == 0L) modelFile.delete()
    if (mmprojFile.exists() && mmprojFile.length() == 0L) mmprojFile.delete()

    emit(ModelDownloadState.Downloading(0, 0, 0, 0))

    try {
      // 获取两个文件的总大小
      val modelUrl = resolveDownloadUrl(MODEL_DOWNLOAD_URL)
      val mmprojUrl = resolveDownloadUrl(MMPROJ_DOWNLOAD_URL)
      val modelSize = fetchContentLength(modelUrl)
      val mmprojSize = fetchContentLength(mmprojUrl)
      if (modelSize <= 0 || mmprojSize <= 0) {
        emit(ModelDownloadState.Failed("无法获取模型文件信息，请检查网络连接"))
        return@flow
      }
      val totalSize = modelSize + mmprojSize

      coroutineScope {
        val progressChannel = Channel<Pair<Long, Long>>(Channel.CONFLATED)

        val downloadJob = launch(Dispatchers.IO) {
          // H-N2 修复：移除 withContext(NonCancellable)，使下载流的读取和写入可被 stopDownload() 取消。
          // finally 中的 progressChannel.close() 为非挂起操作，无需 NonCancellable 即可正常执行。
          try {
            // 下载主模型
            var totalDownloaded = 0L
            downloadFile(modelUrl, modelFile, modelFile.length(), modelSize) { downloaded, speed ->
              progressChannel.trySend((totalDownloaded + downloaded) to speed)
            }
            totalDownloaded = modelSize

            // 下载 mmproj
            downloadFile(mmprojUrl, mmprojFile, mmprojFile.length(), mmprojSize) { downloaded, speed ->
              progressChannel.trySend((totalDownloaded + downloaded) to speed)
            }
          } finally {
            progressChannel.close()
          }
        }

        for ((downloaded, speed) in progressChannel) {
          val pct = if (totalSize > 0) (downloaded * 100 / totalSize).toInt().coerceIn(0, 100) else 0
          emit(ModelDownloadState.Downloading(pct, downloaded, totalSize, speed))
        }
      }

      // 校验
      emit(ModelDownloadState.Verifying)
      if (!isModelReady()) {
        emit(ModelDownloadState.Failed("模型文件校验失败，请重试"))
        return@flow
      }

      emit(ModelDownloadState.Completed)
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      emit(ModelDownloadState.Failed("下载中断：${e.message ?: "网络错误"}，已下载部分已保留"))
    } catch (e: Exception) {
      emit(ModelDownloadState.Failed("下载失败：${e.message ?: "未知错误"}"))
    }
  }.flowOn(Dispatchers.IO)

  /**
   * 内部下载方法（供 ForegroundService 直接调用，与 downloadModel 等价）。
   */
  internal fun downloadModelInternal(): Flow<ModelDownloadState> = downloadModel()

  /**
   * 带自动重试的下载方法。
   */
  fun downloadModelWithRetry(retryCount: Int = 0): Flow<ModelDownloadState> = flow {
    var currentRetry = retryCount
    var lastState: ModelDownloadState = ModelDownloadState.Idle

    while (currentRetry <= MAX_RETRIES) {
      var downloadSucceeded = false

      // H-N4 修复：downloadModel().collect 会挂起直到 flow 完成（其内部 coroutineScope 会
      // 等待 downloadJob 结束）。配合 H-N2 移除 NonCancellable 后，collect 返回时上一轮
      // 下载已彻底结束，不会与新轮 downloadFile 同写 modelFile。
      downloadModel().collect { state ->
        lastState = state
        when (state) {
          is ModelDownloadState.Completed -> {
            downloadSucceeded = true
            emit(state)
          }
          is ModelDownloadState.Failed -> {
            lastState = state
          }
          else -> emit(state)
        }
      }

      if (downloadSucceeded) return@flow

      currentRetry++
      if (currentRetry > MAX_RETRIES) {
        val errorMsg = (lastState as? ModelDownloadState.Failed)?.error ?: "下载失败"
        emit(ModelDownloadState.Failed("$errorMsg（已重试 $MAX_RETRIES 次）"))
        return@flow
      }

      Log.w("ModelInstaller", "Download attempt $currentRetry failed, retrying in ${RETRY_DELAY_MS}ms...")
      delay(RETRY_DELAY_MS)
    }
  }.flowOn(Dispatchers.IO)

  private fun fetchContentLength(urlStr: String): Long {
    var connection: HttpURLConnection? = null
    try {
      connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
        requestMethod = "HEAD"
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        setRequestProperty("User-Agent", "Memento/3.1")
      }
      val length = connection.contentLengthLong
      Log.i("ModelInstaller", "HEAD $urlStr → Content-Length: $length, response: ${connection.responseCode}")
      return if (length > 0) length else -1
    } catch (e: Exception) {
      Log.e("ModelInstaller", "HEAD request failed: ${e.message}", e)
      return -1
    } finally {
      connection?.disconnect()
    }
  }

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
        setRequestProperty("User-Agent", "Memento/3.1")
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

      // H-N3 修复：服务端不支持 Range 请求时返回 200（完整文件）而非 206（部分内容）。
      // 若仍以 append 模式写入，会导致 文件 = 旧分片 + 完整文件，破坏数据且大小校验可能漏过。
      // 仅当返回 206 时才以 append 模式续传；返回 200 时删除旧分片并以覆盖模式从头写入。
      val isPartial = responseCode == HttpURLConnection.HTTP_PARTIAL
      val startBytes = if (isPartial) existingBytes else 0L
      if (!isPartial && target.exists()) {
        target.delete()
      }

      input = connection.inputStream
      output = FileOutputStream(target, isPartial)

      val buffer = ByteArray(BUFFER_SIZE)
      var bytesRead: Int
      var downloaded = startBytes
      var lastReportTime = System.currentTimeMillis()
      var lastReportBytes = downloaded

      while (input.read(buffer).also { bytesRead = it } != -1) {
        output.write(buffer, 0, bytesRead)
        downloaded += bytesRead

        val now = System.currentTimeMillis()
        if (now - lastReportTime >= 200) {
          val elapsed = (now - lastReportTime).coerceAtLeast(1)
          val speed = (downloaded - lastReportBytes) * 1000 / elapsed
          onProgress(downloaded, speed)
          lastReportTime = now
          lastReportBytes = downloaded
        }
      }

      output.fd.sync()
      onProgress(downloaded, 0)

      if (downloaded != totalSize) {
        throw IOException(
          "下载不完整: 期望 $totalSize 字节, 实际仅收到 $downloaded 字节 " +
          "(${"%.1f".format(downloaded * 100.0 / totalSize.coerceAtLeast(1))}%)"
        )
      }
    } finally {
      output?.close()
      input?.close()
      connection?.disconnect()
    }
  }

  private fun verifyChecksum(file: File, expectedSha256: String): Boolean {
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
      hash == expectedSha256.uppercase()
    } catch (_: Exception) {
      false
    }
  }
}
