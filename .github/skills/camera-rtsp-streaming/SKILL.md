---
name: camera-rtsp-streaming
description: 'Add or fix the on-screen camera preview on the OCHelper RTSP streaming page (Streaming 页). USE WHEN: the user wants a camera preview rectangle on the RTSP page; the preview is blank/black after clicking "开始推流"/"start streaming"; the preview surface never reaches the RTSPServer; the preview is the wrong size; or the camera capture session needs to mirror the streamed H.264 frames to a TextureView. Covers the Camera2 multi-target capture session (encoder Surface + preview Surface), hoisting the Compose TextureView surface, re-attaching it across service/server start, and the RTSPServer/RTSPService/StreamingScreen wiring.'
argument-hint: 'e.g. "camera preview is blank after start streaming"'
---

# OCHelper Camera Preview on the RTSP Streaming Page

How the on-screen camera preview works on the **Streaming 页**, and the bugs to watch for.

## When to Use
- User asks to add/resize a camera preview rectangle on the RTSP streaming page.
- Preview area stays black/blank, especially **after** clicking "开始推流" (start streaming).
- The preview should mirror exactly what is being H.264-streamed over RTSP.

## Architecture (3 files)
The preview Surface is created by the UI and flows down to the Camera2 capture session:

```
StreamingScreen.kt (TextureView in Compose)
        │  Surface(SurfaceTexture)
        ▼
RTSPService.kt  (holds previewSurface, forwards to server)
        │  setPreviewSurface(surface)
        ▼
RTSPServer.kt   (capture session targets: encoderInputSurface + previewSurface)
```

- [StreamingScreen.kt](../../../app/src/main/java/com/ochelper/ui/streaming/StreamingScreen.kt) — `CameraPreview` composable wraps an `android.view.TextureView` via `AndroidView`. Its `SurfaceTextureListener` reports the `Surface` up through an `onSurfaceChanged` callback.
- [RTSPService.kt](../../../app/src/main/java/com/ochelper/service/RTSPService.kt) — keeps `previewSurface` and forwards it to the live server; re-attaches it whenever the server is (re)created in `startStreaming`.
- [RTSPServer.kt](../../../app/src/main/java/com/ochelper/rtsp/RTSPServer.kt) — `startCaptureSession()` targets BOTH the encoder input surface and (when present) the preview surface; `setPreviewSurface()` rebuilds the live session.

## THE KEY BUG: preview blank after "start streaming"
**Symptom:** the preview rectangle stays black; the RTSP stream itself works.

**Root cause (timing/ordering):** the Compose `TextureView` surface becomes available as
soon as the screen renders — but at that moment the RTSP **service is not running yet**
(`ServiceRegistry.rtspService == null`, the service only starts on "开始推流"). The first
version called `ServiceRegistry.rtspService?.setPreviewSurface(...)` directly from the
`SurfaceTextureListener`, which **silently no-op'd** because the service was null. The server
then started with no preview target, so nothing was ever mirrored to screen.

**Fix:** hoist the surface into remembered Compose state and re-push it to the service whenever
the surface **or** the streaming state changes — so once the service exists (after start), the
surface is delivered:
```kotlin
var previewSurface by remember { mutableStateOf<Surface?>(null) }
LaunchedEffect(previewSurface, isStreaming) {
    ServiceRegistry.rtspService?.setPreviewSurface(previewSurface)
}
// CameraPreview(onSurfaceChanged = { previewSurface = it }, ...)
```
`RTSPService.startStreaming` must also forward the stored surface to the freshly-created server:
```kotlin
rtspServer = RTSPServer(applicationContext, cfg)
rtspServer!!.setPreviewSurface(previewSurface)   // re-attach across server recreation
rtspServer!!.start()
```

## Server side — multi-target capture session
The camera must render to two surfaces at once: the MediaCodec encoder input (for H.264/RTP)
and the preview. In [RTSPServer.kt](../../../app/src/main/java/com/ochelper/rtsp/RTSPServer.kt):

- `startCaptureSession()` builds `targets = listOf(encoderInputSurface) (+ previewSurface)`,
  adds each as a `TEMPLATE_RECORD` request target, and `setRepeatingRequest`.
- `setPreviewSurface(surface)` stores the surface and, if `running && cameraDevice != null`,
  posts `startCaptureSession()` on the camera handler to **rebuild the session live** (so the
  preview can attach/detach during streaming).
- `stop()` clears `previewSurface = null`.
- The preview `SurfaceTexture` uses `setDefaultBufferSize(1280, 720)` to match the stream.

> Gotcha: `android.view.Surface` collides with Compose Material3 `Surface`. In the UI use a
> `Box(Modifier.background(Color.Black))` for the preview frame, not Material `Surface`, to
> avoid the name clash.

## Sizing the preview rectangle
The preview is a fixed-aspect rect at the top of the page:
```kotlin
CameraPreview(
    onSurfaceChanged = { previewSurface = it },
    modifier = Modifier
        .fillMaxWidth(0.5f)        // half width; height follows aspect ratio
        .aspectRatio(16f / 9f)
        .clip(RoundedCornerShape(12.dp)),
)
```
Use `fillMaxWidth(fraction)` to scale; `aspectRatio` keeps height proportional.

## Lifecycle notes
- The camera only opens while **streaming is active** (`RTSPServer.start()` → `openCamera()`),
  so the preview is blank until "开始推流" is clicked — this is expected, not a bug.
- `onSurfaceTextureDestroyed` must call `onSurfaceChanged(null)` so a destroyed surface is not
  reused by the capture session.

## Verify
- No JDK in this workspace env (`JAVA_HOME is not set`) — static check via the IDE language
  server (`get_errors`) is the available verification. To compile:
  ```bash
  cd /home/yaoxin/share/ochelper
  JAVA_HOME=/home/yaoxin/Android/android-studio/jbr \
  ANDROID_HOME=/home/yaoxin/Android/Sdk \
  ./gradlew :app:compileDebugKotlin
  ```
- On device: open Streaming 页, click "开始推流" → the rect shows the live camera, identical to
  the RTSP stream played in VLC/ffplay (`rtsp://<device-ip>:<port>/live`).

## Troubleshooting Map
| Symptom | Cause | Fix |
|---|---|---|
| Preview black after "开始推流" | surface pushed to a null service at render time | Hoist surface to state + `LaunchedEffect(previewSurface, isStreaming)`; forward in `startStreaming` |
| Preview never appears even while streaming | server started without preview target | `RTSPServer.setPreviewSurface` before/at `start`; rebuild session in `startCaptureSession` |
| Compile error: ambiguous `Surface` | `android.view.Surface` vs Material3 `Surface` | Use `Box` for the preview frame |
| Preview too big/small | fixed full width | `Modifier.fillMaxWidth(fraction).aspectRatio(...)` |
| Preview frozen after camera toggle / re-stream | stale capture session | `setPreviewSurface` posts `startCaptureSession()` to rebuild live |
