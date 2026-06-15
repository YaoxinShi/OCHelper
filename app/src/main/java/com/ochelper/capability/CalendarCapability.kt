package com.ochelper.capability

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Calendar

class CalendarCapability(private val context: Context) : DeviceCapability {
    override val id = "calendar.events"
    override val name = "Calendar Events"
    override val description = "Query upcoming calendar events"
    override val inputSchema: JsonObject = buildJsonObject {}

    override suspend fun execute(params: JsonObject): JsonObject {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED) {
            return buildJsonObject { put("error", "calendar permission not granted") }
        }

        val action = params["action"]?.jsonPrimitive?.content ?: "list"

        return when (action) {
            "list" -> listEvents(params)
            "add" -> addEvent(params)
            else -> buildJsonObject { put("error", "unknown action: $action") }
        }
    }

    private fun listEvents(params: JsonObject): JsonObject {
        val daysAhead = params["days_ahead"]?.jsonPrimitive?.content?.toIntOrNull() ?: 7
        val now = System.currentTimeMillis()
        val end = now + daysAhead * 24 * 60 * 60 * 1000L

        val events = buildJsonArray {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(
                    CalendarContract.Events._ID,
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND,
                    CalendarContract.Events.DESCRIPTION,
                    CalendarContract.Events.EVENT_LOCATION,
                ),
                "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ? " +
                "AND ${CalendarContract.Events.DELETED} = 0",
                arrayOf(now.toString(), end.toString()),
                "${CalendarContract.Events.DTSTART} ASC LIMIT 50"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    add(buildJsonObject {
                        put("id", cursor.getLong(0))
                        put("title", cursor.getString(1) ?: "")
                        put("start_ms", cursor.getLong(2))
                        put("end_ms", cursor.getLong(3))
                        put("description", cursor.getString(4) ?: "")
                        put("location", cursor.getString(5) ?: "")
                    })
                }
            }
        }
        return buildJsonObject {
            put("events", events)
            put("count", events.size)
        }
    }

    private fun addEvent(params: JsonObject): JsonObject {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR)
            != PackageManager.PERMISSION_GRANTED) {
            return buildJsonObject { put("error", "write_calendar permission not granted") }
        }
        val title = params["title"]?.jsonPrimitive?.content
            ?: return buildJsonObject { put("error", "title required") }
        val startMs = params["start_ms"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: return buildJsonObject { put("error", "start_ms required") }
        val endMs = params["end_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: (startMs + 3600_000)

        // Get primary calendar ID
        val calendarId = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.IS_PRIMARY} = 1",
            null, null
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else 1L } ?: 1L

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, Calendar.getInstance().timeZone.id)
            params["description"]?.jsonPrimitive?.content?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            params["location"]?.jsonPrimitive?.content?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        return buildJsonObject {
            put("ok", uri != null)
            put("event_id", uri?.let { ContentUris.parseId(it) } ?: -1L)
        }
    }
}
