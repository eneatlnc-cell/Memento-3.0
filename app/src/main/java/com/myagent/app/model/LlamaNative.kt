package com.myagent.app.model

import android.util.Log

/**
 * llama.cpp + libmtmd 的 JNI 声明层。
 *
 * 句柄用 Long 传递（opaque pointer），Kotlin 侧不接触原生指针。
 * 所有方法必须在 LlamaEngine 的 synchronized 块内调用，避免并发竞争。
 *
 * 依赖的原生库（jniLibs/arm64-v8a/）：
 * - libopencl_stub.so — OpenCL 3.0 stub（提供 clCreateBufferWithProperties，CMake 编译）
 * - libllama.so — 合体库（llama.cpp + ggml + mtmd），
 *   来自 llama.rn 0.12.5 预编译。DT_NEEDED libcdsprpc.so 已剥离（空字符串替换）。
 * - libllama_jni.so — 本项目自编的 JNI wrapper
 *
 * 加载顺序：opencl_stub → llama → llama_jni
 */
object LlamaNative {
  private const val TAG = "LlamaNative"

  @Volatile private var loaded = false

  fun ensureLoaded() {
    if (loaded) return
    synchronized(this) {
      if (loaded) return
      try {
        // 0) 先加载 OpenCL stub（提供 clCreateBufferWithProperties 桩实现）
        //    旧 GPU 驱动（OpenCL 1.2/2.0）缺少该 OpenCL 3.0 符号，
        //    libllama.so 的 dlopen 会失败。此 stub 在 libllama.so 之前加载，
        //    使动态链接器能解析该符号。
        try {
          System.loadLibrary("opencl_stub")
          Log.i(TAG, "opencl_stub loaded (OpenCL 3.0 stub for old GPU drivers)")
        } catch (e: UnsatisfiedLinkError) {
          Log.w(TAG, "opencl_stub not found: ${e.message}")
        }

        // 1) 加载底层合体库（含 llama.cpp + ggml + mtmd + Hexagon + OpenCL）
        //    DT_NEEDED libcdsprpc.so 已从 .so 中剥离（空字符串替换），
        //    DT_NEEDED libOpenCL.so 由系统库 + opencl_stub 共同解析。
        System.loadLibrary("llama")
        // 2) 加载我们的 JNI wrapper
        System.loadLibrary("llama_jni")
        loaded = true
        Log.i(TAG, "Native libraries loaded (opencl_stub + llama + llama_jni)")
      } catch (e: UnsatisfiedLinkError) {
        Log.e(TAG, "Failed to load native libraries: ${e.message}", e)
        throw e
      }
    }
  }

  // ── backend ──

  // 初始化崩溃日志文件（需在 backendInit 之前调用）
  // path 示例：/data/data/com.myagent.app/files/logs/llama_crash.log
  external fun initLogFile(path: String)

  // 写入一行诊断日志到崩溃日志文件
  external fun logToFile(msg: String)

  external fun backendInit()
  external fun backendFree()

  // ── cancel ──
  // 设置全局取消标志，使正在运行的 completion 在下一个 token 迭代退出。
  // 用于 close() 安全等待推理结束，避免并发 free 导致 UAF。

  external fun cancelCompletion()

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

  external fun mtmdInit(model: Long, mmprojPath: String, useGpu: Boolean): Long
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

  // ── 带 GBNF grammar 约束的流式生成（结构化输出） ──
  // grammarStr 是 GBNF 语法定义，grammarRoot 通常是 "root"
  // 采样阶段强制约束，模型无法生成非法 token，无需事后校验循环

  external fun completionWithGrammar(
    ctx: Long,
    prompt: String,
    grammarStr: String,
    maxTokens: Int,
    temperature: Float,
    topP: Float,
    topK: Int,
    callback: TokenCallback,
  )

  /**
   * 流式 token 回调接口。
   * onToken 在 JNI 线程被调用，Kotlin 侧应快速转发到 Channel/Flow。
   */
  interface TokenCallback {
    fun onToken(piece: String, isEos: Boolean)
  }
}
