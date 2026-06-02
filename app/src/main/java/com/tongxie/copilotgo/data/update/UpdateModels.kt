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
    @SerialName("content_type") val contentType: String = ""
)

/** 解析后的更新信息（已选出 APK 资产）。 */
data class UpdateInfo(
    val versionName: String,
    val tagName: String,
    val releaseName: String,
    val changelog: String,
    val apkUrl: String,
    val apkSize: Long,
    val releasePageUrl: String
)
