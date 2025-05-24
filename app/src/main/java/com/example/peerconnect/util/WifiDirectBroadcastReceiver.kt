package com.example.peerconnect.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
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
                    manager.requestPeers(channel, peerListener)
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    Log.d(TAG, "P2P connection changed")
                    
                    // Instead of using deprecated NetworkInfo, check group info directly
                    manager.requestGroupInfo(channel) { group ->
                        val isConnected = group != null
                        
                        if (isConnected) {
                            // Connection established
                            Log.d(TAG, "Group formed. Owner: ${group!!.owner.deviceAddress}, Clients: ${group.clientList.joinToString { it.deviceAddress }}")
                            manager.requestConnectionInfo(channel) { info ->
                                if (info != null && info.groupFormed) {
                                    Log.d(TAG, "Connected to P2P network. Group owner: ${info.isGroupOwner}, Address: ${info.groupOwnerAddress?.hostAddress}")
                                    connectionInfoListener.onConnectionInfoAvailable(info)
                                }
                            }
                        } else {
                            // Connection lost or disconnected
                            Log.d(TAG, "P2P connection lost or disconnected")
                            viewModel.disconnect()
                        }
                    }
                    // Always request peers to update states
                    manager.requestPeers(channel, peerListener)
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                    
                    Log.d(TAG, "This device changed. Status: ${getDeviceStatus(device?.status ?: -1)}")
                    
                    // If this device's status indicates disconnection, ensure peer is also disconnected
                    if (device?.status == WifiP2pDevice.AVAILABLE && viewModel.connectionState.isConnected) {
                        viewModel.disconnect()
                    }
                    
                    // Request peers update to refresh all device states
                    manager.requestPeers(channel, peerListener)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception in broadcast receiver: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error in broadcast receiver: ${e.message}")
        }
    }

    private fun getDeviceStatus(status: Int): String {
        return when (status) {
            WifiP2pDevice.AVAILABLE -> "Available"
            WifiP2pDevice.INVITED -> "Invited"
            WifiP2pDevice.CONNECTED -> "Connected"
            WifiP2pDevice.FAILED -> "Failed"
            WifiP2pDevice.UNAVAILABLE -> "Unavailable"
            else -> "Unknown"
        }
    }

    companion object {
        private const val TAG = "WifiDirectReceiver"
    }
}
