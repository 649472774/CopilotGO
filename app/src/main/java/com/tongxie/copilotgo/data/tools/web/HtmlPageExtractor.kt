package com.tongxie.copilotgo.data.tools.web

import com.tongxie.copilotgo.data.tools.ToolProblemCode
import com.tongxie.copilotgo.data.tools.boundToolText
import com.tongxie.copilotgo.data.tools.toolFailure
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.UnsupportedCharsetException

internal data class ExtractedPage(val title: String, val text: String, val truncated: Boolean)

internal object HtmlPageExtractor {
    fun extract(bytes: ByteArray, contentType: String?, sourceUrl: String): ExtractedPage {
        validateContentType(contentType)
        if (bytes.size > MAX_DOCUMENT_BYTES) toolFailure(ToolProblemCode.TOO_LARGE, "网页正文超过 1 MiB 大小限制")
        if (bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))) {
            toolFailure(ToolProblemCode.UNSUPPORTED_CONTENT, "PDF 网页暂不支持，未将二进制文档作为正文解码")
        }
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()
        val charset = declaredCharset(contentType)
        if (type == "text/plain") {
            val text = decode(bytes, charset ?: Charsets.UTF_8)
            if (text.any { it == '\u0000' }) toolFailure(ToolProblemCode.UNSUPPORTED_CONTENT, "网页返回了二进制内容而非纯文本")
            val clipped = boundToolText(text.trim(), MAX_TEXT_BYTES)
            if (clipped.text.isBlank()) toolFailure(ToolProblemCode.UNSUPPORTED_CONTENT, "网页没有可读取的文本")
            return ExtractedPage(sourceUrl, clipped.text, clipped.truncated)
        }
        if (bytes.count { it == '<'.code.toByte() } > MAX_MARKUP_TOKENS) {
            toolFailure(ToolProblemCode.TOO_LARGE, "网页结构过于复杂，已停止解析")
        }
        val parser = Parser.htmlParser().setMaxDepth(128).setTrackErrors(8)
        val document = Jsoup.parse(ByteArrayInputStream(bytes), charset?.name(), sourceUrl, parser)
        val title = document.title().trim().ifBlank { document.selectFirst("h1")?.text().orEmpty() }
            .ifBlank { sourceUrl }.take(240)
        document.select(
            "script,style,noscript,template,svg,canvas,iframe,object,embed,form,nav,header,footer,aside," +
                "[hidden],[aria-hidden=true]"
        ).remove()
        val main = document.selectFirst("main") ?: document.selectFirst("article")
            ?: document.selectFirst("[role=main]") ?: document.body()
        val collected = StringBuilder()
        var collectionLimited = false
        fun append(value: String) {
            val remaining = MAX_TEXT_BYTES - collected.length
            if (value.length > remaining) collectionLimited = true
            if (remaining > 0) collected.append(value.take(remaining))
        }
        fun separator() {
            if (collected.isNotEmpty() && collected.last() != '\n') append("\n")
        }
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when (node) {
                    is TextNode -> append(node.wholeText)
                    is Element -> if (node.isBlock || node.normalName() == "br") separator()
                }
            }
            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.isBlock) separator()
            }
        }, main)
        val text = collected.toString().lineSequence()
            .map { it.trimEnd() }.filter { it.isNotBlank() }.joinToString("\n").trim()
        if (text.isBlank()) toolFailure(ToolProblemCode.UNSUPPORTED_CONTENT, "网页没有可读取的正文，可能需要登录或执行脚本")
        val clipped = boundToolText(text, MAX_TEXT_BYTES)
        val depthLimited = parser.errors.any { it.errorMessage.contains("depth", ignoreCase = true) }
        return ExtractedPage(title, clipped.text, clipped.truncated || depthLimited || collectionLimited)
    }

    fun validateContentType(contentType: String?) {
        if (contentType?.substringBefore(';')?.trim()?.lowercase() !in
            setOf("text/html", "application/xhtml+xml", "text/plain")
        ) {
            toolFailure(
                ToolProblemCode.UNSUPPORTED_CONTENT,
                "网页读取仅支持 HTML 和纯文本；PDF、图片、音视频及其他二进制内容暂不支持"
            )
        }
        declaredCharset(contentType)
    }

    private fun declaredCharset(contentType: String?): Charset? {
        val declarations = contentType.orEmpty().split(';').drop(1).map { it.trim() }
            .filter { it.substringBefore('=').trim().equals("charset", true) }
        if (declarations.size > 1) toolFailure(ToolProblemCode.UNSUPPORTED_CONTENT, "网页声明了重复字符编码")
        val declaration = declarations.singleOrNull() ?: return null
        val name = declaration.substringAfter('=', "").trim().removeSurrounding("\"")
        if (name.isBlank() || name.length > 64) toolFailure(ToolProblemCode.UNSUPPORTED_CONTENT, "网页字符编码声明无效")
        return try {
            Charset.forName(name)
        } catch (_: IllegalCharsetNameException) {
            toolFailure(ToolProblemCode.UNSUPPORTED_CONTENT, "网页使用了尚未支持的字符编码")
        } catch (_: UnsupportedCharsetException) {
            toolFailure(ToolProblemCode.UNSUPPORTED_CONTENT, "网页使用了尚未支持的字符编码")
        }
    }

    private fun decode(bytes: ByteArray, charset: Charset): String = try {
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    } catch (_: CharacterCodingException) {
        toolFailure(ToolProblemCode.UNSUPPORTED_CONTENT, "网页文本的字符编码无效")
    }

    const val MAX_DOCUMENT_BYTES = 1024 * 1024
    private const val MAX_MARKUP_TOKENS = 20_000
    private const val MAX_TEXT_BYTES = 32 * 1024
}
