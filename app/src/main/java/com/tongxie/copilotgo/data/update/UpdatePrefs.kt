package com.tongxie.copilotgo.data.update

import android.content.Context

/** 记录用户「忽略此版本」的选择，避免每次冷启动都弹更新提示。 */
class UpdatePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)

    var skippedVersion: String?
        get() = prefs.getString(KEY_SKIPPED, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_SKIPPED) else putString(KEY_SKIPPED, value)
            }.apply()
        }

    companion object {
        private const val KEY_SKIPPED = "skipped_version"
    }
}
