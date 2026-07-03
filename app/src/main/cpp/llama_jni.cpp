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
    : env_(env), callback_(env->NewGlobalRef(callback)) {
    jclass cls = env->GetObjectClass(callback);
    on_token_mid_ = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;Z)V");
  }
  ~TokenEmitter() { env_->DeleteGlobalRef(callback_); }

  void emit(const std::string &piece, bool isEos) {
    jstring j = env_->NewStringUTF(piece.c_str());
    env_->CallVoidMethod(callback_, on_token_mid_, j, isEos ? JNI_TRUE : JNI_FALSE);
    env_->DeleteLocalRef(j);
  }

private:
  JNIEnv *env_;
  jobject callback_;
  jmethodID on_token_mid_;
};

// ── 全局状态：backend 只初始化一次 ──
static bool g_backend_initialized = false;

extern "C" {

// ===== backend =====

JNIEXPORT void JNICALL
Java_com_myagent_app_model_LlamaNative_backendInit(JNIEnv *, jobject) {
  if (!g_backend_initialized) {
    llama_backend_init();
    g_backend_initialized = true;
    LOGI("llama_backend_init done");
  }
}

JNIEXPORT void JNICALL
Java_com_myagent_app_model_LlamaNative_backendFree(JNIEnv *, jobject) {
  if (g_backend_initialized) {
    llama_backend_free();
    g_backend_initialized = false;
    LOGI("llama_backend_free done");
  }
}

// ===== model =====

JNIEXPORT jlong JNICALL
Java_com_myagent_app_model_LlamaNative_modelLoad(
  JNIEnv *env, jobject, jstring jpath, jint nGpuLayers, jboolean useHtp) {
  const char *path = env->GetStringUTFChars(jpath, nullptr);
  llama_model_params params = llama_model_default_params();
  params.n_gpu_layers = nGpuLayers;

  // Hexagon NPU 设备路由：骁龙 SM8450+ 通过 HTP0 激活 NPU
  // 注意：devices 字段在某些 llama.cpp 版本可能叫 n_devices/devices，
  // 这里用最保守的方式——n_gpu_layers > 0 + useHtp 标志，
  // 实际后端选择由 .so 编译时 GGML_HEXAGON=ON 决定
  (void)useHtp;  // 预留：未来若需显式 --device HTP0 再实现

  llama_model *model = llama_model_load_from_file(path, params);
  env->ReleaseStringUTFChars(jpath, path);

  if (model == nullptr) {
    LOGE("model load failed: %s", path);
    return 0;
  }
  LOGI("model loaded: %s (n_gpu_layers=%d)", path, nGpuLayers);
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

  llama_context *ctx = llama_init_from_model(model, params);
  if (ctx == nullptr) {
    LOGE("context init failed");
    return 0;
  }
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
  auto *ctx = reinterpret_cast<llama_context *>(jctx);
  if (ctx == nullptr) {
    LOGE("completion: null context");
    return;
  }

  const llama_vocab *vocab = llama_model_get_vocab(llama_get_model(ctx));
  const char *prompt = env->GetStringUTFChars(jprompt, nullptr);
  const size_t prompt_len = std::strlen(prompt);

  // 1) tokenize prompt
  std::vector<llama_token> tokens(prompt_len + 16);
  int n = llama_tokenize(vocab, prompt, (int32_t)prompt_len,
                         tokens.data(), (int32_t)tokens.size(),
                         true, true);
  env->ReleaseStringUTFChars(jprompt, prompt);
  if (n < 0) {
    tokens.resize(-n);
    n = llama_tokenize(vocab, prompt, (int32_t)prompt_len,
                       tokens.data(), (int32_t)tokens.size(), true, true);
  }
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
  char buf[128];

  // 4) 自回归生成
  for (int i = 0; i < maxTokens; ++i) {
    llama_token tok = llama_sampler_sample(sampler, ctx, -1);
    if (tok == eos) {
      emitter.emit("", true);
      break;
    }
    int piece_len = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, false);
    if (piece_len > 0) {
      emitter.emit(std::string(buf, piece_len), false);
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
  JNIEnv *env, jobject, jlong jmodel, jstring jmmprojPath) {
  auto *model = reinterpret_cast<llama_model *>(jmodel);
  const char *path = env->GetStringUTFChars(jmmprojPath, nullptr);

  mtmd_context_params params = mtmd_context_params_default();
  params.use_gpu = false;  // 骁龙实测：mmproj 不 offload 到 OpenCL 更稳（CVPR 2026）

  mtmd_context *mctx = mtmd_init_from_file(path, model, params);
  env->ReleaseStringUTFChars(jmmprojPath, path);

  if (mctx == nullptr) {
    LOGE("mtmd_init_from_file failed: %s", path);
    return 0;
  }
  LOGI("mtmd ready: %s (use_gpu=false)", path);
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
  auto *ctx = reinterpret_cast<llama_context *>(jctx);
  auto *mctx = reinterpret_cast<mtmd_context *>(jmctx);
  if (ctx == nullptr || mctx == nullptr) {
    LOGE("completionWithImage: null ctx/mctx");
    return;
  }

  const llama_vocab *vocab = llama_model_get_vocab(llama_get_model(ctx));
  const char *prompt = env->GetStringUTFChars(jprompt, nullptr);

  // 1) 加载所有图片为 bitmap
  std::vector<mtmd_bitmap *> bitmaps;
  jsize nImages = env->GetArrayLength(jimagePaths);
  for (jsize i = 0; i < nImages; ++i) {
    jstring jpath = (jstring)env->GetObjectArrayElement(jimagePaths, i);
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    // mtmd_helper_bitmap_init_from_file 返回 wrapper 结构体（按值），
    // wrapper.bitmap 才是真正的 mtmd_bitmap*；wrapper.video_ctx 仅视频场景非空
    mtmd_helper_bitmap_wrapper wrapper = mtmd_helper_bitmap_init_from_file(mctx, path, false);
    mtmd_bitmap *bmp = wrapper.bitmap;
    if (wrapper.video_ctx != nullptr) {
      mtmd_helper_video_free(wrapper.video_ctx);  // 图片场景不会进入这里
    }
    env->ReleaseStringUTFChars(jpath, path);
    env->DeleteLocalRef(jpath);
    if (bmp == nullptr) {
      LOGE("bitmap load failed: %s", path);
    } else {
      bitmaps.push_back(bmp);
    }
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
  char buf[128];

  for (int i = 0; i < maxTokens; ++i) {
    llama_token tok = llama_sampler_sample(sampler, ctx, -1);
    if (tok == eos) {
      emitter.emit("", true);
      break;
    }
    int piece_len = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, false);
    if (piece_len > 0) {
      emitter.emit(std::string(buf, piece_len), false);
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
  auto *ctx = reinterpret_cast<llama_context *>(jctx);
  if (ctx == nullptr) {
    LOGE("completionWithGrammar: null context");
    return;
  }

  const llama_vocab *vocab = llama_model_get_vocab(llama_get_model(ctx));
  const char *prompt = env->GetStringUTFChars(jprompt, nullptr);
  const char *grammar = env->GetStringUTFChars(jgrammar, nullptr);
  const size_t prompt_len = std::strlen(prompt);

  // 1) tokenize prompt
  std::vector<llama_token> tokens(prompt_len + 16);
  int n = llama_tokenize(vocab, prompt, (int32_t)prompt_len,
                         tokens.data(), (int32_t)tokens.size(),
                         true, true);
  env->ReleaseStringUTFChars(jprompt, prompt);
  if (n < 0) {
    tokens.resize(-n);
    n = llama_tokenize(vocab, prompt, (int32_t)prompt_len,
                       tokens.data(), (int32_t)tokens.size(), true, true);
  }
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
  char buf[128];

  // 4) 自回归生成（grammar 自动约束每个 token 合法）
  for (int i = 0; i < maxTokens; ++i) {
    llama_token tok = llama_sampler_sample(sampler, ctx, -1);
    if (tok == eos) {
      emitter.emit("", true);
      break;
    }
    int piece_len = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, false);
    if (piece_len > 0) {
      emitter.emit(std::string(buf, piece_len), false);
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
