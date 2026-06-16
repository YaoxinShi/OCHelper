package com.ochelper.capability

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraCapability(private val context: Context) : DeviceCapability {
    override val id = "camera.take_photo"
    override val name = "Take Photo"
    override val description = "Capture a photo using the device camera and return it as base64 JPEG"
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
    }

    override suspend fun execute(params: JsonObject): JsonObject {
        // Gateway camera.snap sends "facing"; tolerate the legacy "camera" key too.
        val facing = params["facing"]?.jsonPrimitive?.content
            ?: params["camera"]?.jsonPrimitive?.content
            ?: "back"
        val quality = params["quality"]?.jsonPrimitive?.content?.toIntOrNull() ?: 85

        return try {
            val base64Image = capturePhoto(facing, quality)
            buildJsonObject {
                // Canonical fields expected by the gateway's camera.snap result parser.
                put("format", "jpg")
                put("base64", base64Image)
                put("width", CAPTURE_WIDTH)
                put("height", CAPTURE_HEIGHT)
                // Extra context (ignored by the gateway, useful for debugging).
                put("mime_type", "image/jpeg")
                put("camera", facing)
            }
        } catch (e: Exception) {
            buildJsonObject { put("error", e.message ?: "camera capture failed") }
        }
    }

    private suspend fun capturePhoto(facing: String, quality: Int): String =
        withTimeout(10_000L) {
            suspendCancellableCoroutine { cont ->
                val thread = HandlerThread("CameraCapture").also { it.start() }
                val handler = Handler(thread.looper)
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

                val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val lensFacing = chars.get(CameraCharacteristics.LENS_FACING)
                    if (facing == "front") lensFacing == CameraCharacteristics.LENS_FACING_FRONT
                    else lensFacing == CameraCharacteristics.LENS_FACING_BACK
                } ?: cameraManager.cameraIdList.firstOrNull()
                ?: throw IllegalStateException("No camera available")

                val imageReader = ImageReader.newInstance(CAPTURE_WIDTH, CAPTURE_HEIGHT, ImageFormat.JPEG, 1)

                imageReader.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        thread.quitSafely()
                        image.close()
                        imageReader.close()
                        if (cont.isActive) cont.resume(base64)
                    } catch (e: Exception) {
                        image?.close()
                        thread.quitSafely()
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }, handler)

                try {
                    cameraManager.openCamera(
                        cameraId,
                        object : android.hardware.camera2.CameraDevice.StateCallback() {
                            override fun onOpened(camera: android.hardware.camera2.CameraDevice) {
                                try {
                                    val surface = imageReader.surface
                                    val captureRequest = camera.createCaptureRequest(
                                        android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE
                                    ).apply {
                                        addTarget(surface)
                                    }.build()

                                    camera.createCaptureSession(
                                        listOf(surface),
                                        object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                                            override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                                                session.capture(captureRequest, object :
                                                    android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                                                    override fun onCaptureCompleted(
                                                        session: android.hardware.camera2.CameraCaptureSession,
                                                        request: CaptureRequest,
                                                        result: android.hardware.camera2.TotalCaptureResult
                                                    ) {
                                                        camera.close()
                                                    }
                                                }, handler)
                                            }
                                            override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                                                camera.close()
                                                thread.quitSafely()
                                                if (cont.isActive) cont.resumeWithException(Exception("Camera session config failed"))
                                            }
                                        },
                                        handler
                                    )
                                } catch (e: Exception) {
                                    camera.close()
                                    thread.quitSafely()
                                    if (cont.isActive) cont.resumeWithException(e)
                                }
                            }
                            override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) {
                                camera.close()
                                thread.quitSafely()
                            }
                            override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) {
                                camera.close()
                                thread.quitSafely()
                                if (cont.isActive) cont.resumeWithException(Exception("Camera error: $error"))
                            }
                        },
                        handler
                    )
                } catch (e: SecurityException) {
                    thread.quitSafely()
                    cont.resumeWithException(Exception("Camera permission denied"))
                }

                cont.invokeOnCancellation {
                    thread.quitSafely()
                    imageReader.close()
                }
            }
        }

    companion object {
        private const val CAPTURE_WIDTH = 1280
        private const val CAPTURE_HEIGHT = 720
    }
}
