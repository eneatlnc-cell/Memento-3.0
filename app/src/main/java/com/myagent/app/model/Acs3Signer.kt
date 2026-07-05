package com.myagent.app.model

import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 阿里云 ACS3-HMAC-SHA256 请求签名。
 *
 * 用于客户端对函数计算（FC）HTTP 触发器的请求做签名认证。
 * 等价于 alibabacloud_openapi_util 的 get_authorization(ACS3-HMAC-SHA256)，
 * 手动实现以避免引入完整阿里云 SDK（节省 APK 体积）。
 *
 * 算法规范（https://help.aliyun.com/document_detail/315526.html）：
 *
 *   CanonicalRequest = HTTPMethod + "\n"
 *                    + CanonicalURI + "\n"
 *                    + CanonicalQueryString + "\n"
 *                    + CanonicalHeaders + "\n"
 *                    + SignedHeaders + "\n"
 *                    + HashedRequestPayload
 *
 *   StringToSign = "ACS3-HMAC-SHA256" + "\n" + Hex(SHA256(CanonicalRequest))
 *
 *   Signature = Hex(HMAC-SHA256(AccessKeySecret, StringToSign))
 *
 *   Authorization = "ACS3-HMAC-SHA256 Credential=" + AccessKeyId
 *                 + ",SignedHeaders=" + SignedHeaders
 *                 + ",Signature=" + Signature
 *
 * CanonicalHeaders 过滤规则（ACS3 规范）：
 *   只保留 x-acs-* 前缀、host、content-type 的 header。
 *   Date 等非 ACS3 header 不参与签名。
 */
object Acs3Signer {

  /**
   * 为请求生成 Authorization header 值。
   *
   * @param method HTTP 方法（GET/POST，大写）
   * @param url 完整 URL（含 host、path、query）
   * @param headers 请求头（会原地补充 host、x-acs-date、x-acs-content-sha256；
   *                返回的 Authorization 需调用方自行写入）
   * @param accessKeyId 阿里云 AccessKey ID
   * @param accessKeySecret 阿里云 AccessKey Secret
   * @param payload 请求体（GET 传空字符串）
   * @return Authorization header 值
   */
  fun sign(
    method: String,
    url: String,
    headers: MutableMap<String, String>,
    accessKeyId: String,
    accessKeySecret: String,
    payload: String = "",
  ): String {
    val parsed = java.net.URL(url)

    // ── 1. 确保必要 header ──
    // host
    headers["host"] = parsed.host + (parsed.port.takeIf { it > 0 && it != parsed.defaultPort }?.let { ":$it" } ?: "")
    // x-acs-date（UTC ISO8601，秒精度）
    if (!headers.containsKey("x-acs-date")) {
      headers["x-acs-date"] = java.time.format.DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .withZone(java.time.ZoneOffset.UTC)
        .format(java.time.Instant.now())
    }
    // x-acs-content-sha256（必选，值为请求体的 SHA256 十六进制）
    val hashedPayload = sha256Hex(payload)
    headers["x-acs-content-sha256"] = hashedPayload

    // ── 2. CanonicalURI ──
    val canonicalUri = parsed.path.ifEmpty { "/" }

    // ── 3. CanonicalQueryString ──
    val canonicalQuery = buildCanonicalQuery(parsed.query)

    // ── 4. CanonicalHeaders + SignedHeaders ──
    // ACS3 规范：只签名 x-acs-*、host、content-type 这些 header。
    // Date 等 header 不参与 ACS3 签名计算。
    val acsHeaders = headers.filterKeys { key ->
      val lower = key.lowercase()
      lower.startsWith("x-acs-") || lower == "host" || lower == "content-type"
    }
    val sortedHeaders = acsHeaders.keys.map { it.lowercase() to it }.sortedBy { it.first }
    val canonicalHeaders = sortedHeaders.joinToString("") { (lower, orig) ->
      "$lower:${acsHeaders[orig]?.trim()}\n"
    }
    val signedHeaders = sortedHeaders.joinToString(";") { it.first }

    // ── 5. CanonicalRequest ──
    val canonicalRequest = buildString {
      append(method.uppercase()).append("\n")
      append(canonicalUri).append("\n")
      append(canonicalQuery).append("\n")
      append(canonicalHeaders).append("\n")
      append(signedHeaders).append("\n")
      append(hashedPayload)
    }

    // ── 6. StringToSign ──
    val stringToSign = "ACS3-HMAC-SHA256\n" + sha256Hex(canonicalRequest)

    // ── 7. Signature ──
    val signature = hmacSha256Hex(accessKeySecret, stringToSign)

    // ── 8. Authorization ──
    return "ACS3-HMAC-SHA256 Credential=$accessKeyId" +
      ",SignedHeaders=$signedHeaders" +
      ",Signature=$signature"
  }

  /** 构建 CanonicalQueryString：参数名按字典序，key=value URL 编码，& 连接 */
  private fun buildCanonicalQuery(rawQuery: String?): String {
    if (rawQuery.isNullOrBlank()) return ""
    val pairs = rawQuery.split("&").mapNotNull { pair ->
      val idx = pair.indexOf("=")
      if (idx < 0) {
        encode(pair) to ""
      } else {
        encode(pair.substring(0, idx)) to encode(pair.substring(idx + 1))
      }
    }
    return pairs.sortedBy { it.first }.joinToString("&") { "${it.first}=${it.second}" }
  }

  /** 阿里云规范的 URL 编码：RFC 3986，空格用 %20（非 +） */
  private fun encode(s: String): String {
    return URLEncoder.encode(s, "UTF-8")
      .replace("+", "%20")
      .replace("*", "%2A")
      .replace("%7E", "~")
  }

  private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
    return bytes.toHex()
  }

  private fun hmacSha256Hex(key: String, data: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val bytes = mac.doFinal(data.toByteArray(Charsets.UTF_8))
    return bytes.toHex()
  }

  private fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it) }
}
