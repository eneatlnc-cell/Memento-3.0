#include <android/log.h>
#include <jni.h>
#include <string>
#include <vector>
#include <cmath>
#include <unistd.h>

#include "common.h"
#include "llama.h"
#include "sampling.h"

#define LOG_TAG "LingjiLLaMA"
#define LOGd(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGw(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── Inference parameters ──
constexpr int N_THREADS_MIN      = 2;
constexpr int N_THREADS_MAX      = 4;
constexpr int N_THREADS_HEADROOM = 2;

constexpr int DEFAULT_CONTEXT_SIZE = 2048;
constexpr int BATCH_SIZE           = 256;
constexpr int OVERFLOW_HEADROOM    = 4;
constexpr float DEFAULT_TEMP       = 0.7f;

// ── Global state ──
static llama_model          * g_model   = nullptr;
static llama_context        * g_context = nullptr;
static llama_batch            g_batch;
static common_sampler       * g_sampler = nullptr;

static llama_pos              current_position = 0;
static llama_pos              stop_generation_position = 0;
static std::string            cached_token_chars;
static std::ostringstream     assistant_ss;

// ── Helper: decode tokens in batches ──
static bool decode_tokens_in_batches(
    llama_context * ctx,
    llama_batch   & batch,
    const std::vector<llama_token> & tokens,
    llama_pos start_pos,
    bool compute_last_logit = false)
{
    for (int i = 0; i < (int)tokens.size(); i += BATCH_SIZE) {
        int cur_batch_size = std::min((int)tokens.size() - i, BATCH_SIZE);
        common_batch_clear(batch);
        for (int j = 0; j < cur_batch_size; j++) {
            llama_token token_id = tokens[i + j];
            llama_pos   pos      = start_pos + i + j;
            bool want_logit = compute_last_logit && (i + j == (int)tokens.size() - 1);
            common_batch_add(batch, token_id, pos, {0}, want_logit);
        }
        if (llama_decode(ctx, batch) != 0) {
            LOGe("%s: llama_decode() failed at batch %d", __func__, i);
            return true;
        }
    }
    return false;
}

// ── Helper: UTF-8 validation ──
static bool is_valid_utf8(const char * str) {
    if (!str) return true;
    const auto * bytes = (const unsigned char *)str;
    while (*bytes != 0x00) {
        int num;
        if ((*bytes & 0x80) == 0x00)       num = 1;
        else if ((*bytes & 0xE0) == 0xC0)  num = 2;
        else if ((*bytes & 0xF0) == 0xE0)  num = 3;
        else if ((*bytes & 0xF8) == 0xF0)  num = 4;
        else return false;
        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) return false;
            bytes += 1;
        }
    }
    return true;
}

// ── Helper: new sampler ──
static common_sampler * new_sampler(float temp) {
    common_params_sampling sparams;
    sparams.temp = temp;
    sparams.top_k = 40;
    sparams.top_p = 0.95f;
    sparams.penalty_repeat = 64;   // 重复惩罚：防止模型循环输出同一 token
    sparams.penalty_freq  = 0.5f;  // 频率惩罚：降低高频 token 概率
    return common_sampler_init(g_model, sparams);
}

// ── Helper: create context ──
static llama_context * init_context(llama_model * model, int n_ctx) {
    if (!model) return nullptr;
    int n_threads = std::max(N_THREADS_MIN,
        std::min(N_THREADS_MAX, (int)sysconf(_SC_NPROCESSORS_ONLN) - N_THREADS_HEADROOM));
    LOGi("Using %d threads", n_threads);

    llama_context_params ctx_params = llama_context_default_params();
    int trained_ctx = llama_model_n_ctx_train(model);
    if (n_ctx > trained_ctx) {
        LOGw("Model trained with %d ctx, capping to %d", trained_ctx, trained_ctx);
        n_ctx = trained_ctx;
    }
    ctx_params.n_ctx           = n_ctx;
    ctx_params.n_batch         = BATCH_SIZE;
    ctx_params.n_ubatch        = BATCH_SIZE;
    ctx_params.n_threads       = n_threads;
    ctx_params.n_threads_batch = n_threads;
    return llama_init_from_model(model, ctx_params);
}

// ═══════════════════════════════════════════════════════════════
//  JNI exports  ( Kotlin: com.myagent.app.model.LlamaEngine )
// ═══════════════════════════════════════════════════════════════

extern "C"
JNIEXPORT void JNICALL
Java_com_myagent_app_model_LlamaEngine_nativeInit(
    JNIEnv * env, jobject /*unused*/, jstring j_nativeLibDir)
{
    llama_log_set(nullptr, nullptr); // suppress verbose llama.cpp logs

    const char * path = env->GetStringUTFChars(j_nativeLibDir, nullptr);
    LOGi("Loading backends from %s", path);
    ggml_backend_load_all_from_path(path);
    env->ReleaseStringUTFChars(j_nativeLibDir, path);

    llama_backend_init();
    LOGi("llama.cpp backend initialized");
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_myagent_app_model_LlamaEngine_nativeLoadModel(
    JNIEnv * env, jobject /*unused*/, jstring j_modelPath)
{
    const char * model_path = env->GetStringUTFChars(j_modelPath, nullptr);
    LOGi("Loading model: %s", model_path);

    llama_model_params model_params = llama_model_default_params();
    g_model = llama_model_load_from_file(model_path, model_params);

    env->ReleaseStringUTFChars(j_modelPath, model_path);

    if (!g_model) {
        LOGe("Failed to load model");
        return 1;
    }
    LOGi("Model loaded successfully");
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_myagent_app_model_LlamaEngine_nativePrepare(
    JNIEnv * /*env*/, jobject /*unused*/)
{
    auto * ctx = init_context(g_model, DEFAULT_CONTEXT_SIZE);
    if (!ctx) return 1;
    g_context = ctx;
    g_batch   = llama_batch_init(BATCH_SIZE, 0, 1);
    g_sampler = new_sampler(DEFAULT_TEMP);

    current_position = 0;
    stop_generation_position = 0;
    cached_token_chars.clear();
    assistant_ss.str("");

    LOGi("Context prepared");
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_myagent_app_model_LlamaEngine_nativeProcessPrompt(
    JNIEnv * env, jobject /*unused*/, jstring j_prompt)
{
    if (!g_context) return 1;

    // Reset state
    llama_memory_clear(llama_get_memory(g_context), false);
    current_position = 0;
    stop_generation_position = 0;
    cached_token_chars.clear();
    assistant_ss.str("");

    const char * prompt = env->GetStringUTFChars(j_prompt, nullptr);
    std::string prompt_str(prompt);
    env->ReleaseStringUTFChars(j_prompt, prompt);

    LOGi("Processing prompt (%zu chars)", prompt_str.size());

    // Tokenize the full prompt
    auto tokens = common_tokenize(g_context, prompt_str, false, false);
    LOGi("Tokenized to %zu tokens", tokens.size());

    if (tokens.empty()) {
        LOGe("Empty tokenization result");
        return 2;
    }

    // Truncate if too long
    int max_batch = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    if ((int)tokens.size() > max_batch) {
        LOGw("Prompt too long (%zu tokens), truncating to %d", tokens.size(), max_batch);
        tokens.resize(max_batch);
    }

    // Decode all prompt tokens
    if (decode_tokens_in_batches(g_context, g_batch, tokens, 0, true)) {
        LOGe("Prompt decode failed");
        return 3;
    }

    current_position = (int)tokens.size();
    stop_generation_position = current_position + 512; // max 512 new tokens

    LOGi("Prompt processed. pos=%d, stop_at=%d", current_position, stop_generation_position);
    return 0;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_myagent_app_model_LlamaEngine_nativeGenerateNextToken(
    JNIEnv * env, jobject /*unused*/)
{
    if (!g_context || !g_sampler) return nullptr;

    // Check if we should stop
    if (current_position >= stop_generation_position) {
        LOGd("Reached stop position");
        return nullptr;
    }

    // Sample next token
    const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, new_token_id, true);

    // Decode the new token
    common_batch_clear(g_batch);
    common_batch_add(g_batch, new_token_id, current_position, {0}, true);
    if (llama_decode(g_context, g_batch) != 0) {
        LOGe("llama_decode() failed for generated token");
        return nullptr;
    }
    current_position++;

    // Check EOG
    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
        LOGd("EOG token reached");
        return nullptr;
    }

    // Convert to text
    auto new_token_chars = common_token_to_piece(g_context, new_token_id);
    cached_token_chars += new_token_chars;

    jstring result = nullptr;
    if (is_valid_utf8(cached_token_chars.c_str())) {
        result = env->NewStringUTF(cached_token_chars.c_str());
        assistant_ss << cached_token_chars;
        cached_token_chars.clear();
    } else {
        // Multi-byte character not yet complete, return empty string
        result = env->NewStringUTF("");
    }
    return result;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_myagent_app_model_LlamaEngine_nativePing(
    JNIEnv * /*env*/, jobject /*unused*/)
{
    if (!g_context || !g_sampler) return JNI_FALSE;

    // Tokenize a short test prompt
    auto tokens = common_tokenize(g_context, "Hello", false, false);
    if (tokens.empty()) return JNI_FALSE;

    // Decode
    common_batch_clear(g_batch);
    for (int i = 0; i < (int)tokens.size(); i++) {
        common_batch_add(g_batch, tokens[i], i, {0}, i == (int)tokens.size() - 1);
    }
    if (llama_decode(g_context, g_batch) != 0) return JNI_FALSE;

    // Sample one token to verify full pipeline works
    const auto token = common_sampler_sample(g_sampler, g_context, -1);
    if (token < 0) return JNI_FALSE;

    LOGi("Ping OK — inference pipeline works");
    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_myagent_app_model_LlamaEngine_nativeUnload(
    JNIEnv * /*env*/, jobject /*unused*/)
{
    LOGi("Unloading model...");

    if (g_sampler) {
        common_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    if (g_context) {
        llama_batch_free(g_batch);
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }

    current_position = 0;
    stop_generation_position = 0;
    cached_token_chars.clear();
    assistant_ss.str("");

    LOGi("Model unloaded");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_myagent_app_model_LlamaEngine_nativeShutdown(
    JNIEnv * /*env*/, jobject /*unused*/)
{
    llama_backend_free();
    LOGi("llama.cpp backend shut down");
}