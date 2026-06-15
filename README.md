# OCHelper

OCHelper 是一个 Android APK，将 Android 设备能力（摄像头、位置、联系人、通知等）通过以下四个模块暴露给 [OpenClaw](https://github.com/openclaw) 生态：

| 模块 | 协议 | 说明 |
|------|------|------|
| **OpenClaw Android Node** | WebSocket (OpenClaw Node 协议) | 将设备注册为 OpenClaw Gateway 的一个 Node |
| **Android MCP Server** | HTTP JSON-RPC 2.0 (MCP) | 本地 HTTP 服务，供 MCP 客户端调用设备能力 |
| **Video Streaming** | RTSP/RTP | 摄像头 H.264 实时视频流 |
| **OC Gateway Client** | HTTP SSE | 主动向 OpenClaw Gateway 发送任务并监控模型/工具状态 |

---

## 环境要求

- Android SDK（API 26+，推荐 API 36）
- JDK 17+（或 Android Studio 自带 JBR）
- Gradle 9.2.1（Gradle Wrapper 自动下载）
- 设备：Android 8.0（API 26）及以上

---

## 编译 APK

### 1. 克隆项目

```bash
git clone <repo-url>
cd ochelper
```

### 2. 配置环境变量

```bash
export JAVA_HOME="$HOME/Android/android-studio/jbr"   # 或系统 JDK 路径
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$PATH:$ANDROID_HOME/platform-tools"
```

如需代理（公司网络），在 `gradle.properties` 中已预置：

```properties
systemProp.http.proxyHost=child-prc.intel.com
systemProp.http.proxyPort=913
systemProp.https.proxyHost=child-prc.intel.com
systemProp.https.proxyPort=913
```

同时设置 shell 代理：

```bash
export http_proxy="http://child-prc.intel.com:913"
export https_proxy="http://child-prc.intel.com:913"
```

### 3. 编译 Debug APK

```bash
./gradlew assembleDebug --no-daemon
```

编译成功后，APK 位于：

```
app/build/outputs/apk/debug/app-debug.apk
```

### 4. 编译 Release APK（可选）

```bash
./gradlew assembleRelease --no-daemon
```

> Release 构建需要配置签名，参考 [Android 文档](https://developer.android.com/studio/publish/app-signing)。

---

## 安装 APK

### 通过 USB/TCP ADB 安装

```bash
# USB 连接
adb install -r app/build/outputs/apk/debug/app-debug.apk

# TCP 连接（如测试设备 10.239.152.121）
adb connect 10.239.152.121:5555
adb -s 10.239.152.121:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

安装成功后终端输出 `Success`。

### 通过文件传输安装

将 APK 文件传输到设备后，在设备文件管理器中点击安装（需开启"允许安装未知来源应用"）。

---

## 使用说明

首次启动 OCHelper 后，进入底部导航栏各功能页进行配置。

### 权限授权

进入 **Settings（设置）** 页，查看各权限状态并按需授权：

- 摄像头、麦克风 —— 拍照/录音功能
- 位置 —— GPS 定位功能
- 联系人、日历 —— 读写联系人/日程
- 通知访问 —— 读取设备通知（需在系统设置 → 通知访问中手动开启）
- 屏幕录制 —— 截屏功能（每次调用需用户确认）

---

### 模块一：OpenClaw Android Node

将设备注册为 OpenClaw Gateway 的 Node，AI 模型可直接调用设备能力。

**配置步骤（Node 页）：**

1. 填写 **Gateway URL**（WebSocket 地址，如 `ws://192.168.1.100:8080`）
2. 填写 **Auth Token**（Gateway 颁发的认证 Token）
3. Node ID 默认自动生成，可自定义
4. 点击 **Connect** 建立连接

连接成功后状态显示 `Connected`，Gateway 即可通过 OpenClaw 协议调用以下能力：

| Capability ID | 说明 |
|--------------|------|
| `device.info` | 电量、存储、内存、Android 版本 |
| `system.settings` | 亮度/音量/铃声模式/WiFi 状态 |
| `camera.take_photo` | 拍照并返回 base64 JPEG |
| `gallery.list` | 列出相册图片/视频 |
| `microphone.record` | 录音并返回 base64 |
| `screen.capture` | 截屏并返回 base64 |
| `location.get` | 获取 GPS/网络定位坐标 |
| `notifications.list` | 获取当前活动通知 |
| `contacts.list` | 读取联系人 |
| `calendar.list` / `calendar.add` | 列出/添加日程 |
| `apps.list` / `apps.launch` | 列出/启动应用 |

---

### 模块二：Android MCP Server

在设备上运行本地 MCP HTTP 服务，供支持 MCP 协议的客户端（如 Claude Desktop、Cursor 等）直接调用。

**配置步骤（MCP 页）：**

1. 设置监听端口（默认 `11800`）
2. 记录自动生成的 **Bearer Token**
3. 点击 **Start Server** 启动

**MCP 客户端配置（以 Claude Desktop 为例）：**

```json
{
  "mcpServers": {
    "android-device": {
      "url": "http://<设备IP>:11800/mcp",
      "headers": {
        "Authorization": "Bearer <Token>"
      }
    }
  }
}
```

支持的 JSON-RPC 方法：`initialize`、`tools/list`、`tools/call`、`ping`。

---

### 模块三：Video Streaming（RTSP）

将设备摄像头画面以 RTSP/RTP 协议推流，供 VLC、ffplay、ffmpeg 等播放器接收。

**配置步骤（Streaming 页）：**

1. 选择摄像头（前置/后置）
2. 设置分辨率、帧率、码率
3. 点击 **Start Streaming**

**播放地址：**

```
rtsp://<设备IP>:11801/stream
```

**使用 VLC 播放：**

```bash
vlc rtsp://<设备IP>:11801/stream
```

**使用 ffplay：**

```bash
ffplay rtsp://<设备IP>:11801/stream
```

---

### 模块四：OC Gateway Client

主动向 OpenClaw Gateway 发送任务，并通过 SSE 实时监控模型推理、工具调用状态。

**配置步骤（Gateway → Config 标签页）：**

1. 填写 **Gateway URL**（HTTP 地址，如 `http://192.168.1.100:8080`）
2. 填写 **API Key**
3. 选择默认模型（可留空，由 Gateway 自动选择）
4. 点击 **Save & Connect**

**发送任务（Task 标签页）：**

在输入框输入消息，点击发送，响应以流式方式逐字显示。

**监控（Monitor 标签页）：**

实时展示：
- 连接状态与当前模型
- 工具调用历史（支持展开查看输入/输出详情）

---

## 架构说明

详见 [Architecture.md](Architecture.md)。

---

## 开发说明

- **包名**：`com.ochelper`
- **Min SDK**：26（Android 8.0）
- **Target SDK**：36（Android 16）
- **语言**：Kotlin + Jetpack Compose
- **主要依赖**：Ktor 3.x（MCP Server）、OkHttp 5.x（WebSocket/HTTP）、Netty 4.x（RTSP）、CameraX 1.5.x、kotlinx.serialization
