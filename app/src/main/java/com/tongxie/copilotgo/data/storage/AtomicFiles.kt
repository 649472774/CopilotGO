package com.tongxie.copilotgo.data.storage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal object AtomicFiles {
    fun ensureDirectory(directory: File): File {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("无法创建存储目录")
        }
        return directory
    }

    fun write(file: File, bytes: ByteArray, backup: Boolean = false) {
        ensureDirectory(requireNotNull(file.parentFile))
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.outputStream().use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        if (backup && file.isFile) {
            val backupFile = File(file.parentFile, "${file.name}.bak")
            val backupTemporary = File(file.parentFile, "${file.name}.bak.tmp")
            file.inputStream().use { input ->
                backupTemporary.outputStream().use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            replace(backupTemporary, backupFile)
        }
        // Never delete the last good file or fall back to an in-place partial write.
        replace(temporary, file)
    }

    fun replace(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    fun read(file: File, maxBytes: Int): ByteArray {
        if (file.length() > maxBytes) throw IOException("文件超过读取大小限制")
        return file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 8192))
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw IOException("文件超过读取大小限制")
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }
}
