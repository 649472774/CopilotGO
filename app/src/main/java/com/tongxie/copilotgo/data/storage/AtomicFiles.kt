package com.tongxie.copilotgo.data.storage

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
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
        FileChannel.open(temporary.toPath(), WRITE, CREATE, TRUNCATE_EXISTING, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        if (backup && file.isFile) {
            val backupFile = File(file.parentFile, "${file.name}.bak")
            val backupTemporary = File(file.parentFile, "${file.name}.bak.tmp")
            Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
                FileChannel.open(backupTemporary.toPath(), WRITE, CREATE, TRUNCATE_EXISTING, NOFOLLOW_LINKS).use { channel ->
                    input.copyTo(Channels.newOutputStream(channel))
                    channel.force(true)
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
        return Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
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
