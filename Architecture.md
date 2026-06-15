# OCHelper Android APK — 软件架构与 UI 设计

## 一、总体架构概览

```
┌──────────────────────────────────────────────────────────────────────┐
│                          UI Layer (Jetpack Compose)                  │
│  Dashboard │ OC-Node │ MCP Server │ Gateway │ Streaming │ Settings   │
├──────────────────────────────────────────────────────────────────────┤
│                       ViewModel Layer (MVVM)                         │
│  DashboardVM │ OCNodeVM │ MCPServerVM │ GatewayVM │ StreamVM │ SettVM │
├──────────────────────────────────────────────────────────────────────┤
│                     Foreground Service Layer                         │
│  OCNodeService │ MCPServerService │ RTSPService │ OCGatewayService    │
├─────────────────────┬────────────────┬─────────────┬────────────────┤
│  OpenClaw Node      │   MCP Module   │ RTSP Module │ Gateway Module  │
│  OCNodeClient       │   MCPServer    │ RTSPServer  │ OCGatewayClient │
│  OCNodeProtocol     │   MCPProtocol  │ RTPSender   │ OCStatusMonitor │
│  OCNodeRegistry     │   ToolRegistry │ VideoEncoder│ TaskDispatcher  │
├─────────────────────┴────────────────┴─────────────┴────────────────┤
│                   Capability Abstraction Layer                       │
│  SystemSettings │ DeviceInfo │ Camera │ Gallery │ Microphone          │
│  Screen │ Location │ Notification │ Contacts │ Calendar │ App         │
├──────────────────────────────────────────────────────────────────────┤
│                      Android Platform Layer                          │
│  Camera2 │ MediaCodec │ ContentProvider │ TelecomManager ...          │
└──────────────────────────────────────────────────────────────────────┘
```

**数据流向总览：**
```
Android → OCNode Client   ──(WebSocket)──> OpenClaw Server  [模块1: 被动响应]
Android ← MCP Server      ←─(HTTP/SSE)─── OpenClaw/LLM      [模块2: 被动响应]
Android → RTSP Server     ──(RTP/UDP)───> OpenClaw/Player   [模块3: 推流]
Android → Gateway Client  ──(HTTP/SSE)──> OpenClaw Gateway  [模块4: 主动发起]
Android ← Status Monitor  ←─(WS/SSE)──── OpenClaw Gateway  [模块4: 状态订阅]
```

---

## 二、技术选型

| 关注点 | 选型 | 理由 |
|--------|------|------|
| 语言 | Kotlin | 官方推荐，协程支持良好 |
| 架构模式 | Clean Architecture + MVVM | 分层清晰，便于测试 |
| 依赖注入 | Hilt | 与 ViewModel/Service 集成好 |
| 异步 | Coroutines + Flow | 背压处理，与生命周期绑定 |
| UI | Jetpack Compose | 声明式 UI，响应式状态 |
| 内嵌 HTTP 服务器 | Ktor (Server) | 纯 Kotlin，支持协程 + WebSocket + SSE |
| HTTP 客户端 | OkHttp | 支持 WebSocket，成熟稳定 |
| 序列化 | kotlinx.serialization | 与 Ktor 原生配合 |
| 视频编码 | Camera2 API + MediaCodec | 原生，低延迟 H.264/H.265 |
| RTSP | 自实现 RTSP/RTP 栈（Netty） | 可控性强，无重量级依赖 |
| 权限管理 | accompanist-permissions | Compose 中声明式权限请求 |
| 持久化 | DataStore + Room | 配置存 DataStore，日志存 Room |
| 后台保活 | Foreground Service + Wake Lock | 服务长期运行 |

---

## 三、模块详细设计

### 3.1 Capability Abstraction Layer（能力抽象层）

所有 Android 能力封装为统一接口，供上层 OCNode 和 MCP 两个模块调用：

```kotlin
interface DeviceCapability {
    val id: String          // 唯一标识，如 "camera.take_photo"
    val name: String        // 可读名称
    val description: String // 功能描述（用于 MCP tool description）
    val inputSchema: JsonObject   // JSON Schema 描述入参
    suspend fun execute(params: JsonObject): JsonObject  // 执行并返回结果
}
```

**能力模块列表：**

| 模块 | 主要功能 |
|------|---------|
| `SystemSettingsCapability` | 读写 WiFi、蓝牙、亮度、音量、飞行模式、勿扰 |
| `DeviceInfoCapability` | 电量、IMEI（限授权）、分辨率、Android 版本、内存磁盘使用率 |
| `CameraCapability` | 拍照（返回 base64/文件路径）、录像短片 |
| `GalleryCapability` | 列举/查询媒体文件、读取指定图片 |
| `MicrophoneCapability` | 录制指定时长音频，返回文件路径 |
| `ScreenCapability` | 截屏（返回 base64）、获取当前 Activity 信息 |
| `LocationCapability` | 获取当前 GPS/网络位置、地理围栏监听 |
| `NotificationCapability` | 发送本地通知、读取通知栏消息（需 NotificationListenerService）|
| `ContactsCapability` | 查询/新增/更新联系人 |
| `CalendarCapability` | 查询/新增/删除日历事件 |
| `AppManagerCapability` | 列举已安装 App、启动 App、查询 App 信息 |

**CapabilityManager** 维护所有 Capability 实例，并持有一个 "启用/禁用" 状态表，供用户在 UI 中控制哪些能力可被外部调用。

---

### 3.2 Module 1：OpenClaw Android Node

#### 架构

```
OCNodeService (ForegroundService)
    └── OCNodeClient
            ├── WebSocketConnection  ──────> OpenClaw Server
            ├── OCNodeProtocol        (握手、心跳、消息编解码)
            └── OCNodeRegistry        (注册/注销 Capability)
```

#### 协议流程

```
Android                          OpenClaw
  │                                  │
  │─── connect (WebSocket) ─────────>│
  │<── challenge / session_token ────│
  │─── register_node(capabilities)──>│  上报所有已启用的能力及其 schema
  │<── ack ──────────────────────────│
  │                                  │
  │<── invoke_tool(id, params) ──────│  OpenClaw 调用能力
  │─── tool_result(result) ─────────>│
  │                                  │
  │─── heartbeat ───────────────────>│  每 30s
  │<── heartbeat_ack ────────────────│
```

#### 关键类

```kotlin
// 连接配置（存 DataStore）
data class OCNodeConfig(
    val serverUrl: String,        // ws://192.168.1.x:port/node
    val nodeId: String,           // UUID，首次生成后固化
    val authToken: String,
    val autoReconnect: Boolean,
    val reconnectIntervalSec: Int
)

// 节点状态
sealed class OCNodeState {
    object Disconnected : OCNodeState()
    object Connecting : OCNodeState()
    data class Connected(val serverUrl: String, val sessionId: String) : OCNodeState()
    data class Error(val message: String) : OCNodeState()
}
```

---

### 3.3 Module 2：MCP Server

MCP（Model Context Protocol）使用 **JSON-RPC 2.0** over **HTTP + SSE**（或 Streamable HTTP）。

#### 架构

```
MCPServerService (ForegroundService)
    └── KtorEmbeddedServer  (监听 :PORT)
            ├── POST /mcp           → MCPRequestHandler
            │       ├── initialize
            │       ├── tools/list
            │       └── tools/call  → CapabilityManager.execute()
            └── GET  /mcp/events    → SSE 事件推流（可选）
```

#### MCP Tool 映射

每个 `DeviceCapability` 自动映射为一个 MCP Tool：

```json
{
  "name": "camera.take_photo",
  "description": "拍一张照片并返回 base64 图片数据",
  "inputSchema": {
    "type": "object",
    "properties": {
      "camera": { "type": "string", "enum": ["front", "back"], "default": "back" },
      "quality": { "type": "integer", "minimum": 1, "maximum": 100, "default": 85 }
    }
  }
}
```

#### MCPServer 核心

```kotlin
class MCPServer(
    private val capabilityManager: CapabilityManager,
    private val config: MCPServerConfig  // port, auth token, CORS origin
) {
    // 基于 Ktor embeddedServer
    // 支持 Basic Auth / Bearer Token 认证
    // 所有 tool/call 请求转发给 CapabilityManager
}

data class MCPServerConfig(
    val port: Int = 8765,
    val authEnabled: Boolean = true,
    val bearerToken: String,         // 随机生成，UI 中显示供复制
    val allowedOrigins: List<String>
)
```

#### OpenClaw 接入配置示例

用户在 OpenClaw 中添加如下 MCP Server 配置：
```json
{
  "type": "http",
  "url": "http://<device-ip>:8765/mcp",
  "headers": { "Authorization": "Bearer <token>" }
}
```

---

### 3.4 Module 3：RTSP Video Streaming

#### 架构

```
RTSPService (ForegroundService)
    ├── RTSPServer (Netty, :8554)
    │       ├── RTSP OPTIONS / DESCRIBE / SETUP / PLAY / TEARDOWN
    │       └── SessionManager (管理多路 client session)
    ├── CameraStreamEncoder
    │       ├── Camera2 → ImageReader (YUV_420_888)
    │       └── MediaCodec (H.264, Surface Mode)
    └── RTPSender
            └── UDP RTP packets → RTSP client
```

#### 流程

```
Client (OpenClaw/VLC)                  RTSPServer
  │─── OPTIONS rtsp://ip:8554/live ──>│
  │<── 200 OK (Public: methods) ──────│
  │─── DESCRIBE ──────────────────────>│
  │<── 200 OK (SDP: H264, port...) ───│
  │─── SETUP (client_port=xxxxx) ─────>│
  │<── 200 OK (server_port=yyyyy) ────│
  │─── PLAY ──────────────────────────>│
  │<── 200 OK ─────────────────────────│
  │<════ RTP UDP packets (H264) ═══════│  实时视频流
```

#### 关键参数

```kotlin
data class RTSPStreamConfig(
    val port: Int = 8554,
    val streamPath: String = "/live",
    val camera: CameraFacing = CameraFacing.BACK,
    val resolution: Resolution = Resolution(1280, 720),
    val frameRate: Int = 30,
    val bitrateBps: Int = 2_000_000,  // 2 Mbps
    val codec: VideoCodec = VideoCodec.H264,
    val audioEnabled: Boolean = false  // 可选 AAC 音频轨
)
```

**RTSP URL 格式：** `rtsp://<device-ip>:8554/live`

---

### 3.5 Module 4：OpenClaw Gateway Client

本模块是 Android 主动向 OpenClaw 发起请求的**客户端**，与模块1（被动接受调用）方向相反，实现两类功能：
- **Task Dispatch（任务下发）**：发消息给 OpenClaw Gateway，让 OpenClaw 执行任务，实时接收流式响应
- **Status Monitor（状态监控）**：订阅 OpenClaw 的事件流，实时掌握模型状态、工具调用链、会话指标等

#### 架构

```
OCGatewayService (ForegroundService，后台保持事件流订阅)
    ├── OCGatewayClient (OkHttp)
    │       ├── POST /api/chat        → 发送任务消息（SSE 流式返回）
    │       ├── GET  /api/status      → 一次性查询 Gateway 状态快照
    │       └── GET  /api/events      → 订阅 Gateway 事件流（SSE/WS）
    │
    ├── TaskDispatcher
    │       ├── SessionManager        (维护多会话 sessionId)
    │       └── AttachmentUploader    (上传图片/文件作为上下文)
    │
    └── OCStatusMonitor
            ├── currentModel:    StateFlow<ModelInfo>
            ├── activeTools:     StateFlow<List<ToolCallEvent>>
            ├── activeSessions:  StateFlow<List<SessionInfo>>
            └── metrics:         StateFlow<GatewayMetrics>
```

#### 协议流程

```
Android (Gateway Client)              OpenClaw Gateway
  │                                       │
  │─── GET /api/status ─────────────────>│  查询当前模型/工具配置
  │<── { model, tools, sessions } ────────│
  │                                       │
  │─── GET /api/events (SSE) ───────────>│  订阅实时事件
  │<══ event: model_changed ══════════════│
  │<══ event: tool_call_start ════════════│  工具调用开始
  │<══ event: tool_call_end ══════════════│  工具调用结束（含结果）
  │<══ event: session_update ═════════════│
  │                                       │
  │─── POST /api/chat ──────────────────>│  发送任务消息
  │    { message, sessionId, model? }     │
  │<══ SSE: text_delta ══════════════════│  流式文字输出
  │<══ SSE: tool_call_start ══════════════│  模型调用工具
  │<══ SSE: tool_call_end ════════════════│
  │<══ SSE: text_delta ══════════════════│  继续输出
  │<══ SSE: task_complete ════════════════│  { sessionId, totalTokens }
```

#### 关键数据类

```kotlin
// Gateway 连接配置
data class OCGatewayConfig(
    val gatewayUrl: String,          // http://192.168.1.1:port
    val apiKey: String,
    val defaultModel: String = "",   // 空 = 使用 Gateway 默认
    val statusPollIntervalSec: Int = 5,
    val enableEventStream: Boolean = true
)

// 任务请求
data class OCTaskRequest(
    val message: String,
    val sessionId: String? = null,   // null = 新建会话
    val model: String? = null,       // null = 使用 Gateway 默认
    val attachments: List<TaskAttachment> = emptyList(),
    val toolFilter: List<String>? = null  // 限定本次可用工具
)

// Gateway 实时事件
sealed class OCGatewayEvent {
    data class TextDelta(val sessionId: String, val delta: String) : OCGatewayEvent()
    data class ToolCallStart(val id: String, val toolName: String,
                             val input: JsonObject) : OCGatewayEvent()
    data class ToolCallEnd(val id: String, val toolName: String,
                           val output: JsonObject, val durationMs: Long,
                           val status: ToolCallStatus) : OCGatewayEvent()
    data class ModelChanged(val newModel: ModelInfo) : OCGatewayEvent()
    data class TaskComplete(val sessionId: String, val totalTokens: Int,
                            val costUsd: Double) : OCGatewayEvent()
    data class SessionUpdate(val sessions: List<SessionInfo>) : OCGatewayEvent()
    data class GatewayError(val code: Int, val message: String) : OCGatewayEvent()
}

// Gateway 状态快照
data class OCGatewayStatus(
    val connected: Boolean,
    val gatewayVersion: String,
    val activeModel: ModelInfo,
    val activeSessions: List<SessionInfo>,
    val recentToolCalls: List<ToolCallEvent>,  // 最近 50 条
    val metrics: GatewayMetrics
)

data class ModelInfo(
    val id: String,           // "claude-sonnet-4-5"
    val provider: String,     // "anthropic" / "openai" / ...
    val contextWindow: Int,
    val temperature: Float
)

data class GatewayMetrics(
    val totalTokensToday: Long,
    val estimatedCostUsd: Double,
    val activeSessions: Int,
    val queuedTasks: Int,
    val avgLatencyMs: Long,
    val successRate: Float       // 0.0 ~ 1.0
)
```

#### 与其他模块的关系

| | OCNode（模块1） | Gateway Client（模块4） |
|---|---|---|
| 方向 | OpenClaw → Android（被调用） | Android → OpenClaw（主动发起） |
| 协议 | OpenClaw Node 私有协议 | OpenClaw REST/SSE 公开 API |
| 目的 | 暴露 Android 能力给 AI | 让 Android 直接驱动 AI 完成任务 |
| 连接复用 | 可共用 Gateway URL 配置 | 独立 HTTP 客户端，可独立开关 |

---

## 四、Service 保活策略

```
所有四个服务均为 Foreground Service：
  - startForeground() + 持久通知
  - BOOT_COMPLETED BroadcastReceiver 自动重启
  - 服务间通过 Binder 通信（同进程）
  - WakeLock (PARTIAL_WAKE_LOCK) 防止 CPU 休眠
  - WifiLock (WIFI_MODE_FULL_HIGH_PERF) 防止 WiFi 断开

进程架构：
  :main 进程      — UI + ViewModel
  :service 进程   — 四个 ForegroundService（android:process=":service"）
                    OCNodeService / MCPServerService / RTSPService / OCGatewayService
                    隔离崩溃影响，且 UI 退出后服务继续运行

OCGatewayService 特殊说明：
  - 后台保持 SSE 长连接，订阅 Gateway 事件流
  - 收到事件后通过 LocalBroadcastManager 通知 UI 层 StateFlow
  - 若连接断开，指数退避重连（1s → 2s → 4s → ... → 60s 上限）
```

---

## 五、安全设计

| 风险点 | 对策 |
|--------|------|
| MCP 接口被局域网内其他设备滥用 | Bearer Token 认证 + 可选 IP 白名单 |
| OCNode 连接被中间人攻击 | TLS (wss://) + 证书固定 |
| 敏感能力（联系人、通话记录）被调用 | 每个 Capability 有独立启用开关，默认关闭 |
| RTSP 流被截获 | 可选 SRTP 或在 OpenClaw 侧通过 VPN 隔离 |
| 权限滥用 | 运行时权限逐一申请，权限状态实时显示在 UI |

---

## 六、项目目录结构

```
app/src/main/
├── java/com/ochelper/
│   ├── di/                          # Hilt 模块
│   │   ├── AppModule.kt
│   │   ├── CapabilityModule.kt
│   │   └── NetworkModule.kt
│   │
│   ├── capability/                  # 能力抽象层
│   │   ├── DeviceCapability.kt      # 接口定义
│   │   ├── CapabilityManager.kt
│   │   ├── system/SystemSettingsCapability.kt
│   │   ├── device/DeviceInfoCapability.kt
│   │   ├── camera/CameraCapability.kt
│   │   ├── gallery/GalleryCapability.kt
│   │   ├── audio/MicrophoneCapability.kt
│   │   ├── screen/ScreenCapability.kt
│   │   ├── location/LocationCapability.kt
│   │   ├── notification/NotificationCapability.kt
│   │   ├── contacts/ContactsCapability.kt
│   │   ├── calendar/CalendarCapability.kt
│   │   └── apps/AppManagerCapability.kt
│   │
│   ├── ocnode/                      # OpenClaw Node 模块
│   │   ├── OCNodeClient.kt
│   │   ├── OCNodeProtocol.kt
│   │   ├── OCNodeConfig.kt
│   │   └── OCNodeState.kt
│   │
│   ├── mcp/                         # MCP Server 模块
│   │   ├── MCPServer.kt
│   │   ├── MCPRequestHandler.kt
│   │   ├── MCPProtocol.kt           # JSON-RPC 2.0 数据类
│   │   ├── MCPToolAdapter.kt        # Capability → MCP Tool 映射
│   │   └── MCPServerConfig.kt
│   │
│   ├── rtsp/                        # RTSP 流媒体模块
│   │   ├── RTSPServer.kt
│   │   ├── RTSPSession.kt
│   │   ├── CameraStreamEncoder.kt
│   │   ├── RTPSender.kt
│   │   ├── SDPBuilder.kt
│   │   └── RTSPStreamConfig.kt
│   │
│   ├── gateway/                     # OpenClaw Gateway Client 模块
│   │   ├── OCGatewayClient.kt       # OkHttp SSE + REST 封装
│   │   ├── OCGatewayConfig.kt
│   │   ├── OCGatewayEvent.kt        # sealed class 事件定义
│   │   ├── OCGatewayStatus.kt       # 状态快照数据类
│   │   ├── OCStatusMonitor.kt       # StateFlow 状态聚合
│   │   ├── TaskDispatcher.kt        # 发送任务 + 会话管理
│   │   └── AttachmentUploader.kt    # 图片/文件上传
│   │
│   ├── service/                     # Foreground Services
│   │   ├── OCNodeService.kt
│   │   ├── MCPServerService.kt
│   │   ├── RTSPService.kt
│   │   └── OCGatewayService.kt      # 后台保持事件流订阅
│   │
│   ├── data/                        # 持久化
│   │   ├── PreferencesDataStore.kt
│   │   └── LogDatabase.kt           # Room DB
│   │
│   ├── ui/                          # Jetpack Compose UI
│   │   ├── MainActivity.kt
│   │   ├── navigation/AppNavigation.kt
│   │   ├── dashboard/
│   │   │   ├── DashboardScreen.kt
│   │   │   └── DashboardViewModel.kt
│   │   ├── ocnode/
│   │   │   ├── OCNodeScreen.kt
│   │   │   └── OCNodeViewModel.kt
│   │   ├── mcp/
│   │   │   ├── MCPServerScreen.kt
│   │   │   └── MCPServerViewModel.kt
│   │   ├── gateway/
│   │   │   ├── GatewayScreen.kt     # 含 Task/Chat 和 Monitor 两个子 Tab
│   │   │   ├── GatewayViewModel.kt
│   │   │   ├── TaskChatPanel.kt     # 发送消息 + 流式响应展示
│   │   │   └── StatusMonitorPanel.kt # 模型/工具/会话状态实时展示
│   │   ├── streaming/
│   │   │   ├── StreamingScreen.kt
│   │   │   └── StreamingViewModel.kt
│   │   ├── permissions/
│   │   │   ├── PermissionsScreen.kt
│   │   │   └── PermissionsViewModel.kt
│   │   ├── settings/
│   │   │   └── SettingsScreen.kt
│   │   └── components/              # 通用 Compose 组件
│   │       ├── StatusIndicator.kt
│   │       ├── ServiceCard.kt
│   │       ├── LogViewer.kt
│   │       ├── ToolCallCard.kt      # 工具调用气泡（含展开/折叠）
│   │       ├── StreamingText.kt     # 打字机效果文字组件
│   │       └── CopyableText.kt
│   │
│   └── util/
│       ├── NetworkUtils.kt          # 获取本机 IP
│       ├── PermissionUtils.kt
│       └── TokenGenerator.kt
│
└── res/
    ├── drawable/                    # 图标
    └── values/                      # strings, colors, themes
```

---

## 七、UI 设计

### 导航结构

```
Bottom Navigation Bar (6 Tab，图标+短标签)
  ├── 🏠 Dashboard   — 总览
  ├── 🔗 Node        — OpenClaw Node 管理
  ├── 🔧 MCP         — MCP Server 管理
  ├── 🤖 Gateway     — 向 OpenClaw 发任务 + 状态监控  ← 新增
  ├── 📹 Stream      — RTSP 视频流
  └── ⚙️ Settings    — 权限与设置
```

> 6 个 Tab 在手机上使用**图标+极短标签**（≤4字），或在平板上改为 Navigation Rail。

---

### Screen 1：Dashboard（总览）

```
┌─────────────────────────────────┐
│  OCHelper            [●运行中]  │  ← 全局状态
├─────────────────────────────────┤
│  ┌─────────────┐ ┌───────────┐  │
│  │ OC Node     │ │ MCP Server│  │
│  │ ● 已连接    │ │ ● 运行中  │  │
│  │ ws://...    │ │ :8765     │  │
│  └─────────────┘ └───────────┘  │
│  ┌─────────────┐ ┌───────────┐  │
│  │ OC Gateway  │ │ RTSP 推流 │  │
│  │ ● 已连接    │ │ ● 推流中  │  │
│  │ 模型:Claude │ │ 720p 30fp │  │
│  └─────────────┘ └───────────┘  │
├─────────────────────────────────┤
│  Gateway 实时状态                 │
│  模型: claude-sonnet-4-5  [●]   │
│  活跃会话: 2   队列任务: 0       │
│  今日 Token: 12,450   $0.08     │
├─────────────────────────────────┤
│  设备信息                        │
│  IP: 192.168.1.5    电量: 87%   │
│  Android 14   内存: 3.2/8 GB   │
├─────────────────────────────────┤
│  最近事件                        │
│  14:23 Gateway tool_call camera │
│  14:22 MCP tools/call location  │
│  14:21 OCNode heartbeat ok      │
│  14:20 RTSP client connected    │
└─────────────────────────────────┘
```

---

### Screen 2：OC Node

```
┌─────────────────────────────────┐
│  OpenClaw Node                  │
├─────────────────────────────────┤
│  连接状态: ● 已连接              │
│  Node ID: oc-android-xxxx       │
│                      [断开连接] │
├─────────────────────────────────┤
│  服务器配置                      │
│  URL  [ws://192.168.1.1:9000  ] │
│  Token[********************   ] │
│                      [保存连接] │
├─────────────────────────────────┤
│  暴露能力 (已启用 8/11)          │
│  ☑ 系统设置      ☑ 设备信息     │
│  ☑ 相机拍照      ☑ 图库         │
│  ☑ 麦克风        ☑ 截屏         │
│  ☑ 位置          ☐ 通知(读取)   │
│  ☑ 联系人        ☑ 日历         │
│  ☐ 应用管理                     │
├─────────────────────────────────┤
│  调用日志            [清除]      │
│  14:23 camera.take_photo ✓      │
│  14:20 location.get ✓           │
└─────────────────────────────────┘
```

---

### Screen 3：MCP Server

```
┌─────────────────────────────────┐
│  MCP Server                     │
├─────────────────────────────────┤
│  状态: ● 运行中    连接数: 1    │
│                      [停止服务] │
├─────────────────────────────────┤
│  接入信息                        │
│  地址: http://192.168.1.5:8765  │
│  端点: /mcp          [复制]     │
│  Token: oc-mcp-xxxxxxxx  [复制] │
│  [生成新 Token]                  │
│                                 │
│  ┌─ OpenClaw 配置示例 ─────────┐ │
│  │ {                           │ │
│  │   "type": "http",           │ │
│  │   "url": "http://...:8765/  │ │
│  │   "headers": {"Auth":"..."}│ │
│  │ }                [复制全部] │ │
│  └─────────────────────────────┘ │
├─────────────────────────────────┤
│  MCP Tools (11 个)  [全选][全清]│
│  ☑ system.get_wifi_state        │
│  ☑ system.set_brightness        │
│  ☑ device.get_battery           │
│  ☑ camera.take_photo            │
│  ☑ gallery.list_photos          │
│  ☑ location.get_current         │
│  ☑ contacts.query               │
│  ... (可折叠展开)                │
├─────────────────────────────────┤
│  请求日志                        │
│  14:23 tools/call camera ✓ 1.2s│
│  14:22 tools/list     ✓         │
└─────────────────────────────────┘
```

---

### Screen 4：OC Gateway（新增）

GatewayScreen 内含两个子 Tab：**任务** 和 **监控**。

#### 4a. 子 Tab「任务」（Task Dispatch）

```
┌─────────────────────────────────┐
│  OC Gateway  [任务] [监控]       │
├─────────────────────────────────┤
│  Gateway: ● 已连接               │
│  http://192.168.1.1:9000        │
│  模型: claude-sonnet-4-5  [切换▼]│
├─────────────────────────────────┤
│  会话: sess-abc123  [新建会话]   │
├─────────────────────────────────┤
│  ┌─────────────────────────────┐│
│  │ [Assistant]                 ││
│  │ 好的，我来帮你拍一张照片...  ││
│  │                             ││
│  │ ┌── 工具调用 ─────────────┐ ││
│  │ │ 🔧 camera.take_photo    │ ││
│  │ │ 参数: {camera:"back"}  │ ││
│  │ │ ✓ 耗时 1.2s  [展开结果] │ ││
│  │ └─────────────────────────┘ ││
│  │                             ││
│  │ 照片已拍摄，以下是结果：▌   ││  ← 流式光标
│  └─────────────────────────────┘│
│   [图片] [文件]                  │
│  ┌──────────────────────────[↑]┐│
│  │ 输入任务消息...              ││
│  └─────────────────────────────┘│
└─────────────────────────────────┘
```

#### 4b. 子 Tab「监控」（Status Monitor）

```
┌─────────────────────────────────┐
│  OC Gateway  [任务] [监控]       │
├─────────────────────────────────┤
│  ▌ 当前模型                      │
│  claude-sonnet-4-5  (Anthropic) │
│  上下文窗口: 200K  Temperature:0.7│
├─────────────────────────────────┤
│  ▌ 实时指标                      │
│  活跃会话  队列任务  今日Token   │
│     2         0      12,450     │
│  平均延迟   成功率   今日费用    │
│   340ms      99%     $0.08      │
├─────────────────────────────────┤
│  ▌ 活跃会话                      │
│  sess-abc123  进行中  3 turns   │
│  └ 最后: camera.take_photo ✓    │
│  sess-def456  等待中  1 turn    │
├─────────────────────────────────┤
│  ▌ 工具调用历史      [清除] [导出]│
│  14:23 camera.take_photo  ✓1.2s│
│    ↳ in:  {camera:"back"}       │
│    ↳ out: {path:"/sdcard/..."}  │
│  14:22 location.get_current ✓  │
│    ↳ in:  {}                    │
│    ↳ out: {lat:39.9,lng:116.3} │
│  14:21 contacts.query  ✓ 0.8s  │
│  ...（可滚动，最近100条）         │
├─────────────────────────────────┤
│  ▌ 已加载 MCP Tools（来自本机）  │
│  ● camera.take_photo            │
│  ● location.get_current         │
│  ● contacts.query               │
│  ○ calendar.create_event (未启用)│
└─────────────────────────────────┘
```

> 「监控」Tab 顶部的模型名、指标卡片通过 SSE 事件流实时刷新，无需手动刷新。

---

### Screen 5：RTSP Stream

```
┌─────────────────────────────────┐
│  视频流                          │
├─────────────────────────────────┤
│  ┌─────────────────────────────┐│
│  │                             ││
│  │    相机实时预览画面          ││
│  │                             ││
│  │         ● REC  720p 30fps  ││
│  └─────────────────────────────┘│
│        [切换前置/后置]           │
├─────────────────────────────────┤
│  RTSP 地址                       │
│  rtsp://192.168.1.5:8554/live   │
│  [复制链接]        [停止推流]    │
├─────────────────────────────────┤
│  流配置                          │
│  分辨率  [1280×720  ▼]          │
│  帧率    [30 fps    ▼]          │
│  码率    [2 Mbps    ▼]          │
│  编码    [H.264     ▼]          │
│  音频    [○ 关  ● 开]           │
├─────────────────────────────────┤
│  实时统计                        │
│  实际帧率: 29.8 fps              │
│  实际码率: 1.94 Mbps             │
│  连接客户端: 1                   │
│  已推流时长: 00:12:34            │
└─────────────────────────────────┘
```

---

### Screen 6：Settings（权限与设置）

```
┌─────────────────────────────────┐
│  设置                            │
├─────────────────────────────────┤
│  权限状态                        │
│  相机          ✅ 已授权         │
│  麦克风        ✅ 已授权         │
│  位置          ✅ 已授权         │
│  联系人        ✅ 已授权         │
│  日历          ❌ 未授权  [去授权]│
│  读取通知      ❌ 未授权  [去授权]│
│  存储/媒体库   ✅ 已授权         │
│  悬浮窗        ✅ 已授权         │
├─────────────────────────────────┤
│  通用                           │
│  开机自动启动      [●]           │
│  保持 WiFi 高性能  [●]           │
│  前台服务通知      [●]           │
├─────────────────────────────────┤
│  端口配置                        │
│  MCP Server 端口  [8765]        │
│  RTSP 端口        [8554]        │
├─────────────────────────────────┤
│  关于                           │
│  OCHelper v1.0.0                │
│  Android SDK: API 34+           │
└─────────────────────────────────┘
```

---

## 八、关键依赖（build.gradle）

```kotlin
// Kotlin & Coroutines
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

// Jetpack
implementation("androidx.core:core-ktx:1.13.1")
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
implementation("androidx.datastore:datastore-preferences:1.1.1")
implementation("androidx.room:room-ktx:2.6.1")

// Compose
implementation(platform("androidx.compose:compose-bom:2024.09.00"))
implementation("androidx.compose.material3:material3")
implementation("androidx.navigation:navigation-compose:2.8.0")
implementation("com.google.accompanist:accompanist-permissions:0.36.0")

// Hilt
implementation("com.google.dagger:hilt-android:2.52")
implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

// Network
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("io.ktor:ktor-server-core:2.3.12")
implementation("io.ktor:ktor-server-netty:2.3.12")
implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")

// RTSP/RTP（Netty for raw TCP/UDP）
implementation("io.netty:netty-all:4.1.112.Final")
```

---

## 九、AndroidManifest 关键配置

```xml
<!-- 权限声明 -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.READ_CONTACTS" />
<uses-permission android:name="android.permission.WRITE_CONTACTS" />
<uses-permission android:name="android.permission.READ_CALENDAR" />
<uses-permission android:name="android.permission.WRITE_CALENDAR" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
    tools:ignore="ProtectedPermissions" />

<!-- Service 声明（独立进程）-->
<service android:name=".service.OCNodeService"
    android:foregroundServiceType="connectedDevice"
    android:process=":service" />
<service android:name=".service.MCPServerService"
    android:foregroundServiceType="connectedDevice"
    android:process=":service" />
<service android:name=".service.RTSPService"
    android:foregroundServiceType="camera|microphone"
    android:process=":service" />
<service android:name=".notification.OCNotificationListenerService"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>

<!-- 开机自启 -->
<receiver android:name=".BootReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

---

## 十、开发里程碑建议

| 阶段 | 内容 | 预计工作量 |
|------|------|----------|
| P0 | 项目骨架 + DI + DataStore + 基础 UI 导航 | 1 天 |
| P1 | CapabilityManager + 全部 11 个 Capability 实现 | 3 天 |
| P2 | MCP Server（Ktor + JSON-RPC 2.0 + Tool 路由）| 2 天 |
| P3 | OpenClaw Node Client（WebSocket + 协议握手）| 2 天 |
| P4 | RTSP Server（Camera2 + MediaCodec + RTP/RTSP）| 3 天 |
| P5 | Gateway Client（OkHttp SSE + TaskDispatcher + StatusMonitor）| 2 天 |
| P6 | Gateway UI（Task Chat Panel + Status Monitor Panel）| 1 天 |
| P7 | 权限管理 UI + 完整 Dashboard + 日志系统 | 1 天 |
| P8 | 测试、稳定性、保活策略调优 | 2 天 |
