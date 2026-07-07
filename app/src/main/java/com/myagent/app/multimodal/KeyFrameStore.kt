package com.myagent.app.multimodal

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 关键帧缓存 — 三段式工作流（图形/帧/合成）的状态传递枢纽。
 *
 * 工作流：
 *   图形 → 产出关键帧 → 缓存到这里
 *   帧   → 读取缓存的关键帧 → 改造 → 写回缓存
 *   合成 → 读取缓存的关键帧 → 渲染 MP4
 *
 * 这是一个单例对象，生命周期与 App 相同。
 * 关键帧以 Uri 形式存储（可以是 content:// 或 file://）。
 */
object KeyFrameStore {

  private val _keyFrames = MutableStateFlow<List<Uri>>(emptyList())
  val keyFrames: StateFlow<List<Uri>> = _keyFrames.asStateFlow()

  private val _sourceLabel = MutableStateFlow("")
  val sourceLabel: StateFlow<String> = _sourceLabel.asStateFlow()

  /** 设置关键帧（来自图形工坊或帧工坊） */
  fun setKeyFrames(uris: List<Uri>, source: String = "") {
    _keyFrames.value = uris.toList()
    _sourceLabel.value = source
  }

  /** 替换单帧 */
  fun replaceFrame(index: Int, uri: Uri) {
    val current = _keyFrames.value.toMutableList()
    if (index in current.indices) {
      current[index] = uri
      _keyFrames.value = current
    }
  }

  /** 删除单帧 */
  fun removeFrame(index: Int) {
    val current = _keyFrames.value.toMutableList()
    if (index in current.indices) {
      current.removeAt(index)
      _keyFrames.value = current
    }
  }

  /** 追加帧 */
  fun appendFrame(uri: Uri) {
    val current = _keyFrames.value.toMutableList()
    current.add(uri)
    _keyFrames.value = current
  }

  /** 清空 */
  fun clear() {
    _keyFrames.value = emptyList()
    _sourceLabel.value = ""
  }

  val size: Int get() = _keyFrames.value.size
  val isEmpty: Boolean get() = _keyFrames.value.isEmpty()
}
