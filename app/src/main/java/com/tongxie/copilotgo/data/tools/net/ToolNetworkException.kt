package com.tongxie.copilotgo.data.tools.net

import java.io.IOException

enum class ToolNetworkErrorCode {
    UNSAFE_URL,
    UNSAFE_DNS,
    UNSAFE_PROXY_ROUTE,
    UNSAFE_REDIRECT,
    UNSUPPORTED_CONTENT_TYPE,
    UNSUPPORTED_CONTENT_ENCODING,
    INVALID_RESPONSE,
    RESPONSE_TOO_LARGE,
    NETWORK_ERROR
}

/** Intentionally has no constructor accepting a URL, server message, response body or cause. */
class ToolNetworkException(
    val code: ToolNetworkErrorCode,
    val requestMayHaveBeenSent: Boolean = false
) : IOException(
    when (code) {
        ToolNetworkErrorCode.UNSAFE_URL -> "工具地址未通过 HTTPS 安全检查"
        ToolNetworkErrorCode.UNSAFE_DNS -> "工具地址解析结果不符合网络安全策略"
        ToolNetworkErrorCode.UNSAFE_PROXY_ROUTE -> if (requestMayHaveBeenSent) {
            "工具请求期间网络或代理策略发生变化，结果未能确认；不会绕过代理或自动重试"
        } else {
            "当前代理无法保证工具目标地址的安全解析，已阻止请求；不会绕过代理"
        }
        ToolNetworkErrorCode.UNSAFE_REDIRECT -> if (requestMayHaveBeenSent) {
            "工具响应的重定向已被阻止，原操作结果未能确认；不会继续或重试请求"
        } else {
            "工具请求的重定向已被安全策略阻止"
        }
        ToolNetworkErrorCode.UNSUPPORTED_CONTENT_TYPE -> "工具响应的内容类型不受支持"
        ToolNetworkErrorCode.UNSUPPORTED_CONTENT_ENCODING -> "工具响应的压缩编码不受支持"
        ToolNetworkErrorCode.INVALID_RESPONSE -> "工具响应的编码或长度无效"
        ToolNetworkErrorCode.RESPONSE_TOO_LARGE -> "工具响应超过允许的大小"
        ToolNetworkErrorCode.NETWORK_ERROR -> "工具网络请求未完成；本次操作没有被自动重试"
    }
)

internal fun networkFailure(code: ToolNetworkErrorCode): Nothing = throw ToolNetworkException(code)
