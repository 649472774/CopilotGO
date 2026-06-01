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
- 项目 author：Tong Xie `<v-tongxie@microsoft.com>`
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
