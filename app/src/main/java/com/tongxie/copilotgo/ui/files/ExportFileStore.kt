package com.tongxie.copilotgo.ui.files

import com.tongxie.copilotgo.data.chat.Session
import com.tongxie.copilotgo.data.chat.UiMessage
import com.tongxie.copilotgo.data.agent.AgentRunRecord
import com.tongxie.copilotgo.ui.agent.agentSourceDestination
import com.tongxie.copilotgo.ui.agent.isAgentSourceId
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ExportEntry(val name: String, val sizeBytes: Long, val modifiedAt: Long)
data class ExportPreview(val text: String, val truncated: Boolean)

class ExportTooLargeException : IOException("Export exceeds the file limit")

/**
 * Owns only cache/exports, never the session store or attachment blob directory.
 * All public I/O is suspend so callers cannot accidentally enumerate files on Main.
 */
class ExportFileStore(cacheDir: File) {
    private val root = File(cacheDir, "exports")

    suspend fun list(): List<ExportEntry> = withContext(Dispatchers.IO) {
        val directory = directory()
        val files = directory.listFiles() ?: throw IOException("Cannot list exports")
        files.asSequence()
            .filter { it.isFile && it.extension == "md" && !Files.isSymbolicLink(it.toPath()) }
            .map { ExportEntry(it.name, it.length(), it.lastModified()) }
            .sortedByDescending { it.modifiedAt }
            .toList()
    }

    suspend fun writeSession(
        session: Session,
        attachmentNames: Map<String, List<String>> = emptyMap()
    ): ExportEntry = write { writer ->
        writer.write("# ")
        writer.write(session.title)
        writer.write("\n\n")
        for (message in session.messages) {
            currentCoroutineContext().ensureActive()
            writeMessage(writer, message, attachmentNames[message.id].orEmpty())
        }
    }

    suspend fun writeMessage(
        message: UiMessage,
        attachmentNames: List<String> = emptyList()
    ): ExportEntry = write { writer ->
        writeMessage(writer, message, attachmentNames)
    }

    suspend fun file(name: String): File = withContext(Dispatchers.IO) { existingFile(name) }

    suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        if (!existingFile(name).delete()) throw IOException("Cannot delete export")
    }

    suspend fun preview(name: String): ExportPreview = withContext(Dispatchers.IO) {
        existingFile(name).reader(Charsets.UTF_8).use { reader ->
            val chars = CharArray(PREVIEW_CHAR_LIMIT + 1)
            var count = 0
            while (count < chars.size) {
                currentCoroutineContext().ensureActive()
                val read = reader.read(chars, count, chars.size - count)
                if (read == -1) break
                count += read
            }
            var length = minOf(count, PREVIEW_CHAR_LIMIT)
            if (length > 0 && Character.isHighSurrogate(chars[length - 1])) length--
            ExportPreview(String(chars, 0, length), count > PREVIEW_CHAR_LIMIT)
        }
    }

    private suspend fun write(block: suspend (OutputStreamWriter) -> Unit): ExportEntry =
        withContext(Dispatchers.IO) {
            val directory = directory()
            val stem = "CopilotGo-${System.currentTimeMillis()}-${UUID.randomUUID()}"
            val temporary = File(directory, "$stem.part")
            val destination = File(directory, "$stem.md")
            try {
                FileOutputStream(temporary).use { file ->
                    val writer = OutputStreamWriter(SizeLimitedOutputStream(file, EXPORT_BYTE_LIMIT), Charsets.UTF_8)
                    block(writer)
                    writer.flush()
                    file.fd.sync()
                }
                currentCoroutineContext().ensureActive()
                if (!temporary.renameTo(destination)) throw IOException("Cannot finalize export")
                ExportEntry(destination.name, destination.length(), destination.lastModified())
            } finally {
                if (temporary.exists() && !temporary.delete()) {
                    com.tongxie.copilotgo.util.Logger.w("Could not remove unfinished export")
                }
            }
        }

    private suspend fun writeMessage(
        writer: OutputStreamWriter,
        message: UiMessage,
        attachmentNames: List<String>
    ) {
        writer.write("## ")
        writer.write(if (message.role == "user") "User" else "Copilot")
        writer.write("\n\n")
        val text = message.content
        var offset = 0
        while (offset < text.length) {
            currentCoroutineContext().ensureActive()
            val count = minOf(8_192, text.length - offset)
            writer.write(text, offset, count)
            offset += count
        }
        writer.write("\n\n")
        for (name in attachmentNames) {
            writer.write("- ")
            writer.write(name.replace('\n', ' ').replace('\r', ' '))
            writer.write("\n")
        }
        if (attachmentNames.isNotEmpty()) writer.write("\n")
        message.agentRun?.let { writeAgentRun(writer, it) }
    }

    private suspend fun writeAgentRun(writer: OutputStreamWriter, run: AgentRunRecord) {
        writer.write("### Tool activity\n\nStatus: ${run.status.name}\n\n")
        run.notice?.let { writeLiteralBlock(writer, it) }
        for (step in run.steps) {
            for (call in step.toolCalls) {
                currentCoroutineContext().ensureActive()
                writer.write("Tool: ")
                writer.write(call.name.replace('\n', ' ').replace('\r', ' '))
                writer.write("\n\nState: ${call.status.name}\n\n")
                if (call.outcomeUnknown || call.result?.outcomeUnknown == true) {
                    writer.write("Remote outcome unknown. Do not automatically repeat this action.\n\n")
                }
                if (call.destination.isNotEmpty()) {
                    writer.write("Destination:\n\n")
                    writeLiteralBlock(writer, call.destination)
                }
                call.arguments?.let {
                    writer.write("Arguments:\n\n")
                    writeLiteralBlock(writer, it.toString())
                }
                call.result?.let {
                    writer.write(if (it.isError) "Tool error:\n\n" else "Tool result:\n\n")
                    writeLiteralBlock(writer, it.content)
                    if (it.truncated) writer.write("The tool result was limited in size.\n\n")
                }
            }
        }
        if (run.sources.isNotEmpty()) {
            writer.write("### Actual sources\n\n")
            for (source in run.sources) {
                currentCoroutineContext().ensureActive()
                writer.write("${source.kind.name} ")
                writer.write(source.id.replace('\n', ' ').replace('\r', ' '))
                writer.write("\n\n")
                writeLiteralBlock(writer, source.title)
                writeLiteralBlock(writer, source.url)
                source.excerpt?.let { writeLiteralBlock(writer, it) }
                val destination = agentSourceDestination(source.url)
                if (isAgentSourceId(source.id) && destination != null) {
                    writer.write("[${source.id}](<$destination>)\n\n")
                }
            }
        }
    }

    private suspend fun writeLiteralBlock(writer: OutputStreamWriter, text: String) {
        for (line in text.lineSequence()) {
            currentCoroutineContext().ensureActive()
            writer.write("    ")
            var offset = 0
            while (offset < line.length) {
                currentCoroutineContext().ensureActive()
                val length = minOf(8_192, line.length - offset)
                writer.write(line, offset, length)
                offset += length
            }
            writer.write("\n")
        }
        writer.write("\n")
    }

    private fun directory(): File {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create export directory")
        return root
    }

    private fun existingFile(name: String): File {
        if (name.isBlank() || name != File(name).name || !name.endsWith(".md")) {
            throw IllegalArgumentException("Invalid export name")
        }
        val directory = directory().canonicalFile
        val file = File(directory, name)
        if (file.canonicalFile.parentFile != directory || Files.isSymbolicLink(file.toPath())) {
            throw IllegalArgumentException("Export is outside the allowed directory")
        }
        if (!file.isFile) throw IOException("Export no longer exists")
        return file
    }

    companion object {
        const val EXPORT_BYTE_LIMIT = 64L * 1_024 * 1_024
        const val PREVIEW_CHAR_LIMIT = 8_192
    }
}

internal class SizeLimitedOutputStream(
    output: OutputStream,
    private val limit: Long
) : FilterOutputStream(output) {
    private var written = 0L

    override fun write(value: Int) {
        if (written >= limit) throw ExportTooLargeException()
        out.write(value)
        written++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (length.toLong() > limit - written) throw ExportTooLargeException()
        out.write(bytes, offset, length)
        written += length
    }
}
