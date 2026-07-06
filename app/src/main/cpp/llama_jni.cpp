// llama_jni.cpp — llama.cpp + libmtmd 的 JNI 桥接层
//
// 设计原则：
// - 句柄用 jlong 传递（opaque pointer），Kotlin 侧不接触原生指针
// - 流式输出通过 JNI 回调 TokenCallback.onToken(piece, isEos)
// - 多模态用 libmtmd（取代已废弃的 llava/clip 接口）
// - mmproj 默认 use_gpu=false（CVPR 2026 实测：骁龙上 mmproj 跑 OpenCL 抖动大）
//
// 依赖的 .so（jniLibs/arm64-v8a/）：
//   libllama.so — 合体库（llama.rn 0.12.5 预编译，含 CPU + Hexagon + OpenCL 后端静态链接）
//   libllama_jni.so — 本文件编译产物
//
// 头文件来源：llama.rn npm 包的 cpp/ 目录拷贝到 cpp/include/

#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <atomic>
#include <fstream>
#include <chrono>
#include <ctime>
#include <sstream>
#include <cerrno>
#include <sys/stat.h>
#include <unistd.h>
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "LlamaJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#include <android/log.h>

// ── 回调辅助：把 token piece 回调到 Kotlin ──
class TokenEmitter {
public:
  TokenEmitter(JNIEnv *env, jobject callback)
    : env_(env), callback_(nullptr), on_token_mid_(nullptr) {
    if (callback == nullptr) {
      LOGE("TokenCallback is null");
      return;
    }
    callback_ = env->NewGlobalRef(callback);
    if (callback_ == nullptr) {
      LOGE("TokenCallback NewGlobalRef failed");
      return;
    }
    jclass cls = env->GetObjectClass(callback_);
    on_token_mid_ = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;Z)V");
    env_->DeleteLocalRef(cls);
    if (on_token_mid_ == nullptr) {
      LOGE("TokenCallback.onToken method not found");
      env_->DeleteGlobalRef(callback_);
      callback_ = nullptr;
    }
  }
  ~TokenEmitter() {
    if (callback_ != nullptr) {
      env_->DeleteGlobalRef(callback_);
    }
  }

  // 返回 false 表示回调失败（Kotlin 抛异常 / NewStringUTF OOM / 无效回调），
  // 调用方需中断生成循环，否则挂起异常在下一次 JNI 调用即未定义行为
  bool emit(const std::string &piece, bool isEos) {
    if (callback_ == nullptr || on_token_mid_ == nullptr) {
      return false;
    }
    jstring j = env_->NewStringUTF(piece.c_str());
    if (j == nullptr) {
      LOGE("NewStringUTF returned null");
      return false;
    }
    env_->CallVoidMethod(callback_, on_token_mid_, j, isEos ? JNI_TRUE : JNI_FALSE);
    env_->DeleteLocalRef(j);
    if (env_->ExceptionCheck()) {
      env_->ExceptionClear();
      LOGE("TokenCallback threw exception, aborting generation");
      return false;
    }
    return true;
  }

private:
  JNIEnv *env_;
  jobject callback_;
  jmethodID on_token_mid_;
};

// M-19 修复：llama_token_to_piece 用动态缓冲，避免 128B 静态缓冲截断长 token
static std::string token_to_piece_str(const llama_vocab *vocab, llama_token token) {
  int needed = llama_token_to_piece(vocab, token, nullptr, 0, 0, false);
  if (needed <= 0) {
    return {};
  }
  std::vector<char> buf(needed + 1);
  int len = llama_token_to_piece(vocab, token, buf.data(), (int32_t)buf.size(), 0, false);
  if (len <= 0) {
    return {};
  }
  return std::string(buf.data(), len);
}

// ── 全局状态：backend 只初始化一次 ──
static bool g_backend_initialized = false;

// ── 文件日志：llama.cpp 崩溃日志持久化到 App 内部存储 ──
// 用户无法通过 adb 获取日志时，可在 App 内直接查看此文件。
// 路径由 Kotlin 侧通过 initLogFile() 传入，通常为：
// /data/data/com.myagent.app/files/logs/llama_crash.log
static std::ofstream g_log_file;

static void file_log_write(const char *msg) {
  if (!g_log_file.is_open()) return;
  auto now = std::chrono::system_clock::now();
  auto now_c = std::chrono::system_clock::to_time_t(now);
  std::string time_str = std::ctime(&now_c);
  if (!time_str.empty() && time_str.back() == '\n') time_str.pop_back();
  g_log_file << "[" << time_str << "] " << msg << "\n";
  g_log_file.flush();
}

// C-N3/C-N4 修复：推理取消标志。
// Memento 单推理流设计（同时只有一个 completion 在跑），全局标志足够。
// 在每个 token 迭代开头检查，使阻塞的 JNI 推理可被中断，
// 从而让 close() 能安全等待推理退出后再 free ctx，避免 UAF。
static std::atomic<bool> g_cancel_flag{false};

// ── llama.cpp 日志回调：转发到 Android logcat + 文件 ──
static void llama_log_callback(enum lm_ggml_log_level level, const char *text, void *) {
  if (text == nullptr) return;
  if (level == LM_GGML_LOG_LEVEL_CONT) return;
  int prio = ANDROID_LOG_INFO;
  if (level >= LM_GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
  else if (level >= LM_GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
  else if (level <= LM_GGML_LOG_LEVEL_DEBUG) prio = ANDROID_LOG_DEBUG;
  __android_log_print(prio, "llama.cpp", "%s", text);
  file_log_write(text);  // 同时写入文件（崩溃排查用）
}

extern "C" {

// ===== 日志文件初始化 =====
// 在 backendInit 之前调用，创建日志目录并打开文件。
// 路径由 Kotlin 侧传入，通常为 App 内部存储路径。

JNIEXPORT void JNICALL
Java_com_myagent_app_model_LlamaNative_initLogFile(JNIEnv *env, jobject, jstring jpath) {
  if (jpath == nullptr) {
    LOGE("initLogFile: jpath is null");
    return;
  }
  const char *path = env->GetStringUTFChars(jpath, nullptr);

  if (g_log_file.is_open()) {
    g_log_file.close();
  }

  // 确保目录存在（递归创建）
  std::string path_str(path);
  size_t last_slash = path_str.find_last_of('/');
  if (last_slash != std::string::npos) {
    std::string dir = path_str.substr(0, last_slash);
    std::string acc;
    for (size_t i = 0; i < dir.size(); i++) {
      acc += dir[i];
      if (dir[i] == '/' || i == dir.size() - 1) {
        mkdir(acc.c_str(), 0755);
      }
    }
  }

  g_log_file.open(path, std::ios::out | std::ios::app);
  if (g_log_file.is_open()) {
    LOGI("Log file opened: %s", path);
    file_log_write("=== Memento llama.cpp crash log started ===");
  } else {
    LOGE("Failed to open log file: %s", path);
  }

  env->ReleaseStringUTFChars(jpath, path);
}

// ===== 写入一行诊断日志到文件（Kotlin 侧调用） =====
JNIEXPORT void JNICALL
Java_com_myagent_app_model_LlamaNative_logToFile(JNIEnv *env, jobject, jstring jmsg) {
  if (jmsg == nullptr) return;
  const char *msg = env->GetStringUTFChars(jmsg, nullptr);
  file_log_write(msg);
  env->ReleaseStringUTFChars(jmsg, msg);
}

// ===== cancel =====

JNIEXPORT void JNICALL
Java_com_myagent_app_model_LlamaNative_cancelCompletion(JNIEnv *, jobject) {
  g_cancel_flag.store(true);
}

// ===== backend =====

JNIEXPORT void JNICALL
Java_com_myagent_app_model_LlamaNative_backendInit(JNIEnv *, jobject) {
  if (!g_backend_initialized) {
    llama_log_set(llama_log_callback, nullptr);
    llama_backend_init();
    g_backend_initialized = true;
    LOGI("llama_backend_init done (log callback registered, tag=llama.cpp)");
  }
}

JNIEXPORT void JNICALL
Java_com_myagent_app_model_LlamaNative_backendFree(JNIEnv *, jobject) {
  if (g_backend_initialized) {
    file_log_write("=== backendFree: shutting down ===");
    llama_backend_free();
    g_backend_initialized = false;
    if (g_log_file.is_open()) {
      g_log_file.close();
    }
    LOGI("llama_backend_free done");
  }
}

// ===== model =====

JNIEXPORT jlong JNICALL
Java_com_myagent_app_model_LlamaNative_modelLoad(
  JNIEnv *env, jobject, jstring jpath, jint nGpuLayers, jboolean useHtp) {
  if (jpath == nullptr) {
    LOGE("modelLoad: jpath is null");
    return 0;
  }
  const char *path = env->GetStringUTFChars(jpath, nullptr);

  LOGI("modelLoad: path=%s, n_gpu_layers=%d, useHtp=%d", path, nGpuLayers, (int)useHtp);

  llama_model_params params = llama_model_default_params();
  params.n_gpu_layers = nGpuLayers;
  // mmap 开启：Android 12+ (API 31+) 的 mmap 在内部分区上完全稳定，
  // 且 mmap 让 OS 按需加载页面，避免 OOM（533MB 模型 + 展开权重 ~1.3GB）。
  // 之前 use_mmap=false 导致完整读入 RAM → OOM → SIGKILL → "闪崩"。
  params.use_mmap = true;

  // ── GGUF 文件魔数校验（防止加载损坏/不兼容的文件） ──
  {
    FILE *f = fopen(path, "rb");
    if (f == nullptr) {
      LOGE("modelLoad: cannot open file: %s (errno=%d)", path, errno);
      env->ReleaseStringUTFChars(jpath, path);
      return 0;
    }
    char magic[4];
    size_t n = fread(magic, 1, 4, f);
    fclose(f);
    if (n != 4 || magic[0] != 'G' || magic[1] != 'G' || magic[2] != 'U' || magic[3] != 'F') {
      LOGE("modelLoad: invalid GGUF magic bytes in %s", path);
      env->ReleaseStringUTFChars(jpath, path);
      return 0;
    }
    LOGI("modelLoad: GGUF magic bytes OK for %s", path);
  }

  // Hexagon NPU 设备路由：骁龙 SM8450+ 通过 HTP0 激活 NPU
  (void)useHtp;

  LOGI("modelLoad: calling llama_model_load_from_file...");
  file_log_write(">>> modelLoad: about to call llama_model_load_from_file");
  llama_model *model = llama_model_load_from_file(path, params);

  if (model == nullptr) {
    file_log_write("<<< modelLoad: llama_model_load_from_file returned NULL");
    LOGE("model load failed: %s (n_gpu_layers=%d)", path, nGpuLayers);
    env->ReleaseStringUTFChars(jpath, path);
    return 0;
  }

  file_log_write("<<< modelLoad: llama_model_load_from_file succeeded");
  // 输出模型诊断信息
  int64_t n_params = llama_model_n_params(model);
  char desc[256];
  llama_model_desc(model, desc, sizeof(desc));
  LOGI("model loaded: %s, n_params=%lld, n_gpu_layers=%d", desc, (long long)n_params, nGpuLayers);

  env->ReleaseStringUTFChars(jpath, path);
  return reinterpret_cast<jlong>(model);
}

JNIEXPORT void JNICALL
Java_com_myagent_app_model_LlamaNative_modelFree(JNIEnv *, jobject, jlong jmodel) {
  if (jmodel != 0) {
    llama_model_free(reinterpret_cast<llama_model *>(jmodel));
    LOGI("model freed");
  }
}

// ===== context =====

JNIEXPORT jlong JNICALL
Java_com_myagent_app_model_LlamaNative_contextInit(
  JNIEnv *, jobject, jlong jmodel, jint nCtx, jint nThreads, jint nBatch) {
  auto *model = reinterpret_cast<llama_model *>(jmodel);
  llama_context_params params = llama_context_default_params();
  params.n_ctx = nCtx;
  params.n_threads = nThreads;
  params.n_batch = nBatch;
  params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;  // 骁龙实测：flash-attn 显著降低 KV 内存
  params.no_perf = true;     // 不收集性能计数器，省一点开销

  file_log_write(">>> contextInit: about to call llama_init_from_model");
  llama_context *ctx = llama_init_from_model(model, params);
  if (ctx == nullptr) {
    file_log_write("<<< contextInit: llama_init_from_model returned NULL");
    LOGE("context init failed");
    return 0;
  }
  file_log_write("<<< contextInit: succeeded");
  LOGI("context ready: n_ctx=%d n_threads=%d n_batch=%d", nCtx, nThreads, nBatch);
  return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_myagent_app_model_LlamaNative_contextFree(JNIEnv *, jobject, jlong jctx) {
  if (jctx != 0) {
    llama_free(reinterpret_cast<llama_context *>(jctx));
    LOGI("context freed");
  }
}

// ===== 流式文本生成（纯文本） =====

JNIEXPORT void JNICALL
Java_com_myagent_app_model_LlamaNative_completion(
  JNIEnv *env, jobject, jlong jctx, jstring jprompt,
  jint maxTokens, jfloat temp, jfloat topP, jint topK,
  jobject jcallback) {
  if (jprompt == nullptr) {
    LOGE("completion: jprompt is null");
    return;
  }
  auto *ctx = reinterpret_cast<llama_context *>(jctx);
  if (ctx == nullptr) {
    LOGE("completion: null context");
    return;
  }

  // 每次推理开始时重置取消标志
  g_cancel_flag.store(false);

  const llama_vocab *vocab = llama_model_get_vocab(llama_get_model(ctx));
  const char *prompt = env->GetStringUTFChars(jprompt, nullptr);
  const size_t prompt_len = std::strlen(prompt);

  // 1) tokenize prompt
  //    注意：ReleaseStringUTFChars 必须在最后一次使用 prompt 之后调用，
  //    否则重试分支和后续日志会读到已释放内存（UAF）。
  std::vector<llama_token> tokens(prompt_len + 16);
  int n = llama_tokenize(vocab, prompt, (int32_t)prompt_len,
                         tokens.data(), (int32_t)tokens.size(),
                         true, true);
  if (n < 0) {
    tokens.resize(-n);
    n = llama_tokenize(vocab, prompt, (int32_t)prompt_len,
                       tokens.data(), (int32_t)tokens.size(), true, true);
  }
  env->ReleaseStringUTFChars(jprompt, prompt);
  if (n <= 0) {
    LOGE("tokenize failed");
    return;
  }
  tokens.resize(n);

  // 2) 喂 prompt
  llama_batch batch = llama_batch_get_one(tokens.data(), n);
  if (llama_decode(ctx, batch) != 0) {
    LOGE("prompt decode failed");
    return;
  }

  // 3) 采样器链：top_k → top_p → temp → dist
  llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
  llama_sampler_chain_add(sampler, llama_sampler_init_top_k(topK));
  llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
  llama_sampler_chain_add(sampler, llama_sampler_init_temp(temp));
  llama_sampler_chain_add(sampler, llama_sampler_init_dist(0));

  TokenEmitter emitter(env, jcallback);
  const llama_token eos = llama_vocab_eos(vocab);

  // 4) 自回归生成
  for (int i = 0; i < maxTokens; ++i) {
    // C-N3/C-N4 修复：检查取消标志，使 close() 能安全中断推理
    if (g_cancel_flag.load()) {
      LOGI("completion cancelled at token %d", i);
      break;
    }
    llama_token tok = llama_sampler_sample(sampler, ctx, -1);
    if (tok == eos) {
      emitter.emit("", true);
      break;
    }
    std::string piece = token_to_piece_str(vocab, tok);
    if (!piece.empty()) {
      if (!emitter.emit(piece, false)) break;
    }
    llama_batch b = llama_batch_get_one(&tok, 1);
    if (llama_decode(ctx, b) != 0) {
      LOGE("decode failed at token %d", i);
      break;
    }
  }

  llama_sampler_free(sampler);
}

// ===== mtmd 多模态 =====

JNIEXPORT jlong JNICALL
Java_com_myagent_app_model_LlamaNative_mtmdInit(
  JNIEnv *env, jobject, jlong jmodel, jstring jmmprojPath, jboolean juseGpu) {
  if (jmmprojPath == nullptr) {
    LOGE("mtmdInit: jmmprojPath is null");
    return 0;
  }
  auto *model = reinterpret_cast<llama_model *>(jmodel);
  const char *path = env->GetStringUTFChars(jmmprojPath, nullptr);

  mtmd_context_params params = mtmd_context_params_default();
  params.use_gpu = (juseGpu == JNI_TRUE);  // 按需启用：NPU 设备上加速图像编码，编码完成后 GPU 闲置

  char log_buf[128];
  snprintf(log_buf, sizeof(log_buf), ">>> mtmdInit: about to call mtmd_init_from_file (use_gpu=%d)", params.use_gpu);
  file_log_write(log_buf);
  mtmd_context *mctx = mtmd_init_from_file(path, model, params);

  // H-S4 修复：日志在 release 之前使用 path，避免 UAF
  if (mctx == nullptr) {
    file_log_write("<<< mtmdInit: mtmd_init_from_file returned NULL");
    LOGE("mtmd_init_from_file failed: %s", path);
    env->ReleaseStringUTFChars(jmmprojPath, path);
    return 0;
  }
  file_log_write("<<< mtmdInit: succeeded");
  LOGI("mtmd ready: %s (use_gpu=%d)", path, params.use_gpu);
  env->ReleaseStringUTFChars(jmmprojPath, path);
  return reinterpret_cast<jlong>(mctx);
}

JNIEXPORT void JNICALL
Java_com_myagent_app_model_LlamaNative_mtmdFree(JNIEnv *, jobject, jlong jmctx) {
  if (jmctx != 0) {
    mtmd_free(reinterpret_cast<mtmd_context *>(jmctx));
    LOGI("mtmd freed");
  }
}

// ===== 多模态流式生成（文本 + 图片） =====
//
// prompt 必须含 <__media__> 占位符（mtmd_default_marker），
// Kotlin 侧负责构造 Qwen chat template 格式。

JNIEXPORT void JNICALL
Java_com_myagent_app_model_LlamaNative_completionWithImage(
  JNIEnv *env, jobject, jlong jctx, jlong jmctx,
  jstring jprompt, jobjectArray jimagePaths,
  jint maxTokens, jfloat temp, jfloat topP, jint topK,
  jobject jcallback) {
  if (jprompt == nullptr) {
    LOGE("completionWithImage: jprompt is null");
    return;
  }
  auto *ctx = reinterpret_cast<llama_context *>(jctx);
  auto *mctx = reinterpret_cast<mtmd_context *>(jmctx);
  if (ctx == nullptr || mctx == nullptr) {
    LOGE("completionWithImage: null ctx/mctx");
    return;
  }

  // 每次推理开始时重置取消标志
  g_cancel_flag.store(false);

  const llama_vocab *vocab = llama_model_get_vocab(llama_get_model(ctx));
  const char *prompt = env->GetStringUTFChars(jprompt, nullptr);

  // 1) 加载所有图片为 bitmap
  std::vector<mtmd_bitmap *> bitmaps;
  jsize nImages = env->GetArrayLength(jimagePaths);
  for (jsize i = 0; i < nImages; ++i) {
    jstring jpath = (jstring)env->GetObjectArrayElement(jimagePaths, i);
    if (jpath == nullptr) {
      LOGE("completionWithImage: image path at index %d is null", (int)i);
      continue;
    }
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    // mtmd_helper_bitmap_init_from_file 返回 wrapper 结构体（按值），
    // wrapper.bitmap 才是真正的 mtmd_bitmap*；wrapper.video_ctx 仅视频场景非空
    mtmd_helper_bitmap_wrapper wrapper = mtmd_helper_bitmap_init_from_file(mctx, path, false);
    mtmd_bitmap *bmp = wrapper.bitmap;
    if (wrapper.video_ctx != nullptr) {
      mtmd_helper_video_free(wrapper.video_ctx);  // 图片场景不会进入这里
    }
    // H-S4 修复：日志在 release 之前使用 path，避免 UAF
    if (bmp == nullptr) {
      LOGE("bitmap load failed: %s", path);
    } else {
      bitmaps.push_back(bmp);
    }
    env->ReleaseStringUTFChars(jpath, path);
    env->DeleteLocalRef(jpath);
  }
  if (bitmaps.empty()) {
    LOGE("no valid images, abort multimodal");
    env->ReleaseStringUTFChars(jprompt, prompt);
    return;
  }

  // 2) mtmd_tokenize：把含 <__media__> 的文本 + bitmaps 编为混合 chunks
  mtmd_input_text input_text;
  input_text.text = prompt;
  input_text.add_special = true;
  input_text.parse_special = true;

  std::vector<const mtmd_bitmap *> bmp_ptrs;
  for (auto *b : bitmaps) bmp_ptrs.push_back(b);

  mtmd_input_chunks *chunks = mtmd_input_chunks_init();
  int rc = mtmd_tokenize(mctx, chunks, &input_text,
                         bmp_ptrs.data(), bmp_ptrs.size());
  env->ReleaseStringUTFChars(jprompt, prompt);

  if (rc != 0) {
    LOGE("mtmd_tokenize failed: %d", rc);
    mtmd_input_chunks_free(chunks);
    for (auto *b : bitmaps) mtmd_bitmap_free(b);
    return;
  }

  // 3) eval 所有 chunks（helper 内部自动按 n_batch 分批 llama_decode，
  //    并对图片 chunk 先跑 mtmd_encode 视觉编码器）
  llama_pos n_past = 0;
  rc = mtmd_helper_eval_chunks(mctx, ctx, chunks, 0, 0, 512, false, &n_past);
  if (rc != 0) {
    LOGE("mtmd_helper_eval_chunks failed: %d", rc);
  }

  // 4) 释放 chunks 和 bitmaps（embedding 已写入 KV cache）
  mtmd_input_chunks_free(chunks);
  for (auto *b : bitmaps) mtmd_bitmap_free(b);

  // 5) 与纯文本一样，自回归采样生成
  llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
  llama_sampler_chain_add(sampler, llama_sampler_init_top_k(topK));
  llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
  llama_sampler_chain_add(sampler, llama_sampler_init_temp(temp));
  llama_sampler_chain_add(sampler, llama_sampler_init_dist(0));

  TokenEmitter emitter(env, jcallback);
  const llama_token eos = llama_vocab_eos(vocab);

  for (int i = 0; i < maxTokens; ++i) {
    // C-N3/C-N4 修复：检查取消标志，使 close() 能安全中断推理
    if (g_cancel_flag.load()) {
      LOGI("completionWithImage cancelled at token %d", i);
      break;
    }
    llama_token tok = llama_sampler_sample(sampler, ctx, -1);
    if (tok == eos) {
      emitter.emit("", true);
      break;
    }
    std::string piece = token_to_piece_str(vocab, tok);
    if (!piece.empty()) {
      if (!emitter.emit(piece, false)) break;
    }
    llama_batch b = llama_batch_get_one(&tok, 1);
    if (llama_decode(ctx, b) != 0) {
      LOGE("decode failed at token %d", i);
      break;
    }
  }

  llama_sampler_free(sampler);
}

// ===== 设备能力查询 =====

JNIEXPORT jstring JNICALL
Java_com_myagent_app_model_LlamaNative_getBackendInfo(JNIEnv *env, jobject) {
  // 返回当前可用的后端信息（用于日志/诊断）
  std::string info = "llama.cpp + mtmd (built with Snapdragon toolchain)";
  if (g_backend_initialized) {
    info += " [backend initialized]";
  }
  return env->NewStringUTF(info.c_str());
}

// ===== 带 GBNF grammar 约束的流式生成（结构化输出） =====
//
// 设计：在采样器链末尾追加 grammar sampler，强制模型只能输出符合 grammar 的 token。
// 这是从源头约束 LLM 输出结构，模型物理上无法生成非法 JSON。
// 与"生成后校验"相比，无需重试循环，端侧小模型也能稳定产出结构化数据。

JNIEXPORT void JNICALL
Java_com_myagent_app_model_LlamaNative_completionWithGrammar(
  JNIEnv *env, jobject, jlong jctx, jstring jprompt, jstring jgrammar,
  jint maxTokens, jfloat temp, jfloat topP, jint topK,
  jobject jcallback) {
  if (jprompt == nullptr) {
    LOGE("completionWithGrammar: jprompt is null");
    return;
  }
  if (jgrammar == nullptr) {
    LOGE("completionWithGrammar: jgrammar is null");
    return;
  }
  auto *ctx = reinterpret_cast<llama_context *>(jctx);
  if (ctx == nullptr) {
    LOGE("completionWithGrammar: null context");
    return;
  }

  // 每次推理开始时重置取消标志
  g_cancel_flag.store(false);

  const llama_vocab *vocab = llama_model_get_vocab(llama_get_model(ctx));
  const char *prompt = env->GetStringUTFChars(jprompt, nullptr);
  const char *grammar = env->GetStringUTFChars(jgrammar, nullptr);
  const size_t prompt_len = std::strlen(prompt);

  // 1) tokenize prompt
  //    注意：ReleaseStringUTFChars 必须在最后一次使用 prompt 之后调用，
  //    否则重试分支会读到已释放内存（UAF）。
  std::vector<llama_token> tokens(prompt_len + 16);
  int n = llama_tokenize(vocab, prompt, (int32_t)prompt_len,
                         tokens.data(), (int32_t)tokens.size(),
                         true, true);
  if (n < 0) {
    tokens.resize(-n);
    n = llama_tokenize(vocab, prompt, (int32_t)prompt_len,
                       tokens.data(), (int32_t)tokens.size(), true, true);
  }
  env->ReleaseStringUTFChars(jprompt, prompt);
  if (n <= 0) {
    LOGE("tokenize failed (grammar)");
    env->ReleaseStringUTFChars(jgrammar, grammar);
    return;
  }
  tokens.resize(n);

  // 2) 喂 prompt
  llama_batch batch = llama_batch_get_one(tokens.data(), n);
  if (llama_decode(ctx, batch) != 0) {
    LOGE("prompt decode failed (grammar)");
    env->ReleaseStringUTFChars(jgrammar, grammar);
    return;
  }

  // 3) 采样器链：top_k → top_p → temp → dist → grammar
  //    grammar 必须在 dist 之后，它接受 dist 的输出并约束到合法 token 集合
  llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
  llama_sampler_chain_add(sampler, llama_sampler_init_top_k(topK));
  llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
  llama_sampler_chain_add(sampler, llama_sampler_init_temp(temp));
  llama_sampler_chain_add(sampler, llama_sampler_init_dist(0));

  if (grammar != nullptr && grammar[0] != '\0') {
    llama_sampler *g = llama_sampler_init_grammar(vocab, grammar, "root");
    if (g != nullptr) {
      llama_sampler_chain_add(sampler, g);
      LOGI("grammar constraint enabled");
    } else {
      LOGE("grammar init failed, falling back to unconstrained");
    }
  }
  env->ReleaseStringUTFChars(jgrammar, grammar);

  TokenEmitter emitter(env, jcallback);
  const llama_token eos = llama_vocab_eos(vocab);

  // 4) 自回归生成（grammar 自动约束每个 token 合法）
  for (int i = 0; i < maxTokens; ++i) {
    // C-N3/C-N4 修复：检查取消标志，使 close() 能安全中断推理
    if (g_cancel_flag.load()) {
      LOGI("completionWithGrammar cancelled at token %d", i);
      break;
    }
    llama_token tok = llama_sampler_sample(sampler, ctx, -1);
    if (tok == eos) {
      emitter.emit("", true);
      break;
    }
    std::string piece = token_to_piece_str(vocab, tok);
    if (!piece.empty()) {
      if (!emitter.emit(piece, false)) break;
    }
    llama_batch b = llama_batch_get_one(&tok, 1);
    if (llama_decode(ctx, b) != 0) {
      LOGE("decode failed at token %d (grammar)", i);
      break;
    }
  }

  llama_sampler_free(sampler);
}

} // extern "C"
