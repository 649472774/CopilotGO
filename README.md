# CopilotGO

Android 客户端，复用 **GitHub Copilot Enterprise** 订阅在手机上聊天 / 用图片。

## 功能

- GitHub **Device Flow** 登录（无需输入密码）
- 通过 Copilot Token 调用 `api.enterprise.githubcopilot.com/chat/completions`
- **流式 SSE** + UI 端 typewriter 节流（统一打字感）
- 多会话、模型切换、附件 / 图片（vision）
- IME 自适应、键盘不挡输入框

## 构建

```powershell
# 需要 JDK 17+（Android Studio 自带 jbr 即可）
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
./gradlew.bat assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 模块结构

```
app/src/main/java/com/tongxie/copilotgo/
├── data/
│   ├── auth/         GitHub Device Flow + Copilot token 兑换
│   ├── chat/         CopilotChatClient + SSE parser + 数据模型
│   └── storage/      SessionStore（本地持久化）
├── ui/
│   ├── screens/      ChatScreen, ChatListScreen, LoginScreen
│   └── viewmodel/    ChatViewModel, AuthViewModel
└── CopilotGoApp.kt   DI 容器
```

## 关键实现

- **流式刷新**：`StateFlow<Session>` + `revision` 字段 + `replaceAssistant()` 替换 list 元素，避免 data class equals 去重把 emit 吃掉
- **gzip 关掉**：SSE 请求显式 `Accept-Encoding: identity`，避免 OkHttp 解压缓冲
- **Typewriter**：独立协程从 buffer 按 20ms / 2 字符的速率推进 displayedLen
- **IME inset**：ChatScreen 加 `.imePadding()`，配合 manifest `adjustResize`

## 版本

- 0.1.1 — 加 IME 适配
- 0.1.0 — 首个能用版本

## 开发说明

详细历史在 [`docs/HISTORY.md`](docs/HISTORY.md)。
