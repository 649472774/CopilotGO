package com.tongxie.copilotgo.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.networknt.schema.InputFormat
import com.networknt.schema.OutputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Pure library execution: no account, storage, UI mutation, or network fixture is needed. */
@RunWith(AndroidJUnit4::class)
class ToolDependencyInstrumentedTest {
    @Test
    fun bundledMetaschemasAndBothDialectsExecuteOnAndroid() {
        val trustedMetaIds = setOf(
            "http://json-schema.org/draft-07/schema",
            "https://json-schema.org/draft/2020-12/schema"
        ) + listOf(
            "core", "applicator", "unevaluated", "validation", "meta-data", "format-annotation", "content"
        ).map { "https://json-schema.org/draft/2020-12/meta/$it" }
        val metaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
            builder.schemaLoader { loader ->
                loader.fetchRemoteResources(false).allow { it.toString() in trustedMetaIds }
            }
        }
        listOf(SpecificationVersion.DRAFT_7, SpecificationVersion.DRAFT_2020_12).forEach { draft ->
            val schemaJson = """{"type":"object","required":["count"],"properties":{"count":{"type":"integer","minimum":1}}}"""
            val meta = metaRegistry.getSchema(SchemaLocation.of(draft.dialectId))
            meta.initializeValidators()
            assertTrue(meta.validate(schemaJson, InputFormat.JSON, OutputFormat.BOOLEAN))
            assertFalse(meta.validate("""{"required":"count"}""", InputFormat.JSON, OutputFormat.BOOLEAN))
            val registry = SchemaRegistry.withDefaultDialect(draft) { builder ->
                builder.schemaLoader { loader -> loader.fetchRemoteResources(false).block { true } }
            }
            val schema = registry.getSchema(schemaJson)
            schema.initializeValidators()
            assertTrue(schema.validate("""{"count":2}""", InputFormat.JSON, OutputFormat.BOOLEAN))
            assertFalse(schema.validate("""{"count":"2"}""", InputFormat.JSON, OutputFormat.BOOLEAN))
        }
    }

    @Test
    fun localReferencesWorkButExternalReferencesCannotInitialize() {
        val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
            builder.schemaLoader { loader -> loader.fetchRemoteResources(false).block { true } }
        }
        val local = registry.getSchema(
            """{"${'$'}defs":{"count":{"type":"integer"}},"${'$'}ref":"#/${'$'}defs/count"}"""
        )
        local.initializeValidators()
        assertTrue(local.validate("2", InputFormat.JSON, OutputFormat.BOOLEAN))
        assertFalse(local.validate("\"2\"", InputFormat.JSON, OutputFormat.BOOLEAN))
        val external = registry.getSchema("""{"${'$'}ref":"https://example.invalid/schema"}""")
        assertThrows(RuntimeException::class.java) { external.initializeValidators() }
    }

    @Test
    fun boundedHtmlParserHandlesUnicodeAndMalformedMarkupOnAndroid() {
        val document = Jsoup.parse(
            """<title>Fixture &amp; parser</title><p>&#x4E2D;&#x6587;<a href="https://example.invalid/"> link</a><script>notVisible()</script>""",
            "",
            Parser.htmlParser().setMaxDepth(128).setTrackErrors(0)
        )
        assertEquals("Fixture & parser", document.title())
        assertEquals("\u4E2D\u6587 link", document.body().text())
        assertEquals("https://example.invalid/", document.selectFirst("a")?.attr("href"))
    }
}
