package com.example.peerconnect.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.peerconnect.ui.screens.*

@Composable
fun PeerConnectNavGraph(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(navController = navController)
        }
        composable("details") {
            DetailsScreen()
        }
        composable("folderSync") {
            FolderSyncScreen()
        }
        composable("peerDiscovery") {
            PeerDiscoveryScreen()
        }
        composable("settings") {
            SettingsScreen()
        }
        composable("transferLogs") {
            TransferLogsScreen()
        }
    }
}
