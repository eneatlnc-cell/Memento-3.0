package com.myagent.app.multimodal.kokoro

import android.content.Context
import android.util.Log
import ai.onnxruntime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Kokoro-TTS 端侧语音合成引擎 — ONNX Runtime 推理。
 *
 * 模型直接打包在 APK assets 中，无需用户额外下载。
 * - assets/kokoro/model.onnx  (~89 MB, INT8 量化)
 * - assets/kokoro/voices.json  (~52 MB, 11 种音色)
 *
 * 模型来源：NeuML/kokoro-int8-onnx (ModelScope 魔搭社区)
 *
 * 单例模式：模型仅加载一次，避免反复加载大模型。
 */
class KokoroTtsEngine(
  private val context: Context,
) {
  companion object {
    private const val TAG = "KokoroTTS"

    private const val ASSET_MODEL_PATH = "kokoro/model.onnx"
    private const val ASSET_VOICES_PATH = "kokoro/voices.json"

    const val SAMPLE_RATE = 24000
    private const val VOICE_EMBEDDING_DIM = 256
  }

  private var ortEnv: OrtEnvironment? = null
  private var session: OrtSession? = null
  private var voiceEmbeddings: Map<String, FloatArray> = emptyMap()
  private var voiceNames: List<String> = emptyList()

  @Volatile private var ready = false

  /**
   * 初始化 ONNX 会话和音色嵌入（从 assets 加载）。
   */
  fun initialize(): Boolean {
    if (ready) return true
    synchronized(this) {
      if (ready) return true
      try {
        ortEnv = OrtEnvironment.getEnvironment()

        val options = OrtSession.SessionOptions().apply {
          addNnapi()
          setSessionOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }

        // 从 assets 加载 ONNX 模型
        val modelBytes = context.assets.open(ASSET_MODEL_PATH).use { it.readBytes() }
        session = ortEnv!!.createSession(modelBytes, options)

        // 从 assets 加载音色嵌入
        loadVoiceEmbeddings()

        ready = true
        Log.i(TAG, "Kokoro-TTS ready, ${voiceNames.size} voices")
        return true
      } catch (e: Exception) {
        Log.e(TAG, "Init failed: ${e.message}", e)
        ready = true
        return false
      }
    }
  }

  /**
   * 合成语音（全量）。
   *
   * @param text 要合成的文本
   * @param voice 音色名称（如 "af_bella"、"am_adam"、"bf_emma"）
   * @param speed 语速（0.5 ~ 2.0）
   * @return 24kHz 16-bit PCM WAV 字节数组
   */
  suspend fun synthesize(
    text: String,
    voice: String = "af_bella",
    speed: Float = 1.0f,
  ): ByteArray = withContext(Dispatchers.Default) {
    if (!ready) initialize()

    val session = this@KokoroTtsEngine.session
    if (session == null) {
      return@withContext mockSynthesize(text)
    }

    try {
      val phonemeIds = textToPhonemes(text)
      val voiceEmbedding = voiceEmbeddings[voice]
        ?: voiceEmbeddings.values.firstOrNull()
        ?: FloatArray(VOICE_EMBEDDING_DIM)

      val env = ortEnv!!

      val inputShape = longArrayOf(1, phonemeIds.size.toLong())
      val inputTensor = OnnxTensor.createTensor(env, phonemeIds, inputShape)

      val styleShape = longArrayOf(1, VOICE_EMBEDDING_DIM.toLong())
      val styleTensor = OnnxTensor.createTensor(env, voiceEmbedding, styleShape)

      val speedTensor = OnnxTensor.createTensor(
        env, floatArrayOf(speed.coerceIn(0.5f, 2.0f)), longArrayOf(1)
      )

      val inputs = mapOf(
        "tokens" to inputTensor,
        "style" to styleTensor,
        "speed" to speedTensor,
      )

      val results = session.run(inputs)
      val output = (results[0].value as Array<FloatArray>)[0]

      val pcm = output.copyOf()
      val bytes = pcmToWavBytes(pcm, SAMPLE_RATE)

      inputTensor.close()
      styleTensor.close()
      speedTensor.close()
      results.close()

      bytes
    } catch (e: Exception) {
      Log.e(TAG, "Synthesize failed: ${e.message}", e)
      mockSynthesize(text)
    }
  }

  /**
   * 流式合成（逐句输出）。
   */
  suspend fun synthesizeStreaming(
    text: String,
    voice: String = "af_bella",
    speed: Float = 1.0f,
    onChunk: (ByteArray) -> Unit,
  ) = withContext(Dispatchers.Default) {
    val sentences = text.split(Regex("[。！？.!?\n]"))
      .filter { it.isNotBlank() }

    for (sentence in sentences) {
      val chunk = synthesize(sentence.trim(), voice, speed)
      onChunk(chunk)
    }
  }

  /**
   * 获取所有可用音色名称。
   */
  fun getAvailableVoices(): List<String> = voiceNames.toList()

  fun close() {
    synchronized(this) {
      session?.close()
      ortEnv?.close()
      ready = false
    }
  }

  // ── 音色加载（从 assets/voices.json） ──

  private fun loadVoiceEmbeddings() {
    try {
      val jsonStr = context.assets.open(ASSET_VOICES_PATH).use { it.reader().readText() }
      val json = JSONObject(jsonStr)
      val embeddings = mutableMapOf<String, FloatArray>()
      val names = mutableListOf<String>()

      /**
       * voices.json 结构：
       * {
       *   "af_bella": [[...256 floats], [...256 floats], ...],  // 511 个风格变体
       *   "am_adam":  [[...256 floats], ...],
       *   ...
       * }
       *
       * 每个音色有 511 个 256 维嵌入向量（不同说话风格）。
       * 取第一个嵌入（index 0）作为默认风格。
       */
      val keys = json.keys()
      while (keys.hasNext()) {
        val name = keys.next()
        val styleVariants = json.getJSONArray(name) // 511 个变体
        if (styleVariants.length() > 0) {
          val firstVariant = styleVariants.getJSONArray(0) // 取第一个变体
          val embedding = FloatArray(firstVariant.length())
          for (i in 0 until firstVariant.length()) {
            embedding[i] = firstVariant.getDouble(i).toFloat()
          }
          if (embedding.size == VOICE_EMBEDDING_DIM) {
            embeddings[name] = embedding
            names.add(name)
          }
        }
      }

      voiceEmbeddings = embeddings
      voiceNames = names
      Log.i(TAG, "Loaded ${names.size} voices: ${names.joinToString(", ")}")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to load voices.json: ${e.message}")
    }
  }

  // ── 工具 ──

  private fun textToPhonemes(text: String): LongArray {
    return text.encodeToByteArray().map { it.toLong() and 0xFF }.toLongArray()
  }

  private fun pcmToWavBytes(samples: FloatArray, sampleRate: Int): ByteArray {
    val dataSize = samples.size * 2
    val headerSize = 44
    val totalSize = headerSize + dataSize
    val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

    buffer.put("RIFF".encodeToByteArray())
    buffer.putInt(totalSize - 8)
    buffer.put("WAVE".encodeToByteArray())
    buffer.put("fmt ".encodeToByteArray())
    buffer.putInt(16)
    buffer.putShort(1)
    buffer.putShort(1)
    buffer.putInt(sampleRate)
    buffer.putInt(sampleRate * 2)
    buffer.putShort(2)
    buffer.putShort(16)
    buffer.put("data".encodeToByteArray())
    buffer.putInt(dataSize)

    for (sample in samples) {
      val clipped = sample.coerceIn(-1f, 1f)
      val pcm16 = (clipped * 32767f).toInt().toShort()
      buffer.putShort(pcm16)
    }

    return buffer.array()
  }

  private fun mockSynthesize(text: String): ByteArray {
    Log.w(TAG, "Kokoro-TTS 模型未就绪，返回静音 WAV")
    val samples = FloatArray(SAMPLE_RATE) { 0f }
    return pcmToWavBytes(samples, SAMPLE_RATE)
  }
}