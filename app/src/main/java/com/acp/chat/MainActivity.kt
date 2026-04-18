package com.acp.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.acp.chat.ui.agents.AgentConfigurationScreen
import com.acp.chat.ui.agents.AgentListScreen
import com.acp.chat.ui.chat.ChatScreen
import com.acp.chat.ui.qr.QRScannerScreen
import com.acp.chat.ui.settings.SettingsScreen
import com.acp.chat.ui.theme.ACPChatTheme
import dagger.hilt.android.AndroidEntryPoint

sealed class Screen(val route: String) {
    data object AgentList : Screen("agents")
    data object QRScanner : Screen("qr_scanner")
    data object Chat : Screen("chat/{agentId}") {
        fun createRoute(agentId: String) = "chat/$agentId"
    }
    data object AgentConfiguration : Screen("agent_config/{agentId}") {
        fun createRoute(agentId: String) = "agent_config/$agentId"
    }
    data object Settings : Screen("settings")
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            ACPChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    NavHost(
                        navController = navController,
                        startDestination = Screen.AgentList.route
                    ) {
                        composable(Screen.AgentList.route) {
                            AgentListScreen(
                                onNavigateToChat = { agentId ->
                                    navController.navigate(Screen.Chat.createRoute(agentId))
                                },
                                onNavigateToQRScanner = {
                                    navController.navigate(Screen.QRScanner.route)
                                },
                                onNavigateToAgentConfig = { agentId ->
                                    navController.navigate(Screen.AgentConfiguration.createRoute(agentId))
                                },
                                onNavigateToSettings = {
                                    navController.navigate(Screen.Settings.route)
                                }
                            )
                        }

                        composable(Screen.QRScanner.route) {
                            QRScannerScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onAgentConnected = { agentId ->
                                    navController.popBackStack()
                                    navController.navigate(Screen.Chat.createRoute(agentId))
                                }
                            )
                        }

                        composable(
                            route = Screen.Chat.route,
                            arguments = listOf(
                                navArgument("agentId") { type = NavType.StringType }
                            )
                        ) {
                            ChatScreen(
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }

                        composable(
                            route = Screen.AgentConfiguration.route,
                            arguments = listOf(
                                navArgument("agentId") { type = NavType.StringType }
                            )
                        ) {
                            AgentConfigurationScreen(
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }

                        composable(Screen.Settings.route) {
                            SettingsScreen(
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
