package com.tongxie.copilotgo.data.net

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException

class ApiException(
    val statusCode: Int?,
    val errorCode: String? = null,
    message: String
) : IOException(message)

fun Response.readBodyLimited(maxBytes: Long = 1024 * 1024): String {
    val body = body ?: throw IOException("服务器返回了空响应")
    if (body.contentLength() > maxBytes) throw IOException("服务器响应超过大小限制")
    val source = body.source()
    source.request(maxBytes + 1)
    if (source.buffer.size > maxBytes) throw IOException("服务器响应超过大小限制")
    return body.string()
}

internal fun apiFailure(status: Int?, body: String, json: Json): ApiException {
    val error = try {
        val root = json.parseToJsonElement(body) as? JsonObject
        (root?.get("error") as? JsonObject) ?: root
    } catch (_: SerializationException) {
        null
    }
    val code = (error?.get("code") as? kotlinx.serialization.json.JsonPrimitive)?.content
        ?.takeIf { it.length <= 80 && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }
    val message = when {
        status == 401 -> "登录已失效，请重新登录"
        status == 403 -> "当前账号没有此请求的访问权限"
        status == 429 -> "请求过于频繁或配额不足，请稍后重试"
        status == 421 -> "服务端点不匹配，请重新登录"
        code == "model_not_supported" || code == "model_not_found" ->
            "当前模型不可用，请刷新模型列表并重新选择"
        code == "content_filter" -> "此请求被服务端内容策略拒绝"
        status != null -> "服务请求失败（HTTP $status）"
        else -> "流式请求被服务端中断，请重试"
    }
    return ApiException(status, code, message)
}

internal fun networkErrorMessage(error: Exception): String = when (error) {
    is ApiException -> error.message ?: "服务请求失败"
    is SocketTimeoutException -> "连接超时，请检查网络或代理后重试"
    is SerializationException -> "服务响应格式不受支持，请重试"
    is IOException -> "连接中断，请检查网络或代理后重试"
    else -> "请求未完成，请重试"
}
