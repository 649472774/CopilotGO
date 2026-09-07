# Web and remote MCP tools

`ConfiguredToolExecutor` implements the runtime-owned `AgentToolExecutor`. It has
no account, Copilot token, attachment, conversation store, or session job registry.
The application runtime remains responsible for proposals, immutable approval,
run cancellation, and durable tool-call history.

## Configuration and credentials

`ToolSettingsStore` uses the existing `SecretVault` / `CredentialVault`. Its
`tools-settings-v1` encrypted record atomically contains server settings and their
independent credentials, with a 64 KiB record limit. Only redacted projections are
exposed through `state`; there is no credential readback API for UI consumers.

Edits and deletion require the displayed configuration revision. Endpoint or auth
binding changes cannot retain an existing credential through `CredentialUpdate.Keep`.
Deletion and accepted edits revoke captured revisions before storage IO. Reload
issues fresh revisions so a failed edit followed by a quick reload cannot revive
an old captured credential. Storage failures block tools and preserve the record.

Servers have generated stable IDs, not IDs derived from self-reported server names.
Tool names supplied to the model are namespaced by those configured IDs. Server
descriptions, schemas, annotations, and results are untrusted. `readOnlyHint` never
turns an MCP tool into an automatically approved public web read.

## Web providers

The two initial search choices are Exa's anonymous free tier and the same service
with an explicitly supplied Exa API key. The endpoint is restricted to
`https://mcp.exa.ai/mcp?tools=web_search_exa`. The optional key is sent only as
`x-api-key` to that exact endpoint. No accounts, paid plans, or purchases are
created. Free-tier rate/quota failures remain explicit; there are no scraper or
challenge-bypass fallbacks.

External-sharing consent is required before search or page reading. Search sends
only the bounded query and result count, not conversation or attachment objects.
Exa's actual `Title:` / `URL:` text records become `SEARCH_HIT` sources. Incidental
URLs, malformed output, challenges, and unsafe destinations are not synthesized
into results.

The page reader fetches public HTTPS HTML or plain text. It validates the MIME type
before consuming the body, follows guarded GET redirects, and parses HTML locally
with jsoup. Scripts, styles, forms, assets, frames, canonical links, and refreshes
are not executed or fetched. Sources use the actual final response URL and are
marked `FETCHED_PAGE`; they are not inferred from document canonical metadata.
PDF, image, audio, video, and other binary formats are unsupported.

## Network boundary

Tools await `HttpClientProvider` readiness and derive a credential-isolated client
from its configured route. Origin authenticators, inherited interceptors (including
debug logging), cookies, and cache are not forwarded to tools. The explicit proxy
route is not replaced with a silent direct fallback.

Public destinations require HTTPS and public-address enforcement at connection
time. Every followed redirect is checked; POST tool calls are never replayed
through redirects or transport retry. Credential-bearing cross-origin redirects
are forbidden. Compressed and decoded response bodies, framing, redirects, and
request duration have independent limits. Normal certificate verification remains
enabled.

A route whose remote DNS cannot be safely constrained fails with a visible proxy
policy error instead of claiming protection from a preflight lookup. An explicitly
configured LAN HTTPS MCP server is a separate, per-server trust choice; it never
grants the public page reader permission to access private destinations. Desktop
stdio and deprecated standalone HTTP+SSE require an external HTTPS bridge and are
not executed on Android.

## MCP protocol and schema admission

The client supports two protocol eras:

- **2026-07-28:** read-only `server/discover`, per-request protocol/client metadata,
  `MCP-Protocol-Version` / `Mcp-Method` / `Mcp-Name`, JSON or request-scoped SSE.
  There is no initialize handshake, GET stream, or protocol session.
- **2025-11-25, 2025-06-18, 2025-03-26:** initialization, initialized notification,
  optional origin-bound session ID, paginated tool listing, JSON and request-scoped
  SSE. Only read-only discovery may recover an expired session; a tool call is not
  replayed.

JSON-RPC IDs and progress tokens are correlated. UTF-8, JSON nesting, duplicate
keys, body size, event count, pagination, and cursor loops are bounded or rejected.
Modern `input_required`, unknown result types, sampling, elicitation, roots, and
unsupported media are not reported as successful completion. A legacy
server-initiated unsupported request receives an explicit protocol rejection.

Schemas use the maintained networknt validator, not handwritten permissive
validation. Draft-07 and default 2020-12 admission includes actual metaschema
validation using an exact whitelist of bundled metaschemas; instance validators
block external HTTP, file, and classpath references. Unsupported dialects,
unresolvable or unsafe references, and features outside the bounded admission
profile produce visible rejected-tool rows. Schema, reference/composition work,
and instance limits apply in addition to the library's validation. CPU-bound
regular expressions are not made safe merely by a coroutine timeout.

`x-mcp-header` definitions must be primitive, unique, and statically reachable
through `properties`. Header values use the required UTF-8 Base64 sentinel encoding
when needed, including literal sentinel collisions and exact safe-integer checks.
Input validation and header extraction occur before approval as well as before
execution. Declared output schemas are checked against actual structured results.

## Composition

Application DI creates one instance of each service using its application scope:

```kotlin
val toolHttp = ToolHttpClient(httpProvider)
val toolSettings = ToolSettingsStore(CredentialVault(context), appScope)
val remoteMcp = RemoteMcpService(toolSettings, toolHttp, appScope)
val webTools = WebToolService(toolSettings, toolHttp, remoteMcp)
val toolExecutor = ConfiguredToolExecutor(toolSettings, remoteMcp, webTools, appScope)
```

UI discovery uses `discover(serverId, expectedRevision)` or
`discoverSearch(expectedRevision)`, neither of which sends `tools/call`. Saving
selected tools changes the revision and requires current discovery before exposure.
The executor's snapshot is side-effect-free and returns availability issues rather
than inventing tools or successful connection state.

## Verification entry points

Owned tests live under `data/tools` in the existing JUnit/MockWebServer suite.
The live smoke tests are opt-in and use only a synthetic in-memory vault, a public
documentation query, and `https://example.com/`; they do not read user credentials:

```powershell
$env:COPILOTGO_LIVE_WEB_SMOKE = "1"
.\gradlew.bat "-Dorg.gradle.java.home=$env:JAVA_HOME" --no-daemon --console=plain --max-workers=2 :app:testDebugUnitTest --tests "com.tongxie.copilotgo.data.tools.web.WebLiveSmokeTest"
```

An unavailable live provider remains a failure, not a fixture-generated source.
Device acceptance and application DI are owned by the integration lane.

## References

- <https://exa.ai/docs/reference/exa-mcp>
- <https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning>
- <https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http>
- <https://modelcontextprotocol.io/specification/2026-07-28/server/discover>
- <https://modelcontextprotocol.io/specification/2026-07-28/server/tools>
- <https://modelcontextprotocol.io/specification/2025-11-25/basic/transports>
