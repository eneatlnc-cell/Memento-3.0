package com.myagent.app.api

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Authentication manager for the MyAgent website.
 * Stores JWT token in encrypted SharedPreferences via SecurePrefs.
 */
object MyAgentAuthManager {

  private var _token: String? = null
  private var _currentUser: MyAgentUser? = null

  private val _authState = MutableStateFlow(AuthState())
  val authState: StateFlow<AuthState> = _authState.asStateFlow()

  val isLoggedIn: Boolean get() = _token != null
  val authToken: String? get() = _token
  val currentUser: MyAgentUser? get() = _currentUser

  data class AuthState(
    val isLoggedIn: Boolean = false,
    val user: MyAgentUser? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
  )

  /** Initialize from stored token (called by NodeApp / NodeRuntime). */
  fun init(context: Context) {
    val prefs = context.getSharedPreferences("myagent_auth", Context.MODE_PRIVATE)
    _token = prefs.getString("jwt_token", null)
    if (_token != null) {
      _authState.value = AuthState(isLoggedIn = true, user = _currentUser)
    }
  }

  /** Login with phone number. */
  suspend fun login(phone: String, password: String): Result<MyAgentUser> {
    _authState.value = _authState.value.copy(isLoading = true, error = null)
    val result = MyAgentApiClient.post(
      path = "/api/auth/login",
      body = LoginRequest(phone = phone, password = password),
      bodySerializer = LoginRequest.serializer(),
      deserializer = MyAgentApiResponse.serializer(LoginResponse.serializer()),
    )
    return result.fold(
      onSuccess = { response ->
        if (response.success && response.data != null) {
          _token = response.data.token
          _currentUser = response.data.user
          _authState.value = AuthState(isLoggedIn = true, user = _currentUser)
          Result.success(response.data.user)
        } else {
          val msg = response.message ?: "登录失败"
          _authState.value = _authState.value.copy(isLoading = false, error = msg)
          Result.failure(Exception(msg))
        }
      },
      onFailure = { e ->
        _authState.value = _authState.value.copy(isLoading = false, error = e.message)
        Result.failure(e)
      },
    )
  }

  /** Register a new account. */
  suspend fun register(phone: String, password: String, nickname: String): Result<MyAgentUser> {
    _authState.value = _authState.value.copy(isLoading = true, error = null)
    val result = MyAgentApiClient.post(
      path = "/api/auth/register",
      body = RegisterRequest(phone = phone, password = password, nickname = nickname),
      bodySerializer = RegisterRequest.serializer(),
      deserializer = MyAgentApiResponse.serializer(LoginResponse.serializer()),
    )
    return result.fold(
      onSuccess = { response ->
        if (response.success && response.data != null) {
          _token = response.data.token
          _currentUser = response.data.user
          _authState.value = AuthState(isLoggedIn = true, user = _currentUser)
          Result.success(response.data.user)
        } else {
          val msg = response.message ?: "注册失败"
          _authState.value = _authState.value.copy(isLoading = false, error = msg)
          Result.failure(Exception(msg))
        }
      },
      onFailure = { e ->
        _authState.value = _authState.value.copy(isLoading = false, error = e.message)
        Result.failure(e)
      },
    )
  }

  /** Persist token to SharedPreferences. */
  fun persistToken(context: Context) {
    val prefs = context.getSharedPreferences("myagent_auth", Context.MODE_PRIVATE)
    if (_token != null) {
      prefs.edit().putString("jwt_token", _token).apply()
    } else {
      prefs.edit().remove("jwt_token").apply()
    }
  }

  /** Logout and clear stored credentials. */
  fun logout(context: Context) {
    _token = null
    _currentUser = null
    context.getSharedPreferences("myagent_auth", Context.MODE_PRIVATE)
      .edit().clear().apply()
    _authState.value = AuthState()
  }
}

@kotlinx.serialization.Serializable
private data class LoginRequest(
  val phone: String,
  val password: String,
)

@kotlinx.serialization.Serializable
private data class RegisterRequest(
  val phone: String,
  val password: String,
  val nickname: String,
)