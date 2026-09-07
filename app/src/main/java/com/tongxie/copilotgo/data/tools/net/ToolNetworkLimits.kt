package com.tongxie.copilotgo.data.tools.net

data class ToolNetworkLimits(
    val maxCompressedBytes: Long = 1_048_576,
    val maxDecodedBytes: Long = 2_097_152,
    val callTimeoutMillis: Long = 30_000,
    val maxRedirects: Int = 5
) {
    init {
        require(maxCompressedBytes in 1..MAX_BODY_BYTES) { "Invalid compressed byte limit" }
        require(maxDecodedBytes in 1..MAX_BODY_BYTES) { "Invalid decoded byte limit" }
        require(callTimeoutMillis in 1..120_000) { "Invalid tool timeout" }
        require(maxRedirects in 0..10) { "Invalid redirect limit" }
    }

    private companion object {
        const val MAX_BODY_BYTES = 16L * 1024 * 1024
    }
}
