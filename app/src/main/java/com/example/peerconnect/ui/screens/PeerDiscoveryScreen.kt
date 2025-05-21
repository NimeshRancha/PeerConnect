package com.example.peerconnect.ui.screens

import android.Manifest
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.peerconnect.util.WifiDirectBroadcastReceiver

@Composable
fun PeerDiscoveryScreen() {
    val context = LocalContext.current
    val peers = remember { mutableStateListOf<WifiP2pDevice>() }
    val manager = remember { context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager }
    val channel = remember { manager.initialize(context, context.mainLooper, null) }

    val peerListListener = WifiP2pManager.PeerListListener { peerList ->
        peers.clear()
        peers.addAll(peerList.deviceList)
    }

    val receiver = remember {
        WifiDirectBroadcastReceiver(manager, channel, peerListListener)
    }

    val intentFilter = remember {
        IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
    }

    DisposableEffect(Unit) {
        context.registerReceiver(receiver, intentFilter)
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                discoverPeers(manager, channel, context)
            } else {
                Toast.makeText(context, "Location permission is required to discover peers.", Toast.LENGTH_SHORT).show()
            }
        }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Nearby Devices:")

        if (peers.isEmpty()) {
            Text("No devices found.")
        } else {
            peers.forEach { device ->
                Text("- ${device.deviceName} (${device.deviceAddress})")
            }
        }

        Button(onClick = {
            val permissionCheck = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
                discoverPeers(manager, channel, context)
            } else {
                permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }) {
            Text("Discover Peers")
        }
    }
}

@RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
private fun discoverPeers(
    manager: WifiP2pManager,
    channel: WifiP2pManager.Channel,
    context: Context
) {
    manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            Toast.makeText(context, "Discovery Started", Toast.LENGTH_SHORT).show()
        }

        override fun onFailure(reason: Int) {
            Toast.makeText(context, "Discovery Failed: $reason", Toast.LENGTH_SHORT).show()
        }
    })
}



