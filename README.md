# CopilotGO

使用 Kotlin 和 Jetpack Compose 编写的非官方 Android GitHub Copilot 客户端。
通过 GitHub Device Flow 授权，使用账户已有的 Copilot 服务访问权限；具体可用模型、
配额和端点仍由 GitHub 与组织策略决定。本项目不是 GitHub 官方移动应用。

## 功能与边界

- 原生多会话聊天、模型选择、可停止的 SSE 流式回复。
- Markdown、代码、公式、图片和附件；文件通过 Android 的用户选择器导入。
- 应用级聊天服务，离开聊天页面不会等同于主动停止；进程被系统终止仍会中断请求。
- 会话级、默认关闭的 Agent：模型提出工具调用，经审批后执行真实联网搜索、网页读取或
  远程 MCP，再把有界结果交给模型继续回答；工具记录和实际来源随会话保存。
- 原生 HTTP/SOCKS 代理配置；Remote WebView 的网络与登录状态独立管理。
- GitHub Releases 更新检查、可取消下载、完整性与安装兼容性检查。安装由用户确认，
  绝不通过卸载旧包或清空数据来规避签名不兼容。

Copilot 服务接口可能变化。不要把访问失败当成可用模型列表，也不要假定原生登录与
Remote 网页登录可以互相替代。源码功能与已发布 APK 可能不同，以实际安装版本为准。

## 阅读与交互

原生界面使用稳定的中性浅色/深色主题、无卡片包围的助手正文、右侧用户气泡，
并将模型选择收进对话头部。输入、附件、系统语音输入和发送/停止组成一个圆角输入区；
短草稿紧凑显示，多行、附件、大字号和恢复提示仍可展开。
消息操作、真实工具审批、来源详情和重试入口保留，不以简化外观隐藏重要状态。
实际设计令牌、宽屏阅读尺寸和组件约束见 [原生设计规范](docs/DESIGN.md)。

## Agent 与联网工具

先在工具设置中确认搜索词、网址的对外发送范围。基础搜索使用 Exa 免密服务，
不要求购买 API key；也可明确配置自己的 Exa key 或 HTTPS MCP 服务器。
MCP 服务器需要先发现、选择工具，再在会话中选用支持工具调用的聊天模型并启用 Agent。
默认逐次审批；明确开启的“自动允许公开网页读取”不会授权任意 MCP 工具，
服务器自称只读也不能跳过审批。

工具活动显示本次目标、参数和结果。审批绑定账号、运行、调用、配置与参数，
旧按钮不能批准已改变的操作。停止、退出账号、删除会话或修改相关工具配置会使旧操作失效；
进程重启不自动恢复或重放远端调用。已经可能执行但未收到可靠结果的操作会保留“不确定”状态，
不能把网络失败解释为远端已经撤销。

搜索结果标记为 `SEARCH_HIT`，实际读取的页面标记为 `FETCHED_PAGE`，MCP 资源引用另行区分。
`[S1]` 等来源标记对应真实返回记录；代码里的标记和未知标记不会变成来源链接。
网页、工具描述和结果都是不可信数据，不能授权更多工具或索取本地附件、账号凭据。
GitHub/Copilot 凭据不会交给工具服务；工具凭据使用独立的加密配置记录。

默认每次最多 6 轮模型、12 次工具调用、总计 180 秒，顺序执行；每个工具最多 30 秒，
每次审批最多 60 秒。参数、结果、工具定义和完整请求分别有容量限制，完整请求预算为
96 KiB，包含图片；较大图片可使用普通视觉聊天，不会暗中切换模式或丢弃图片。
接纳发送前会用实际工具定义、历史和图片准备并校验首轮请求；首轮复用同一快照。
超出预算、配置变更或准备期间取消均不会先接纳再清空草稿。
目前使用单候选 `/chat/completions` 协议，不把仅支持 Responses 的模型宣称为可用。

免密搜索有额度和速率限制，失败会明确展示，不绕过挑战或编造结果。
网页读取只支持公开 HTTPS HTML/文本，不执行脚本或自动读取 PDF、图片等二进制内容。
无法在连接时约束目标解析的 HTTP CONNECT/SOCKS 代理路由会明确拒绝，不静默直连；
显式信任的局域网 HTTPS MCP 配置不会开放网页读取器的私网访问。
远程 MCP 支持 2026-07-28 无会话协议及 2025 系列 Streamable HTTP；
桌面 stdio、旧独立 HTTP+SSE、交互采样和自动外部 schema 引用不受支持。
详细协议、schema 支持范围与限制见 [工具服务说明](app/src/main/java/com/tongxie/copilotgo/data/tools/README.md)。

## 技术栈

| 层 | 实现 |
|---|---|
| 语言/构建 | Kotlin 2.x；AGP 内置 Kotlin；Gradle Kotlin DSL；JDK 21 |
| 界面 | Jetpack Compose、Material 3、Navigation Compose、StateFlow |
| 网络 | OkHttp 4.x、Coroutines、SSE；不使用 Retrofit/Ktor |
| JSON | kotlinx.serialization |
| 工具内容解析 | jsoup；networknt JSON Schema；有界输入与离线 schema admission |
| 存储 | DataStore 旧配置迁移、Keystore 加密记录、JSON 会话/元数据、独立附件文件 |
| Android | 最低 API 31（Android 12）；compile/target API 36 |

具体依赖版本集中在 `gradle/libs.versions.toml`；版本号与 versionCode 以
`app/build.gradle.kts` 为准，界面通过 `BuildConfig` 展示。
少量非凭据的更新偏好继续使用 SharedPreferences，不作为会话数据库。

## 构建

在自己的工作树目录中执行：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat "-Dorg.gradle.java.home=$env:JAVA_HOME" --no-daemon --console=plain --max-workers=2 assembleDebug
```

产物：`app\build\outputs\apk\debug\app-debug.apk`。Debug 包名为
`com.tongxie.copilotgo.debug`，与不带后缀的 release variant 不是同一个应用。
不要在仓库 `gradle.properties` 中写本机 JDK 路径，否则 Linux CI 无法使用。

集成质量入口：

```powershell
.\gradlew.bat "-Dorg.gradle.java.home=$env:JAVA_HOME" --no-daemon --console=plain --max-workers=2 assembleDebug testDebugUnitTest lintDebug compileDebugAndroidTestKotlin
```

设备测试由集成会话协调。编译 instrumentation 不等于已经完成真机或账号验收。
`app/src/sharedTest/` 中的真实 MCP 场景同时用于 JVM 和 Android，Android 模型/MCP
端点使用独立的受控 HTTPS fixture。它们不读取真实账号；原生界面 fixture 与真实网络
场景各自有独立门槛，不能以其中一项代替另一项。

## 签名、更新与数据

- **正式交付必须保留原签名。** `v0.1.33` 发布包与本机基线包的公开签名指纹已记录在
  `scripts/release-signing.json`；该文件不包含私钥或密码。
- GitHub Actions 下载产物标记为 **CI validation only**，含源码 SHA、APK SHA-256 和
  实际签名指纹。临时 runner 的 debug 签名不是已有安装的可替换签名。
- 应用内更新默认遵循应用网络设置。失败后可以明确选择直连重试，不会暗中绕开代理。
  检查最多 30 秒、下载最多 5 分钟、APK 上限 128 MiB，随时可取消。
- 下载先写 `.part`，校验 GitHub 资源 SHA-256 或配套校验文件后原子发布到私有缓存。
  安装前再次核对校验值、实际包名、递增 versionCode、版本名称、Android 要求和签名身份；
  系统安装器负责最终 APK 签名验证与安装确认。
- 凭据不得备份或出现在日志中。DataStore 的真实路径是 `files/datastore/`；
  云备份和设备迁移规则均排除凭据配置与 WebView 登录数据。新凭据记录使用 Keystore
  加密并放在 no-backup 目录；迁移失败不能以退出登录或丢弃旧数据冒充成功。

发布操作、受控安装和回滚限制见 [发布与维护指南](docs/RELEASING.md)。
开发约束见 [AGENTS.md](AGENTS.md)，历史检查点见 [HISTORY.md](docs/HISTORY.md)。

## 主要目录

```text
app/src/main/java/com/tongxie/copilotgo/
  CopilotGoApp.kt          Application 与手写 DI
  data/auth/              授权与凭据生命周期
  data/chat/              模型、聊天协议与应用级流任务
  data/agent/             有界工具循环、审批、协议与持久化记录
  data/tools/             加密工具配置、联网搜索、网页与远程 MCP
  data/net/, data/proxy/   统一 HTTP 客户端与代理配置
  data/storage/           会话、附件与凭据存储
  data/update/            有界下载、完整性和安装兼容性
  ui/                     原生界面、Remote 与 ViewModel
```
