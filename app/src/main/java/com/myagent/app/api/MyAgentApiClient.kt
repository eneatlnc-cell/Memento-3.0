package com.myagent.app.api

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Singleton HTTP client for the MyAgent website API.
 * Uses OkHttp (already a project dependency) with kotlinx.serialization.
 */
object MyAgentApiClient {

  /** Base URL for the MyAgent website. Update this to the production URL before release. */
  var baseUrl: String = "http://10.0.2.2:3000" // Android emulator → host localhost

  val json: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
  }

  private val client: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .writeTimeout(30, TimeUnit.SECONDS)
      .addInterceptor { chain ->
        val request = chain.request()
        val builder = request.newBuilder()
        val token = MyAgentAuthManager.authToken
        if (token != null) {
          builder.header("Authorization", "Bearer $token")
        }
        builder.header("Content-Type", "application/json")
        chain.proceed(builder.build())
      }
      .build()
  }

  private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

  /** GET request returning a parsed JSON response. */
  suspend fun <T : Any> get(
    path: String,
    deserializer: kotlinx.serialization.DeserializationStrategy<T>,
  ): Result<T> {
    return try {
      val request = Request.Builder()
        .url("$baseUrl$path")
        .get()
        .build()
      val response = client.newCall(request).execute()
      val body = response.body.string()
      if (!response.isSuccessful) {
        return Result.failure(Exception("HTTP ${response.code}: $body"))
      }
      val parsed = json.decodeFromString(deserializer, body)
      Result.success(parsed)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  /** POST request with a JSON body. */
  suspend fun <T : Any, B : Any> post(
    path: String,
    body: B,
    bodySerializer: kotlinx.serialization.SerializationStrategy<B>,
    deserializer: kotlinx.serialization.DeserializationStrategy<T>,
  ): Result<T> {
    return try {
      val bodyJson = json.encodeToString(bodySerializer, body)
      val request = Request.Builder()
        .url("$baseUrl$path")
        .post(bodyJson.toRequestBody(JSON_MEDIA))
        .build()
      val response = client.newCall(request).execute()
      val responseBody = response.body.string()
      if (!response.isSuccessful) {
        return Result.failure(Exception("HTTP ${response.code}: $responseBody"))
      }
      val parsed = json.decodeFromString(deserializer, responseBody)
      Result.success(parsed)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }
}