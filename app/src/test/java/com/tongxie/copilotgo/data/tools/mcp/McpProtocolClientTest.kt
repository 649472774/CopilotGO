package com.tongxie.copilotgo.data.tools.mcp

import com.tongxie.copilotgo.BuildConfig
import com.tongxie.copilotgo.data.tools.McpAuthMode
import com.tongxie.copilotgo.data.tools.ToolException
import com.tongxie.copilotgo.data.tools.ToolProblemCode
import com.tongxie.copilotgo.data.tools.mcp.McpProtocolFixture.Companion.AUTH_HEADER
import com.tongxie.copilotgo.data.tools.mcp.McpProtocolFixture.Companion.CONFIGURATION_ID
import com.tongxie.copilotgo.data.tools.mcp.McpProtocolFixture.Companion.FAKE_KEY
import com.tongxie.copilotgo.data.tools.mcp.McpProtocolFixture.Companion.LEGACY_VERSION
import com.tongxie.copilotgo.data.tools.mcp.McpProtocolFixture.Companion.MODERN_VERSION
import com.tongxie.copilotgo.data.tools.mcp.McpProtocolFixture.Companion.REMOTE_DIAGNOSTIC
import com.tongxie.copilotgo.data.tools.mcp.McpProtocolFixture.Companion.TOOL_NAME
import com.tongxie.copilotgo.data.tools.mcp.McpProtocolFixture.Companion.arguments
import com.tongxie.copilotgo.data.tools.mcp.McpProtocolFixture.Companion.event
import com.tongxie.copilotgo.data.tools.mcp.McpProtocolFixture.Companion.listing
import com.tongxie.copilotgo.data.tools.mcp.McpProtocolFixture.Companion.querySchema
import com.tongxie.copilotgo.data.tools.mcp.McpProtocolFixture.Companion.stream
import com.tongxie.copilotgo.data.tools.mcp.McpProtocolFixture.Companion.textResult
import com.tongxie.copilotgo.data.tools.mcp.McpProtocolFixture.Companion.tool
import com.tongxie.copilotgo.data.tools.mcp.McpProtocolFixture.Era
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class McpProtocolClientTest {
    @Test(timeout = 20_000)
    fun modernDiscoveryAndCallSendMetadataAndEncodedRoutingHeadersWithoutInitialization() = protocolTest {
        McpProtocolFixture(authMode = McpAuthMode.CUSTOM_HEADER, credential = FAKE_KEY).use { fixture ->
            val name = "搜索_工具"
            val query = "  synthetic 世界  "
            fixture.tools = listOf(tool(name, input = querySchema(header = "Query")))

            val catalog = fixture.protocol.discover(fixture.lease)
            assertEquals(MODERN_VERSION, catalog.connection.version)
            assertNull(catalog.connection.sessionId)
            assertEquals(fixture.serverName, catalog.connection.serverName)
            assertEquals(listOf(name), catalog.tools.map { it.name })
            assertSame(catalog, fixture.protocol.catalog(CONFIGURATION_ID, 1))
            assertNull(fixture.protocol.catalog(CONFIGURATION_ID, 2))
            assertEquals(0, fixture.callCount.get())
            assertEquals(0, fixture.mutationCount.get())
            assertTraffic(fixture, "server/discover", "tools/list")

            val content = fixture.protocol.call(fixture.lease, catalog.tools.single(), arguments(query))

            assertEquals("Fixture mutation 1", content.text)
            assertFalse(content.isError)
            assertFalse(content.truncated)
            assertTrue(content.sources.isEmpty())
            assertEquals(1, fixture.mutationCount.get())
            assertEquals(0, fixture.initializationCount.get())
            assertTraffic(fixture, "server/discover", "tools/list", "tools/call")
            fixture.requests.forEach { request ->
                assertEquals(MODERN_VERSION, request.http.getHeader("MCP-Protocol-Version"))
                assertEquals(request.method, request.http.getHeader("Mcp-Method"))
                assertEquals(FAKE_KEY, request.http.getHeader(AUTH_HEADER))
                assertNull(request.http.getHeader("Authorization"))
                assertNull(request.http.getHeader("Mcp-Session-Id"))
                assertEquals("application/json, text/event-stream", request.http.getHeader("Accept"))
                assertEquals(JsonPrimitive(MODERN_VERSION), request.meta["io.modelcontextprotocol/protocolVersion"])
                assertEquals(JsonObject(emptyMap()), request.meta["io.modelcontextprotocol/clientCapabilities"])
                val clientInfo = request.meta["io.modelcontextprotocol/clientInfo"] as JsonObject
                assertEquals(JsonPrimitive("CopilotGO"), clientInfo["name"])
                assertEquals(JsonPrimitive(BuildConfig.VERSION_NAME), clientInfo["version"])
                assertEquals(request.id, request.meta["progressToken"])
            }
            fixture.requests.take(2).forEach { assertNull(it.http.getHeader("Mcp-Name")) }
            val call = fixture.requestsFor("tools/call").single()
            assertEquals(encodedHeader(name), call.http.getHeader("Mcp-Name"))
            assertEquals(encodedHeader(query), call.http.getHeader("Mcp-Param-Query"))
            assertEquals(JsonPrimitive(name), call.params["name"])
            assertEquals(arguments(query), call.params["arguments"])
        }
    }

    @Test(timeout = 20_000)
    fun modernModeNeverAdoptsAnAdvertisedSessionOrOpensAGetStream() = protocolTest {
        McpProtocolFixture().use { fixture ->
            fixture.handler = { request ->
                if (request.method == "server/discover") {
                    fixture.reply(request, fixture.discoveryResult())
                        .setHeader("Mcp-Session-Id", "ignored-modern-session")
                } else {
                    null
                }
            }
            val catalog = fixture.protocol.discover(fixture.lease)
            fixture.protocol.call(fixture.lease, catalog.tools.single(), arguments())

            assertNull(catalog.connection.sessionId)
            assertTraffic(fixture, "server/discover", "tools/list", "tools/call")
            fixture.requests.forEach { assertNull(it.http.getHeader("Mcp-Session-Id")) }
            assertEquals(0, fixture.initializationCount.get())
            assertEquals(1, fixture.callCount.get())
        }
    }

    @Test(timeout = 20_000)
    fun aServerWithoutToolsCapabilityIsCachedWithoutListingOrCallingTools() = protocolTest {
        Era.entries.forEach { era ->
            McpProtocolFixture(era).use { fixture ->
                fixture.hasTools = false
                val catalog = fixture.protocol.discover(fixture.lease)

                assertFalse(catalog.connection.hasTools)
                assertTrue(catalog.tools.isEmpty())
                assertTrue(catalog.rejected.isEmpty())
                assertSame(catalog, fixture.protocol.catalog(CONFIGURATION_ID, 1))
                assertTrue(fixture.requestsFor("tools/list").isEmpty())
                assertEquals(0, fixture.callCount.get())
                assertEquals(0, fixture.mutationCount.get())
                if (era == Era.MODERN) {
                    assertTraffic(fixture, "server/discover")
                } else {
                    assertTraffic(fixture, "server/discover", "initialize", "notifications/initialized")
                }
            }
        }
    }

    @Test(timeout = 20_000)
    fun exaShapedNotInitializedProbeNegotiatesAllSupportedLegacyVersionsAndPropagatesSession() = protocolTest {
        listOf("2025-11-25", "2025-06-18", "2025-03-26").forEach { version ->
            McpProtocolFixture(Era.LEGACY, negotiatedVersion = version).use { fixture ->
                val catalog = fixture.protocol.discover(fixture.lease)
                assertEquals(version, catalog.connection.version)
                assertEquals("fixture-session-1", catalog.connection.sessionId)
                assertEquals(0, fixture.mutationCount.get())

                val result = fixture.protocol.call(fixture.lease, catalog.tools.single(), arguments())

                assertEquals("Fixture mutation 1", result.text)
                assertTraffic(
                    fixture, "server/discover", "initialize", "notifications/initialized", "tools/list", "tools/call"
                )
                val probe = fixture.requests.first()
                assertEquals(MODERN_VERSION, probe.http.getHeader("MCP-Protocol-Version"))
                assertEquals("server/discover", probe.http.getHeader("Mcp-Method"))
                val initialize = fixture.requestsFor("initialize").single()
                assertEquals(JsonPrimitive(LEGACY_VERSION), initialize.params["protocolVersion"])
                assertEquals(JsonObject(emptyMap()), initialize.params["capabilities"])
                assertEquals(JsonPrimitive("CopilotGO"), (initialize.params["clientInfo"] as JsonObject)["name"])
                assertNull(initialize.http.getHeader("Mcp-Session-Id"))
                assertEquals(LEGACY_VERSION, initialize.http.getHeader("MCP-Protocol-Version"))
                val initialized = fixture.requestsFor("notifications/initialized").single()
                assertNull(initialized.id)
                assertFalse("params" in initialized.body)
                fixture.requests.drop(1).forEach { request ->
                    assertNull(request.http.getHeader("Mcp-Method"))
                    assertNull(request.http.getHeader("Mcp-Name"))
                    assertFalse("io.modelcontextprotocol/protocolVersion" in request.meta)
                    assertFalse("io.modelcontextprotocol/clientCapabilities" in request.meta)
                }
                fixture.requests.drop(2).forEach { request ->
                    assertEquals(version, request.http.getHeader("MCP-Protocol-Version"))
                    assertEquals("fixture-session-1", request.http.getHeader("Mcp-Session-Id"))
                }
            }
        }
    }

    @Test(timeout = 20_000)
    fun legacyServerMayOmitSessionHeader() = protocolTest {
        McpProtocolFixture(Era.LEGACY, issueSession = false).use { fixture ->
            val catalog = fixture.protocol.discover(fixture.lease)
            assertNull(catalog.connection.sessionId)
            val content = fixture.protocol.call(fixture.lease, catalog.tools.single(), arguments())

            assertFalse(content.isError)
            assertEquals(1, fixture.callCount.get())
            assertTraffic(
                fixture, "server/discover", "initialize", "notifications/initialized", "tools/list", "tools/call"
            )
            fixture.requests.forEach { assertNull(it.http.getHeader("Mcp-Session-Id")) }
        }
    }

    @Test(timeout = 20_000)
    fun recognizedVersionNegotiationSelectsNewestMutuallySupportedLegacyVersion() = protocolTest {
        McpProtocolFixture(Era.LEGACY, negotiatedVersion = "2025-06-18").use { fixture ->
            fixture.handler = { request ->
                if (request.method == "server/discover") {
                    request.error(-32022, supported = listOf("2025-03-26", "2025-06-18"))
                } else {
                    null
                }
            }
            val catalog = fixture.protocol.discover(fixture.lease)

            assertEquals("2025-06-18", catalog.connection.version)
            assertEquals(
                JsonPrimitive("2025-06-18"),
                fixture.requestsFor("initialize").single().params["protocolVersion"]
            )
            assertTraffic(fixture, "server/discover", "initialize", "notifications/initialized", "tools/list")
            assertEquals(0, fixture.callCount.get())
        }
    }

    @Test(timeout = 20_000)
    fun unsupportedOrContradictoryVersionNegotiationDoesNotFallBack() = protocolTest {
        listOf(
            listOf("2024-01-01"),
            listOf(MODERN_VERSION),
            listOf(MODERN_VERSION, LEGACY_VERSION),
            emptyList()
        ).forEach { versions ->
            McpProtocolFixture().use { fixture ->
                fixture.handler = { it.error(-32022, supported = versions) }
                rejected(ToolProblemCode.PROTOCOL) { fixture.protocol.discover(fixture.lease) }

                assertTraffic(fixture, "server/discover")
                assertUncached(fixture)
                assertEquals(0, fixture.initializationCount.get())
                assertEquals(0, fixture.callCount.get())
            }
        }
    }

    @Test(timeout = 20_000)
    fun modernHeaderAndCapabilityErrorsDoNotSilentlyDowngradeEvenOnHttp400() = protocolTest {
        mapOf(
            -32020 to ToolProblemCode.PROTOCOL,
            -32021 to ToolProblemCode.UNSUPPORTED_INTERACTION,
            -32042 to ToolProblemCode.UNSUPPORTED_INTERACTION
        ).forEach { (code, expected) ->
            McpProtocolFixture().use { fixture ->
                fixture.handler = { it.error(code, status = 400, message = "$REMOTE_DIAGNOSTIC $FAKE_KEY") }
                val failure = rejected(expected) { fixture.protocol.discover(fixture.lease) }

                assertEquals(400, failure.problem.httpStatus)
                assertTraffic(fixture, "server/discover")
                assertUncached(fixture)
                assertEquals(0, fixture.initializationCount.get())
            }
        }
    }

    @Test(timeout = 20_000)
    fun discoveryRejectsWrongStringNumericNullAndMissingResponseIds() = protocolTest {
        listOf<JsonElement?>(JsonPrimitive("foreign-request"), JsonPrimitive(7), JsonNull, null).forEach { id ->
            McpProtocolFixture().use { fixture ->
                fixture.handler = { it.json(fixture.complete(fixture.discoveryResult()), responseId = id) }
                rejected(ToolProblemCode.PROTOCOL) { fixture.protocol.discover(fixture.lease) }

                assertTraffic(fixture, "server/discover")
                assertUncached(fixture)
            }
        }
    }

    @Test(timeout = 20_000)
    fun notInitializedFallbackDoesNotExcuseAForeignNonNullResponseId() = protocolTest {
        McpProtocolFixture(Era.LEGACY).use { fixture ->
            fixture.handler = { it.error(-32000, responseId = JsonPrimitive("another-client")) }
            rejected(ToolProblemCode.PROTOCOL) { fixture.protocol.discover(fixture.lease) }

            assertTraffic(fixture, "server/discover")
            assertEquals(0, fixture.initializationCount.get())
            assertUncached(fixture)
        }
    }

    @Test(timeout = 20_000)
    fun modernDiscoveryRequiresCompleteTypeMatchingVersionAndCapabilityObject() = protocolTest {
        val malformed: List<(McpProtocolFixture) -> JsonObject> = listOf(
            { it.discoveryResult() },
            { JsonObject(it.complete(it.discoveryResult()) - "capabilities") },
            { JsonObject(it.complete(it.discoveryResult()) + ("capabilities" to JsonPrimitive(true))) },
            { JsonObject(it.complete(it.discoveryResult()) + ("supportedVersions" to JsonArray(emptyList()))) },
            {
                JsonObject(it.complete(it.discoveryResult()) + (
                    "supportedVersions" to JsonArray(listOf(JsonPrimitive(LEGACY_VERSION)))
                ))
            }
        )
        malformed.forEach { response ->
            McpProtocolFixture().use { fixture ->
                fixture.handler = { it.json(response(fixture)) }
                rejected(ToolProblemCode.PROTOCOL) { fixture.protocol.discover(fixture.lease) }

                assertTraffic(fixture, "server/discover")
                assertEquals(0, fixture.initializationCount.get())
                assertUncached(fixture)
            }
        }
    }

    @Test(timeout = 20_000)
    fun initializedNotificationRequiresAnEmpty202Acknowledgement() = protocolTest {
        listOf(200, 202).forEach { status ->
            McpProtocolFixture(Era.LEGACY).use { fixture ->
                fixture.handler = { request ->
                    if (request.method == "notifications/initialized") {
                        MockResponse().setResponseCode(status)
                            .setHeader("Content-Type", "application/json")
                            .setBody(REMOTE_DIAGNOSTIC)
                    } else {
                        null
                    }
                }
                rejected(ToolProblemCode.PROTOCOL) { fixture.protocol.discover(fixture.lease) }

                assertTraffic(fixture, "server/discover", "initialize", "notifications/initialized")
                assertUncached(fixture)
            }
        }
    }

    @Test(timeout = 20_000)
    fun discoveryFollowsOpaqueCursorsWithoutExecutingAnyDiscoveredTool() = protocolTest {
        McpProtocolFixture().use { fixture ->
            val cursor = JsonPrimitive("opaque/下一页?value=a+b==")
            fixture.handler = { request ->
                if (request.method == "tools/list") {
                    val page = if (fixture.requestsFor("tools/list").size == 1) {
                        listing(listOf(tool("first")), cursor)
                    } else {
                        listing(listOf(tool("second")))
                    }
                    fixture.reply(request, page)
                } else {
                    null
                }
            }
            val catalog = fixture.protocol.discover(fixture.lease)

            assertEquals(listOf("first", "second"), catalog.tools.map { it.name })
            assertTrue(catalog.rejected.isEmpty())
            assertEquals(0, fixture.callCount.get())
            assertEquals(0, fixture.mutationCount.get())
            assertTraffic(fixture, "server/discover", "tools/list", "tools/list")
            val pages = fixture.requestsFor("tools/list")
            assertFalse("cursor" in pages.first().params)
            assertEquals(cursor, pages.last().params["cursor"])
        }
    }

    @Test(timeout = 20_000)
    fun duplicatesAcrossPagesAndUnsupportedSchemasAreRejectedIndividually() = protocolTest {
        McpProtocolFixture().use { fixture ->
            val unsupportedSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("pattern", ".*")
                    }
                }
            }
            fixture.handler = { request ->
                if (request.method == "tools/list") {
                    val page = if (fixture.requestsFor("tools/list").size == 1) {
                        listing(
                            listOf(tool("duplicate"), tool("unsupported_schema", input = unsupportedSchema)),
                            JsonPrimitive("page-2")
                        )
                    } else {
                        listing(listOf(tool("duplicate", description = "Changed duplicate"), tool("usable")))
                    }
                    fixture.reply(request, page)
                } else {
                    null
                }
            }
            val catalog = fixture.protocol.discover(fixture.lease)

            assertEquals(listOf("usable"), catalog.tools.map { it.name })
            assertEquals(setOf("duplicate", "unsupported_schema"), catalog.rejected.map { it.name }.toSet())
            assertEquals(2, catalog.rejected.size)
            catalog.rejected.forEach {
                assertEquals(ToolProblemCode.SCHEMA, it.problem.code)
                assertTrue(it.problem.message.isNotBlank())
            }
            assertEquals(0, fixture.callCount.get())
            assertTraffic(fixture, "server/discover", "tools/list", "tools/list")
        }
    }

    @Test(timeout = 20_000)
    fun repeatedCursorFailsWithoutCachingPartialDiscovery() = protocolTest {
        McpProtocolFixture().use { fixture ->
            fixture.handler = { request ->
                if (request.method == "tools/list") {
                    fixture.reply(request, listing(emptyList(), JsonPrimitive("same-page")))
                } else {
                    null
                }
            }
            rejected(ToolProblemCode.PROTOCOL) { fixture.protocol.discover(fixture.lease) }

            assertTraffic(fixture, "server/discover", "tools/list", "tools/list")
            assertEquals(0, fixture.callCount.get())
            assertUncached(fixture)
        }
    }

    @Test(timeout = 20_000)
    fun emptyOversizedNonStringAndNullCursorsAreRejectedBeforeAnotherPage() = protocolTest {
        listOf(JsonPrimitive(""), JsonPrimitive("x".repeat(2049)), JsonPrimitive(17), JsonNull).forEach { cursor ->
            McpProtocolFixture().use { fixture ->
                fixture.handler = { request ->
                    if (request.method == "tools/list") {
                        fixture.reply(request, listing(emptyList(), cursor))
                    } else {
                        null
                    }
                }
                rejected(ToolProblemCode.PROTOCOL) { fixture.protocol.discover(fixture.lease) }

                assertTraffic(fixture, "server/discover", "tools/list")
                assertUncached(fixture)
            }
        }
    }

    @Test(timeout = 20_000)
    fun eightDiscoveryPagesAreAllowedButANinthIsNeverRequested() = protocolTest {
        listOf(false, true).forEach { overflow ->
            McpProtocolFixture().use { fixture ->
                fixture.handler = { request ->
                    if (request.method == "tools/list") {
                        val page = fixture.requestsFor("tools/list").size
                        val cursor = if (!overflow && page == 8) null else JsonPrimitive("page-$page")
                        fixture.reply(request, listing(emptyList(), cursor))
                    } else {
                        null
                    }
                }
                if (overflow) {
                    rejected(ToolProblemCode.TOO_LARGE) { fixture.protocol.discover(fixture.lease) }
                    assertUncached(fixture)
                } else {
                    assertTrue(fixture.protocol.discover(fixture.lease).tools.isEmpty())
                    assertNotNull(fixture.protocol.catalog(CONFIGURATION_ID, 1))
                }
                assertEquals(8, fixture.requestsFor("tools/list").size)
                assertEquals(9, fixture.requests.size)
                assertEquals(0, fixture.callCount.get())
            }
        }
    }

    @Test(timeout = 20_000)
    fun discoveryToolLimitIsEnforcedAcrossPagesAsWellAsWithinOnePage() = protocolTest {
        listOf(listOf(128), listOf(129), listOf(64, 65)).forEach { pageSizes ->
            McpProtocolFixture().use { fixture ->
                fixture.handler = { request ->
                    if (request.method == "tools/list") {
                        val page = fixture.requestsFor("tools/list").size - 1
                        val definitions = List(pageSizes[page]) { tool("page_${page}_tool_$it") }
                        val cursor = if (page < pageSizes.lastIndex) JsonPrimitive("next-$page") else null
                        fixture.reply(request, listing(definitions, cursor))
                    } else {
                        null
                    }
                }
                if (pageSizes.sum() == 128) {
                    assertEquals(128, fixture.protocol.discover(fixture.lease).tools.size)
                } else {
                    rejected(ToolProblemCode.TOO_LARGE) { fixture.protocol.discover(fixture.lease) }
                    assertUncached(fixture)
                }
                assertEquals(pageSizes.size, fixture.requestsFor("tools/list").size)
                assertEquals(0, fixture.callCount.get())
                assertEquals(0, fixture.mutationCount.get())
            }
        }
    }

    @Test(timeout = 20_000)
    fun discoveredDraft07QuerySchemaRejectsBadArgumentsBeforeAnyToolsCall() = protocolTest {
        McpProtocolFixture(Era.LEGACY, negotiatedVersion = "2025-03-26").use { fixture ->
            fixture.tools = listOf(tool(input = querySchema(draft07 = true)))
            val catalog = fixture.protocol.discover(fixture.lease)
            val discovered = catalog.tools.single()
            assertTrue(catalog.rejected.isEmpty())
            assertEquals(querySchema(draft07 = true), discovered.input.definition)
            val invalid = listOf(
                arguments(""),
                JsonObject(emptyMap()),
                buildJsonObject { put("query", 42) },
                buildJsonObject { put("query", JsonNull) },
                buildJsonObject { put("query", "valid"); put("unexpected", true) }
            )
            invalid.forEach { args ->
                rejected(ToolProblemCode.SCHEMA) { fixture.protocol.call(fixture.lease, discovered, args) }
                assertEquals(0, fixture.callCount.get())
                assertEquals(0, fixture.mutationCount.get())
            }

            fixture.protocol.call(fixture.lease, discovered, arguments("synthetic Exa-shaped query"))

            assertEquals(1, fixture.callCount.get())
            assertEquals(1, fixture.mutationCount.get())
            val call = fixture.requestsFor("tools/call").single()
            assertEquals(JsonPrimitive(TOOL_NAME), call.params["name"])
            assertEquals(arguments("synthetic Exa-shaped query"), call.params["arguments"])
        }
    }

    @Test(timeout = 20_000)
    fun aForeignToolsListResponseCannotPopulateTheCatalog() = protocolTest {
        McpProtocolFixture(Era.LEGACY).use { fixture ->
            fixture.handler = { request ->
                if (request.method == "tools/list") {
                    request.json(listing(fixture.tools), responseId = JsonPrimitive("another-list"))
                } else {
                    null
                }
            }
            rejected(ToolProblemCode.PROTOCOL) { fixture.protocol.discover(fixture.lease) }

            assertUncached(fixture)
            assertEquals(0, fixture.callCount.get())
            assertTraffic(fixture, "server/discover", "initialize", "notifications/initialized", "tools/list")
        }
    }

    @Test(timeout = 20_000)
    fun toolsCallRejectsWrongStringNumericBooleanNullAndMissingIdsWithoutReplay() = protocolTest {
        val wrongIds = listOf<JsonElement?>(
            JsonPrimitive("another-call"), JsonPrimitive(1), JsonPrimitive(true), JsonNull, null
        )
        wrongIds.forEach { responseId ->
            McpProtocolFixture().use { fixture ->
                val catalog = fixture.protocol.discover(fixture.lease)
                fixture.handler = { request ->
                    if (request.method == "tools/call") {
                        request.json(fixture.complete(textResult(REMOTE_DIAGNOSTIC)), responseId)
                    } else {
                        null
                    }
                }
                rejected(ToolProblemCode.PROTOCOL) {
                    fixture.protocol.call(fixture.lease, catalog.tools.single(), arguments())
                }

                assertEquals(1, fixture.callCount.get())
                assertEquals(0, fixture.mutationCount.get())
                assertTraffic(fixture, "server/discover", "tools/list", "tools/call")
            }
        }
    }

    @Test(timeout = 20_000)
    fun legacyNullIdNotInitializedExceptionIsRestrictedToTheInitialProbe() = protocolTest {
        McpProtocolFixture(Era.LEGACY).use { fixture ->
            val catalog = fixture.protocol.discover(fixture.lease)
            fixture.handler = { request ->
                if (request.method == "tools/call") {
                    request.error(-32000, responseId = JsonNull, message = "not initialized")
                } else {
                    null
                }
            }
            rejected(ToolProblemCode.PROTOCOL) {
                fixture.protocol.call(fixture.lease, catalog.tools.single(), arguments())
            }

            assertEquals(1, fixture.initializationCount.get())
            assertEquals(1, fixture.callCount.get())
            assertTraffic(
                fixture, "server/discover", "initialize", "notifications/initialized", "tools/list", "tools/call"
            )
        }
    }

    @Test(timeout = 20_000)
    fun bothErasAcceptRequestScopedSseCommentsEmptyPrimingProgressAndCorrelatedFinalResults() = protocolTest {
        Era.entries.forEach { era ->
            McpProtocolFixture(era).use { fixture ->
                fixture.handler = { request ->
                    val prefix = ": fixture keep-alive\r\n\r\nevent: message\r\ndata:\r\n\r\n" +
                        event(request.progress())
                    when (request.method) {
                        "tools/list" -> fixture.sse(request, listing(fixture.tools), prefix)
                        "tools/call" -> fixture.sse(
                            request, fixture.mutationResult(), prefix,
                            suffix = event(serverRequest("roots/list", JsonPrimitive("after-completion")))
                        )
                        else -> null
                    }
                }
                val catalog = fixture.protocol.discover(fixture.lease)
                assertEquals(listOf(TOOL_NAME), catalog.tools.map { it.name })
                assertEquals(0, fixture.callCount.get())
                val result = fixture.protocol.call(fixture.lease, catalog.tools.single(), arguments())

                assertEquals("Fixture mutation 1", result.text)
                assertFalse(result.isError)
                assertEquals(1, fixture.callCount.get())
                assertEquals(1, fixture.mutationCount.get())
                assertTrue(fixture.requestsFor(null).isEmpty())
                if (era == Era.MODERN) {
                    assertTraffic(fixture, "server/discover", "tools/list", "tools/call")
                } else {
                    assertTraffic(
                        fixture, "server/discover", "initialize", "notifications/initialized", "tools/list", "tools/call"
                    )
                }
            }
        }
    }

    @Test(timeout = 20_000)
    fun malformedProgressErrorEventsAbruptEndAndForeignSseResultsFailWithoutReplay() = protocolTest {
        val invalidStreams: List<(McpFixtureRequest, McpProtocolFixture) -> String> = listOf(
            { request, _ -> event(request.progress(token = JsonPrimitive("another-progress-token"))) },
            { request, _ -> event(request.progress(token = JsonPrimitive(1))) },
            { request, _ -> event(request.progress(amount = JsonPrimitive("1"))) },
            { _, _ -> "event: error\ndata: $REMOTE_DIAGNOSTIC $FAKE_KEY\n\n" },
            { _, _ -> "event: error\n\n" },
            { request, _ -> ": stream closes without a final result\n\n" + event(request.progress()) },
            { request, fixture ->
                event(request.envelope(fixture.complete(textResult(REMOTE_DIAGNOSTIC)), JsonPrimitive("foreign"))) +
                    event(request.envelope(fixture.complete(textResult("must not skip the foreign result"))))
            }
        )
        invalidStreams.forEach { body ->
            McpProtocolFixture().use { fixture ->
                val catalog = fixture.protocol.discover(fixture.lease)
                fixture.handler = { request ->
                    if (request.method == "tools/call") stream(body(request, fixture)) else null
                }
                rejected(ToolProblemCode.PROTOCOL) {
                    fixture.protocol.call(fixture.lease, catalog.tools.single(), arguments())
                }

                assertEquals(1, fixture.callCount.get())
                assertEquals(0, fixture.mutationCount.get())
                assertTraffic(fixture, "server/discover", "tools/list", "tools/call")
            }
        }
    }

    @Test(timeout = 20_000)
    fun sseEventLimitStopsAnUnboundedProgressStream() = protocolTest {
        McpProtocolFixture().use { fixture ->
            val catalog = fixture.protocol.discover(fixture.lease)
            fixture.handler = { request ->
                if (request.method == "tools/call") {
                    stream(buildString {
                        repeat(257) { append(event(request.progress())) }
                        append(event(request.envelope(fixture.complete(textResult("too late")))))
                    })
                } else {
                    null
                }
            }
            rejected(ToolProblemCode.TOO_LARGE) {
                fixture.protocol.call(fixture.lease, catalog.tools.single(), arguments())
            }

            assertEquals(1, fixture.callCount.get())
            assertTraffic(fixture, "server/discover", "tools/list", "tools/call")
        }
    }

    @Test(timeout = 20_000)
    fun modernServerCannotObtainRootsSamplingOrElicitationThroughAnSseRequest() = protocolTest {
        listOf("roots/list", "sampling/createMessage", "elicitation/create").forEach { method ->
            McpProtocolFixture().use { fixture ->
                val catalog = fixture.protocol.discover(fixture.lease)
                fixture.handler = { request ->
                    if (request.method == "tools/call") {
                        fixture.sse(
                            request, textResult(REMOTE_DIAGNOSTIC),
                            prefix = event(serverRequest(method, JsonPrimitive("server-interaction")))
                        )
                    } else {
                        null
                    }
                }
                rejected(ToolProblemCode.UNSUPPORTED_INTERACTION) {
                    fixture.protocol.call(fixture.lease, catalog.tools.single(), arguments())
                }

                assertTrue(fixture.requestsFor(null).isEmpty())
                assertEquals(1, fixture.callCount.get())
                assertEquals(0, fixture.mutationCount.get())
                assertTraffic(fixture, "server/discover", "tools/list", "tools/call")
            }
        }
    }

    @Test(timeout = 20_000)
    fun legacyServerRequestsReceiveOneExplicitMethodNotFoundReplyWithTheOriginalId() = protocolTest {
        listOf("roots/list", "sampling/createMessage", "elicitation/create").forEachIndexed { index, method ->
            McpProtocolFixture(Era.LEGACY).use { fixture ->
                val serverId = if (index == 0) JsonPrimitive(73) else JsonPrimitive("server-request-$index")
                val catalog = fixture.protocol.discover(fixture.lease)
                fixture.handler = { request ->
                    if (request.method == "tools/call") {
                        fixture.sse(
                            request, textResult(REMOTE_DIAGNOSTIC),
                            prefix = event(serverRequest(method, serverId))
                        )
                    } else {
                        null
                    }
                }
                rejected(ToolProblemCode.UNSUPPORTED_INTERACTION) {
                    fixture.protocol.call(fixture.lease, catalog.tools.single(), arguments())
                }

                val refusal = fixture.requestsFor(null).single()
                assertEquals(serverId, refusal.id)
                assertNull(refusal.body["result"])
                assertNull(refusal.body["params"])
                val error = refusal.body["error"] as JsonObject
                assertEquals(JsonPrimitive(-32601), error["code"])
                assertEquals(JsonPrimitive("Client capability not supported"), error["message"])
                assertEquals(LEGACY_VERSION, refusal.http.getHeader("MCP-Protocol-Version"))
                assertEquals(catalog.connection.sessionId, refusal.http.getHeader("Mcp-Session-Id"))
                assertEquals(1, fixture.callCount.get())
                assertEquals(0, fixture.mutationCount.get())
                assertTraffic(
                    fixture,
                    "server/discover", "initialize", "notifications/initialized", "tools/list", "tools/call", null
                )
            }
        }
    }

    @Test(timeout = 20_000)
    fun legacyPingIsAnsweredWithoutGrantingCapabilitiesAndTheOriginalCallCompletes() = protocolTest {
        McpProtocolFixture(Era.LEGACY).use { fixture ->
            val catalog = fixture.protocol.discover(fixture.lease)
            val serverId = JsonPrimitive("server-ping-1")
            fixture.handler = { request ->
                if (request.method == "tools/call") {
                    fixture.sse(
                        request, fixture.mutationResult(),
                        prefix = event(serverRequest("ping", serverId)) + event(request.progress())
                    )
                } else {
                    null
                }
            }
            val content = fixture.protocol.call(fixture.lease, catalog.tools.single(), arguments())

            assertEquals("Fixture mutation 1", content.text)
            assertFalse(content.isError)
            val pong = fixture.requestsFor(null).single()
            assertEquals(serverId, pong.id)
            assertEquals(JsonObject(emptyMap()), pong.body["result"])
            assertNull(pong.body["error"])
            assertEquals(catalog.connection.sessionId, pong.http.getHeader("Mcp-Session-Id"))
            assertEquals(1, fixture.callCount.get())
            assertEquals(1, fixture.mutationCount.get())
            assertTraffic(
                fixture, "server/discover", "initialize", "notifications/initialized", "tools/list", "tools/call", null
            )
        }
    }

    @Test(timeout = 20_000)
    fun inputRequiredNeverSuppliesInteractionDataOrReplaysEitherEraOfToolsCall() = protocolTest {
        Era.entries.forEach { era ->
            McpProtocolFixture(era).use { fixture ->
                val catalog = fixture.protocol.discover(fixture.lease)
                fixture.handler = { request ->
                    if (request.method == "tools/call") {
                        request.json(buildJsonObject {
                            put("resultType", "input_required")
                            put("request", serverRequest("elicitation/create", JsonPrimitive("interaction-1")))
                            put("resumeToken", "synthetic-resume-token")
                        })
                    } else {
                        null
                    }
                }
                rejected(ToolProblemCode.UNSUPPORTED_INTERACTION) {
                    fixture.protocol.call(fixture.lease, catalog.tools.single(), arguments())
                }

                assertEquals(1, fixture.callCount.get())
                assertEquals(0, fixture.mutationCount.get())
                assertTrue(fixture.requestsFor(null).isEmpty())
                assertTrue(fixture.requests.all {
                    it.method in setOf("server/discover", "initialize", "notifications/initialized", "tools/list", "tools/call")
                })
                assertEquals(if (era == Era.MODERN) 3 else 5, fixture.requests.size)
            }
        }
    }

    @Test(timeout = 20_000)
    fun unknownMissingAndNonStringModernResultTypesFailInsteadOfReturningOrContinuingContent() = protocolTest {
        listOf<JsonElement?>(JsonPrimitive("pending"), JsonPrimitive("future_result"), JsonNull, JsonPrimitive(1), null)
            .forEach { type ->
                McpProtocolFixture().use { fixture ->
                    val catalog = fixture.protocol.discover(fixture.lease)
                    fixture.handler = { request ->
                        if (request.method == "tools/call") {
                            request.json(buildJsonObject {
                                textResult(REMOTE_DIAGNOSTIC).forEach { (key, value) -> put(key, value) }
                                if (type != null) put("resultType", type)
                                put("resumeToken", "not-an-authorization-to-retry")
                            })
                        } else {
                            null
                        }
                    }
                    rejected(ToolProblemCode.PROTOCOL) {
                        fixture.protocol.call(fixture.lease, catalog.tools.single(), arguments())
                    }

                    assertEquals(1, fixture.callCount.get())
                    assertTraffic(fixture, "server/discover", "tools/list", "tools/call")
                }
            }
    }

    @Test(timeout = 20_000)
    fun declaredToolErrorRemainsAnErrorAndDoesNotRequireSuccessfulStructuredOutput() = protocolTest {
        McpProtocolFixture(Era.LEGACY).use { fixture ->
            fixture.tools = listOf(tool(output = countSchema()))
            val catalog = fixture.protocol.discover(fixture.lease)
            fixture.handler = { request ->
                if (request.method == "tools/call") {
                    fixture.reply(request, textResult("Synthetic tool declined the operation", isError = true))
                } else {
                    null
                }
            }
            val content = fixture.protocol.call(fixture.lease, catalog.tools.single(), arguments())

            assertTrue(content.isError)
            assertEquals("Synthetic tool declined the operation", content.text)
            assertFalse(content.truncated)
            assertEquals(1, fixture.callCount.get())
            assertEquals(0, fixture.mutationCount.get())
            assertTraffic(
                fixture, "server/discover", "initialize", "notifications/initialized", "tools/list", "tools/call"
            )
        }
    }

    @Test(timeout = 20_000)
    fun outputSchemaMismatchMissingOutputAndAdditionalPropertiesNeverBecomeSuccessfulResults() = protocolTest {
        val outputs = listOf<JsonElement?>(
            null,
            buildJsonObject { put("count", "not an integer") },
            buildJsonObject { put("count", 1); put("extra", true) }
        )
        outputs.forEach { structured ->
            McpProtocolFixture().use { fixture ->
                fixture.tools = listOf(tool(output = countSchema()))
                val catalog = fixture.protocol.discover(fixture.lease)
                fixture.handler = { request ->
                    if (request.method == "tools/call") {
                        fixture.reply(request, buildJsonObject {
                            textResult(REMOTE_DIAGNOSTIC).forEach { (key, value) -> put(key, value) }
                            if (structured != null) put("structuredContent", structured)
                        })
                    } else {
                        null
                    }
                }
                rejected(ToolProblemCode.SCHEMA) {
                    fixture.protocol.call(fixture.lease, catalog.tools.single(), arguments())
                }

                assertEquals(1, fixture.callCount.get())
                assertTraffic(fixture, "server/discover", "tools/list", "tools/call")
            }
        }
    }

    @Test(timeout = 20_000)
    fun matchingStructuredOnlyOutputIsValidatedAndRenderedWithoutInventingText() = protocolTest {
        McpProtocolFixture().use { fixture ->
            fixture.tools = listOf(tool(output = countSchema()))
            val catalog = fixture.protocol.discover(fixture.lease)
            val structured = buildJsonObject { put("count", 2) }
            fixture.handler = { request ->
                if (request.method == "tools/call") {
                    fixture.reply(request, buildJsonObject { put("structuredContent", structured) })
                } else {
                    null
                }
            }
            val content = fixture.protocol.call(fixture.lease, catalog.tools.single(), arguments())

            assertEquals(structured.toString(), content.text)
            assertFalse(content.isError)
            assertFalse(content.truncated)
            assertTrue(content.sources.isEmpty())
            assertEquals(1, fixture.callCount.get())
            assertTraffic(fixture, "server/discover", "tools/list", "tools/call")
        }
    }

    @Test(timeout = 20_000)
    fun resourceLinksBecomeCitationsButUnsupportedBinaryContentIsNotDecodedOrFetched() = protocolTest {
        McpProtocolFixture().use { fixture ->
            val catalog = fixture.protocol.discover(fixture.lease)
            fixture.handler = { request ->
                if (request.method == "tools/call") {
                    fixture.reply(request, buildJsonObject {
                        put("content", JsonArray(listOf(
                            buildJsonObject {
                                put("type", "resource_link")
                                put("uri", "https://example.com/synthetic-reference")
                                put("name", "Synthetic reference")
                            },
                            buildJsonObject {
                                put("type", "resource")
                                putJsonObject("resource") {
                                    put("uri", "https://example.com/synthetic-excerpt")
                                    put("text", "Synthetic excerpt")
                                    put("mimeType", "text/plain")
                                }
                            },
                            buildJsonObject {
                                put("type", "image")
                                put("mimeType", "image/png")
                                put("data", "synthetic-binary-payload-not-to-decode")
                            }
                        )))
                    })
                } else {
                    null
                }
            }
            val content = fixture.protocol.call(fixture.lease, catalog.tools.single(), arguments())

            assertEquals(
                listOf("https://example.com/synthetic-reference", "https://example.com/synthetic-excerpt"),
                content.sources.map { it.url }
            )
            assertEquals("Synthetic reference", content.sources.first().title)
            assertEquals("Synthetic excerpt", content.sources.last().excerpt)
            assertTrue(content.text.contains("Synthetic excerpt"))
            assertFalse(content.text.contains("synthetic-binary-payload-not-to-decode"))
            assertTrue(content.isError)
            assertTraffic(fixture, "server/discover", "tools/list", "tools/call")
        }
    }

    @Test(timeout = 20_000)
    fun sessionExpiryDuringToolsCallInvalidatesCacheAndRequiresExplicitRediscoveryBeforeAnotherCall() = protocolTest {
        McpProtocolFixture(Era.LEGACY).use { fixture ->
            val original = fixture.protocol.discover(fixture.lease)
            fixture.handler = { request ->
                if (request.method == "tools/call" && fixture.callCount.get() == 1) {
                    MockResponse().setResponseCode(404).setBody("$REMOTE_DIAGNOSTIC $FAKE_KEY")
                } else {
                    null
                }
            }
            val failure = rejected(ToolProblemCode.CONFIGURATION_CHANGED) {
                fixture.protocol.call(fixture.lease, original.tools.single(), arguments())
            }
            assertEquals(404, failure.problem.httpStatus)
            assertUncached(fixture)
            assertEquals(1, fixture.initializationCount.get())
            assertEquals(1, fixture.callCount.get())
            assertEquals(0, fixture.mutationCount.get())

            rejected(ToolProblemCode.CONFIGURATION_CHANGED) {
                fixture.protocol.call(fixture.lease, original.tools.single(), arguments())
            }
            assertEquals(1, fixture.callCount.get())
            assertEquals(5, fixture.requests.size)

            val renewed = fixture.protocol.discover(fixture.lease)
            assertEquals("fixture-session-2", renewed.connection.sessionId)
            assertEquals(2, fixture.initializationCount.get())
            assertEquals(1, fixture.callCount.get())
            assertSame(renewed, fixture.protocol.catalog(CONFIGURATION_ID, 1))
            val content = fixture.protocol.call(fixture.lease, renewed.tools.single(), arguments())

            assertEquals("Fixture mutation 1", content.text)
            assertEquals(2, fixture.callCount.get())
            assertEquals(1, fixture.mutationCount.get())
            assertEquals(
                listOf("fixture-session-1", "fixture-session-2"),
                fixture.requestsFor("tools/call").map { it.http.getHeader("Mcp-Session-Id") }
            )
            assertTraffic(
                fixture,
                "server/discover", "initialize", "notifications/initialized", "tools/list", "tools/call",
                "server/discover", "initialize", "notifications/initialized", "tools/list", "tools/call"
            )
        }
    }

    @Test(timeout = 20_000)
    fun readOnlyDiscoveryMayRenegotiateAnExpiredCachedLegacySessionOnce() = protocolTest {
        McpProtocolFixture(Era.LEGACY).use { fixture ->
            fixture.protocol.discover(fixture.lease)
            fixture.handler = { request ->
                if (request.method == "tools/list" && fixture.requestsFor("tools/list").size == 2) {
                    MockResponse().setResponseCode(404)
                } else {
                    null
                }
            }
            val renewed = fixture.protocol.discover(fixture.lease)

            assertEquals("fixture-session-2", renewed.connection.sessionId)
            assertSame(renewed, fixture.protocol.catalog(CONFIGURATION_ID, 1))
            assertEquals(2, fixture.initializationCount.get())
            assertEquals(2, fixture.requestsFor("server/discover").size)
            assertEquals(3, fixture.requestsFor("tools/list").size)
            assertEquals(0, fixture.callCount.get())
            assertEquals(0, fixture.mutationCount.get())
            assertEquals(
                listOf("fixture-session-1", "fixture-session-1", "fixture-session-2"),
                fixture.requestsFor("tools/list").map { it.http.getHeader("Mcp-Session-Id") }
            )
            assertTraffic(
                fixture,
                "server/discover", "initialize", "notifications/initialized", "tools/list",
                "tools/list", "server/discover", "initialize", "notifications/initialized", "tools/list"
            )
        }
    }

    @Test(timeout = 20_000)
    fun repeatedDiscoveryExpiryStopsAfterOneRecoveryWithoutCachingOrCallingTools() = protocolTest {
        McpProtocolFixture(Era.LEGACY).use { fixture ->
            fixture.handler = { request ->
                if (request.method == "tools/list") MockResponse().setResponseCode(404) else null
            }
            rejected(ToolProblemCode.PROTOCOL) { fixture.protocol.discover(fixture.lease) }

            assertEquals(2, fixture.initializationCount.get())
            assertEquals(2, fixture.requestsFor("server/discover").size)
            assertEquals(2, fixture.requestsFor("tools/list").size)
            assertEquals(0, fixture.callCount.get())
            assertUncached(fixture)
            assertTraffic(
                fixture,
                "server/discover", "initialize", "notifications/initialized", "tools/list",
                "server/discover", "initialize", "notifications/initialized", "tools/list"
            )
        }
    }

    @Test(timeout = 20_000)
    fun aChangedLegacySessionHeaderIsAProtocolFailureNotAnInvitationToReplay() = protocolTest {
        McpProtocolFixture(Era.LEGACY).use { fixture ->
            val catalog = fixture.protocol.discover(fixture.lease)
            fixture.handler = { request ->
                if (request.method == "tools/call") {
                    fixture.reply(request, textResult(REMOTE_DIAGNOSTIC))
                        .setHeader("Mcp-Session-Id", "unexpected-replacement-session")
                } else {
                    null
                }
            }
            rejected(ToolProblemCode.PROTOCOL) {
                fixture.protocol.call(fixture.lease, catalog.tools.single(), arguments())
            }

            assertEquals(1, fixture.initializationCount.get())
            assertEquals(1, fixture.callCount.get())
            assertTraffic(
                fixture, "server/discover", "initialize", "notifications/initialized", "tools/list", "tools/call"
            )
        }
    }

    @Test(timeout = 20_000)
    fun authenticationQuotaAndServerErrorsDuringDiscoveryAreSafeAndDoNotTriggerFallback() = protocolTest {
        mapOf(
            401 to ToolProblemCode.AUTHENTICATION_REQUIRED,
            403 to ToolProblemCode.AUTHENTICATION_REQUIRED,
            429 to ToolProblemCode.RATE_LIMITED,
            503 to ToolProblemCode.NETWORK
        ).forEach { (status, expected) ->
            McpProtocolFixture().use { fixture ->
                fixture.handler = {
                    MockResponse().setResponseCode(status)
                        .setHeader("Content-Type", "application/json")
                        .setHeader("Retry-After", "0")
                        .setBody("$REMOTE_DIAGNOSTIC $FAKE_KEY")
                }
                val failure = rejected(expected) { fixture.protocol.discover(fixture.lease) }

                assertEquals(status, failure.problem.httpStatus)
                assertEquals(status == 429 || status == 503, failure.problem.retryable)
                assertTraffic(fixture, "server/discover")
                assertUncached(fixture)
                assertEquals(0, fixture.initializationCount.get())
                assertEquals(0, fixture.callCount.get())
            }
        }
    }

    @Test(timeout = 20_000)
    fun authenticationAndQuotaErrorsDuringCallNeverProduceFakeResultsOrAutomaticRetries() = protocolTest {
        mapOf(401 to ToolProblemCode.AUTHENTICATION_REQUIRED, 429 to ToolProblemCode.RATE_LIMITED)
            .forEach { (status, expected) ->
                McpProtocolFixture(authMode = McpAuthMode.BEARER, credential = FAKE_KEY).use { fixture ->
                    val catalog = fixture.protocol.discover(fixture.lease)
                    fixture.handler = { request ->
                        if (request.method == "tools/call") {
                            MockResponse().setResponseCode(status)
                                .setHeader("Content-Type", "application/json")
                                .setHeader("WWW-Authenticate", "Bearer realm=\"fixture\"")
                                .setHeader("Retry-After", "0")
                                .setBody("$REMOTE_DIAGNOSTIC $FAKE_KEY")
                        } else {
                            null
                        }
                    }
                    val failure = rejected(expected) {
                        fixture.protocol.call(fixture.lease, catalog.tools.single(), arguments())
                    }

                    assertEquals(status, failure.problem.httpStatus)
                    assertEquals(1, fixture.callCount.get())
                    assertEquals(0, fixture.mutationCount.get())
                    assertEquals("Bearer $FAKE_KEY", fixture.requestsFor("tools/call").single().http.getHeader("Authorization"))
                    assertTraffic(fixture, "server/discover", "tools/list", "tools/call")
                }
            }
    }

    @Test(timeout = 20_000)
    fun recognizedCallErrorsNeverRenegotiateOrResumeTheOperation() = protocolTest {
        mapOf(
            -32020 to ToolProblemCode.PROTOCOL,
            -32022 to ToolProblemCode.PROTOCOL,
            -32021 to ToolProblemCode.UNSUPPORTED_INTERACTION,
            -32042 to ToolProblemCode.UNSUPPORTED_INTERACTION
        ).forEach { (code, expected) ->
            McpProtocolFixture().use { fixture ->
                val catalog = fixture.protocol.discover(fixture.lease)
                fixture.handler = { request ->
                    if (request.method == "tools/call") {
                        request.error(
                            code, status = 400,
                            supported = if (code == -32022) listOf(LEGACY_VERSION) else null,
                            message = "$REMOTE_DIAGNOSTIC $FAKE_KEY"
                        )
                    } else {
                        null
                    }
                }
                rejected(expected) { fixture.protocol.call(fixture.lease, catalog.tools.single(), arguments()) }

                assertEquals(1, fixture.callCount.get())
                assertEquals(0, fixture.initializationCount.get())
                assertTraffic(fixture, "server/discover", "tools/list", "tools/call")
            }
        }
    }

    @Test(timeout = 20_000)
    fun leaseCredentialsAndLegacySessionsAreRedactedFromServerNamesToolDescriptionsAndResults() = protocolTest {
        McpProtocolFixture(Era.LEGACY, authMode = McpAuthMode.BEARER, credential = FAKE_KEY).use { fixture ->
            val sessionId = "fixture-session-1"
            fixture.serverName = "Synthetic $FAKE_KEY $sessionId"
            fixture.tools = listOf(
                tool("safe"),
                tool("credential_in_description", description = "Never publish $FAKE_KEY"),
                tool("session_in_description", description = "Never publish $sessionId")
            )
            val catalog = fixture.protocol.discover(fixture.lease)
            assertEquals(listOf("safe"), catalog.tools.map { it.name })
            assertEquals(setOf("credential_in_description", "session_in_description"), catalog.rejected.map { it.name }.toSet())
            assertSafe(
                catalog.connection.serverName.orEmpty() + catalog.tools.toString() +
                    catalog.rejected.toString() + catalog.toString() + catalog.connection.toString(),
                sessionId
            )
            fixture.handler = { request ->
                if (request.method == "tools/call") {
                    fixture.reply(request, buildJsonObject {
                        put("content", JsonArray(listOf(
                            buildJsonObject {
                                put("type", "text")
                                put("text", "Synthetic response containing $FAKE_KEY and $sessionId")
                            },
                            buildJsonObject {
                                put("type", "resource_link")
                                put("uri", "https://example.com/private?key=$FAKE_KEY")
                                put("name", "Do not expose this credential-bearing citation")
                            }
                        )))
                    })
                } else {
                    null
                }
            }
            val content = fixture.protocol.call(fixture.lease, catalog.tools.single(), arguments())

            assertSafe(content.text + content.sources.toString() + content.toString(), sessionId)
            assertTrue(content.text.contains("[redacted]"))
            assertTrue(content.sources.isEmpty())
            fixture.requests.forEach { request ->
                assertEquals("Bearer $FAKE_KEY", request.http.getHeader("Authorization"))
                assertNull(request.http.getHeader(AUTH_HEADER))
            }
            assertEquals(1, fixture.callCount.get())
            assertTraffic(
                fixture, "server/discover", "initialize", "notifications/initialized", "tools/list", "tools/call"
            )
        }
    }

    @Test(timeout = 20_000)
    fun modernServerInfoCannotEchoTheConfiguredCredentialIntoDiscovery() = protocolTest {
        McpProtocolFixture(authMode = McpAuthMode.CUSTOM_HEADER, credential = FAKE_KEY).use { fixture ->
            fixture.serverName = "Synthetic $FAKE_KEY"
            val catalog = fixture.protocol.discover(fixture.lease)

            assertEquals("Synthetic [redacted]", catalog.connection.serverName)
            assertSafe(catalog.connection.serverName.orEmpty())
            assertTraffic(fixture, "server/discover", "tools/list")
            assertEquals(0, fixture.callCount.get())
        }
    }

    @Test(timeout = 20_000)
    fun changedConfigurationIsRejectedBeforeSendingAnOldToolCall() = protocolTest {
        McpProtocolFixture(authMode = McpAuthMode.CUSTOM_HEADER, credential = FAKE_KEY).use { fixture ->
            val catalog = fixture.protocol.discover(fixture.lease)
            fixture.currentRevision.incrementAndGet()

            rejected(ToolProblemCode.CONFIGURATION_CHANGED) {
                fixture.protocol.call(fixture.lease, catalog.tools.single(), arguments())
            }

            assertEquals(0, fixture.callCount.get())
            assertEquals(0, fixture.mutationCount.get())
            assertTraffic(fixture, "server/discover", "tools/list")
        }
    }

    @Test(timeout = 20_000)
    fun configurationChangedDuringDiscoveryCannotCacheALateResponse() = protocolTest {
        McpProtocolFixture().use { fixture ->
            fixture.handler = { request ->
                if (request.method == "tools/list") {
                    fixture.currentRevision.incrementAndGet()
                    fixture.reply(request, listing(fixture.tools))
                } else {
                    null
                }
            }
            rejected(ToolProblemCode.CONFIGURATION_CHANGED) { fixture.protocol.discover(fixture.lease) }

            assertUncached(fixture)
            assertEquals(0, fixture.callCount.get())
            assertTraffic(fixture, "server/discover", "tools/list")
        }
    }

    @Test(timeout = 20_000)
    fun configurationChangedDuringCallDiscardsTheLateResultWithoutReplay() = protocolTest {
        McpProtocolFixture().use { fixture ->
            val catalog = fixture.protocol.discover(fixture.lease)
            fixture.handler = { request ->
                if (request.method == "tools/call") {
                    fixture.currentRevision.incrementAndGet()
                    fixture.reply(request, textResult(REMOTE_DIAGNOSTIC))
                } else {
                    null
                }
            }
            rejected(ToolProblemCode.CONFIGURATION_CHANGED) {
                fixture.protocol.call(fixture.lease, catalog.tools.single(), arguments())
            }

            assertEquals(1, fixture.callCount.get())
            assertTraffic(fixture, "server/discover", "tools/list", "tools/call")
        }
    }

    @Test(timeout = 20_000)
    fun rediscoveryOfAChangedToolDefinitionPreventsExecutingThePreviouslyDiscoveredSpec() = protocolTest {
        McpProtocolFixture().use { fixture ->
            val old = fixture.protocol.discover(fixture.lease)
            fixture.tools = listOf(tool(description = "An explicitly changed fixture definition"))
            val current = fixture.protocol.discover(fixture.lease)

            rejected(ToolProblemCode.CONFIGURATION_CHANGED) {
                fixture.protocol.call(fixture.lease, old.tools.single(), arguments())
            }
            assertEquals(0, fixture.callCount.get())
            assertSame(current, fixture.protocol.catalog(CONFIGURATION_ID, 1))
            fixture.protocol.call(fixture.lease, current.tools.single(), arguments())

            assertEquals(1, fixture.callCount.get())
            assertEquals(1, fixture.mutationCount.get())
            assertTraffic(fixture, "server/discover", "tools/list", "tools/list", "tools/call")
        }
    }

    @Test(timeout = 20_000)
    fun cancellingAStalledToolsCallPropagatesCancellationAndNeverRetriesTheMutation() = protocolTest {
        McpProtocolFixture().use { fixture ->
            val catalog = fixture.protocol.discover(fixture.lease)
            val arrived = CompletableDeferred<Unit>()
            fixture.handler = { request ->
                if (request.method == "tools/call") {
                    arrived.complete(Unit)
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                } else {
                    null
                }
            }
            val failure = cancelAfterArrival(arrived) {
                fixture.protocol.call(fixture.lease, catalog.tools.single(), arguments())
            }

            assertTrue(failure is CancellationException)
            assertEquals(1, fixture.callCount.get())
            assertEquals(0, fixture.mutationCount.get())
            assertSame(catalog, fixture.protocol.catalog(CONFIGURATION_ID, 1))
            assertTraffic(fixture, "server/discover", "tools/list", "tools/call")
        }
    }

    @Test(timeout = 20_000)
    fun cancellingStalledDiscoveryDoesNotRetryDowngradeOrCacheAConnection() = protocolTest {
        McpProtocolFixture().use { fixture ->
            val arrived = CompletableDeferred<Unit>()
            fixture.handler = {
                arrived.complete(Unit)
                MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
            val failure = cancelAfterArrival(arrived) { fixture.protocol.discover(fixture.lease) }

            assertTrue(failure is CancellationException)
            assertUncached(fixture)
            assertEquals(0, fixture.initializationCount.get())
            assertEquals(0, fixture.callCount.get())
            assertTraffic(fixture, "server/discover")
        }
    }

    private fun protocolTest(block: suspend CoroutineScope.() -> Unit) {
        runBlocking { withTimeout(15_000) { block() } }
    }

    private suspend fun rejected(
        code: ToolProblemCode,
        block: suspend () -> Any?
    ): ToolException {
        try {
            block()
        } catch (failure: ToolException) {
            assertEquals(code, failure.problem.code)
            assertTrue(failure.problem.message.isNotBlank())
            assertSafe(failure.toString() + failure.problem.toString())
            return failure
        }
        throw AssertionError("Expected a safe $code failure")
    }

    private fun assertTraffic(fixture: McpProtocolFixture, vararg methods: String?) {
        val requests = fixture.requests
        assertEquals(methods.toList(), requests.map { it.method })
        requests.forEach { request ->
            assertEquals("POST", request.http.method)
            assertNotNull("The fixture must use real TLS", request.http.handshake)
            assertEquals("/mcp?fixture=protocol", request.http.path)
            assertEquals(JsonPrimitive("2.0"), request.body["jsonrpc"])
            assertNull(request.http.getHeader("Cookie"))
            assertNull(request.http.getHeader("Proxy-Authorization"))
        }
        val ids = requests.filter { it.method != null && it.method != "notifications/initialized" }.map { it.id }
        assertFalse(ids.any { it == null || it == JsonNull })
        assertEquals(ids.size, ids.toSet().size)
    }

    private fun assertUncached(fixture: McpProtocolFixture) {
        assertNull(fixture.protocol.catalog(CONFIGURATION_ID, 1))
    }

    private fun assertSafe(text: String, vararg additionalSecrets: String) {
        (listOf(FAKE_KEY, REMOTE_DIAGNOSTIC) + additionalSecrets).forEach {
            assertFalse("Sensitive fixture text escaped into a result or diagnostic", text.contains(it))
        }
    }

    private fun encodedHeader(value: String): String =
        "=?base64?${Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))}?="

    private fun serverRequest(method: String, id: JsonElement): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        put("params", JsonObject(emptyMap()))
    }

    private fun countSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("count") { put("type", "integer") }
        }
        put("required", JsonArray(listOf(JsonPrimitive("count"))))
        put("additionalProperties", false)
    }

    private suspend fun CoroutineScope.cancelAfterArrival(
        arrived: CompletableDeferred<Unit>,
        operation: suspend () -> Any?
    ): Throwable? {
        val finished = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                operation()
                finished.complete(null)
            } catch (failure: Throwable) {
                finished.complete(failure)
                if (failure is CancellationException) throw failure
            }
        }
        withTimeout(4_000) { arrived.await() }
        job.cancel(CancellationException("Synthetic fixture cancellation"))
        withTimeout(2_000) { job.join() }
        return finished.await()
    }
}
