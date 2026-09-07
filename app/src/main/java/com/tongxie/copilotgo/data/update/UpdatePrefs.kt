package com.tongxie.copilotgo.data.update

import android.content.Context
import java.io.IOException

interface UpdatePreferences {
    var skippedVersion: String?
    var lastCheckAt: Long
    var wifiAutoDownload: Boolean
}

/** 记录更新相关偏好，避免每次冷启动都弹更新提示或重复联网。 */
class UpdatePrefs(context: Context) : UpdatePreferences {
    private val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)

    override var skippedVersion: String?
        get() = prefs.getString(KEY_SKIPPED, null)
        set(value) {
            val saved = prefs.edit().apply {
                if (value == null) remove(KEY_SKIPPED) else putString(KEY_SKIPPED, value)
            }.commit()
            if (!saved) throw IOException("无法保存忽略版本设置")
        }

    override var lastCheckAt: Long
        get() = prefs.getLong(KEY_LAST_CHECK_AT, 0L)
        set(value) {
            if (!prefs.edit().putLong(KEY_LAST_CHECK_AT, value).commit()) throw IOException("无法保存更新检查时间")
        }

    override var wifiAutoDownload: Boolean
        get() = prefs.getBoolean(KEY_WIFI_AUTO_DOWNLOAD, false)
        set(value) {
            prefs.edit().putBoolean(KEY_WIFI_AUTO_DOWNLOAD, value).apply()
        }

    companion object {
        private const val KEY_SKIPPED = "skipped_version"
        private const val KEY_LAST_CHECK_AT = "last_check_at"
        private const val KEY_WIFI_AUTO_DOWNLOAD = "wifi_auto_download"
    }
}
