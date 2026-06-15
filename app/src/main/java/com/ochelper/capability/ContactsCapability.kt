package com.ochelper.capability

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ContactsCapability(private val context: Context) : DeviceCapability {
    override val id = "contacts.search"
    override val name = "Search Contacts"
    override val description = "Search and retrieve contacts from the device address book"
    override val inputSchema: JsonObject = buildJsonObject {}

    override suspend fun execute(params: JsonObject): JsonObject {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            return buildJsonObject { put("error", "contacts permission not granted") }
        }

        val query = params["query"]?.jsonPrimitive?.content ?: ""
        val limit = params["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20

        val selection = if (query.isNotBlank())
            "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?" else null
        val selectionArgs = if (query.isNotBlank()) arrayOf("%$query%") else null

        val contacts = buildJsonArray {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Contacts.HAS_PHONE_NUMBER,
                ),
                selection, selectionArgs,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC LIMIT $limit"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                val hasPhoneCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)
                while (cursor.moveToNext()) {
                    val contactId = cursor.getString(idCol)
                    val displayName = cursor.getString(nameCol) ?: ""
                    val hasPhone = cursor.getInt(hasPhoneCol) > 0

                    val phones = if (hasPhone) {
                        buildList {
                            context.contentResolver.query(
                                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                                arrayOf(contactId), null
                            )?.use { phoneCursor ->
                                while (phoneCursor.moveToNext()) {
                                    add(phoneCursor.getString(0) ?: "")
                                }
                            }
                        }
                    } else emptyList()

                    add(buildJsonObject {
                        put("id", contactId)
                        put("name", displayName)
                        put("phones", buildJsonArray { phones.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                    })
                }
            }
        }

        return buildJsonObject {
            put("contacts", contacts)
            put("count", contacts.size)
        }
    }
}
