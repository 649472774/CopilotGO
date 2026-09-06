package com.tongxie.copilotgo.data.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.tongxie.copilotgo.data.chat.AttachmentKind
import com.tongxie.copilotgo.data.chat.AttachmentRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

enum class AttachmentImportFailure {
    UNSUPPORTED_TYPE, BINARY_TEXT, TOO_LARGE, TOO_MANY, UNREADABLE, INVALID_REFERENCE
}

class AttachmentImportException(val reason: AttachmentImportFailure, val userMessage: String) :
    IOException(userMessage)

class AttachmentStore(private val paths: AppPaths) {
    private val mutex = Mutex()

    suspend fun importAttachment(resolver: ContentResolver, uri: Uri): AttachmentRef =
        withContext(Dispatchers.IO) {
            try {
                if (uri.scheme != "content") {
                    throw failure(AttachmentImportFailure.UNSUPPORTED_TYPE, "请选择系统文件选择器提供的文件")
                }
                var name = "附件"
                var size: Long? = null
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameColumn >= 0) name = cursor.getString(nameColumn) ?: name
                            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
                        }
                    }
                val mime = resolver.getType(uri)?.lowercase(Locale.ROOT)?.substringBefore(';')
                val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                val image = mime in IMAGE_TYPES || extension in IMAGE_EXTENSIONS
                val text = mime?.startsWith("text/") == true || mime in TEXT_TYPES || extension in TEXT_EXTENSIONS
                if (!image && !text) {
                    throw failure(AttachmentImportFailure.UNSUPPORTED_TYPE, "仅支持 PNG、JPEG、GIF、WebP 图片和 UTF-8 文本文件")
                }
                val limit = if (image) MAX_IMAGE_BYTES else MAX_TEXT_BYTES
                if (size != null && size!! > limit) throw tooLarge(image)
                val bytes = resolver.openInputStream(uri)?.use { readBounded(it, limit, image) }
                    ?: throw failure(AttachmentImportFailure.UNREADABLE, "无法打开此附件，请重新选择")
                if (image) importImage(bytes, name) else importTextBytes(bytes, name, mime ?: "text/plain")
            } catch (e: CancellationException) {
                throw e
            } catch (e: AttachmentImportException) {
                throw e
            } catch (_: SecurityException) {
                throw failure(AttachmentImportFailure.UNREADABLE, "附件读取权限已失效，请重新选择")
            } catch (_: IOException) {
                throw failure(AttachmentImportFailure.UNREADABLE, "附件读取或保存失败，请重试")
            }
        }

    suspend fun importText(content: String, name: String = "附件.txt"): AttachmentRef =
        withContext(Dispatchers.IO) {
            if (content.length > MAX_TEXT_BYTES) throw tooLarge(false)
            val bytes = content.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_TEXT_BYTES) throw tooLarge(false)
            importTextBytes(bytes, name, "text/plain")
        }

    suspend fun importDataUri(dataUri: String, legacy: Boolean = false): AttachmentRef =
        withContext(Dispatchers.IO) {
            val limit = if (legacy) MAX_LEGACY_IMAGE_BYTES else MAX_IMAGE_BYTES
            if (dataUri.length.toLong() > (limit.toLong() + 2) / 3 * 4 + 256) throw tooLarge(true)
            val comma = dataUri.indexOf(',')
            if (comma !in 1..128 || !dataUri.startsWith("data:image/") ||
                !dataUri.substring(0, comma).endsWith(";base64")
            ) throw failure(AttachmentImportFailure.UNSUPPORTED_TYPE, "图片数据格式不受支持")
            val bytes = try {
                Base64.getDecoder().decode(dataUri.substring(comma + 1).replace("\r", "").replace("\n", ""))
            } catch (_: IllegalArgumentException) {
                throw failure(AttachmentImportFailure.UNREADABLE, "图片数据损坏，原始内容已保留")
            }
            if (bytes.size > limit) throw tooLarge(true)
            importImage(bytes, "图片", legacy)
        }

    private suspend fun importImage(bytes: ByteArray, name: String, legacy: Boolean = false): AttachmentRef {
        if (bytes.size > if (legacy) MAX_LEGACY_IMAGE_BYTES else MAX_IMAGE_BYTES) throw tooLarge(true)
        val mime = imageType(bytes)
            ?: throw failure(AttachmentImportFailure.UNSUPPORTED_TYPE, "图片格式不受支持或内容损坏")
        return store(bytes, name, mime, AttachmentKind.IMAGE)
    }

    private suspend fun importTextBytes(bytes: ByteArray, name: String, mime: String): AttachmentRef {
        if (bytes.size > MAX_TEXT_BYTES) throw tooLarge(false)
        decodeText(bytes)
        return store(bytes, name, mime, AttachmentKind.TEXT)
    }

    private suspend fun store(bytes: ByteArray, name: String, mime: String, kind: AttachmentKind): AttachmentRef =
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val id = digest(bytes)
            val ref = AttachmentRef(id, safeName(name), mime, bytes.size.toLong(), kind)
            val file = validatedFile(ref)
            if (file.exists()) {
                if (file.length() != bytes.size.toLong() || digest(AtomicFiles.read(file, bytes.size)) != id) {
                    throw failure(AttachmentImportFailure.INVALID_REFERENCE, "已有附件文件损坏，请保留原文件并重新导入")
                }
            } else {
                AtomicFiles.write(file, bytes)
            }
            ref
        }

    /** Syntax-only resolver; callers decode previews off Main. */
    fun attachmentFile(ref: AttachmentRef): File {
        if (!ref.id.matches(Regex("[a-f0-9]{64}")) || ref.sizeBytes < 0 ||
            ref.sizeBytes > MAX_LEGACY_IMAGE_BYTES
        ) throw failure(AttachmentImportFailure.INVALID_REFERENCE, "附件引用无效")
        return File(paths.attachments, "${ref.id}.blob")
    }

    private fun validatedFile(ref: AttachmentRef): File {
        val file = attachmentFile(ref)
        if (file.canonicalFile.parentFile != paths.attachments.canonicalFile) {
            throw failure(AttachmentImportFailure.INVALID_REFERENCE, "附件路径无效")
        }
        return file
    }

    suspend fun validate(ref: AttachmentRef) = withContext(Dispatchers.IO) {
        readBytes(ref)
        Unit
    }

    suspend fun readText(ref: AttachmentRef): String = withContext(Dispatchers.IO) {
        if (ref.kind != AttachmentKind.TEXT) {
            throw failure(AttachmentImportFailure.UNSUPPORTED_TYPE, "此附件不是文本文件")
        }
        decodeText(readBytes(ref))
    }

    suspend fun imageDataUri(ref: AttachmentRef): String = withContext(Dispatchers.IO) {
        if (ref.kind != AttachmentKind.IMAGE) {
            throw failure(AttachmentImportFailure.UNSUPPORTED_TYPE, "此附件不是图片")
        }
        val bytes = readBytes(ref)
        val mime = imageType(bytes)
            ?: throw failure(AttachmentImportFailure.UNREADABLE, "图片数据损坏")
        "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
    }

    private fun readBytes(ref: AttachmentRef): ByteArray {
        val image = ref.kind == AttachmentKind.IMAGE
        val limit = if (image) MAX_IMAGE_BYTES else MAX_TEXT_BYTES
        if (ref.sizeBytes > limit) throw tooLarge(image)
        val file = validatedFile(ref)
        if (!file.isFile || file.length() != ref.sizeBytes) {
            throw failure(AttachmentImportFailure.INVALID_REFERENCE, "附件文件已丢失或发生变化，请重新选择")
        }
        val bytes = AtomicFiles.read(file, limit)
        if (digest(bytes) != ref.id) {
            throw failure(AttachmentImportFailure.INVALID_REFERENCE, "附件校验失败，请重新选择")
        }
        return bytes
    }

    private suspend fun readBounded(input: InputStream, limit: Int, image: Boolean): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) return output.toByteArray()
            if (output.size().toLong() + read > limit) throw tooLarge(image)
            output.write(buffer, 0, read)
        }
    }

    private fun decodeText(bytes: ByteArray): String {
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw failure(AttachmentImportFailure.BINARY_TEXT, "附件不是有效的 UTF-8 文本")
        }
        if (text.any { it < ' ' && it != '\n' && it != '\r' && it != '\t' }) {
            throw failure(AttachmentImportFailure.BINARY_TEXT, "附件包含二进制内容，无法作为文本发送")
        }
        return text
    }

    private fun imageType(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map { it.toByte() } -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
        bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> null
    }

    private fun safeName(name: String): String = name.map {
        if (it.isISOControl() || it == '/' || it == '\\') '_' else it
    }.joinToString("").take(128).ifBlank { "附件" }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun tooLarge(image: Boolean) = failure(
        AttachmentImportFailure.TOO_LARGE,
        if (image) "图片不能超过 8 MiB" else "文本附件不能超过 256 KiB"
    )

    private fun failure(reason: AttachmentImportFailure, message: String) = AttachmentImportException(reason, message)

    companion object {
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        const val MAX_TEXT_BYTES = 256 * 1024
        const val MAX_ATTACHMENTS = 8
        const val MAX_TURN_BYTES = 16L * 1024 * 1024
        private const val MAX_LEGACY_IMAGE_BYTES = 32 * 1024 * 1024
        private val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp")
        private val TEXT_TYPES = setOf("application/json", "application/xml", "application/javascript", "application/x-yaml")
        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "csv", "json", "xml", "yaml", "yml", "log", "kt", "kts", "java",
            "py", "js", "jsx", "ts", "tsx", "c", "cpp", "h", "hpp", "cs", "go", "rs", "sql",
            "html", "css", "sh", "ps1", "toml", "ini", "properties"
        )
    }
}
