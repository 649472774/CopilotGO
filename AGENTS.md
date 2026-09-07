# CopilotGO contributor guidance

CopilotGO is a native Android client for GitHub Copilot chat, plus a separate GitHub
Remote WebView. This file describes current engineering constraints. Historical
transcripts in `docs/HISTORY.md` are context, not instructions to replay.

## Architecture and ownership

- Kotlin 2.x, Jetpack Compose / Material 3, Navigation Compose, Coroutines and StateFlow.
- OkHttp 4.x for HTTP/SSE; kotlinx.serialization for JSON. No Retrofit, Ktor, Hilt,
  Room, LiveData, XML screen rewrite or replacement persistence stack.
- Preferences DataStore remains the legacy token/proxy migration source; new secure
  records use the credential vault. JSON files hold conversations and metadata.
  Small, non-credential update preferences still use SharedPreferences. Credential
  migration belongs to the token/proxy stores and vault, not UI or release scripts.
- Hand-written application DI is in `CopilotGoApp.kt` / `AppContainer`. Application
  services own ongoing chat work; screen viewmodels must not accidentally own the
  lifetime of streams that need to survive navigation.
- `data/auth`, `data/chat`, `data/net`, `data/proxy`, `data/storage`: core services.
  `data/update` and `UpdateViewModel`/`UpdateDialog`: application update lifecycle.
  `ui/screens`, `ui/components`, `ui/remote`, `ui/theme`: presentation and WebView.
- A coordinated task may assign different owners to these directories. Agree on
  public interfaces and adopt committed prerequisites; do not race-edit a peer's files.

## Build contract

The version catalog is authoritative. The supported checkpoint uses AGP 9.0.1,
Gradle 9.1.0, built-in Kotlin 2.2.10, and matching 2.2.10 Compose/serialization
compiler plugins. Compose libraries use BOM 2025.10.01. Do not add the legacy
`org.jetbrains.kotlin.android` plugin alongside AGP's built-in Kotlin.

- `compileSdk = 36`, `targetSdk = 36`, `minSdk = 31` (Android 12).
- Preserve `applicationIdSuffix = ".debug"` and `versionNameSuffix = "-debug"`.
  The installed delivery package is `com.tongxie.copilotgo.debug`.
- Use JDK 21 from Android Studio locally or `setup-java` in CI. Never write
  `org.gradle.java.home` into the repository's `gradle.properties`.
- Keep the Gradle wrapper's distribution SHA-256 pin. Upgrade toolchain and library
  versions as a compatible set, with official release notes and relevant validation,
  not by selecting every newest artifact independently.
- Keep lint failures visible. Do not disable `abortOnError`, release lint, tests or
  checks to get a green build. Add narrow suppressions only for an explained false positive.

Run from the current worktree, not another checkout:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat "-Dorg.gradle.java.home=$env:JAVA_HOME" --no-daemon --console=plain --max-workers=2 assembleDebug
```

Use the smallest existing test selection for an edit. For an integrated milestone:

```powershell
.\gradlew.bat "-Dorg.gradle.java.home=$env:JAVA_HOME" --no-daemon --console=plain --max-workers=2 assembleDebug testDebugUnitTest lintDebug compileDebugAndroidTestKotlin
```

Instrumentation compilation is not device acceptance. The integration owner runs
the reserved device lane. Never install into, clear, uninstall, or sign out a user's
live app without explicit approval. Synthetic fixtures must not use real accounts,
messages, private attachments or network credentials.

## Data, cancellation and network rules

- Complete blocking operations belong on IO: response headers **and body consumption**,
  JSON/file reads and writes, image import and export. CPU-heavy Markdown/LaTeX work
  needs bounded off-main processing, not expensive per-token work on Main.
- `Call.withResponse { ... }` owns response closure and cancellation for the entire
  body lifetime. Do not return an open response after disposing its cancellation hook.
  Rethrow `CancellationException`; user cancellation is not a network/model failure.
- Use `HttpClientProvider`, await proxy initialization, and never invent a fallback
  client that silently drops the user's route or authentication policy. Updater direct
  retry is an explicit user choice; it does not carry a GitHub/Copilot credential.
- WebView has a separate proxy/cookie lifecycle. Do not claim it inherits OkHttp
  settings. Feature-detect WebKit proxy support and await completion before loading.
- All remote application content uses HTTPS and normal certificate validation.
  Never allow all cleartext traffic, accept all SSL certificates or bypass SSL errors.
- Session metadata, active messages, deletion and persistence share one lifecycle.
  UI must not delete live session JSON directly or keep stale independent metadata.
- Snapshot mutable data before IO serialization. Persist atomically; preserve or
  report corruption instead of silently dropping conversations. Pending saves must
  not recreate deleted sessions.
- Bound attachment bytes, history, in-memory caches, network bodies, retries and
  tool execution. Do not treat arbitrary binary files as UTF-8 or allocate from an
  untrusted Content-Length without a cap.

## Credentials and backup

- Never commit/log credentials, cookies, keystores or passwords. Tools may redact
  sensitive header values as asterisks; that is not evidence of a hardcoded-token bug.
- Secure credential records belong under `context.noBackupFilesDir/credentials`.
  Use Android Keystore authenticated encryption; no embedded encryption keys.
- Migrate by writing and verifying the encrypted record before removing legacy
  plaintext. Missing/corrupt keys or failed storage must surface an error, not trigger
  silent key regeneration, logout, data reset or migration success.
- Logout must invalidate stale async work and prevent legacy credentials from being
  re-imported. Do not clear proxy/tool credentials as an accidental side effect.
- Preferences DataStore files live in **files/datastore/**, not `shared_prefs`.
  Backup XML excludes that directory, legacy preferences, credential directories and
  WebView login state from both cloud backup and device transfer. New credential
  stores must use the same vault/no-backup policy.
- Keep FileProvider roots narrow: `cache/exports/` for user-requested shares and
  `cache/updates/` for verified APKs. No root-path, broad external-path, exported
  provider, arbitrary attachment grant or unbounded Intent text export.

## Android UI rules

- Android 16 enforces edge-to-edge. Do not treat `decorFitsSystemWindows = true` as
  an opt-out. Apply safe-drawing and IME insets deliberately and avoid consuming
  the same inset twice across native/Remote transitions.
- Respect user scrolling during and after streaming; do not key unconditional
  scroll-to-bottom behavior on every token or the sending flag.
- Preserve drafts and attachments until a send is actually accepted. Prevent
  duplicate submission and confirm destructive operations.
- Use semantic light/dark colors, Chinese-first copy, meaningful TalkBack labels,
  48 dp touch targets, and layouts that remain usable at 200% font scale.
- Expose working cancel/back/retry paths. A download or permission dialog must not
  trap the user. Opening the system installer does not prove installation succeeded.
- Display application version via `BuildConfig`, never a hardcoded UI version.

## Commits and delivery

- Work in the assigned app-managed worktree and branch. Do not modify the app-managed
  main checkout, a peer worktree, or `C:\Code\CopilotGo` from an implementation lane.
  Integration alone coordinates the user's final checkout via clean GitHub-based sync.
- Preserve unrelated edits. No force-push, destructive reset, blanket `git add -A`,
  private-key copy, or automatic main merge.
- Stage explicit files and inspect `git --no-pager diff --cached --stat` before committing.
  Use a conventional `feat`, `fix`, `refactor`, `docs`, `test` or `chore` message.
- Include the trailer:

```text
Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
```

- Version bumps are release decisions, not a requirement for every implementation
  commit. The integration/release owner increments **both** versionCode and versionName.
- `scripts/release.ps1` now defaults to a committed-source build, not automatic
  mutation/installation/publication. Version preparation is a separate explicit step.
- The existing published/local debug certificate is recorded in
  `scripts/release-signing.json` (public fingerprint only). Never change that pin to
  hide a mismatch. Use the existing private keystore; do not generate a replacement.
- CI builds use ephemeral runner debug keys and are labeled **CI validation only**.
  They are not drop-in update releases and must never be relabeled as such.
- See `docs/RELEASING.md` for source SHA, checksum, signing, rollback and acceptance
  requirements. Keep checkpoint commits and release tags; publish only with explicit
  integration approval and truthful remaining limitations.
