package com.example.peerconnect.ui.navigation

import android.net.wifi.p2p.WifiP2pInfo
import androidx.compose.runtime.*
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.peerconnect.ui.screens.*

@Composable
fun PeerConnectNavGraph(navController: NavHostController = rememberNavController()) {
    var connectionInfo by remember { mutableStateOf<WifiP2pInfo?>(null) }

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(navController = navController)
        }
        composable("details") {
            DetailsScreen()
        }
        composable("folderSync") {
            FolderSyncScreen(connectionInfo = connectionInfo)
        }
        composable("peerDiscovery") {
            PeerDiscoveryScreen(
                onConnectionInfoChanged = { info ->
                    connectionInfo = info
                    navController.navigate("folderSync")
                }
            )
        }
        composable("settings") {
            SettingsScreen()
        }
        composable("transferLogs") {
            TransferLogsScreen()
        }
    }
}