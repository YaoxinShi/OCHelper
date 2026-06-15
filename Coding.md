Step 1:

帮我实现一个Android apk，包括以下四个功能模块：
1. OpenClaw Android Node。通过OpenClaw标准的Node接口，把Android设备的能力(比如system setting, device information, camera, Gallery, Microphone,Screen, location, Notification, Contacts, Calendar, installed APPs 等等)暴露给OpenClaw。
2. Android use MCP server。通过MCP接口，把Android设备的能力暴露出来。这样OpenClaw里面配置好MCP tools之后，就能调用之。
3. Video Streaming。通过RTSP协议，把Android设备上的camera的视频流传输给OpenClaw。
4. 给OpenClaw gateway发消息，以调用openclaw来完成任务。以及监控OpenClaw的状态，比如用了什么模型，用了什么tools等等。
请分析一下apk软件架构和UI应该如何设计。

Step 2:
开始代码实现和验证。
程序需要支持X86和ARM两种CPU架构。
注：
本地的Android SDK在 ~/Android/Sdk
远程Android测试设备在 adb connect 10.239.152.121

Step 3:
把如何编译apk，如何安装apk，如何使用apk的内容写入readme.md

Step 4:
1. MCP server的默认端口从8765改为11800
2. 推流的默认端口从8554改为11801
3. node页面里面，openclaw的gateway url默认为：ws://10.239.152.92:18789  auth token默认为 b301****b1c0
4. gateway页面里面，openclaw的gateway url默认为：http://10.239.152.92:18789  auth token默认为 b301****b1c0

