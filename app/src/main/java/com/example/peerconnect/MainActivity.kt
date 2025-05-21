package com.example.peerconnect

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import com.example.peerconnect.ui.navigation.PeerConnectNavGraph
import com.example.peerconnect.ui.theme.PeerConnectTheme

class MainActivity : ComponentActivity() {

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
        setContent {
            PeerConnectTheme {
                PeerConnectNavGraph()
            }
        }
    }
}
