package com.ochelper.capability

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Base64
import android.view.WindowManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream

/**
 * Screen capture capability.
 * Note: MediaProjection requires user approval via startActivityForResult.
 * This capability returns device display metrics when projection is unavailable.
 */
class ScreenCapability(private val context: Context) : DeviceCapability {
    override val id = "screen.screenshot"
    override val name = "Take Screenshot"
    override val description = "Capture the current device screen and return as base64 PNG. Requires screen capture permission."
    override val inputSchema: JsonObject = buildJsonObject {}

    @Volatile var mediaProjection: MediaProjection? = null

    override suspend fun execute(params: JsonObject): JsonObject {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val proj = mediaProjection
        if (proj == null) {
            return buildJsonObject {
                put("error", "screen_capture_permission_required")
                put("message", "MediaProjection not granted. Grant screen capture permission in the app.")
                put("screen_width", width)
                put("screen_height", height)
            }
        }

        return try {
            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val virtualDisplay: VirtualDisplay = proj.createVirtualDisplay(
                "OCHelperCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null, null
            )!!

            // Wait a frame
            kotlinx.coroutines.delay(300)

            val image = imageReader.acquireLatestImage()
            val base64 = if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width
                val bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                bitmap.recycle()
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            } else {
                null
            }

            virtualDisplay.release()
            imageReader.close()

            if (base64 != null) {
                buildJsonObject {
                    put("image_base64", base64)
                    put("mime_type", "image/png")
                    put("width", width)
                    put("height", height)
                }
            } else {
                buildJsonObject { put("error", "failed to acquire screen image") }
            }
        } catch (e: Exception) {
            buildJsonObject { put("error", e.message ?: "screenshot failed") }
        }
    }
}
