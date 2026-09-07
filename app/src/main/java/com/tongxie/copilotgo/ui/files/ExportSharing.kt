package com.tongxie.copilotgo.ui.files

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

fun exportShareIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    return Intent(Intent.ACTION_SEND).apply {
        type = "text/markdown"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
