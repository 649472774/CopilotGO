package com.tongxie.copilotgo.data.storage

import android.content.Context
import com.tongxie.copilotgo.data.Constants
import java.io.File

class AppPaths private constructor(private val rootFactory: () -> File) {
    constructor(root: File) : this({ root })
    constructor(context: Context) : this({
        val app = context.applicationContext
        File(app.getExternalFilesDir(null) ?: app.filesDir, Constants.APP_DATA_DIR_NAME)
    })

    val root: File by lazy(rootFactory)
    val sessions: File get() = File(root, "sessions")
    val exports: File get() = File(root, "exports")
    val logs: File get() = File(root, "logs")
    val attachments: File get() = File(root, "attachments")

    fun describe(): String = buildString {
        appendLine("Root: ${root.absolutePath}")
        appendLine("Sessions: ${sessions.absolutePath}")
        appendLine("Exports: ${exports.absolutePath}")
        appendLine("Attachments: ${attachments.absolutePath}")
    }
}
