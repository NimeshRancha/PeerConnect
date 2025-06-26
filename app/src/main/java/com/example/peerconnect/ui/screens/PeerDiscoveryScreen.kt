package com.example.peerconnect.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.peerconnect.util.ConnectionState
import com.example.peerconnect.util.WifiDirectBroadcastReceiver

@SuppressLint("UnspecifiedRegisterReceiverFlag")
@Composable
fun PeerDiscoveryScreen(
    onConnectionInfoChanged: (WifiP2pInfo) -> Unit = {},
    manager: WifiP2pManager,
    channel: WifiP2pManager.Channel
) {
    val context = LocalContext.current

    var peers by remember { mutableStateOf<List<WifiP2pDevice>>(emptyList()) }
    var connectionInfo by remember { mutableStateOf<WifiP2pInfo?>(null) }
    var connectionStatus by remember { mutableStateOf("Not connected") }
    var wasConnected by remember { mutableStateOf(false) }

    var showDisconnectDialog by remember { mutableStateOf(false) }

    val peerListListener = WifiP2pManager.PeerListListener { peerList ->
        peers = peerList.deviceList.toList()
    }

    val connectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->
        connectionInfo = info
        if (info.groupFormed) {
            wasConnected = true
            connectionStatus = "Connected"
            onConnectionInfoChanged(info)
        } else {
            if (wasConnected) {
                connectionStatus = "Disconnected"
                connectionInfo = null
                wasConnected = false
                Toast.makeText(context, "Disconnected from peer", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val receiver = remember {
        WifiDirectBroadcastReceiver(
            manager,
            channel,
            context,
            peerListListener,
            connectionInfoListener
        )
    }

    val intentFilter = remember {
        IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
    }

    val permissionLauncher  = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            try {
                discoverPeers(manager, channel, context)
            } catch (e: SecurityException) {
                Toast.makeText(
                    context,
                    "Missing required permissions for peer discovery",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            Toast.makeText(
                context,
                "All permissions are required for peer discovery and connection",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text("Disconnect") },
            text = { Text("Are you sure you want to disconnect from this device?") },
            confirmButton = {
                Button(
                    onClick = {
                        showDisconnectDialog = false
                        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                connectionStatus = "Disconnected"
                                peers = emptyList()
                                connectionInfo = null
                                wasConnected = false
                                Toast.makeText(context, "Disconnected", Toast.LENGTH_SHORT).show()
                            }

                            override fun onFailure(reason: Int) {
                                Toast.makeText(context, "Failed to disconnect: $reason", Toast.LENGTH_SHORT).show()
                            }
                        })
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Disconnect")
                }
            },
            dismissButton = {
                Button(onClick = { showDisconnectDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Connection Status
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Connection Status", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(connectionStatus)
                if (connectionInfo != null && wasConnected) {
                    Spacer(Modifier.height(8.dp))
                    Text("Role: ${if (connectionInfo!!.isGroupOwner) "Group Owner" else "Client"}")
                    Text("Group Owner Address: ${connectionInfo!!.groupOwnerAddress?.hostAddress}")
                }
            }
        }

        // Discover Button
        Button(
            onClick = {
                if (hasRequiredPermissions(context)) {
                    discoverPeers(manager, channel, context)
                } else {
                    val requiredPermissions = getRequiredPermissions()
                    val missingPermissions = requiredPermissions.filter {
                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                    }
                    permissionLauncher.launch(missingPermissions.toTypedArray())
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Discover Peers")
        }

        // Disconnect Button
        if (wasConnected) {
            Button(
                onClick = { showDisconnectDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Disconnect")
            }
        }

        // Peer List
        Text("Available Devices:", style = MaterialTheme.typography.titleMedium)

        if (peers.isEmpty()) {
            Text(
                "No devices found",
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(peers) { device ->
                    var showPeerDisconnectDialog by remember { mutableStateOf(false) }

                    if (showPeerDisconnectDialog) {
                        AlertDialog(
                            onDismissRequest = { showPeerDisconnectDialog = false },
                            title = { Text("Disconnect") },
                            text = { Text("Are you sure you want to disconnect from this device?") },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        showPeerDisconnectDialog = false
                                        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                                            override fun onSuccess() {
                                                connectionStatus = "Disconnected"
                                                peers = emptyList()
                                                connectionInfo = null
                                                wasConnected = false
                                            }

                                            override fun onFailure(reason: Int) {
                                                Toast.makeText(context, "Failed to disconnect: $reason", Toast.LENGTH_SHORT).show()
                                            }
                                        })
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text(if (device.status == WifiP2pDevice.INVITED) "Cancel" else "Disconnect")
                                }
                            },
                            dismissButton = {
                                Button(onClick = { showPeerDisconnectDialog = false }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                enabled = device.status == WifiP2pDevice.AVAILABLE,
                                onClick = {
                                    val config = WifiP2pConfig().apply {
                                        deviceAddress = device.deviceAddress
                                    }
                                    manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                                        override fun onSuccess() {
                                            connectionStatus = "Connecting to ${device.deviceName}..."
                                        }

                                        override fun onFailure(reason: Int) {
                                            connectionStatus = "Connection failed (reason: $reason)"
                                        }
                                    })
                                }
                            )
                            .padding(4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Device Info
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    device.deviceName.ifEmpty { "Unknown Device" },
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    device.deviceAddress,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    getDeviceStatus(device.status),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Action Button if Connected or Invited
                            if (device.status == WifiP2pDevice.CONNECTED || device.status == WifiP2pDevice.INVITED) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = { showPeerDisconnectDialog = true },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text(if (device.status == WifiP2pDevice.INVITED) "Cancel" else "Disconnect")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Modified DisposableEffect
    DisposableEffect(Unit) {
        context.registerReceiver(receiver, intentFilter)
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }
}

private fun getDeviceStatus(deviceStatus: Int): String {
    return when (deviceStatus) {
        WifiP2pDevice.AVAILABLE -> "Available"
        WifiP2pDevice.INVITED -> "Invited"
        WifiP2pDevice.CONNECTED -> "Connected"
        WifiP2pDevice.FAILED -> "Failed"
        WifiP2pDevice.UNAVAILABLE -> "Unavailable"
        else -> "Unknown"
    }
}

@RequiresPermission(allOf = [
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_WIFI_STATE,
    Manifest.permission.CHANGE_WIFI_STATE,
    Manifest.permission.NEARBY_WIFI_DEVICES
])
private fun discoverPeers(
    manager: WifiP2pManager,
    channel: WifiP2pManager.Channel,
    context: Context
) {
    if (!hasRequiredPermissions(context)) {
        throw SecurityException("Missing required permissions")
    }

    manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            Toast.makeText(context, "Discovery started", Toast.LENGTH_SHORT).show()
        }

        override fun onFailure(reason: Int) {
            val message = when (reason) {
                WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this device"
                WifiP2pManager.ERROR -> "Discovery failed due to an error"
                WifiP2pManager.BUSY -> "Discovery failed because the system is busy"
                else -> "Discovery failed"
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    })
}

private fun getRequiredPermissions(): List<String> {
    val base = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        base.add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
    return base
}


private fun hasRequiredPermissions(context: Context): Boolean {
    val requiredPermissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        requiredPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }

    return requiredPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}