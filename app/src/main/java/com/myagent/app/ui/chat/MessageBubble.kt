package com.myagent.app.ui.chat

import com.myagent.app.chat.ChatMessage
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.myagent.app.ui.LocalSkinColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 多模态消息气泡 — 支持文字、图片、语音、视频。
 *
 * - 图片气泡：点击 → 系统图片查看器
 * - 视频气泡：点击 → 系统视频播放器
 * - 都支持"保存到相册"按钮
 */
@Composable
fun MessageBubble(
  message: ChatMessage,
) {
  val isUser = message.role == "user"
  val skinColors = LocalSkinColors.current
  val bubbleRadius = skinColors.bubbleRadius
  // H-U2 修复：用于在点击事件中切到 IO 线程执行文件拷贝
  val scope = rememberCoroutineScope()

  Box(
    modifier = Modifier.fillMaxWidth(),
    contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
  ) {
    Column(
      modifier = Modifier
        .widthIn(max = 300.dp)
        .clip(
          RoundedCornerShape(
            topStart = bubbleRadius.topStart,
            topEnd = bubbleRadius.topEnd,
            bottomStart = if (isUser) bubbleRadius.bottomStart else bubbleRadius.bottomEnd,
            bottomEnd = if (isUser) bubbleRadius.bottomEnd else bubbleRadius.bottomStart,
          ),
        )
        .background(
          if (isUser) skinColors.userBubble
          else skinColors.assistantBubble,
        )
        .padding(12.dp),
    ) {
      MessageContent(message = message, isUser = isUser)
      // 操作栏：保存到相册
      if (!isUser) {
        Spacer(modifier = Modifier.height(4.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // 保存到相册（图片/视频）
          if (message.type == "image" || message.type == "video") {
            val ctx = LocalContext.current
            IconButton(
              // H-U2 修复：在协程中切到 IO 线程执行文件拷贝，避免主线程同步拷贝多 MB 文件导致 ANR
              onClick = { scope.launch { saveToGallery(ctx, message) } },
              modifier = Modifier.size(28.dp),
            ) {
              Icon(
                imageVector = Icons.Default.Download,
                contentDescription = "保存到相册",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.size(16.dp),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun MessageContent(
  message: ChatMessage,
  isUser: Boolean,
) {
  val contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary
  else MaterialTheme.colorScheme.onSurface

  when (message.type) {
    "image" -> ImageBubble(message = message, isUser = isUser, contentColor = contentColor)
    "video" -> VideoBubble(message = message, isUser = isUser, contentColor = contentColor)
    else -> TextBubble(message = message, contentColor = contentColor)
  }
}

@Composable
private fun ImageBubble(
  message: ChatMessage,
  isUser: Boolean,
  contentColor: androidx.compose.ui.graphics.Color,
) {
  val context = LocalContext.current
  val uri = message.attachmentUri

  if (uri != null) {
    AsyncImage(
      model = ImageRequest.Builder(context)
        .data(uri)
        .crossfade(true)
        .build(),
      contentDescription = "图片（点击查看大图）",
      modifier = Modifier
        .width(200.dp)
        .height(150.dp)
        .clip(RoundedCornerShape(8.dp))
        .clickable {
          openWithSystemViewer(context, uri, "image/png")
        },
      contentScale = ContentScale.Crop,
    )
  } else {
    Box(
      modifier = Modifier
        .width(200.dp)
        .height(150.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(
          if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f)
          else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
        ),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Default.Image,
        contentDescription = "图片",
        tint = contentColor.copy(alpha = if (isUser) 0.5f else 0.4f),
        modifier = Modifier.size(40.dp),
      )
    }
  }
  if (message.content.isNotEmpty()) {
    Spacer(modifier = Modifier.height(6.dp))
    Text(text = message.content, color = contentColor, fontSize = 13.sp)
  }
}

@Composable
private fun VideoBubble(
  message: ChatMessage,
  isUser: Boolean,
  contentColor: androidx.compose.ui.graphics.Color,
) {
  val context = LocalContext.current
  val uri = message.attachmentUri

  Box(
    modifier = Modifier
      .width(200.dp)
      .height(120.dp)
      .clip(RoundedCornerShape(8.dp))
      .background(
        if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f)
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
      )
      .clickable(enabled = uri != null) {
        if (uri != null) {
          openWithSystemViewer(context, uri, "video/mp4")
        }
      },
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = Icons.Default.PlayArrow,
      contentDescription = if (uri != null) "点击播放视频" else "视频",
      tint = if (uri != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
      else contentColor.copy(alpha = if (isUser) 0.6f else 0.5f),
      modifier = Modifier.size(44.dp),
    )
  }
  if (message.content.isNotEmpty()) {
    Spacer(modifier = Modifier.height(6.dp))
    Text(text = message.content, color = contentColor, fontSize = 13.sp)
  }
}

@Composable
private fun TextBubble(
  message: ChatMessage,
  contentColor: androidx.compose.ui.graphics.Color,
) {
  if (message.content.isNotEmpty()) {
    Text(text = message.content, color = contentColor, fontSize = 15.sp)
  }
  if (message.attachmentUri != null) {
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      text = "[附件]",
      color = contentColor.copy(alpha = 0.6f),
      fontSize = 12.sp,
    )
  }
}

// ── 工具函数 ──

/**
 * 用系统查看器打开媒体文件。
 * 通过 content:// URI（FileProvider）或 file:// URI 启动 ACTION_VIEW Intent。
 */
private fun openWithSystemViewer(context: android.content.Context, uriString: String, mimeType: String) {
  try {
    val uri = Uri.parse(uriString)
    val intent = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, mimeType)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      // ClipData 兜底：部分 OEM ROM（MIUI/ColorOS）上 NEW_TASK 与 URI 授权冲突，
      // 通过 ClipData 授予额外权限可提高兼容性
      clipData = android.content.ClipData.newUri(context.contentResolver, "media", uri)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    // 检查是否有应用能处理
    if (intent.resolveActivity(context.packageManager) != null) {
      context.startActivity(intent)
    } else {
      Toast.makeText(context, "未找到可用的播放器", Toast.LENGTH_SHORT).show()
    }
  } catch (e: Exception) {
    Toast.makeText(context, "打开失败: ${e.message}", Toast.LENGTH_SHORT).show()
  }
}

/**
 * 保存媒体文件到系统相册（Pictures/Memento）。
 * 优先使用 MediaStore API（Android 10+），兼容旧版本直接复制文件。
 */
private suspend fun saveToGallery(context: android.content.Context, message: ChatMessage) {
  val localPath = message.localPath
  if (localPath == null) {
    Toast.makeText(context, "文件路径不可用", Toast.LENGTH_SHORT).show()
    return
  }
  val file = File(localPath)
  if (!file.exists()) {
    Toast.makeText(context, "文件不存在，可能已被清理", Toast.LENGTH_SHORT).show()
    return
  }
  try {
    val isVideo = message.type == "video"
    val mimeType = if (isVideo) "video/mp4" else "image/png"
    val relativePath = if (isVideo) "${Environment.DIRECTORY_MOVIES}/Memento"
    else "${Environment.DIRECTORY_PICTURES}/Memento"

    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
      val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
      }
      val uri = context.contentResolver.insert(
        if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        values,
      )
      if (uri != null) {
        // H-U2 修复：文件拷贝移到 IO 线程，避免主线程同步拷贝多 MB 文件导致 ANR
        withContext(Dispatchers.IO) {
          context.contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
          }
        }
        Toast.makeText(context, "已保存到相册", Toast.LENGTH_SHORT).show()
      } else {
        throw Exception("无法创建 MediaStore 条目")
      }
    } else {
      @Suppress("DEPRECATION")
      val destDir = File(
        Environment.getExternalStoragePublicDirectory(
          if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        ),
        "Memento",
      ).also { it.mkdirs() }
      val dest = File(destDir, file.name)
      // H-U2 修复：文件拷贝移到 IO 线程，避免主线程同步拷贝多 MB 文件导致 ANR
      withContext(Dispatchers.IO) {
        file.copyTo(dest, overwrite = true)
      }
      // 通知媒体扫描
      val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
        data = Uri.fromFile(dest)
      }
      context.sendBroadcast(intent)
      Toast.makeText(context, "已保存到相册", Toast.LENGTH_SHORT).show()
    }
  } catch (e: Exception) {
    Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
  }
}