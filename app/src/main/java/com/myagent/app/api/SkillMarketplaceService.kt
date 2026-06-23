package com.myagent.app.api

/**
 * API service for the MyAgent skill marketplace.
 * Calls the MyAgent website REST API to browse, search, and install skills.
 */
object SkillMarketplaceService {

  /** Fetch the marketplace skill list. */
  suspend fun fetchSkills(): Result<List<MyAgentSkill>> {
    val result = MyAgentApiClient.get(
      path = "/api/skills",
      deserializer = MyAgentApiResponse.serializer(
        kotlinx.serialization.builtins.ListSerializer(MyAgentSkill.serializer()),
      ),
    )
    return result.fold(
      onSuccess = { response ->
        if (response.success) {
          Result.success(response.data ?: emptyList())
        } else {
          Result.failure(Exception(response.message ?: "获取技能列表失败"))
        }
      },
      onFailure = { Result.failure(it) },
    )
  }

  /** Fetch skill detail by ID. */
  suspend fun fetchSkillDetail(skillId: String): Result<MyAgentSkill> {
    val result = MyAgentApiClient.get(
      path = "/api/skills/$skillId",
      deserializer = MyAgentApiResponse.serializer(MyAgentSkill.serializer()),
    )
    return result.fold(
      onSuccess = { response ->
        if (response.success && response.data != null) {
          Result.success(response.data)
        } else {
          Result.failure(Exception(response.message ?: "获取技能详情失败"))
        }
      },
      onFailure = { Result.failure(it) },
    )
  }

  /** Install a skill from the marketplace. */
  suspend fun installSkill(skillId: String): Result<Boolean> {
    val result = MyAgentApiClient.post(
      path = "/api/skills/$skillId/install",
      body = InstallRequest(skillId = skillId),
      bodySerializer = InstallRequest.serializer(),
      deserializer = MyAgentApiResponse.serializer(kotlinx.serialization.json.JsonObject.serializer()),
    )
    return result.fold(
      onSuccess = { response ->
        if (response.success) {
          Result.success(true)
        } else {
          Result.failure(Exception(response.message ?: "安装技能失败"))
        }
      },
      onFailure = { Result.failure(it) },
    )
  }

  /** Fetch user wallet balance. */
  suspend fun fetchWallet(): Result<MyAgentWallet> {
    val result = MyAgentApiClient.get(
      path = "/api/user/wallet",
      deserializer = MyAgentApiResponse.serializer(MyAgentWallet.serializer()),
    )
    return result.fold(
      onSuccess = { response ->
        if (response.success && response.data != null) {
          Result.success(response.data)
        } else {
          Result.failure(Exception(response.message ?: "获取钱包信息失败"))
        }
      },
      onFailure = { Result.failure(it) },
    )
  }
}

@kotlinx.serialization.Serializable
private data class InstallRequest(val skillId: String)