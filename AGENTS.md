# AGENTS.md — 给 AI 编码助手的项目须知

> 给 Claude / Copilot / Cursor / 其他 AI agent 的"项目说明书"。  
> 比 README 更聚焦：**怎么干活、踩什么坑、什么不能动**。  
> 人也可以读。

---

## 1. 项目一句话定位

CopilotGo = Android 客户端，复用用户已有的 **GitHub Copilot Enterprise** 订阅 token，提供原生 ChatGPT 风格聊天 UI（多会话、多模型、流式打字机、Markdown、图片视觉、附件）。  
**目标**：在手机上随时随地用 Enterprise Copilot，不依赖网页和 IDE。

---

## 2. 技术栈（不许擅自换）

| 层 | 技术 | 版本 |
|---|---|---|
| 语言 | Kotlin | 1.9.x |
| UI | Jetpack Compose | Material 3 |
| 异步 | Kotlinx Coroutines + Flow | — |
| 网络 | OkHttp（不用 Retrofit） | 4.x |
| 序列化 | Moshi | — |
| 存储 | SharedPreferences + JSON 文件 | — |
| 构建 | Gradle Kotlin DSL + AGP | — |
| 最低 SDK | 26 | — |
| 目标 SDK | 34 | — |

**红线**：
- 不要引入 Retrofit / Ktor / Hilt / Room — 项目刻意保持轻量
- 不要改成 XML 布局
- 不要用 LiveData，统一用 StateFlow

---

## 3. 关键目录

```
app/src/main/java/com/tongxie/copilotgo/
├── MainActivity.kt              # 唯一 Activity
├── data/
│   ├── Constants.kt             # 默认模型、超时等
│   ├── auth/                    # GitHub OAuth + Copilot token
│   ├── chat/                    # SSE 流式聊天客户端 + 数据模型
│   └── storage/                 # 会话持久化
├── ui/
│   ├── screens/                 # ChatScreen, SessionListScreen 等
│   ├── components/              # 复用组件
│   └── viewmodel/               # ChatViewModel 是核心
└── util/Logger.kt               # 统一日志
docs/HISTORY.md                  # 历史 checkpoint，了解为什么这么写
```

---

## 4. 构建 & 发布命令（**就用这套，别瞎改**）

### 4.1 环境准备
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
```

### 4.2 编译 Debug APK
```powershell
cd C:\Code\CopilotGo
./gradlew.bat --no-daemon --console=plain assembleDebug
```
- 用 `--no-daemon` 避免 daemon 持有文件锁
- 用 `--console=plain` 让输出可被 grep
- 增量编译 ~70s，全量首次 ~5 min

### 4.3 装机
```powershell
& $adb -s emulator-5554 install -r app\build\outputs\apk\debug\app-debug.apk
& $adb -s emulator-5554 shell am force-stop com.tongxie.copilotgo.debug
& $adb -s emulator-5554 shell am start -n com.tongxie.copilotgo.debug/com.tongxie.copilotgo.MainActivity
```

### 4.4 发版交付（同步以下 3 处）
1. `app/build/outputs/apk/debug/app-debug.apk`
2. `D:\APK\CopilotGo-debug.apk`
3. 桌面 `CopilotGo-debug.apk`

---

## 5. 版本号规则（**每次发版必须执行**）

文件：`app/build.gradle.kts`

```kotlin
versionCode = N      // 整数，每次发版 +1
versionName = "0.1.X"// 语义化
```

- 修 bug：patch +1（0.1.1 → 0.1.2）
- 加 feature：minor +1（0.1.x → 0.2.0）
- 破坏性变更或正式发布：major +1

**对应规则**：versionCode 和 versionName 同步增长，绝不允许只改一个。

---

## 6. Git 提交规范

### 6.1 commit message 格式
```
<type>: <一句话总结> (v<版本号>)

<空行>
<详细说明：为什么改、怎么改、潜在影响>

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
```

type 取值：`feat` / `fix` / `refactor` / `docs` / `test` / `chore`

### 6.2 流程
```powershell
cd C:\Code\CopilotGo
git add -A
git commit -m "fix: xxx (v0.1.x)" -m "详细说明"
git push
```

### 6.3 红线
- **不要 commit `D:\APK\` 路径下的 APK**（太大，且 .gitignore 已排除 `*.apk`）
- **不要 commit `local.properties`**（含 SDK 绝对路径）
- **不要 commit token / 任何 ghu_ ghp_ 开头的字符串**
- 一律先 `git --no-pager diff --cached --stat` 看 staged 改动再 commit

---

## 7. 与模型对话时的避坑须知（content filter）

GitHub Copilot 后端对**请求内容**有 content filter。AI agent 在和用户对话过程中曾多次被拦：

**触发场景**：
- 大段日志贴入对话（包含 token、cookie、user-agent、device-id 等）
- 含 `secret_key` / `password` / `Authorization:` 等关键字
- 长 base64 字符串（误判作 binary 攻击载荷）

**对策**：
- 回复保持简短，技术细节放代码块或只贴关键几行
- 必须贴长日志时，先 `Select-Object -First 30` 截断
- 不要 echo 用户输入里的敏感字段

---

## 8. 典型 bug 模式（**血泪经验，仔细看**）

### 8.1 Coroutine 跨线程共享变量
```kotlin
// ❌ 错的写法（v0.1.1 崩在这）
var streamDone = false
launch { while (!streamDone) { ... } }
flow.collect { ... }; streamDone = true  // collect 抛错就跳过 → 死循环

// ✅ 对的写法（v0.1.2 修复）
val streamDone = AtomicBoolean(false)
launch {
    try { while (!streamDone.get() || ...) { ... } }
    catch (_: CancellationException) {}
}
try { flow.collect { ... } }
finally { streamDone.set(true); typewriter.join() }
```
**经验**：协程间共享标志位 → `AtomicBoolean` 或 `MutableStateFlow`；I/O 协程一律 `try/finally` 兜底。

### 8.2 Compose + IME 软键盘适配
manifest 里 `windowSoftInputMode="adjustResize"` 在 **edgeToEdge** 下**失效**。  
**必须**给输入框所在 Container 显式加 modifier：
```kotlin
Column(modifier = Modifier.fillMaxSize().imePadding()) { ... }
```

### 8.3 StateFlow 不刷新 UI
`data class` 的 `equals` 会让 StateFlow 误判"无变化"导致 Compose 不重组。  
**解法**：Session 加 `revision: Int` 字段，每次改动 `_session.update { it.copy(revision = it.revision + 1) }` 强制触发。

### 8.4 模型不支持自动 fallback
后端返回 `model_not_supported` 时，自动降到 `Constants.DEFAULT_MODEL`（gpt-5-mini），并在 UI 提示用户。新加模型要先在白名单里测试。

### 8.5 SSE 流式断包
- OkHttp 默认开 gzip → 流式响应被缓冲。必须加：
  ```kotlin
  .header("Accept-Encoding", "identity")
  ```
- 一定要用 `BufferedSource.readUtf8Line()` 而非整段 read

---

## 9. 改代码前的 mandatory 流程

1. **先读 `docs/HISTORY.md` 对应 checkpoint** —— 了解前因后果
2. **跑一次现状构建** —— 确保 baseline 是绿的
3. **只改和任务直接相关的代码** —— 别捎带"顺手优化"
4. **改完编译 + 装机 + 手测发送一条消息** —— UI 改动尤其重要
5. **升 versionCode/versionName**
6. **同步 D:\APK\ 和桌面**
7. **commit + push**

---

## 10. 不要做的事

| 不要 | 原因 |
|---|---|
| 不要把项目复制出 git 仓库再改 | 失去版本追溯 |
| 不要在中文路径下构建 | NDK/AAPT 历史踩坑 |
| 不要改 `applicationIdSuffix = ".debug"` | adb 命令会全部要改 |
| 不要把 token 写死在 Constants | 必须走 OAuth 流程 |
| 不要在主线程做 store.save() | 会 ANR |
| 不要 catch `Throwable` 不打 log | 静默吞异常找不到 bug |
| 不要给 SSE 接口加 Retrofit | OkHttp 直连更可控 |

---

## 11. 跨设备协同（电脑 / 笔记本 / 家里 / 公司）

**唯一同步通道 = GitHub** (https://github.com/649472774/CopilotGO)

```powershell
# 上班/换机：
git pull
./gradlew.bat assembleDebug

# 下班：
git add -A; git commit -m "wip"; git push
```

**绝不**用 U 盘 / OneDrive / 微信传源码——污染 .git。

---

## 12. 联系 / 责任

- 仓库 owner：[@649472774](https://github.com/649472774)
- 项目 author：CopilotGo Maintainer
- AI 协作模式：人定方向，AI 实现 + 提交 + 验证；每次发版 AI 必须填 `Co-authored-by: Copilot`

---

_最后更新：v0.1.2 后_


## 13. 发版流程（v0.1.3+）

一键发版脚本：`scripts/release.ps1`

```powershell
# 常规 patch 发版（0.1.x -> 0.1.x+1），编译 + 装机 + 拷贝
./scripts/release.ps1

# 小版本（功能更新）
./scripts/release.ps1 -Minor

# 大版本（破坏性变更）
./scripts/release.ps1 -Major

# 编译完自动 commit + push 到 main
./scripts/release.ps1 -Push

# 只 bump 版本号不编译
./scripts/release.ps1 -SkipBuild
```

脚本做的事：
1. 解析 `app/build.gradle.kts` 的 versionCode / versionName，按 -Patch/-Minor/-Major 自增
2. 用 Android Studio 内置 JDK 21 调 `gradlew assembleDebug`
3. `adb install -r` 到 emulator-5554（无设备则跳过）
4. APK 拷贝到 `D:\APK\CopilotGo-debug.apk` 和桌面
5. `-Push` 时自动 `git add app/build.gradle.kts && git commit && git push`

## 14. GitHub Actions CI

`.github/workflows/build.yml` 在 push 到 main 或 PR 时自动跑：
- JDK 21 + Android SDK
- Gradle 缓存
- `assembleDebug`
- 上传 APK 为 artifact，名字 `CopilotGo-v<versionName>-debug`

每次 push 后到 GitHub Actions 页可下载 APK。


## 15. v0.1.4 代理（Proxy）架构

**目的**：手机大陆网络无法直连 Copilot 时，让 App 走 Clash/V2Ray 的 HTTP/SOCKS5 端口，不再依赖系统全局 VPN。

**核心文件**：
- `data/proxy/ProxyConfig.kt` - data class（enabled / type / host / port）
- `data/proxy/ProxySettingsStore.kt` - DataStore 持久化，`config: StateFlow<ProxyConfig>`
- `data/proxy/ProxyHealthChecker.kt` - HEAD https://api.githubcopilot.com 测连通
- `data/net/HttpClientProvider.kt` - 监听 ProxyConfig 变化自动重建 OkHttpClient
- `ui/viewmodel/ProxyViewModel.kt`

**铁律**：
- 所有出网的 OkHttpClient **必须** 通过 `HttpClientProvider` 拿（DeviceFlow / TokenRefresher / ChatRepo 都已接）。不要在别处 `new OkHttpClient()`，否则那条链路不走代理。
- WebView **不走** 这个代理（v0.2.0 RemoteWebScreen 要用 `androidx.webkit.ProxyController.setProxyOverride` 单独绑）。
- 模拟器里写 `127.0.0.1` 指的是模拟器自己，宿主机要写 `10.0.2.2`。真机里 `127.0.0.1` 才指手机本身（Clash/V2Ray for Android 的端口）。
- 代理是 HTTP/SOCKS5 **代理端口**，不是 Clash 的订阅 URL；订阅 URL 是给 Clash 自己用的。

## 16. v0.1.5 Settings 拆分模式

主 `SettingsScreen` 只是 4 个 ListItem 入口（账号 / 代理 / 存储 / 关于）：
- `SettingsAccountScreen` - 显示 SKU + 退出登录
- `SettingsProxyScreen` - 完整代理 UI
- `SettingsStorageScreen` - paths.describe()
- `SettingsAboutScreen` - BuildConfig.VERSION_NAME

**接线**（AppNavigation.kt）：
- Routes 新增 `SETTINGS_ACCOUNT/PROXY/STORAGE/ABOUT`
- 每个子页一个 `composable` 块
- ProxyViewModel 在 SETTINGS 和 SETTINGS_PROXY 两个路由内 `viewModel(factory=...)` 各自重建 —— DataStore 是状态源，重建会从 StateFlow 立刻补回 saved config，OK
- AuthViewModel 用 NavHost 外层那个全局实例

**Material 3 注意**：
- `Card(modifier = Modifier.clickable(onClick=))` + 内嵌 ListItem 让 ListItem 背景透明（`colors = ListItemDefaults.colors(containerColor = Color.Transparent)`）
- 返回箭头用 `Icons.AutoMirrored.Filled.ArrowBack`，不要用 `Icons.Default.ArrowBack`（已 deprecated）
- 右侧箭头用 `Icons.AutoMirrored.Filled.KeyboardArrowRight`

## 17. release.ps1 已知坑

- `[switch]\` / `[switch]\` 跟原局部变量 `\` / `\` 大小写不敏感冲突（PowerShell 变量大小写不敏感），导致 versionName 拼出来全 0。**已修**：局部变量改名 `\` / `\`。新加 switch 时小心。
- `-SkipBump`：手动改完 `versionCode/versionName` 后用这个 flag 跳过自动 bump 直接 build。
- `git add -A`：会把所有改动一起 commit，加 `-Push` 前 `git status` 看一下别误传调试代码。
- 没有 emulator 时 install 步骤会跳过，不会失败。

## 18. 版本号显示：必须用 BuildConfig

- UI 上任何展示 App 版本号的位置，**必须** 用 `BuildConfig.VERSION_NAME` + `BuildConfig.VERSION_CODE`，**禁止** 硬编码字符串（"V0.1" 这种）。每次发版肯定忘记改。
- 启用方式（已开）：`app/build.gradle.kts`
  ```
  buildFeatures {
      buildConfig = true
  }
  ```
- import：`import com.tongxie.copilotgo.BuildConfig`（包名跟 namespace 一致）
- debug 构建带后缀 `-debug`，所以显示形如 `0.1.5-debug`，正常。

## 19. Debug 包名带 .debug 后缀

- `app/build.gradle.kts` 里 debug buildType 有 `applicationIdSuffix = ".debug"`
- 实际包名是 `com.tongxie.copilotgo.debug`，不是 `com.tongxie.copilotgo`
- adb 命令必须用 debug 包名：
  ```
  adb shell am force-stop com.tongxie.copilotgo.debug
  adb shell monkey -p com.tongxie.copilotgo.debug -c android.intent.category.LAUNCHER 1
  ```
- release 构建（如果以后做）才用不带后缀的包名。

## 20. AuthState 数据模型（避免再踩）

`data/auth/Models.kt` 里 `AuthState` 是 sealed interface，注意子类**只有**这些字段：
- `NotLoggedIn`（object）
- `AwaitingUserAuthorization(userCode, verificationUri, expiresInSeconds)`
- `LoggedIn(sku: String?)`  ← **只有 sku，没有 login 字段**！想显示用户名要先扩字段并在 AuthRepo 填进去
- `Failed(message)`

## 21. 易踩坑总表

| # | 现象 | 原因 / 修复 |
|---|------|-------------|
| 1 | UI 显示版本号不对 | 硬编码了字符串。改用 `BuildConfig.VERSION_NAME` |
| 2 | adb force-stop / start 不生效 | 漏了 `.debug` 后缀。debug 包名是 `com.tongxie.copilotgo.debug` |
| 3 | release.ps1 versionName 全是 0 | switch 跟局部变量大小写冲突。已修，新加 switch 注意 |
| 4 | 代理设置改了但请求没生效 | 哪里偷偷 new 了新的 OkHttpClient。所有出网必须从 HttpClientProvider 拿 |
| 5 | 模拟器代理填 127.0.0.1 连不上 | 模拟器里 127.0.0.1 = 模拟器自己。宿主机用 10.0.2.2 |
| 6 | Sub-agent 写完代码就过 | sub-agent 编译不算数，必须自己 `gradlew assembleDebug` 复跑 |
| 7 | Icons.Default.ArrowBack 警告 | 已 deprecated，改用 `Icons.AutoMirrored.Filled.ArrowBack` |
| 8 | LoggedIn.login 编译失败 | LoggedIn 没有 login 字段，只有 sku |
| 9 | 进设置子页 ViewModel 状态丢失 | ProxyViewModel 是 DataStore-backed，每次 viewModel(factory=…) 重建会自动补回 config，OK；如果某天加了纯内存的 editing state 才要考虑共享 scope |
| 10 | git push 不上 | remote 是 https://github.com/649472774/CopilotGO，确认 `gh auth status` OK |
| 11 | 装新版本后 ANR / 卡死 | 99% 是 IO 跑到了 main：SSE / OkHttp / 任意阻塞 Flow 必须 `flowOn(Dispatchers.IO)`；UI 收集端 `LaunchedEffect{ flow.collect{} }`，**别在 collect 里做 parse / 大对象 toString** |
| 12 | 气泡撑满整个屏幕 | `Box.widthIn(max = 320.dp)` 容器**里面不要 fillMaxWidth**，否则强制撑到 320dp。流式内容自然 wrap_content 即可 |
| 13 | 流式中 markdown 不变粗 / 公式 raw | 早期用了 dev.jeziellago compose-markdown，流式中卡。**必须**用纯 Compose 增量 parser（见 SimpleMarkdownText），每个 chunk 都 recompose；`remember(markdown)` 缓存 parse 结果 |
| 14 | 升级版本号忘了 bump | 改 `app/build.gradle.kts` 的 `versionCode + versionName` 两个字段，**不要只改一个**；versionCode 必须递增整数，versionName 是字符串 `0.x.y` |

---

## 22. ANR / 主线程铁律（**v0.1.5–0.1.8 反复踩**）

- **任何 SSE / OkHttp / IO Flow 必须 `flowOn(Dispatchers.IO)`**。否则 Flow upstream 跑到 main，UI 收集时所有 emit/parse 都阻塞主线程。
- UI 端：`LaunchedEffect(key) { repository.streamXxx().collect { state.value = it } }`，**collect block 里只做 state 赋值**，禁止 parse / regex / 大 toString。
- SseParser / JSON 解析全部在 IO 线程；只把"已 parse 完的小数据"emit 回来。
- `viewModelScope.launch` 默认 main，里面发起 IO 一定带 `withContext(Dispatchers.IO)` 或 Flow `flowOn`。
- 调试 ANR：`adb shell dumpsys gfxinfo <pkg> framestats`、`adb logcat -d *:E | sls "ANR|AndroidRuntime"`、用户截图里看是否定帧。

## 23. 版本号 bump 检查清单

`app/build.gradle.kts` defaultConfig：
- [ ] `versionCode = N+1`（整数递增）
- [ ] `versionName = "0.x.y"`（字符串）
- BuildConfig 自动生成；UI 用 `BuildConfig.VERSION_NAME`，**不许硬编码字符串**。
- debug 包会自动后缀 `-debug`（applicationIdSuffix + versionNameSuffix）。
- 装机：`gradlew assembleDebug installDebug`，APK 复制 `D:\APK` + 桌面。

## 24. Compose 布局两个反直觉点

1. **`widthIn(max=X)` 容器内不要 `fillMaxWidth()`**。max 是上限，fillMaxWidth 会强制取上限值，自适应失效。
2. **`Modifier.weight(1f)` 只在 Row/Column 内生效**；跨容器要重新指定。

## 25. LaTeX 公式渲染（v0.1.9 集成）

- 依赖：`io.noties.markwon:ext-latex:4.6.2`（实际只为 transitive 拉 `ru.noties:jlatexmath-android:0.2.0`，也可直接依赖后者）
- **不要**用 `JLatexMathPlugin` / Markwon TextView（会再次引入 v0.1.5 那种 TextView 重布局 ANR）
- **直接用底层 API**（见 `ui/components/LatexView.kt`）：
  ```kotlin
  val icon = TeXFormula(latex).createTeXIcon(TeXConstants.STYLE_DISPLAY, sizePx)
  icon.insets = ru.noties.jlatexmath.awt.Insets(4,4,4,4)
  icon.setForeground(ru.noties.jlatexmath.awt.Color(textColor.toArgb()))
  val bmp = Bitmap.createBitmap(icon.iconWidth.coerceAtLeast(1), icon.iconHeight.coerceAtLeast(1), ARGB_8888)
  val g2d = AndroidGraphics2D().apply { setCanvas(Canvas(bmp)) }
  icon.paintIcon(null, g2d, 0, 0)
  ```
- 包路径速查：
  - `org.scilab.forge.jlatexmath.*` → TeXFormula / TeXIcon / TeXConstants（核心引擎）
  - `ru.noties.jlatexmath.awt.*` → AndroidGraphics2D / Color / Insets / Component（Android 桥接）
  - `io.noties.markwon.ext.latex.*` → **不要直接用**
- **Bitmap 尺寸要 `coerceAtLeast(1)`**，空公式会抛 IllegalArgumentException。
- 渲染必须 `remember(latex, textColor, sizeSp)` 缓存，否则每帧重新跑 TeXFormula parser 卡死。
- Markdown 集成：`SimpleMarkdownText` parser 识别 `$$...$$`（块）+ `$...$`（行内），其余文本走原有 inline 渲染。
- 字体资源（assets/fonts/...）由 aar merge 进 APK，运行时自动加载，无需手动初始化。




## 26. ChatScreen 滚动锚定（v0.1.9 修复）

**Bug**：流式输出过程中用户向上翻看，每来一个 SSE chunk 都被强行弹回底部。

**根因**：用 `lastIndex` 触发 `LaunchedEffect` 不行，因为列表长度没变化（同一条 assistant message 反复 update）。

**修复**（`ChatScreen.kt`）：
- 用 `derivedStateOf` 把"最后一条 message.content.length"作为 key
- `LaunchedEffect(lastContentLen, isAtBottom)` 仅当 **当前已经在底部** 时自动滚动
- "在底部"判定：`listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == messages.lastIndex && offset 接近 0`
- 用户手动上滑 → `isAtBottom = false` → 后续 chunk 不再强制滚

如果未来又遇到"被弹回去"的反馈，先检查这套判定逻辑是否生效（log `isAtBottom` 值）。

## 27. ChatStreamCenter — 后台流式（v0.1.10）

**问题**：原 ChatViewModel 跟着 `NavBackStackEntry` 走，用户退出 chat 屏幕 → VM 销毁 → `viewModelScope.cancel()` → SSE 流被切断。

**方案 B**：把流任务搬到 Application 单例 `ChatStreamCenter`（`data/chat/ChatStreamCenter.kt`），ChatViewModel 退化为薄 wrapper（30 行，只 forward）。

**关键点**：
1. `ChatStreamCenter.scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`
   —— Application 生命周期，VM 销毁不影响。**必须 Main.immediate**（参考 §22 ANR 铁律：SSE collect lambda 在 Main 只做 list mutate + StateFlow update，绝不能切 IO，否则又会 ANR）。
2. 每 session 隔离：`ConcurrentHashMap<String, MutableStateFlow<Session?>>` + `jobs/sendingFlows/errorFlows`。
3. `sessionFlow(id)` 懒创建：第一次访问触发 `store.load()`（`AtomicBoolean + Mutex` 保证幂等并发安全）。
4. 流式 collect 内每 **800ms 节流** `store.save(s)`，防进程被杀全丢。
5. **残留 isStreaming 修正**：load 时若 `jobs[id] == null` 但 message 仍 `isStreaming=true`（上次进程被 OS 杀），把它修正成 `false`；如果 content 也是空，content 设为 `"[已中断]"` 避免空气泡。
6. CancellationException 必须 **rethrow**（用户主动 stop 时保留已收内容 + 清 streaming 标志 + save，然后 `throw e`）。
7. AppContainer (`CopilotGoApp.kt`) 持有 singleton；`AppNavigation` 用 `key = sessionId` 复用 VM 实例（同 session 多次进出复用同一 wrapper）。

**已知限制**：
- App 进程被 OS 杀，流仍断。要"切到后台都不断"必须 Foreground Service（v0.2.x 计划）。
- 流式中节流 save 会刷新 Session.updatedAt → SessionList 顺序变化，**这是预期行为**。

**调试**：
- "退出 session 流就断" → 检查 `ChatStreamCenter.scope` 是否被错误绑到 viewModelScope。
- "进 session 看到 `...` 永远转" → 检查 `sessionFlow()` 的残留修正分支。
- "同一 session 两个 VM" → 检查 `AppNavigation.kt` `viewModel(key = sessionId, ...)` 这个 key 是否漏了。
