package com.tongxie.copilotgo.integration

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

class ToolDependencyCompatibilityTest {
    private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
        builder.schemaLoader { loader -> loader.fetchRemoteResources(false).block { true } }
    }

    @Test
    fun draft07IsRecognizedWithoutChangingTheDefaultDialect() {
        val schema = registry.getSchema(
            """{"${'$'}schema":"http://json-schema.org/draft-07/schema#","type":"object","required":["count"],"properties":{"count":{"type":"integer","minimum":1}},"additionalProperties":false}"""
        )
        assertTrue(schema.validate("""{"count":3}""", InputFormat.JSON, OutputFormat.BOOLEAN))
        assertFalse(schema.validate("""{"count":"3"}""", InputFormat.JSON, OutputFormat.BOOLEAN))
        assertFalse(schema.validate("""{"count":0}""", InputFormat.JSON, OutputFormat.BOOLEAN))
    }

    @Test
    fun draft202012EnforcesPrefixItemsAndClosedAdditionalItems() {
        val schema = registry.getSchema(
            """{"${'$'}schema":"https://json-schema.org/draft/2020-12/schema","type":"array","prefixItems":[{"type":"integer"}],"items":false}"""
        )
        assertTrue(schema.validate("[1]", InputFormat.JSON, OutputFormat.BOOLEAN))
        assertFalse(schema.validate("""["1"]""", InputFormat.JSON, OutputFormat.BOOLEAN))
        assertFalse(schema.validate("[1,2]", InputFormat.JSON, OutputFormat.BOOLEAN))
    }

    @Test
    fun localDefinitionsWorkWhenResourceLoadingIsBlocked() {
        val schema = registry.getSchema(
            """{"${'$'}defs":{"count":{"type":"integer"}},"type":"object","properties":{"count":{"${'$'}ref":"#/${'$'}defs/count"}}}"""
        )
        assertTrue(schema.validate("""{"count":3}""", InputFormat.JSON, OutputFormat.BOOLEAN))
        assertFalse(schema.validate("""{"count":"3"}""", InputFormat.JSON, OutputFormat.BOOLEAN))
    }

    @Test
    fun bundledMetaschemasValidateSchemaStructureWithoutRemoteLoading() {
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
            val meta = metaRegistry.getSchema(SchemaLocation.of(draft.dialectId))
            meta.initializeValidators()
            assertTrue(meta.validate("""{"type":"object","required":["count"]}""", InputFormat.JSON, OutputFormat.BOOLEAN))
            assertFalse(meta.validate("""{"type":"object","required":"count"}""", InputFormat.JSON, OutputFormat.BOOLEAN))
            assertFalse(meta.validate("""{"properties":{"count":{"type":17}}}""", InputFormat.JSON, OutputFormat.BOOLEAN))
        }
    }

    @Test
    fun explicitInitializationRejectsBlockedExternalReferences() {
        val schema = registry.getSchema("""{"${'$'}ref":"https://example.invalid/schema"}""")
        assertThrows(RuntimeException::class.java) { schema.initializeValidators() }
    }

    @Test
    fun boundedStringHtmlParsingDecodesEntitiesWithoutLoadingLinks() {
        val document = Jsoup.parse(
            """<title>Fixture &amp; parser</title><p>Body<a href="https://example.invalid/"> link</a><script>notVisible()</script>""",
            "",
            Parser.htmlParser().setMaxDepth(128).setTrackErrors(0)
        )
        assertEquals("Fixture & parser", document.title())
        assertEquals("Body link", document.body().text())
        assertEquals("https://example.invalid/", document.selectFirst("a")?.attr("href"))
    }
}
