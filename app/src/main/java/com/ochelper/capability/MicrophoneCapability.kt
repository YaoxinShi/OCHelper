package com.ochelper.capability

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

class MicrophoneCapability(private val context: Context) : DeviceCapability {
    override val id = "microphone.record"
    override val name = "Record Audio"
    override val description = "Record audio from the microphone for a specified duration and return the file path"
    override val inputSchema: JsonObject = buildJsonObject {}

    override suspend fun execute(params: JsonObject): JsonObject {
        val durationSec = params["duration_sec"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceIn(1, 30) ?: 5
        val outputFile = File(context.cacheDir, "recording_${System.currentTimeMillis()}.m4a")

        @Suppress("DEPRECATION")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

        return try {
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
            delay(durationSec * 1000L)
            recorder.stop()
            recorder.release()

            buildJsonObject {
                put("file_path", outputFile.absolutePath)
                put("duration_sec", durationSec)
                put("size_bytes", outputFile.length())
            }
        } catch (e: Exception) {
            try { recorder.release() } catch (_: Exception) {}
            outputFile.delete()
            buildJsonObject { put("error", e.message ?: "recording failed") }
        }
    }
}
