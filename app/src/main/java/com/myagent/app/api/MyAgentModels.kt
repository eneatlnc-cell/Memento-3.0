package com.myagent.app.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Generic API response from the MyAgent website. */
@Serializable
data class MyAgentApiResponse<T>(
  val success: Boolean,
  val data: T? = null,
  val message: String? = null,
)

/** Login response body. */
@Serializable
data class LoginResponse(
  val token: String,
  val user: MyAgentUser,
)

/** User profile as returned by the API. */
@Serializable
data class MyAgentUser(
  val id: String,
  val phone: String,
  val nickname: String,
  val avatar: String,
  @SerialName("lingdou_balance")
  val lingdouBalance: Int = 0,
  val isNew: Boolean = false,
)

/** Skill from the marketplace API. */
@Serializable
data class MyAgentSkill(
  val id: String,
  val name: String,
  val emoji: String? = null,
  val category: String? = null,
  val slogan: String? = null,
  val description: String? = null,
  val price: Int = 0,
  @SerialName("file_url")
  val fileUrl: String? = null,
  @SerialName("install_count")
  val installCount: Int = 0,
  val author: MyAgentSkillAuthor? = null,
)

@Serializable
data class MyAgentSkillAuthor(
  val id: String,
  val nickname: String,
)

/** Wallet / balance info. */
@Serializable
data class MyAgentWallet(
  @SerialName("lingdou_balance")
  val lingdouBalance: Int = 0,
)