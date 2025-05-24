package com.example.peerconnect.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
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
                        Log.d(TAG, "Wi-Fi P2P is enabled")
                        // Request peers when P2P is enabled
                        manager.requestPeers(channel, peerListener)
                    } else {
                        Log.d(TAG, "Wi-Fi P2P is disabled")
                        viewModel.disconnect()
                    }
                }
                
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    Log.d(TAG, "P2P peers changed")
                    // Request peers immediately
                    manager.requestPeers(channel, peerListener)
                    
                    // Also request peers after a short delay to catch status updates
                    Handler(Looper.getMainLooper()).postDelayed({
                        Log.d(TAG, "Requesting peer list update after delay")
                        manager.requestPeers(channel, peerListener)
                    }, 1000) // 1 second delay
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    Log.d(TAG, "P2P connection changed")
                    // Request both group info and connection info to ensure proper state
                    manager.requestGroupInfo(channel) { group ->
                        Log.d(TAG, "Group info received: ${group != null}")
                        if (group != null) {
                            Log.d(TAG, "Group formed. Owner: ${group.owner.deviceAddress}, Clients: ${group.clientList.joinToString { it.deviceAddress }}")
                            manager.requestConnectionInfo(channel) { info ->
                                if (info != null && info.groupFormed) {
                                    Log.d(TAG, "Connected to P2P network. Group owner: ${info.isGroupOwner}, Address: ${info.groupOwnerAddress?.hostAddress}")
                                    // Update connection state through the viewModel
                                    viewModel.connectionManager.updateConnectionState(isConnected = true)
                                    connectionInfoListener.onConnectionInfoAvailable(info)
                                } else {
                                    Log.d(TAG, "Connection info not available or group not formed")
                                    viewModel.connectionManager.updateConnectionState(isConnected = false)
                                }
                            }
                        } else {
                            // Only disconnect if we were previously connected
                            if (viewModel.connectionState.isConnected) {
                                Log.d(TAG, "Group disbanded, disconnecting")
                                viewModel.disconnect()
                            } else {
                                Log.d(TAG, "No group formed yet")
                                viewModel.connectionManager.updateConnectionState(isConnected = false)
                            }
                        }
                    }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    Log.d(TAG, "This device changed")
                    // Only check connection if we're already connected
                    if (viewModel.connectionState.isConnected) {
                        manager.requestConnectionInfo(channel, connectionInfoListener)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception in broadcast receiver: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error in broadcast receiver: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "WifiDirectReceiver"
    }
}
