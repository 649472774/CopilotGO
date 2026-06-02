package com.tongxie.copilotgo.data.update

import android.content.Context

/** 记录更新相关偏好，避免每次冷启动都弹更新提示或重复联网。 */
class UpdatePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)

    var skippedVersion: String?
        get() = prefs.getString(KEY_SKIPPED, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_SKIPPED) else putString(KEY_SKIPPED, value)
            }.apply()
        }

    var lastCheckAt: Long
        get() = prefs.getLong(KEY_LAST_CHECK_AT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_CHECK_AT, value).apply()
        }

    var wifiAutoDownload: Boolean
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
