package com.tongxie.copilotgo.data.update

data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int,
    val signerSha256: Set<String>
)

object ApkCompatibility {
    fun requireCompatible(installed: ApkIdentity, candidate: ApkIdentity, releaseVersion: String, deviceSdk: Int) {
        if (candidate.packageName != installed.packageName) {
            throw UpdateException("安装包属于其他应用，不能覆盖当前应用")
        }
        if (candidate.versionCode <= installed.versionCode) {
            throw UpdateException("安装包版本不高于已安装版本，已阻止重复安装或降级")
        }
        val versionName = candidate.versionName ?: throw UpdateException("安装包缺少版本名称")
        val archiveVersion = UpdateChecker.normalizeVersion(versionName).removeSuffix("-debug")
        val matchesRelease = try {
            UpdateChecker.compareVersions(archiveVersion, releaseVersion) == 0
        } catch (e: IllegalArgumentException) {
            throw UpdateException("安装包版本号无效", e)
        }
        if (!matchesRelease) throw UpdateException("安装包版本与发布信息不符")
        if (candidate.minSdk > deviceSdk) throw UpdateException("此更新不支持当前 Android 版本")
        if (installed.signerSha256.isEmpty() || candidate.signerSha256.isEmpty() ||
            candidate.signerSha256 != installed.signerSha256
        ) {
            throw UpdateException("安装包签名身份与当前应用不符。请使用原签名构建，勿卸载或清除数据")
        }
    }
}
