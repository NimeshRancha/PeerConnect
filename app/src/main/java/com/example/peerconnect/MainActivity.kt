package com.example.peerconnect

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import com.example.peerconnect.ui.navigation.PeerConnectNavGraph
import com.example.peerconnect.ui.theme.PeerConnectTheme

class MainActivity : ComponentActivity() {
    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel

    private fun requestPermissions() {
        val permissions = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_WIFI_STATE,
            android.Manifest.permission.CHANGE_WIFI_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()

        // Initialize WifiP2pManager and Channel
        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)

        setContent {
            PeerConnectTheme {
                PeerConnectNavGraph(
                    manager = manager,
                    channel = channel
                )
            }
        }
    }
}
