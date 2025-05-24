package com.example.peerconnect.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConnectionState(
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val connectionFailed: Boolean = false,
    val errorMessage: String? = null
)

class ConnectionManager(
    private val context: Context,
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel
) {
    private val _connectionState = MutableStateFlow(ConnectionState())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectionInfo = MutableStateFlow<WifiP2pInfo?>(null)
    val connectionInfo: StateFlow<WifiP2pInfo?> = _connectionInfo.asStateFlow()

    private var connectedDevice: WifiP2pDevice? = null
    private val handler = Handler(Looper.getMainLooper())
    private var connectionTimeoutRunnable: Runnable? = null
    
    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            if (_connectionState.value.isConnected) {
                checkConnection()
                handler.postDelayed(this, KEEP_ALIVE_INTERVAL)
            }
        }
    }

    private var lastActionTimestamp = 0L
    private val MIN_ACTION_INTERVAL = 500L // 500ms minimum interval between actions

    private fun canPerformAction(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastActionTimestamp < MIN_ACTION_INTERVAL) {
            Log.d(TAG, "Action blocked: too soon after last action")
            return false
        }
        lastActionTimestamp = now
        return true
    }

    fun updateConnectionState(
        isConnecting: Boolean = false,
        isConnected: Boolean = false,
        connectionFailed: Boolean = false,
        errorMessage: String? = null
    ) {
        Log.d(TAG, "Updating connection state: connecting=$isConnecting, connected=$isConnected, failed=$connectionFailed, error=$errorMessage")
        _connectionState.value = ConnectionState(
            isConnecting = isConnecting,
            isConnected = isConnected,
            connectionFailed = connectionFailed,
            errorMessage = errorMessage
        )

        if (isConnected) {
            cancelConnectionTimeout()
            startKeepAlive()
        } else if (!isConnecting) {
            stopKeepAlive()
        }
    }

    fun updateConnectionInfo(info: WifiP2pInfo?) {
        Log.d(TAG, "Updating connection info: ${info?.toString()}")
        _connectionInfo.value = info
        if (info?.groupFormed == true) {
            Log.d(TAG, "Group formed, updating connection state. Is group owner: ${info.isGroupOwner}")
            updateConnectionState(isConnected = true)
            startKeepAlive()
        } else {
            Log.d(TAG, "Group not formed or disbanded")
            handleDisconnection("Group not formed")
        }
    }

    private fun startConnectionTimeout() {
        cancelConnectionTimeout()
        connectionTimeoutRunnable = Runnable {
            Log.e(TAG, "Connection attempt timed out")
            if (_connectionState.value.isConnecting) {
                updateConnectionState(
                    isConnecting = false,
                    connectionFailed = true,
                    errorMessage = "Connection attempt timed out"
                )
                disconnect()
            }
        }
        handler.postDelayed(connectionTimeoutRunnable!!, CONNECTION_TIMEOUT)
    }

    private fun cancelConnectionTimeout() {
        connectionTimeoutRunnable?.let {
            handler.removeCallbacks(it)
            connectionTimeoutRunnable = null
        }
    }

    private var connectionRetryCount = 0
    private val MAX_RETRY_ATTEMPTS = 2

    fun connectToPeer(device: WifiP2pDevice) {
        if (!hasRequiredPermissions()) {
            updateConnectionState(
                connectionFailed = true,
                errorMessage = "Missing required permissions"
            )
            return
        }

        if (!canPerformAction()) {
            Log.d(TAG, "Ignoring connect request: too soon after last action")
            return
        }

        try {
            // Cancel any existing connection attempt
            cancelConnectionTimeout()
            
            // Reset connection state
            updateConnectionState(isConnecting = true)

            val config = WifiP2pConfig().apply {
                deviceAddress = device.deviceAddress
                // Set connection timeout and group owner preferences
                groupOwnerIntent = 0 // Let the system decide who becomes group owner
            }

            Log.d(TAG, "Initiating connection to device: ${device.deviceAddress}")
            connectedDevice = device
            
            // Start connection timeout before initiating connection
            startConnectionTimeout()

            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Connection initiated successfully to ${device.deviceAddress}")
                    // Keep isConnecting true until we receive WIFI_P2P_CONNECTION_CHANGED_ACTION
                }

                override fun onFailure(reason: Int) {
                    cancelConnectionTimeout()
                    val message = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this device"
                        WifiP2pManager.ERROR -> "Connection failed due to an internal error"
                        WifiP2pManager.BUSY -> "System is busy, please try again"
                        else -> "Connection failed (error code: $reason)"
                    }
                    Log.e(TAG, "Connection failed: $message")
                    handleDisconnection(message)
                    
                    // Restart discovery after connection failure
                    handler.postDelayed({
                        restartDiscovery()
                    }, 500)
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception during connect: ${e.message}")
            handleDisconnection("Missing required permissions")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during connect: ${e.message}")
            handleDisconnection("Unexpected error: ${e.message}")
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

    private fun restartDiscovery() {
        try {
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Successfully restarted peer discovery")
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Failed to restart peer discovery")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting discovery: ${e.message}")
        }
    }

    private fun checkConnection() {
        if (!hasRequiredPermissions()) return

        try {
            Log.d(TAG, "Checking connection status")
            manager.requestGroupInfo(channel) { group ->
                if (group == null) {
                    Log.d(TAG, "No group info available")
                    handleDisconnection("No group info available")
                    return@requestGroupInfo
                }

                val deviceInGroup = connectedDevice?.let { device ->
                    val isInGroup = group.clientList.any { it.deviceAddress == device.deviceAddress } ||
                            group.owner.deviceAddress == device.deviceAddress
                    Log.d(TAG, "Device ${device.deviceAddress} in group: $isInGroup (owner: ${group.owner.deviceAddress}, clients: ${group.clientList.joinToString { it.deviceAddress }})")
                    isInGroup
                } ?: false

                if (!deviceInGroup) {
                    Log.d(TAG, "Connected device not in group")
                    handleDisconnection("Device not in group")
                } else {
                    Log.d(TAG, "Device still in group, connection maintained")
                    // Double check with connection info
                    manager.requestConnectionInfo(channel) { info ->
                        if (info != null && info.groupFormed) {
                            updateConnectionState(isConnected = true)
                        } else {
                            handleDisconnection("Group formed but no connection info")
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception during connection check: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error during connection check: ${e.message}")
        }
    }

    private fun handleDisconnection(reason: String) {
        Log.d(TAG, "Handling disconnection: $reason")
        updateStateAndRequestPeers(
            isConnected = false,
            errorMessage = reason
        )
        connectedDevice = null
        _connectionInfo.value = null
        Log.d(TAG, "Device disconnected: $reason")
    }

    private fun updateStateAndRequestPeers(
        isConnecting: Boolean = false,
        isConnected: Boolean = false,
        connectionFailed: Boolean = false,
        errorMessage: String? = null
    ) {
        updateConnectionState(isConnecting, isConnected, connectionFailed, errorMessage)
        // Request peers update to refresh device states
        try {
            manager.requestPeers(channel) { /* Using empty listener as the broadcast receiver will handle the update */ }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting peers update: ${e.message}")
        }
    }

    fun disconnect() {
        if (!hasRequiredPermissions()) return
        if (!canPerformAction()) {
            Log.d(TAG, "Ignoring disconnect request: too soon after last action")
            return
        }

        try {
            Log.d(TAG, "Initiating system-level disconnect")
            cancelConnectionTimeout()
            stopKeepAlive()
            
            // First, update state to ensure UI reflects disconnection immediately
            updateStateAndRequestPeers(
                isConnected = false,
                errorMessage = "Disconnecting"
            )

            // Cancel any ongoing discovery first
            manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Successfully stopped peer discovery")
                    proceedWithDisconnect()
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Failed to stop peer discovery, proceeding anyway")
                    proceedWithDisconnect()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect: ${e.message}")
            handleDisconnection("Error during disconnect: ${e.message}")
        }
    }

    private fun proceedWithDisconnect() {
        try {
            // First try to cancel any pending connections
            manager.cancelConnect(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Successfully cancelled pending connections")
                    removeGroupAndReset()
                }
                override fun onFailure(reason: Int) {
                    Log.d(TAG, "No pending connections to cancel or cancellation failed")
                    removeGroupAndReset()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error in proceedWithDisconnect: ${e.message}")
            removeGroupAndReset()
        }
    }

    private fun removeGroupAndReset() {
        try {
            // Then remove any existing group
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Successfully removed P2P group")
                    resetStateAndRestartDiscovery()
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Failed to remove P2P group: $reason")
                    resetStateAndRestartDiscovery()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error in removeGroupAndReset: ${e.message}")
            resetStateAndRestartDiscovery()
        }
    }

    private fun resetStateAndRestartDiscovery() {
        // Reset all state variables
        connectedDevice = null
        _connectionInfo.value = null
        updateConnectionState(
            isConnected = false,
            errorMessage = "Disconnected"
        )

        // Add a small delay before restarting discovery
        handler.postDelayed({
            try {
                // Request peers to refresh the list
                manager.requestPeers(channel) { /* Using empty listener as the broadcast receiver will handle the update */ }
                
                // Restart peer discovery
                manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "Successfully restarted peer discovery")
                        // Request peers again after discovery starts
                        handler.postDelayed({
                            try {
                                manager.requestPeers(channel) { /* Using empty listener as the broadcast receiver will handle the update */ }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error requesting peers after discovery restart: ${e.message}")
                            }
                        }, 1000)
                    }
                    override fun onFailure(reason: Int) {
                        Log.e(TAG, "Failed to restart peer discovery")
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Error in resetStateAndRestartDiscovery: ${e.message}")
            }
        }, 500)
    }

    fun cancelInvitation() {
        if (!hasRequiredPermissions()) return
        if (!canPerformAction()) {
            Log.d(TAG, "Ignoring cancel invitation request: too soon after last action")
            return
        }

        try {
            Log.d(TAG, "Canceling invitation")
            cancelConnectionTimeout()
            stopKeepAlive()
            
            manager.cancelConnect(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Successfully canceled invitation")
                    handler.postDelayed({
                        updateStateAndRequestPeers(
                            isConnected = false,
                            errorMessage = "Invitation canceled"
                        )
                        connectedDevice = null
                        _connectionInfo.value = null
                        Log.d(TAG, "Invitation canceled successfully")
                    }, 100)
                }

                override fun onFailure(reason: Int) {
                    val message = when (reason) {
                        WifiP2pManager.BUSY -> "System is busy"
                        WifiP2pManager.ERROR -> "Internal error"
                        else -> "Error code: $reason"
                    }
                    Log.e(TAG, "Failed to cancel invitation: $message")
                    updateStateAndRequestPeers(
                        isConnected = false,
                        errorMessage = "Failed to cancel invitation"
                    )
                    connectedDevice = null
                    _connectionInfo.value = null
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error during invitation cancellation: ${e.message}")
            handleDisconnection("Error during invitation cancellation: ${e.message}")
        }
    }

    private fun startKeepAlive() {
        handler.removeCallbacks(keepAliveRunnable)
        handler.postDelayed(keepAliveRunnable, KEEP_ALIVE_INTERVAL)
    }

    private fun stopKeepAlive() {
        handler.removeCallbacks(keepAliveRunnable)
    }

    private fun hasRequiredPermissions(): Boolean {
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

    companion object {
        private const val TAG = "ConnectionManager"
        private const val KEEP_ALIVE_INTERVAL = 2000L // Check every 2 seconds
        private const val CONNECTION_TIMEOUT = 15000L // 15 seconds timeout for connection attempts
    }
} 