package com.example.peerconnect.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pManager
import com.example.peerconnect.ui.screens.PeerDiscoveryViewModel

class WifiDirectBroadcastReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val peerListener: WifiP2pManager.PeerListListener,
    private val connectionInfoListener: WifiP2pManager.ConnectionInfoListener,
    private val viewModel: PeerDiscoveryViewModel
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        // Wi-Fi P2P is enabled
                    } else {
                        viewModel.disconnect()
                    }
                }
                
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    manager.requestPeers(channel, peerListener)
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    manager.requestConnectionInfo(channel, connectionInfoListener)
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    // Handle device changes if needed
                }
            }
        } catch (e: SecurityException) {
            // Handle security exception
        }
    }
}
