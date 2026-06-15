package com.ochelper.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

data class PermissionInfo(
    val name: String,
    val label: String,
    val permission: String,
)

val appPermissions = listOf(
    PermissionInfo("camera", "相机", Manifest.permission.CAMERA),
    PermissionInfo("microphone", "麦克风", Manifest.permission.RECORD_AUDIO),
    PermissionInfo("location", "精确位置", Manifest.permission.ACCESS_FINE_LOCATION),
    PermissionInfo("contacts_read", "读取联系人", Manifest.permission.READ_CONTACTS),
    PermissionInfo("contacts_write", "写入联系人", Manifest.permission.WRITE_CONTACTS),
    PermissionInfo("calendar_read", "读取日历", Manifest.permission.READ_CALENDAR),
    PermissionInfo("calendar_write", "写入日历", Manifest.permission.WRITE_CALENDAR),
    PermissionInfo("media_images", "媒体图片", Manifest.permission.READ_MEDIA_IMAGES),
)

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var permissionStates by remember { mutableStateOf(getPermissionStates(context)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionStates = getPermissionStates(context) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.titleLarge)

        // Permissions
        ElevatedCard {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("权限状态", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        launcher.launch(appPermissions.map { it.permission }.toTypedArray())
                    }) { Text("全部授权") }
                }
                HorizontalDivider()
                appPermissions.forEach { perm ->
                    val granted = permissionStates[perm.permission] == true
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (granted) Color(0xFF4CAF50) else Color(0xFFFF9800),
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(perm.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        if (!granted) {
                            TextButton(
                                onClick = { launcher.launch(arrayOf(perm.permission)) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) { Text("授权", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }

                // Notification access (special permission)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val notifAccess = isNotificationAccessGranted(context)
                    Icon(
                        imageVector = if (notifAccess) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (notifAccess) Color(0xFF4CAF50) else Color(0xFFFF9800),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("通知访问权限", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (!notifAccess) {
                        TextButton(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) { Text("去授权", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }

        // App info
        ElevatedCard {
            Column(Modifier.padding(12.dp).fillMaxWidth()) {
                Text("关于", style = MaterialTheme.typography.labelMedium)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("OCHelper v1.0.0", style = MaterialTheme.typography.bodySmall)
                Text("Android Helper APK for OpenClaw", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("系统应用设置") }
            }
        }
    }
}

private fun getPermissionStates(context: Context): Map<String, Boolean> =
    appPermissions.associate { perm ->
        perm.permission to (ContextCompat.checkSelfPermission(context, perm.permission) == PackageManager.PERMISSION_GRANTED)
    }

private fun isNotificationAccessGranted(context: Context): Boolean {
    val enabledPackages = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    ) ?: return false
    return enabledPackages.contains(context.packageName)
}
