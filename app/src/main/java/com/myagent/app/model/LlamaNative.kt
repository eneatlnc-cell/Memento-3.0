package com.myagent.app.model

import android.util.Log

/**
 * llama.cpp + libmtmd 的 JNI 声明层。
 *
 * 句柄用 Long 传递（opaque pointer），Kotlin 侧不接触原生指针。
 * 所有方法必须在 LlamaEngine 的 synchronized 块内调用，避免并发竞争。
 *
 * 依赖的原生库（jniLibs/arm64-v8a/，由 Snapdragon toolchain docker 编译）：
 * - libggml.so / libggml-cpu.so / libggml-opencl.so / libggml-hexagon.so
 * - libggml-htp-v73.so / v75 / v79 / v81（按设备 SoC 运行时选择）
 * - libllama.so / libmtmd.so
 * - libllama_jni.so（本项目自编的 JNI wrapper）
 *
 * 加载顺序很重要：ggml 先于 llama，llama 先于 mtmd，最后加载本项目 wrapper。
 */
object LlamaNative {
  private const val TAG = "LlamaNative"

  @Volatile private var loaded = false

  fun ensureLoaded() {
    if (loaded) return
    synchronized(this) {
      if (loaded) return
      try {
        // 顺序：底层 → 上层 → wrapper
        System.loadLibrary("ggml")
        System.loadLibrary("llama")
        System.loadLibrary("mtmd")
        System.loadLibrary("llama_jni")
        loaded = true
        Log.i(TAG, "Native libraries loaded")
      } catch (e: UnsatisfiedLinkError) {
        Log.e(TAG, "Failed to load native libraries: ${e.message}", e)
        throw e
      }
    }
  }

  // ── backend ──

  external fun backendInit()
  external fun backendFree()

  // ── model ──
  // 返回 0 表示失败

  external fun modelLoad(path: String, nGpuLayers: Int, useHtp: Boolean): Long
  external fun modelFree(model: Long)

  // ── context ──
  // 返回 0 表示失败

  external fun contextInit(model: Long, nCtx: Int, nThreads: Int, nBatch: Int): Long
  external fun contextFree(ctx: Long)

  // ── 纯文本流式生成 ──
  // callback.onToken(piece, isEos) 每个 token 调用一次

  external fun completion(
    ctx: Long,
    prompt: String,
    maxTokens: Int,
    temperature: Float,
    topP: Float,
    topK: Int,
    callback: TokenCallback,
  )

  // ── mtmd 多模态 ──
  // 返回 0 表示失败

  external fun mtmdInit(model: Long, mmprojPath: String): Long
  external fun mtmdFree(mctx: Long)

  // ── 多模态流式生成 ──
  // prompt 必须含 <__media__> 占位符（每张图一个）
  // imagePaths 是图片绝对路径数组

  external fun completionWithImage(
    ctx: Long,
    mctx: Long,
    prompt: String,
    imagePaths: Array<String>,
    maxTokens: Int,
    temperature: Float,
    topP: Float,
    topK: Int,
    callback: TokenCallback,
  )

  // ── 诊断 ──

  external fun getBackendInfo(): String

  /**
   * 流式 token 回调接口。
   * onToken 在 JNI 线程被调用，Kotlin 侧应快速转发到 Channel/Flow。
   */
  interface TokenCallback {
    fun onToken(piece: String, isEos: Boolean)
  }
}
