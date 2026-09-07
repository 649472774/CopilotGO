package com.tongxie.copilotgo.ui.settings

import com.tongxie.copilotgo.data.tools.CredentialUpdate
import com.tongxie.copilotgo.data.tools.McpAuthMode
import com.tongxie.copilotgo.data.tools.McpNetworkTrust
import com.tongxie.copilotgo.data.tools.McpServerDraft
import com.tongxie.copilotgo.data.tools.McpServerSettings
import com.tongxie.copilotgo.data.tools.McpTransport
import com.tongxie.copilotgo.data.tools.SearchProvider
import com.tongxie.copilotgo.data.tools.ToolCredentialState
import com.tongxie.copilotgo.data.tools.ToolProblem
import com.tongxie.copilotgo.data.tools.ToolProblemCode
import com.tongxie.copilotgo.data.tools.ToolSettingsLimits
import com.tongxie.copilotgo.data.tools.WebToolSettings
import com.tongxie.copilotgo.data.tools.mcp.McpDiscoveredTool
import com.tongxie.copilotgo.data.tools.mcp.McpDiscoveryReport
import com.tongxie.copilotgo.data.tools.mcp.McpDiscoveryState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSettingsFormTest {
    @Test fun invalidAndOversizedEndpointsRemainUnmodified() {
        val inputs = listOf(
            "https://example.org/invalid path",
            " https://example.org/mcp",
            "https://example.org/mcp\n",
            "https://example.org/mcp#unexpected",
            "http://example.org/mcp",
            "file:///some/file",
            "https://example.org/" + "x".repeat(ToolSettingsLimits.MAX_ENDPOINT_CHARS)
        )
        inputs.forEach { input ->
            val form = newForm().edit(validDraft().copy(endpoint = input))
            assertEquals(input, form.draft.endpoint)
            assertFalse(input, form.validation.valid)
        }
    }

    @Test fun validationRejectsEmbeddedAuthenticationAndCustomHeaderValues() {
        assertEquals(
            ToolSettingsInputIssue.ENDPOINT_USER_INFO,
            validateToolMcpDraft(validDraft().copy(endpoint = "https://user:password@example.org/mcp")).endpoint
        )
        listOf(
            "", "X Api Key", "X-Api-Key: secret", "X-Key\n", "认证", "a".repeat(65),
            "Host", "Authorization", "Cookie", "MCP-Session-Id", "Proxy-Authorization", "Sec-Fetch-Site"
        ).forEach { header ->
            assertFalse(validateToolMcpDraft(validDraft().copy(
                authMode = McpAuthMode.CUSTOM_HEADER, authHeaderName = header
            )).valid)
        }
        assertTrue(validateToolMcpDraft(validDraft().copy(
            authMode = McpAuthMode.CUSTOM_HEADER, authHeaderName = "X-Api-Key"
        )).valid)
        assertTrue(validateToolMcpDraft(validDraft().copy(
            authMode = McpAuthMode.CUSTOM_HEADER, authHeaderName = "a".repeat(64)
        )).valid)
        listOf("api-key", "token", "Client_Secret", "credential").forEach { parameter ->
            assertEquals(
                ToolSettingsInputIssue.ENDPOINT_SECRET_QUERY,
                validateToolMcpDraft(validDraft().copy(endpoint = "https://example.org/mcp?$parameter=synthetic")).endpoint
            )
        }
    }

    @Test fun labelsTransportAndSelectionLimitsAreExplicit() {
        assertEquals(ToolSettingsInputIssue.LABEL_REQUIRED, validateToolMcpDraft(validDraft().copy(label = " ")).label)
        val oversized = "受".repeat(ToolSettingsLimits.MAX_LABEL_CHARS + 1)
        val form = newForm().edit(validDraft().copy(label = oversized))
        assertEquals(oversized, form.draft.label)
        assertEquals(ToolSettingsInputIssue.LABEL_TOO_LONG, form.validation.label)
        assertEquals(
            ToolSettingsInputIssue.UNSUPPORTED_TRANSPORT,
            validateToolMcpDraft(validDraft().copy(transport = McpTransport.STDIO)).transport
        )
        assertEquals(
            ToolSettingsInputIssue.UNSUPPORTED_TRANSPORT,
            validateToolMcpDraft(validDraft().copy(transport = McpTransport.HTTP_SSE)).transport
        )
        assertEquals(
            ToolSettingsInputIssue.TOO_MANY_TOOLS,
            validateToolMcpDraft(validDraft().copy(enabledTools = (0..64).map { "tool$it" }.toSet())).tools
        )
    }

    @Test fun namespaceChangesRequireAnExplicitCredentialChoiceAndDropSelection() {
        val saved = savedServer().copy(credentialState = ToolCredentialState.CONFIGURED, authMode = McpAuthMode.BEARER)
        val original = ToolMcpForm(saved.id, saved)
        val edited = original.edit(original.draft.copy(endpoint = "https://second.example.org/mcp"))
        assertTrue(edited.namespaceChanged)
        assertTrue(edited.needsCredentialDecision)
        assertTrue(edited.draft.enabledTools.isEmpty())
        assertFalse(edited.copy(credentialAction = ToolCredentialAction.REMOVE).needsCredentialDecision)
        assertFalse(edited.copy(credentialAction = ToolCredentialAction.REPLACE).needsCredentialDecision)
        assertTrue(original.edit(original.draft.copy(authMode = McpAuthMode.NONE)).needsCredentialDecision)
        assertFalse(original.edit(original.draft.copy(label = "只改名称")).needsCredentialDecision)
    }

    @Test fun changingTransportOrNetworkTrustClearsSelectedTools() {
        val saved = savedServer()
        val form = ToolMcpForm(saved.id, saved)
        assertTrue(form.edit(form.draft.copy(networkTrust = McpNetworkTrust.LOCAL_NETWORK)).draft.enabledTools.isEmpty())
        assertTrue(form.edit(form.draft.copy(transport = McpTransport.HTTP_SSE)).draft.enabledTools.isEmpty())
        assertEquals(setOf("lookup"), form.edit(form.draft.copy(label = "新的名称")).draft.enabledTools)
    }

    @Test fun multipleSelectionsUseOnlyTheExactSavedDiscoveryRevision() {
        val saved = savedServer()
        val form = ToolMcpForm(saved.id, saved)
        assertTrue(form.canSelectDiscoveredTools(saved.revision, saved.revision))
        val selected = form.edit(form.draft.copy(enabledTools = setOf("lookup", "second")))
        assertTrue(selected.canSelectDiscoveredTools(saved.revision, saved.revision))
        assertFalse(selected.canSelectDiscoveredTools(saved.revision - 1, saved.revision))
        assertFalse(selected.canSelectDiscoveredTools(saved.revision, saved.revision + 1))
        assertFalse(selected.canSelectDiscoveredTools(saved.revision, null))
        assertFalse(form.edit(form.draft.copy(endpoint = "https://other.example.org/mcp"))
            .canSelectDiscoveredTools(saved.revision, saved.revision))
        assertFalse(form.copy(credentialAction = ToolCredentialAction.REPLACE)
            .canSelectDiscoveredTools(saved.revision, saved.revision))
        assertFalse(newForm().canSelectDiscoveredTools(1, 1))
    }

    @Test fun staleOrDeletedConfigurationsDoNotChangeOrEraseDrafts() {
        val saved = savedServer()
        val form = ToolMcpForm(saved.id, saved).edit(McpServerDraft(saved).copy(label = "保留这个草稿"))
        assertTrue(form.isStale(saved.copy(revision = saved.revision + 1)))
        assertTrue(form.isStale(null))
        assertEquals("保留这个草稿", form.draft.label)
        assertEquals(saved.revision, form.expectedRevision)
        assertFalse(form.isStale(saved))
        assertFalse(newForm().isStale(null))
    }

    @Test fun searchChoicesAreNonsecretAndKeepTheOriginalRevision() {
        val saved = WebToolSettings(revision = 7)
        val pristine = ToolSearchForm(saved)
        assertFalse(pristine.dirty)
        val form = pristine.copy(
            draft = pristine.draft.copy(provider = SearchProvider.EXA_API_KEY, externalSharingConsent = true),
            credentialAction = ToolCredentialAction.REPLACE
        )
        assertTrue(form.dirty)
        assertTrue(form.isStale(saved.copy(revision = 8)))
        assertTrue(form.isStale(null))
        assertEquals(7L, form.original.revision)
        assertEquals(ToolCredentialAction.REPLACE, form.credentialAction)
        listOf(ToolSearchForm::class.java, ToolMcpForm::class.java).forEach { type ->
            assertFalse(type.declaredFields.any { CredentialUpdate::class.java.isAssignableFrom(it.type) })
            assertFalse(type.declaredFields.any { it.name.contains("secret", ignoreCase = true) })
        }
    }

    @Test fun aNewBlankFormIsCleanAndAValidHttpsDraftCanBeSaved() {
        assertFalse(newForm().dirty)
        assertTrue(newForm().edit(validDraft()).dirty)
        assertNull(validateToolMcpDraft(validDraft()).endpoint)
        assertTrue(validateToolMcpDraft(validDraft()).valid)
    }

    @Test fun failedInFlightForeignAndOldReportsCannotEnableTools() {
        val saved = savedServer()
        val report = McpDiscoveryReport(saved.id, saved.revision, "2025-03-26", "受控服务", emptyList(), 0)
        val current = McpDiscoveryState(saved.revision, report = report)
        assertEquals(report, currentToolDiscovery(saved, current))
        assertNull(currentToolDiscovery(saved.copy(revision = saved.revision + 1), current))
        assertNull(currentToolDiscovery(saved, current.copy(loading = true)))
        assertNull(currentToolDiscovery(saved, current.copy(
            problem = ToolProblem(ToolProblemCode.RATE_LIMITED, "请求过于频繁", httpStatus = 429)
        )))
        assertNull(currentToolDiscovery(saved, current.copy(report = report.copy(serverId = "fedcba9876543210"))))
        assertNull(currentToolDiscovery(saved, current.copy(report = report.copy(configRevision = saved.revision - 1))))
        assertNull(currentToolDiscovery(null, current))
    }

    @Test fun unsupportedOrAbsentSchemasStayUnselectableEvenWhenPreviouslyEnabled() {
        val tool = McpDiscoveredTool(
            name = "lookup", description = "受控只读提示不构成授权",
            inputSchema = buildJsonObject { put("type", "object") },
            supported = true, enabled = false
        )
        assertTrue(tool.canBeSelected())
        assertFalse(tool.copy(inputSchema = null, enabled = true).canBeSelected())
        assertFalse(tool.copy(supported = false, enabled = true).canBeSelected())
        assertFalse(tool.copy(
            problem = ToolProblem(ToolProblemCode.SCHEMA, "不支持远程 schema 引用"), enabled = true
        ).canBeSelected())
    }

    @Test fun credentialValidityMatchesSafeHeaderBoundariesWithoutRetainingTheValue() {
        assertTrue(isToolCredentialInputValid("synthetic-token"))
        assertTrue(isToolCredentialInputValid("a".repeat(4096)))
        listOf("", " ", " leading", "trailing ", "line\nbreak", "tab\tvalue", "中文", "a".repeat(4097)).forEach {
            assertFalse(isToolCredentialInputValid(it))
        }
        assertTrue(toolCredentialWillExist(ToolCredentialAction.KEEP, ToolCredentialState.CONFIGURED))
        assertFalse(toolCredentialWillExist(ToolCredentialAction.KEEP, ToolCredentialState.MISSING))
        assertFalse(toolCredentialWillExist(ToolCredentialAction.REMOVE, ToolCredentialState.CONFIGURED))
        assertTrue(toolCredentialWillExist(ToolCredentialAction.REPLACE, ToolCredentialState.MISSING))
    }

    @Test fun changingOnlyHeaderCaseDoesNotMoveTheCredentialNamespace() {
        val saved = savedServer().copy(
            authMode = McpAuthMode.CUSTOM_HEADER,
            authHeaderName = "X-Api-Key",
            credentialState = ToolCredentialState.CONFIGURED
        )
        val form = ToolMcpForm(saved.id, saved)
        assertFalse(form.edit(form.draft.copy(authHeaderName = "x-api-key")).needsCredentialDecision)
        assertTrue(form.edit(form.draft.copy(authHeaderName = "X-Other-Key")).needsCredentialDecision)
    }

    private fun newForm() = ToolMcpForm(serverId = null, original = null)
    private fun validDraft() = McpServerDraft(label = "受控服务", endpoint = "https://example.org/mcp")
    private fun savedServer() = McpServerSettings(
        id = "0123456789abcdef", revision = 4, label = "受控服务", endpoint = "https://example.org/mcp",
        enabled = true, enabledTools = setOf("lookup")
    )
}
