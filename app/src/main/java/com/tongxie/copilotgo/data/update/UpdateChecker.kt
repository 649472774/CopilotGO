package com.tongxie.copilotgo.data.update

import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.net.HttpClientProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Public release requests never use a Copilot token or silently bypass the chosen proxy. */
class UpdateChecker(
    httpProvider: HttpClientProvider,
    private val json: Json,
    private val currentVersionName: String,
    private val latestReleaseApi: String = Constants.GITHUB_LATEST_RELEASE_API,
    private val releasePageUrl: String = Constants.GITHUB_RELEASES_PAGE,
    private val debugPackage: Boolean = true,
    private val limits: UpdateLimits = UpdateLimits(),
    private val urlPolicy: UpdateUrlPolicy = GitHubUpdateUrls
) : UpdateSource {
    private val transport = UpdateTransport(httpProvider, urlPolicy, limits)
    private val downloadMutex = Mutex()

    override val hasConfiguredProxy: Boolean get() = transport.hasConfiguredProxy

    sealed interface CheckResult {
        data class UpToDate(val currentVersion: String) : CheckResult
        data class Available(val info: UpdateInfo) : CheckResult
        data class Failed(val message: String) : CheckResult
    }

    sealed interface DownloadEvent {
        data class Progress(val bytesRead: Long, val total: Long) : DownloadEvent
        data object Verifying : DownloadEvent
        data class Done(val file: File, val sha256: String) : DownloadEvent
    }

    override suspend fun check(route: UpdateRoute): CheckResult = withContext(Dispatchers.IO) {
        try {
            withTimeout(limits.checkTimeoutMillis) {
                val release = transport.read(
                    parseUrl(latestReleaseApi), route, limits.checkTimeoutMillis, "application/vnd.github+json"
                ) { response ->
                    json.decodeFromString<GithubRelease>(UpdateTransport.readText(response, limits.maxMetadataBytes))
                }
                if (release.draft || release.prerelease) {
                    return@withTimeout CheckResult.Failed("发布信息不是稳定版本，请打开发布页确认")
                }
                val remote = normalizeVersion(release.tagName)
                val remoteVersion = Version.parse(remote)
                if (remoteVersion.prerelease != null || remoteVersion.segments.size != 3) {
                    throw UpdateException("发布版本号无效，无法安全判断更新")
                }
                val local = normalizeVersion(currentVersionName).removeSuffix("-debug")
                if (compareVersions(remote, local) <= 0) {
                    return@withTimeout CheckResult.UpToDate(currentVersionName)
                }
                val apk = selectApk(release.assets)
                if (apk.size !in 1..limits.maxApkBytes) throw UpdateException("安装包大小无效或超过 128 MB 限制")
                urlPolicy.requireAsset(parseUrl(apk.browserDownloadUrl))
                val checksum = release.assets.firstOrNull { it.name == "${apk.name}.sha256" }
                    ?: release.assets.singleOrNull { it.name == "SHA256SUMS" }
                val digest = apk.digest?.let { value ->
                    if (!value.startsWith("sha256:")) throw UpdateException("发布资源使用了不支持的校验算法")
                    requireSha256(value.removePrefix("sha256:"))
                }
                if (digest == null && checksum == null) {
                    throw UpdateException("发布未提供 SHA-256 校验值，不能在应用内安装。请联系维护者补齐发布资源")
                }
                checksum?.let { urlPolicy.requireAsset(parseUrl(it.browserDownloadUrl)) }
                CheckResult.Available(
                    UpdateInfo(
                        versionName = remote,
                        tagName = release.tagName,
                        releaseName = release.name.ifBlank { release.tagName }.take(200),
                        changelog = release.body.trim().ifBlank { "（本次发布未提供更新说明）" }
                            .let { if (it.length <= 16_000) it else it.take(16_000) + "\n\n说明较长，请到发布页查看全文。" },
                        apkUrl = apk.browserDownloadUrl,
                        apkSize = apk.size,
                        releasePageUrl = releasePageUrl,
                        apkName = apk.name,
                        apkSha256 = digest,
                        checksumUrl = checksum?.browserDownloadUrl
                    )
                )
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            CheckResult.Failed("检查更新超时，请稍后重试或调整网络设置")
        } catch (e: CancellationException) {
            throw e
        } catch (e: UpdateException) {
            CheckResult.Failed(e.message ?: "更新检查失败")
        } catch (e: SerializationException) {
            CheckResult.Failed("发布信息格式无效，无法检查更新")
        } catch (e: IllegalArgumentException) {
            CheckResult.Failed("发布版本或地址无效，无法检查更新")
        } catch (e: IOException) {
            CheckResult.Failed("无法连接或读取更新服务，请检查网络与代理设置")
        }
    }

    private fun selectApk(assets: List<GithubAsset>): GithubAsset {
        val apks = assets.filter {
            it.name.endsWith(".apk", ignoreCase = true) && it.name.length <= 200 &&
                '/' !in it.name && '\\' !in it.name && it.browserDownloadUrl.isNotBlank()
        }
        val suffix = if (debugPackage) "-debug.apk" else "-release.apk"
        val otherSuffix = if (debugPackage) "-release.apk" else "-debug.apk"
        if (apks.size == 1 && apks.single().name.endsWith(otherSuffix, ignoreCase = true)) {
            throw UpdateException("此发布没有与当前应用渠道匹配的安装包")
        }
        return apks.filter { it.name.endsWith(suffix, ignoreCase = true) }.singleOrNull()
            ?: apks.singleOrNull()
            ?: throw UpdateException("发布中的安装包缺失或不明确，无法安全选择")
    }

    override fun download(info: UpdateInfo, targetFile: File, route: UpdateRoute): Flow<DownloadEvent> = channelFlow {
        val parent = targetFile.parentFile ?: throw UpdateException("更新缓存目录无效")
        if (!downloadMutex.tryLock()) throw UpdateException("已有更新下载正在进行，请先取消或等待完成")
        val partial = File(parent, "${targetFile.name}.part")
        var failure: Exception? = null
        try {
            withTimeout(limits.downloadTimeoutMillis) {
                val url = parseUrl(info.apkUrl)
                urlPolicy.requireAsset(url)
                if (info.apkSize !in 1..limits.maxApkBytes) throw UpdateException("安装包大小无效或超过限制")
                if (!parent.isDirectory && !parent.mkdirs()) throw UpdateException("无法创建更新缓存目录")
                Files.deleteIfExists(partial.toPath())
                val expectedHash = expectedHash(info, route)
                val digest = MessageDigest.getInstance("SHA-256")
                transport.read(url, route, limits.downloadTimeoutMillis, "application/vnd.android.package-archive") { response ->
                    val body = response.body ?: throw UpdateException("安装包响应为空")
                    val declaredLength = body.contentLength()
                    if (declaredLength > limits.maxApkBytes || (declaredLength >= 0 && declaredLength != info.apkSize)) {
                        throw UpdateException("安装包大小与发布信息不符")
                    }
                    body.byteStream().use { input ->
                        FileOutputStream(partial).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var downloaded = 0L
                            var lastProgressAt = System.nanoTime()
                            send(DownloadEvent.Progress(0, info.apkSize))
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count > info.apkSize - downloaded || count > limits.maxApkBytes - downloaded) {
                                    throw UpdateException("安装包内容超过声明的大小")
                                }
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                downloaded += count
                                val now = System.nanoTime()
                                if (now - lastProgressAt >= 100_000_000L) {
                                    send(DownloadEvent.Progress(downloaded, info.apkSize))
                                    lastProgressAt = now
                                }
                            }
                            if (downloaded != info.apkSize) throw UpdateException("安装包下载不完整，请重试")
                            output.fd.sync()
                            send(DownloadEvent.Progress(downloaded, info.apkSize))
                        }
                    }
                }
                send(DownloadEvent.Verifying)
                val actualHash = digest.digest().toHex()
                if (actualHash != expectedHash) throw UpdateException("安装包 SHA-256 校验失败，已丢弃下载内容")
                currentCoroutineContext().ensureActive()
                Files.move(partial.toPath(), targetFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                send(DownloadEvent.Done(targetFile, actualHash))
            }
        } catch (e: TimeoutCancellationException) {
            failure = e
            currentCoroutineContext().ensureActive()
            throw UpdateException("更新下载超过 5 分钟，请重试或打开发布页", e)
        } catch (e: CancellationException) {
            failure = e
            throw e
        } catch (e: IOException) {
            failure = e
            throw if (e is UpdateException) e else UpdateException("下载或保存安装包失败，请检查网络、代理和可用空间", e)
        } finally {
            try {
                Files.deleteIfExists(partial.toPath())
            } catch (cleanup: IOException) {
                if (failure != null) failure.addSuppressed(cleanup)
                else throw UpdateException("无法清理未完成的安装包，请检查存储空间", cleanup)
            } finally {
                downloadMutex.unlock()
            }
        }
    }.buffer(Channel.RENDEZVOUS).flowOn(Dispatchers.IO)

    private suspend fun expectedHash(info: UpdateInfo, route: UpdateRoute): String {
        info.apkSha256?.let { return requireSha256(it) }
        val url = info.checksumUrl?.let(::parseUrl) ?: throw UpdateException("缺少安装包 SHA-256 校验值")
        urlPolicy.requireAsset(url)
        return transport.read(url, route, limits.checkTimeoutMillis, "text/plain") { response ->
            parseChecksum(UpdateTransport.readText(response, limits.maxChecksumBytes), info.apkName)
        }
    }

    companion object {
        fun normalizeVersion(raw: String): String = raw.trim().removePrefix("v").removePrefix("V").trim()

        fun compareVersions(a: String, b: String): Int = Version.parse(a).compareTo(Version.parse(b))

        internal fun requireSha256(value: String): String {
            if (!value.matches(Regex("[a-fA-F0-9]{64}"))) throw UpdateException("发布的 SHA-256 校验值无效")
            return value.lowercase()
        }

        internal fun parseChecksum(text: String, apkName: String): String {
            val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            if (lines.size == 1 && lines[0].matches(Regex("[a-fA-F0-9]{64}"))) return requireSha256(lines[0])
            if (apkName.isBlank()) throw UpdateException("校验文件缺少对应的安装包名称")
            val matches = lines.mapNotNull { line ->
                Regex("^([a-fA-F0-9]{64})\\s+\\*?(.+)$").matchEntire(line)?.let {
                    if (it.groupValues[2] == apkName) it.groupValues[1] else null
                }
            }
            if (matches.size != 1) throw UpdateException("校验文件缺少唯一匹配的安装包记录")
            return requireSha256(matches.single())
        }

        internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun parseUrl(value: String): HttpUrl =
            value.toHttpUrlOrNull() ?: throw UpdateException("更新地址无效")
    }
}

private data class Version(val segments: List<java.math.BigInteger>, val prerelease: List<String>?) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        for (index in 0 until maxOf(segments.size, other.segments.size)) {
            val difference = segments.getOrElse(index) { java.math.BigInteger.ZERO }
                .compareTo(other.segments.getOrElse(index) { java.math.BigInteger.ZERO })
            if (difference != 0) return difference
        }
        val left = prerelease ?: return if (other.prerelease == null) 0 else 1
        val right = other.prerelease ?: return -1
        for (index in 0 until minOf(left.size, right.size)) {
            val a = left[index]
            val b = right[index]
            val numericA = a.toBigIntegerOrNull()
            val numericB = b.toBigIntegerOrNull()
            val difference = when {
                numericA != null && numericB != null -> numericA.compareTo(numericB)
                numericA != null -> -1
                numericB != null -> 1
                else -> a.compareTo(b)
            }
            if (difference != 0) return difference
        }
        return left.size.compareTo(right.size)
    }

    companion object {
        fun parse(raw: String): Version {
            val value = UpdateChecker.normalizeVersion(raw)
            require(value.length <= 128 && value.matches(
                Regex("\\d+(?:\\.\\d+){0,7}(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?")
            )) { "Invalid version" }
            val withoutMetadata = value.substringBefore('+')
            return Version(
                withoutMetadata.substringBefore('-').split('.').map(String::toBigInteger),
                withoutMetadata.substringAfter('-', "").takeIf(String::isNotEmpty)?.split('.')
            )
        }
    }
}
