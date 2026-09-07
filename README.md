# CopilotGO

使用 Kotlin 和 Jetpack Compose 编写的非官方 Android GitHub Copilot 客户端。
通过 GitHub Device Flow 授权，使用账户已有的 Copilot 服务访问权限；具体可用模型、
配额和端点仍由 GitHub 与组织策略决定。本项目不是 GitHub 官方移动应用。

## 功能与边界

- 原生多会话聊天、模型选择、可停止的 SSE 流式回复。
- Markdown、代码、公式、图片和附件；文件通过 Android 的用户选择器导入。
- 应用级聊天服务，离开聊天页面不会等同于主动停止；进程被系统终止仍会中断请求。
- 原生 HTTP/SOCKS 代理配置；Remote WebView 的网络与登录状态独立管理。
- GitHub Releases 更新检查、可取消下载、完整性与安装兼容性检查。安装由用户确认，
  绝不通过卸载旧包或清空数据来规避签名不兼容。

Copilot 服务接口可能变化。不要把访问失败当成可用模型列表，也不要假定原生登录与
Remote 网页登录可以互相替代。工具/Agent 能力以实际集成并发布的功能为准。

## 技术栈

| 层 | 实现 |
|---|---|
| 语言/构建 | Kotlin 2.x；AGP 内置 Kotlin；Gradle Kotlin DSL；JDK 21 |
| 界面 | Jetpack Compose、Material 3、Navigation Compose、StateFlow |
| 网络 | OkHttp 4.x、Coroutines、SSE；不使用 Retrofit/Ktor |
| JSON | kotlinx.serialization |
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
  data/net/, data/proxy/   统一 HTTP 客户端与代理配置
  data/storage/           会话、附件与凭据存储
  data/update/            有界下载、完整性和安装兼容性
  ui/                     原生界面、Remote 与 ViewModel
```
