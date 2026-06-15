package com.ochelper.capability

import android.content.Context
import android.database.Cursor
import android.provider.MediaStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class GalleryCapability(private val context: Context) : DeviceCapability {
    override val id = "gallery.list"
    override val name = "Gallery List"
    override val description = "List recent photos and videos from the device gallery"
    override val inputSchema: JsonObject = buildJsonObject {}

    override suspend fun execute(params: JsonObject): JsonObject {
        val limit = params["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
        val type = params["type"]?.jsonPrimitive?.content ?: "images" // images | videos | all

        val uri = when (type) {
            "videos" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
        )

        val items = buildJsonArray {
            context.contentResolver.query(
                uri,
                projection,
                null, null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC LIMIT $limit"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                while (cursor.moveToNext()) {
                    add(buildJsonObject {
                        put("id", cursor.getLong(idCol))
                        put("name", cursor.getString(nameCol) ?: "")
                        put("date_added", cursor.getLong(dateCol))
                        put("size_bytes", cursor.getLong(sizeCol))
                        put("mime_type", cursor.getString(mimeCol) ?: "")
                        put("uri", "$uri/${cursor.getLong(idCol)}")
                    })
                }
            }
        }

        return buildJsonObject {
            put("items", items)
            put("count", items.size)
        }
    }
}
