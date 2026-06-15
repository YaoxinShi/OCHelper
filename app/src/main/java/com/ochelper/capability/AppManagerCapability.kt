package com.ochelper.capability

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AppManagerCapability(private val context: Context) : DeviceCapability {
    override val id = "apps.list"
    override val name = "List Installed Apps"
    override val description = "List installed applications on the device or launch an app"
    override val inputSchema: JsonObject = buildJsonObject {}

    override suspend fun execute(params: JsonObject): JsonObject {
        val action = params["action"]?.jsonPrimitive?.content ?: "list"

        return when (action) {
            "list" -> {
                val pm = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val apps = pm.queryIntentActivities(intent, 0)
                    .sortedBy { it.loadLabel(pm).toString() }
                    .take(100)

                buildJsonObject {
                    put("apps", buildJsonArray {
                        apps.forEach { info ->
                            add(buildJsonObject {
                                put("package", info.activityInfo.packageName)
                                put("label", info.loadLabel(pm).toString())
                            })
                        }
                    })
                    put("count", apps.size)
                }
            }
            "launch" -> {
                val pkg = params["package"]?.jsonPrimitive?.content
                    ?: return buildJsonObject { put("error", "package required") }
                val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    buildJsonObject { put("ok", true) }
                } else {
                    buildJsonObject { put("error", "app not found or not launchable: $pkg") }
                }
            }
            else -> buildJsonObject { put("error", "unknown action: $action") }
        }
    }
}
