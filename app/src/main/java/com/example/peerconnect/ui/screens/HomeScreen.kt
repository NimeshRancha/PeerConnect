package com.example.peerconnect.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@Composable
fun HomeScreen(navController: NavController) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(onClick = { navController.navigate("details") }) {
            Text("Go to Details Screen")
        }
        Button(onClick = { navController.navigate("folderSync") }) {
            Text("Go to Folder Sync")
        }
        Button(onClick = { navController.navigate("peerDiscovery") }) {
            Text("Go to Peer Discovery")
        }
        Button(onClick = { navController.navigate("settings") }) {
            Text("Go to Settings")
        }
        Button(onClick = { navController.navigate("transferLogs") }) {
            Text("Go to Transfer Logs")
        }
    }
}
