package com.myagent.app.model

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.myagent.app.MainActivity
import com.myagent.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 模型下载前台服务 — 确保下载在后台持续进行，退出 App 后不中断。
 *
 * 职责：
 * - 显示下载进度通知
 * - 保持进程存活（前台服务优先级）
 * - 管理下载协程生命周期
 * - 下载完成/失败后自动停止
 */
class DownloadForegroundService : Service() {
  companion object {
    const val TAG = "DownloadForegroundService"
    const val CHANNEL_ID = "model_download_channel"
    const val NOTIFICATION_ID = 1001

    const val ACTION_START = "com.myagent.app.action.START_DOWNLOAD"
    const val ACTION_STOP = "com.myagent.app.action.STOP_DOWNLOAD"
    const val EXTRA_RETRY_COUNT = "extra_retry_count"

    fun start(context: Context, retryCount: Int = 0) {
      val intent = Intent(context, DownloadForegroundService::class.java).apply {
        action = ACTION_START
        putExtra(EXTRA_RETRY_COUNT, retryCount)
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      val intent = Intent(context, DownloadForegroundService::class.java).apply {
        action = ACTION_STOP
      }
      context.startService(intent)
    }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var downloadJob: Job? = null
  private lateinit var notificationManager: NotificationManager

  override fun onCreate() {
    super.onCreate()
    notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START -> {
        val retryCount = intent.getIntExtra(EXTRA_RETRY_COUNT, 0)
        startDownload(retryCount)
      }
      ACTION_STOP -> stopDownload()
    }
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun startDownload(retryCount: Int) {
    // 如果已有下载任务，先取消
    downloadJob?.cancel()

    // 共享 NodeApp 单例：确保全 App 用同一个 ModelInstaller（鉴权状态、URL 配置一致）
    val installer = (application as com.myagent.app.NodeApp).modelInstaller
    val maxRetries = 3

    downloadJob = scope.launch {
      var currentRetry = retryCount
      var lastError: String? = null

      while (currentRetry <= maxRetries) {
        try {
          var lastProgress = 0
          var lastSpeed = 0L

          installer.downloadModelInternal().collectLatest { state ->
            when (state) {
              is ModelDownloadState.Downloading -> {
                lastProgress = state.progress
                lastSpeed = state.speedBytesPerSec
                updateNotification(
                  progress = state.progress,
                  downloadedBytes = state.downloadedBytes,
                  totalBytes = state.totalBytes,
                  speedBytesPerSec = state.speedBytesPerSec,
                  retryCount = currentRetry,
                )
              }
              is ModelDownloadState.Verifying -> {
                updateNotification(
                  progress = 100,
                  verifying = true,
                  retryCount = currentRetry,
                )
              }
              is ModelDownloadState.Completed -> {
                updateNotificationCompleted()
                stopSelf()
                return@collectLatest
              }
              is ModelDownloadState.Failed -> {
                lastError = state.error
              }
              else -> {}
            }
          }
        } catch (e: Exception) {
          lastError = e.message ?: "未知错误"
          Log.e(TAG, "Download attempt $currentRetry failed: $lastError", e)
        }

        currentRetry++
        if (currentRetry > maxRetries) {
          updateNotificationFailed(lastError ?: "下载失败")
          // 3 次失败后保持前台通知，提供重试入口
          // 服务保持运行，等待用户手动重试
          return@launch
        }
        Log.i(TAG, "Retrying download (attempt $currentRetry/$maxRetries)...")
      }
    }

    // 显示初始通知
    updateNotification(progress = 0, retryCount = retryCount)
  }

  private fun stopDownload() {
    downloadJob?.cancel()
    downloadJob = null
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    downloadJob?.cancel()
    scope.cancel()
    super.onDestroy()
  }

  // ── 通知管理 ──

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "模型下载",
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = "显示 AI 模型下载进度"
        setShowBadge(false)
      }
      notificationManager.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(
    title: String,
    content: String,
    progress: Int = 0,
    maxProgress: Int = 100,
    indeterminate: Boolean = false,
    ongoing: Boolean = true,
  ): Notification {
    val openIntent = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle(title)
      .setContentText(content)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setOngoing(ongoing)
      .setContentIntent(openIntent)
      .setProgress(if (indeterminate) 0 else maxProgress, if (indeterminate) 0 else progress, indeterminate)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()
  }

  private fun updateNotification(
    progress: Int,
    downloadedBytes: Long = 0,
    totalBytes: Long = 0,
    speedBytesPerSec: Long = 0,
    retryCount: Int = 0,
    verifying: Boolean = false,
  ) {
    val title = if (verifying) "正在校验模型..." else "正在下载 AI 模型"
    val retrySuffix = if (retryCount > 0) " (第${retryCount}次尝试)" else ""
    val content = buildString {
      if (verifying) {
        append("校验 SHA256 中...")
      } else {
        append("${progress}%")
        if (downloadedBytes > 0 && totalBytes > 0) {
          val downloadedMB = downloadedBytes / (1024 * 1024)
          val totalMB = totalBytes / (1024 * 1024)
          append(" · ${downloadedMB}MB / ${totalMB}MB")
        }
        if (speedBytesPerSec > 0) {
          val speedMB = speedBytesPerSec / (1024 * 1024)
          append(" · ${speedMB}MB/s")
        }
      }
      append(retrySuffix)
    }

    val notification = buildNotification(
      title = title,
      content = content,
      progress = progress,
      maxProgress = 100,
      indeterminate = progress == 0,
    )

    startForeground(NOTIFICATION_ID, notification)
  }

  private fun updateNotificationCompleted() {
    val notification = buildNotification(
      title = "模型下载完成",
      content = "Memento AI 模型已就绪",
      progress = 0,
      maxProgress = 0,
      ongoing = false,
    )
    notificationManager.notify(NOTIFICATION_ID, notification)
  }

  private fun updateNotificationFailed(error: String) {
    val notification = buildNotification(
      title = "模型下载失败",
      content = "请检查网络后重试",
      progress = 0,
      maxProgress = 0,
      ongoing = false,
    )
    notificationManager.notify(NOTIFICATION_ID, notification)
  }
}