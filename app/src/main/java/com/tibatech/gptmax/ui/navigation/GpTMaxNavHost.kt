package com.tibatech.gptmax.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tibatech.gptmax.R
import com.tibatech.gptmax.ui.screens.AgentScreen
import com.tibatech.gptmax.ui.screens.ChatScreen
import com.tibatech.gptmax.ui.screens.EditorScreen
import com.tibatech.gptmax.ui.screens.ModelsScreen
import com.tibatech.gptmax.ui.screens.SettingsScreen

private data class Tab(val route: String, val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val TABS = listOf(
    Tab("chat", R.string.tab_chat, Icons.Filled.Chat),
    Tab("agent", R.string.tab_agent, Icons.Filled.SmartToy),
    Tab("editor", R.string.tab_editor, Icons.Filled.Code),
    Tab("models", R.string.tab_models, Icons.Filled.Memory),
    Tab("settings", R.string.tab_settings, Icons.Filled.Settings),
)

@Composable
fun GpTMaxRoot() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: "chat"

    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "chat",
            modifier = Modifier.padding(padding),
        ) {
            composable("chat") { ChatScreen() }
            composable("agent") { AgentScreen() }
            composable("editor") { EditorScreen() }
            composable("models") { ModelsScreen() }
            composable("settings") { SettingsScreen() }
        }
    }
}
