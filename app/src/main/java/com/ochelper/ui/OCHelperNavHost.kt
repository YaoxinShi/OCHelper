package com.ochelper.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ochelper.ui.dashboard.DashboardScreen
import com.ochelper.ui.gateway.GatewayScreen
import com.ochelper.ui.mcp.MCPServerScreen
import com.ochelper.ui.ocnode.OCNodeScreen
import com.ochelper.ui.settings.SettingsScreen
import com.ochelper.ui.streaming.StreamingScreen

sealed class NavRoute(val route: String, val label: String) {
    object Dashboard : NavRoute("dashboard", "概览")
    object OCNode : NavRoute("ocnode", "Node")
    object MCP : NavRoute("mcp", "MCP")
    object Gateway : NavRoute("gateway", "网关")
    object Stream : NavRoute("stream", "推流")
    object Settings : NavRoute("settings", "设置")
}

@Composable
fun OCHelperNavHost() {
    val navController = rememberNavController()
    val context = LocalContext.current

    val bottomItems = listOf(
        NavRoute.Dashboard to Icons.Default.Dashboard,
        NavRoute.OCNode to Icons.Default.Hub,
        NavRoute.MCP to Icons.Default.Tune,
        NavRoute.Gateway to Icons.Default.Send,
        NavRoute.Stream to Icons.Default.CameraAlt,
        NavRoute.Settings to Icons.Default.Settings,
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDest = navBackStackEntry?.destination
                bottomItems.forEach { (route, icon) ->
                    NavigationBarItem(
                        icon = { Icon(icon, contentDescription = route.label) },
                        label = { Text(route.label) },
                        selected = currentDest?.hierarchy?.any { it.route == route.route } == true,
                        onClick = {
                            navController.navigate(route.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = NavRoute.Dashboard.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(NavRoute.Dashboard.route) { DashboardScreen() }
            composable(NavRoute.OCNode.route) { OCNodeScreen() }
            composable(NavRoute.MCP.route) { MCPServerScreen() }
            composable(NavRoute.Gateway.route) { GatewayScreen() }
            composable(NavRoute.Stream.route) { StreamingScreen() }
            composable(NavRoute.Settings.route) { SettingsScreen() }
        }
    }
}
