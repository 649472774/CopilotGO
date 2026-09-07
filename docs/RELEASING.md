# 发布与维护

## 渠道与责任

当前用户交付渠道是带 `.debug` 包名后缀、使用既有 debug 签名的 APK。切换到
`assembleRelease`、更换签名或去掉后缀都不是一次兼容升级。不要卸载、清空数据或使用
降级参数来掩盖版本/签名问题。

实现会话可以在获授权后提交并推送自己的检查点。版本号、设备验收、合并主分支、
GitHub Release 和用户最终交付目录由集成负责人统一操作。脚本不自动发布 Release。

已确认的回滚基线：

| 项目 | 记录 |
|---|---|
| 标签 / 源码 | `v0.1.33` / `216ac26f3e8e92a4ab9e5870d64d43fbe8480b48` |
| 安装身份 | `com.tongxie.copilotgo.debug`，versionCode 34，`0.1.33-debug` |
| 公开证书 SHA-256 | `89dbef99c2c81eb48b0bf28dd698631ffcdf8ad7cbb4dac9960914096e68928b` |
| 发布 APK SHA-256 | `f9acee141f956818fdeae64f6082c9b3a2f506f3c4e8b61da57ba7237b93e4d5` |

发布包和本机基线包的证书相同；这不自动证明任何尚未检查的设备安装身份。
公开记录位于 `scripts/release-signing.json`。保留原标签和 APK，不重写历史。

## 1. 先准备并提交版本

在当前工作树使用 PowerShell 7。脚本默认**不升版、不装机、不复制、不提交、不推送**。
先保证源码工作树干净，显式准备版本：

```powershell
# 修复发布用 -Patch；功能里程碑用 -Minor；重大变更用 -Major。
pwsh -File .\scripts\release.ps1 -Minor -SkipBuild
git add -- app\build.gradle.kts
git --no-pager diff --cached --stat
git --no-pager diff --cached
# 审核后提交，包含 Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
```

versionCode 必须递增，versionName 同步变化。代码检查点不需要各自升版。
版本准备与构建拆开，保证交付的版本对应**真实已提交的源码 SHA**。
旧的 `-SkipBump` 可保留，但现在本来就不会隐式升版。

## 2. 使用既有签名构建

脚本要求已有签名文件，缺失时直接失败，不替用户生成或复制新私钥。
默认使用 Android 用户目录已有的 `debug.keystore`；支持通过以下**环境变量名称**
引用用户自备的同一签名身份：

```text
COPILOTGO_SIGNING_STORE_FILE
COPILOTGO_SIGNING_STORE_PASSWORD
COPILOTGO_SIGNING_KEY_ALIAS
COPILOTGO_SIGNING_KEY_PASSWORD
```

不要把值写进 Gradle 文件、命令行参数、日志、GitHub workflow、源码或示例。
私钥备份由用户通过其私密备份流程保管；本仓库只记录公开指纹。
更换文件位置不等于允许更换证书。不要改指纹 pin 让错误签名的构建通过。

```powershell
pwsh -File .\scripts\release.ps1 -JavaHome "C:\Program Files\Android\Android Studio\jbr"
```

脚本使用 `assembleDebug`、`testDebugUnitTest`、`lintDebug`、
`compileDebugAndroidTestKotlin`，并检查每个 native 命令退出码。需要 Android SDK
platforms;android-36、build-tools;36.0.0 和 platform-tools。

构建后读取 APK 的真实包名/版本和 `apksigner verify` 结果，核对回滚证书，生成：

```text
app/build/delivery/v<version>-<source-sha-prefix>/
  CopilotGo-v<version>-debug.apk
  CopilotGo-v<version>-debug.apk.sha256
  SHA256SUMS
  build-provenance.json
  release-notes.md
```

记录包含精确源码 SHA、分支、APK 摘要、实际证书指纹、版本、时间与回滚标签。
同一来源目录已存在不同 APK 时拒绝覆盖，防止混淆产物。发布前补齐实际设备验收、
已知限制和人工审阅过的说明；脚本的提交摘要不是完整验收报告。

## 3. 显式交付，不隐式操作设备

```powershell
# 多设备时必须指定在线且已授权的 serial；只有一台时可自动选中。
pwsh -File .\scripts\release.ps1 -Install -DeviceSerial emulator-5580 -Copy

# 仅在确实授权推送当前工作分支时添加 -Push。
pwsh -File .\scripts\release.ps1 -Push
```

示例 serial 不是固定目标，执行前用 `adb devices` 确认。`-Install` 找不到真实目标时
失败而不是显示装机成功；只使用 `adb install -r`，从不卸载、清数据或强制降级。
`-Copy` 才会复制到 `D:\APK\CopilotGo-debug.apk` 和桌面，并生成旁路 SHA-256 文件；
覆盖前按旧 APK 的完整摘要保存副本。可通过 `-ApkOut` / `-DesktopOut` 指定交付位置。
`-SkipInstall` / `-SkipCopy` 仍可接受，但默认就是不执行这些操作。

`-Push` 仅推送已经构建的当前提交与当前分支，不 stage、不 commit、不创建标签、
不合并 main、不创建 Release。源码或分支在构建/交付期间变化会被拒绝。
`C:\Code\CopilotGo` 的最终同步由集成负责人在重新确认干净后通过 GitHub 快进完成；
实现会话不要直接复制源码或改动那个 checkout。

## 4. GitHub Release 与应用内更新协议

- 仅发布经过集成验收的稳定 APK；未完成验收的候选必须明确标为 prerelease。
- 标签使用 `v<major>.<minor>.<patch>`。上传唯一、命名清晰的
  `CopilotGo-v<version>-debug.apk`，以及 `.apk.sha256` 或 `SHA256SUMS`。
- GitHub 资源的 `digest: sha256:...` 优先；缺失时更新器读取配套校验文件。
  多文件校验表必须包含该 APK 的唯一精确文件名记录。
- 每个正式更新必须有更大的 Android versionCode、相同 packageName 和当前签名身份。
  签名轮换不自动放行；如未来需要轮换，先设计并验证系统签名历史支持。
- 更新默认等待代理配置加载并遵循应用路由。用户可明确选择直连重试，不做隐式回退。
  每次操作总等待有界，重定向限次且仅允许项目 GitHub 发布与可信资源 CDN 的 HTTPS。
- 元数据上限 2 MiB、校验文件上限 64 KiB、APK 上限 128 MiB。
  检查最多 30 秒，下载（含重定向/校验文件）最多 5 分钟。
- 临时文件使用私有 `cache/updates/`，按实际字节数和 SHA-256 校验，成功才原子重命名。
  失败/取消清理 `.part`，不把不完整文件交给安装器。
- 安装前重新校验文件，读取实际 APK 包名/versionCode/versionName/minSdk/证书集合，
  与当前安装比较。PackageManager 提取的签名身份并不替代最终系统 APK 签名验证。
- 下载完成不会自动安装。权限设置返回、权限仍被拒绝、安装器返回都各有状态，
  不能把“已打开安装器”显示为“更新成功”。

## 5. CI 不是正式签名发布

`.github/workflows/build.yml` 对 main push、PR 和手动运行执行构建、单测、严格 lint
和 instrumentation 编译。Gradle wrapper 校验与缓存由官方 Gradle action 管理。
workflow 只有只读 contents 权限，不读取或上传用户签名材料，也不创建发布。

产物名为 `CopilotGo-ci-validation-<sha>`，包含真实构建 SHA、APK 摘要、证书指纹及
明确的临时签名警告。每个 runner 的 debug key 可能不同，因此该 APK **不是现有
用户安装的可直接替换更新**。不要把它重命名后上传到正式 Release，也不要让用户
通过卸载旧应用来试装。测试失败不能由 artifact 上传步骤掩盖。

## 6. 设备验收与回滚

在隔离、无真实凭据的 fixture 设备上覆盖安装保留的基线 APK，再安装新 APK，
确认既有会话/设置保留以及实际 package/version/signature。不要清理用户 AVD 来制造
“干净通过”。升级前后的持久化/加密迁移测试应使用专门构造的数据。

更新器的 JVM 测试覆盖 body IO、socket 取消、总超时、长度/摘要/重定向边界、
并发检查与下载、代理选择、重试和权限状态。设备侧包含：

- `ApkArchiveValidationTest`：使用打包的应用/测试 APK，检查非升级、错误包和篡改文件。
- `UpdateDialogTest`：纯假数据、无外部请求，在 200% 字体下取消检查和下载。
- `CredentialMigrationInstrumentedTest`：隔离的 synthetic 凭据、默认 Android DataStore
  factory 与真实 Keystore；核对迁移、往返读取和损坏密文时不回退为伪成功。

这些类需要由集成设备 lane 实际执行；编译通过不能代替执行结果。

回滚标签保证能找回源码，但 Android 通常不允许把较低 versionCode 直接覆盖较高版本。
优先从保留标签修复并发布递增 versionCode 的兼容版本。不要强制重置会话、恢复
不兼容数据库、改签名、卸载或用 `-d` 掩盖回滚风险。

## 7. 维护检查

依赖升级集中修改 version catalog，确认 AGP/Gradle/JDK/Kotlin 编译插件兼容关系，
以及 AndroidX 的 min/compile SDK 要求。保持 UI、Remote 和 native 的边到边与 Back
行为一致。新的权限、网络例外、导出根目录或凭据存储必须经过跨模块审查。

DataStore 保持稳定的 1.1.7。它的默认 FileStorage 在 Windows/JVM 上将临时文件替换已有
文件时存在 rename 回归（上游问题 203087070）；不得为此跳过迁移测试或使用 alpha 库。
JVM 迁移 fixture 使用 `PreferenceDataStoreFactory.create(storage = ...)`，显式传入真实
`OkioStorage` 与公开的 `PreferencesSerializer`，保留读写/迁移断言。
`createWithPath` 在 1.1.7 仍会选择 FileStorage，不能单靠该重载解决此问题。
Android fixture 仍测试生产默认 factory 与真实 Keystore。
这一区分是平台适配，不是宣称不同后端已被同一项测试覆盖。
背景见 [DataStore 官方发行说明](https://developer.android.com/jetpack/androidx/releases/datastore)。

凭据迁移需验证密文写入后才清理旧值；恢复失败必须可诊断，不能返回伪成功。
检查云备份和 device-transfer 两套 XML，不能再次把 DataStore 当成 sharedpref。
no-backup/cache 本身不参与系统备份，额外排除旧凭据、DataStore 与 WebView 登录目录。
会话备份仍受系统备份策略和容量限制，不能保证所有附件都被云端保存。
