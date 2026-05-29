package com.tongxie.copilotgo.data.storage

import android.content.Context
import com.tongxie.copilotgo.data.Constants
import java.io.File

class AppPaths(private val context: Context) {

    val root: File
        get() {
            val ext = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(ext, Constants.APP_DATA_DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    val sessions: File get() = ensure(File(root, "sessions"))
    val exports: File get() = ensure(File(root, "exports"))
    val logs: File get() = ensure(File(root, "logs"))
    val attachments: File get() = ensure(File(root, "attachments"))

    private fun ensure(dir: File): File {
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun describe(): String = buildString {
        appendLine("Root: ${root.absolutePath}")
        appendLine("Sessions: ${sessions.absolutePath}")
        appendLine("Exports: ${exports.absolutePath}")
        appendLine("Attachments: ${attachments.absolutePath}")
    }
}
