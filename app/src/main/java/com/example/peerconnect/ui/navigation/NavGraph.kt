package com.example.peerconnect.ui.navigation

import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import androidx.compose.runtime.*
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.peerconnect.ui.screens.*

@Composable
fun PeerConnectNavGraph(
    navController: NavHostController = rememberNavController(),
    manager: WifiP2pManager,
    channel: WifiP2pManager.Channel
) {
    var connectionInfo by remember { mutableStateOf<WifiP2pInfo?>(null) }

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(navController = navController)
        }
        composable("details") {
            DetailsScreen()
        }
        composable("folderSync") {
            FolderSyncScreen(
                connectionInfo = connectionInfo,
                manager = manager,
                channel = channel
            )
        }
        composable("peerDiscovery") {
            PeerDiscoveryScreen(
                onConnectionInfoChanged = { info ->
                    connectionInfo = info
                    navController.navigate("folderSync")
                },
                manager = manager,
                channel = channel
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