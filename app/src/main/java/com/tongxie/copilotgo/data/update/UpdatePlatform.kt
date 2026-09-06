package com.tongxie.copilotgo.data.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import com.tongxie.copilotgo.data.Constants
import com.tongxie.copilotgo.data.update.UpdateChecker.Companion.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

interface UpdatePlatform {
    suspend fun createDownloadTarget(info: UpdateInfo): File
    suspend fun verifyApk(info: UpdateInfo, file: File)
    fun isUnmeteredWifi(): Boolean
    fun canInstall(): Boolean
    fun openInstallPermission()
    fun install(file: File)
    fun openReleasePage()
}

class AndroidUpdatePlatform(context: Context) : UpdatePlatform {
    private val context = context.applicationContext

    override suspend fun createDownloadTarget(info: UpdateInfo): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates")
        if (!directory.isDirectory && !directory.mkdirs()) throw UpdateException("无法创建更新缓存目录")
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60_000L
        val files = (directory.listFiles() ?: throw UpdateException("无法读取更新缓存目录"))
            .filter { it.isFile && it.name.matches(CACHE_NAME) }
        val recentApks = files.filter { it.name.endsWith(".apk") }
            .sortedByDescending(File::lastModified).take(2).toSet()
        files.filter { it.lastModified() < cutoff || (it.name.endsWith(".apk") && it !in recentApks) }
            .forEach { Files.delete(it.toPath()) }
        if (directory.usableSpace < info.apkSize + 8 * 1024 * 1024) {
            throw UpdateException("可用空间不足，请释放空间后重试")
        }
        File(directory, "CopilotGo-update-${UUID.randomUUID()}.apk")
    }

    override suspend fun verifyApk(info: UpdateInfo, file: File): Unit = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").canonicalFile
        if (file.canonicalFile.parentFile != directory || !file.name.matches(CACHE_NAME) || !file.name.endsWith(".apk")) {
            throw UpdateException("安装包不在专用更新目录中")
        }
        if (!file.isFile || file.length() != info.apkSize || file.length() !in 1..UpdateLimits().maxApkBytes) {
            throw UpdateException("安装包不存在或已改变，请重新下载")
        }
        val modifiedAt = file.lastModified()
        val expected = info.apkSha256?.let(UpdateChecker::requireSha256)
            ?: throw UpdateException("安装包缺少已验证的校验值，请重新下载")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            var read = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(bytes)
                if (count < 0) break
                read += count
                if (read > info.apkSize) throw UpdateException("安装包在校验过程中超过声明的大小")
                digest.update(bytes, 0, count)
            }
            if (read != info.apkSize) throw UpdateException("安装包在校验过程中被截断")
        }
        if (digest.digest().toHex() != expected) throw UpdateException("安装包在下载后发生变化，请重新下载")
        val manager = context.packageManager
        val archive = archiveInfo(manager, file.absolutePath) ?: throw UpdateException("下载内容不是有效的 APK 安装包")
        val installed = try {
            installedInfo(manager)
        } catch (e: PackageManager.NameNotFoundException) {
            throw UpdateException("无法读取当前应用的安装信息，请重新启动应用", e)
        }
        ApkCompatibility.requireCompatible(installed.identity(), archive.identity(), info.versionName, Build.VERSION.SDK_INT)
        if (file.length() != info.apkSize || file.lastModified() != modifiedAt) {
            throw UpdateException("安装包在校验过程中发生变化，请重新下载")
        }
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(manager: PackageManager, path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            manager.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            manager.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    @Suppress("DEPRECATION")
    private fun installedInfo(manager: PackageManager): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) {
            manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    private fun PackageInfo.identity() = ApkIdentity(
        packageName = packageName,
        versionCode = longVersionCode,
        versionName = versionName,
        minSdk = applicationInfo?.minSdkVersion ?: throw UpdateException("安装包缺少 Android 版本要求"),
        signerSha256 = signingInfo?.apkContentsSigners?.map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex()
        }?.toSet().orEmpty()
    )

    override fun isUnmeteredWifi(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun canInstall(): Boolean = try {
        ApkInstaller.canInstall(context)
    } catch (e: SecurityException) {
        throw UpdateException("系统不允许读取安装权限状态，请检查应用权限", e)
    }

    override fun openInstallPermission() = launchExternal("系统无法打开安装权限设置") {
        ApkInstaller.openInstallPermissionSettings(context)
    }

    override fun install(file: File) = launchExternal("系统无法打开安装器，请稍后重试") {
        ApkInstaller.install(context, file)
    }

    override fun openReleasePage() = launchExternal("没有可用于打开发布页的浏览器") {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(Constants.GITHUB_RELEASES_PAGE))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private inline fun launchExternal(message: String, action: () -> Unit) {
        try {
            action()
        } catch (e: ActivityNotFoundException) {
            throw UpdateException(message, e)
        } catch (e: SecurityException) {
            throw UpdateException("系统拒绝了此操作，请检查应用权限", e)
        }
    }

    companion object {
        private val CACHE_NAME = Regex("CopilotGo-update-[a-f0-9-]{36}\\.apk(?:\\.part)?")
    }
}
