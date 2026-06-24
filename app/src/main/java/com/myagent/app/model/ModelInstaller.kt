package com.myagent.app.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream

/**
 * 模型安装器 — 首次启动从 assets 复制 GGUF 模型到内部存储。
 *
 * 当前阶段：预留接口，模型文件后续放入 assets/models/ 目录。
 * 模型不存在时 LocalModelLoader 自动降级为 Mock。
 */
class ModelInstaller(private val context: Context) {
  companion object {
    const val MODEL_FILE_NAME = "gemma-3-270m-Q4_K_M.gguf"
    private const val ASSETS_MODEL_PATH = "models/$MODEL_FILE_NAME"
  }

  /**
   * 获取模型文件的内部存储路径
   */
  fun getModelPath(): File = File(context.filesDir, "models/$MODEL_FILE_NAME")

  /**
   * 检查模型是否已安装
   */
  fun isModelInstalled(): Boolean = getModelPath().exists()

  /**
   * 安装模型文件，返回进度 Flow (0-100)。
   * 当前为骨架实现，模型文件尚未放入 assets。
   */
  fun install(): Flow<Int> = flow {
    val modelFile = getModelPath()

    // 确保目录存在
    modelFile.parentFile?.mkdirs()

    // 检查 assets 中是否有模型文件
    val assetsList = context.assets.list("models")
    if (assetsList == null || !assetsList.contains(MODEL_FILE_NAME)) {
      // 模型文件尚未放入 assets，跳过安装
      emit(100)
      return@flow
    }

    emit(0)

    try {
      context.assets.open(ASSETS_MODEL_PATH).use { input ->
        FileOutputStream(modelFile).use { output ->
          val buffer = ByteArray(8192)
          var bytesRead: Int
          var totalBytes = 0L
          val totalSize = input.available().toLong()

          while (input.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
            totalBytes += bytesRead
            if (totalSize > 0) {
              val progress = (totalBytes * 100 / totalSize).toInt()
              emit(progress.coerceIn(0, 100))
            }
          }
        }
      }
      emit(100)
    } catch (e: Exception) {
      // 安装失败，清理不完整的文件
      modelFile.delete()
      throw e
    }
  }.flowOn(Dispatchers.IO)
}