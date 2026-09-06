package com.tongxie.copilotgo.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GitHub Releases API 响应（只取需要的字段，ignoreUnknownKeys=true）。 */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("body") val body: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("draft") val draft: Boolean = false,
    @SerialName("assets") val assets: List<GithubAsset> = emptyList()
)

@Serializable
data class GithubAsset(
    @SerialName("name") val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    @SerialName("size") val size: Long = 0,
    @SerialName("content_type") val contentType: String = "",
    @SerialName("digest") val digest: String? = null
)

/** 解析后的更新信息（已选出 APK 资产）。 */
data class UpdateInfo(
    val versionName: String,
    val tagName: String,
    val releaseName: String,
    val changelog: String,
    val apkUrl: String,
    val apkSize: Long,
    val releasePageUrl: String,
    val apkName: String = "",
    val apkSha256: String? = null,
    val checksumUrl: String? = null
)

enum class UpdateRoute {
    APP_SETTINGS,
    DIRECT
}

data class UpdateLimits(
    val checkTimeoutMillis: Long = 30_000,
    val downloadTimeoutMillis: Long = 5 * 60_000,
    val maxApkBytes: Long = 128L * 1024 * 1024,
    val maxMetadataBytes: Int = 2 * 1024 * 1024,
    val maxChecksumBytes: Int = 64 * 1024,
    val maxRedirects: Int = 5
) {
    init {
        require(checkTimeoutMillis > 0 && downloadTimeoutMillis > 0)
        require(maxApkBytes > 0 && maxMetadataBytes > 0 && maxChecksumBytes > 0)
        require(maxRedirects in 0..10)
    }
}

class UpdateException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

interface UpdateSource {
    val hasConfiguredProxy: Boolean
    suspend fun check(route: UpdateRoute = UpdateRoute.APP_SETTINGS): UpdateChecker.CheckResult
    fun download(
        info: UpdateInfo,
        targetFile: java.io.File,
        route: UpdateRoute = UpdateRoute.APP_SETTINGS
    ): kotlinx.coroutines.flow.Flow<UpdateChecker.DownloadEvent>
}
