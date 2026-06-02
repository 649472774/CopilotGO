package com.tongxie.copilotgo.data.update

import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.auth.executeAsync
import com.tongxie.copilotgo.data.net.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File

/**
 * 应用内更新检查器：从 GitHub Releases 拉取最新版本，比较语义化版本号，
 * 并以流式方式下载 APK（走 proxy-aware 的 OkHttpClient，与全局代理设置一致）。
 */
class UpdateChecker(
    private val httpProvider: HttpClientProvider,
    private val json: Json,
    private val currentVersionName: String,
    private val latestReleaseApi: String = Constants.GITHUB_LATEST_RELEASE_API,
    private val releasePageUrl: String = Constants.GITHUB_RELEASES_PAGE
) {

    sealed interface CheckResult {
        data class UpToDate(val currentVersion: String) : CheckResult
        data class Available(val info: UpdateInfo) : CheckResult
        data class Failed(val message: String) : CheckResult
    }

    sealed interface DownloadEvent {
        data class Progress(val bytesRead: Long, val total: Long) : DownloadEvent
        data class Done(val file: File) : DownloadEvent
    }

    suspend fun check(): CheckResult {
        val release = try {
            fetchLatestRelease()
        } catch (e: Exception) {
            return CheckResult.Failed(e.message ?: "网络错误，检查更新失败")
        }

        if (release.draft) {
            return CheckResult.UpToDate(currentVersionName)
        }

        val apk = release.assets.firstOrNull {
            it.name.endsWith(".apk", ignoreCase = true) && it.browserDownloadUrl.isNotBlank()
        } ?: return CheckResult.Failed("最新发布里没有可下载的 APK 安装包")

        val remoteVersion = normalizeVersion(release.tagName.ifBlank { release.name })
        val localVersion = normalizeVersion(currentVersionName)

        return if (compareVersions(remoteVersion, localVersion) > 0) {
            CheckResult.Available(
                UpdateInfo(
                    versionName = remoteVersion,
                    tagName = release.tagName,
                    releaseName = release.name.ifBlank { release.tagName },
                    changelog = release.body.trim().ifBlank { "（本次发布未提供更新说明）" },
                    apkUrl = apk.browserDownloadUrl,
                    apkSize = apk.size,
                    releasePageUrl = release.htmlUrl.ifBlank { releasePageUrl }
                )
            )
        } else {
            CheckResult.UpToDate(currentVersionName)
        }
    }

    private suspend fun fetchLatestRelease(): GithubRelease {
        val req = Request.Builder()
            .url(latestReleaseApi.toHttpUrl())
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .get()
            .build()
        val resp = httpProvider.client.newCall(req).executeAsync()
        resp.use {
            if (it.code == 404) error("尚未发布任何 Release")
            if (!it.isSuccessful) error("GitHub 返回 ${it.code}")
            val text = it.body?.string().orEmpty()
            if (text.isBlank()) error("响应为空")
            return json.decodeFromString(GithubRelease.serializer(), text)
        }
    }

    /** 流式下载 APK 到 [targetFile]，发射进度与完成事件。失败抛异常。 */
    fun download(url: String, targetFile: File): Flow<DownloadEvent> = flow {
        targetFile.parentFile?.mkdirs()
        if (targetFile.exists()) targetFile.delete()

        val req = Request.Builder().url(url.toHttpUrl()).get().build()
        val resp = httpProvider.client.newCall(req).executeAsync()
        resp.use { r ->
            if (!r.isSuccessful) error("下载失败：HTTP ${r.code}")
            val body = r.body ?: error("下载失败：响应体为空")
            val total = body.contentLength().let { if (it > 0) it else -1L }
            body.byteStream().use { input ->
                targetFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var downloaded = 0L
                    var lastEmit = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        // 限流发射，避免过度重组
                        if (downloaded - lastEmit >= 64 * 1024 || total <= 0) {
                            lastEmit = downloaded
                            emit(DownloadEvent.Progress(downloaded, total))
                        }
                    }
                    output.flush()
                    emit(DownloadEvent.Progress(downloaded, if (total > 0) total else downloaded))
                }
            }
        }
        emit(DownloadEvent.Done(targetFile))
    }.flowOn(Dispatchers.IO)

    companion object {
        /** 去掉前缀 v / 构建后缀（如 -debug），只保留 x.y.z 形式的核心版本。 */
        fun normalizeVersion(raw: String): String {
            var s = raw.trim()
            if (s.startsWith("v", ignoreCase = true)) s = s.substring(1)
            // 截断第一个 '-'（如 0.1.15-debug、1.0.0-beta）之后的内容
            val dash = s.indexOf('-')
            if (dash >= 0) s = s.substring(0, dash)
            return s.trim()
        }

        /** 语义化版本比较：返回 >0 表示 a 比 b 新，0 相等，<0 更旧。缺失段按 0 处理。 */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
            val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
            val n = maxOf(pa.size, pb.size)
            for (i in 0 until n) {
                val x = pa.getOrElse(i) { 0 }
                val y = pb.getOrElse(i) { 0 }
                if (x != y) return x.compareTo(y)
            }
            return 0
        }
    }
}
