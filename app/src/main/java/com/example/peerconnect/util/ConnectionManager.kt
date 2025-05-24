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
    private var consecutiveFailures = 0
    private var lastConnectionCheck = 0L
    
    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            if (_connectionState.value.isConnected) {
                val now = System.currentTimeMillis()
                if (now - lastConnectionCheck >= KEEP_ALIVE_CHECK_INTERVAL) {
                    checkConnection()
                    lastConnectionCheck = now
                }
                handler.postDelayed(this, KEEP_ALIVE_INTERVAL)
            }
        }
    }

    private val MIN_ACTION_INTERVAL = 1000L // 1 second minimum interval

    private var connectionRetryCount = 0
    private val MAX_RETRY_ATTEMPTS = 2

    private fun canPerformAction(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastConnectionCheck < MIN_ACTION_INTERVAL) {
            Log.d(TAG, "Action blocked: too soon after last action")
            return false
        }
        lastConnectionCheck = now
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
            consecutiveFailures = 0
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
            // Reset failure counter on successful connection
            consecutiveFailures = 0
        } else {
            Log.d(TAG, "Group not formed or disbanded")
            if (_connectionState.value.isConnected) {
                handleDisconnection("Group not formed")
            }
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

    fun connectToPeer(device: WifiP2pDevice) {
        if (!hasRequiredPermissions()) {
            updateConnectionState(
                connectionFailed = true,
                errorMessage = "Missing required permissions"
            )
            return
        }

        try {
            cancelConnectionTimeout()
            consecutiveFailures = 0
            updateConnectionState(isConnecting = true)
            
            val config = WifiP2pConfig().apply {
                deviceAddress = device.deviceAddress
                groupOwnerIntent = 0
            }

            Log.d(TAG, "Initiating connection to device: ${device.deviceAddress}")
            connectedDevice = device
            startConnectionTimeout()

            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Connection initiated successfully")
                    // Quick connection verification
                    handler.postDelayed({
                        verifyConnection()
                    }, QUICK_CONNECTION_CHECK_DELAY)
                }

                override fun onFailure(reason: Int) {
                    handleConnectionFailure(reason, device)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error during connect: ${e.message}")
            handleConnectionFailure(WifiP2pManager.ERROR, device)
        }
    }

    private fun verifyConnection() {
        manager.requestConnectionInfo(channel) { info ->
            if (info != null && info.groupFormed) {
                updateConnectionState(isConnected = true)
                _connectionInfo.value = info
                startKeepAlive()
            }
        }
    }

    private fun handleConnectionFailure(reason: Int, device: WifiP2pDevice) {
        cancelConnectionTimeout()
        val message = when (reason) {
            WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct not supported"
            WifiP2pManager.ERROR -> "Connection failed due to internal error"
            WifiP2pManager.BUSY -> "System is busy, retrying..."
            else -> "Connection failed (error code: $reason)"
        }
        
        if (reason == WifiP2pManager.BUSY && consecutiveFailures < MAX_CONNECTION_RETRIES) {
            consecutiveFailures++
            handler.postDelayed({
                connectToPeer(device)
            }, CONNECTION_RETRY_DELAY)
        } else {
            resetConnectionState()
            updateConnectionState(
                connectionFailed = true,
                errorMessage = message
            )
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
                    handleConnectionCheckFailure("No group info available")
                    return@requestGroupInfo
                }

                val deviceInGroup = connectedDevice?.let { device ->
                    val isInGroup = group.clientList.any { it.deviceAddress == device.deviceAddress } ||
                            group.owner?.deviceAddress == device.deviceAddress
                    Log.d(TAG, "Device ${device.deviceAddress} in group: $isInGroup")
                    isInGroup
                } ?: false

                if (!deviceInGroup) {
                    handleConnectionCheckFailure("Device not in group")
                } else {
                    // Reset failure count on success
                    consecutiveFailures = 0
                    Log.d(TAG, "Device still in group, connection maintained")
                    // Verify connection info
                    manager.requestConnectionInfo(channel) { info ->
                        if (info != null && info.groupFormed) {
                            _connectionInfo.value = info
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during connection check: ${e.message}")
            handleConnectionCheckFailure("Error during check: ${e.message}")
        }
    }

    private fun handleConnectionCheckFailure(reason: String) {
        consecutiveFailures++
        Log.w(TAG, "Connection check failed ($consecutiveFailures/$MAX_CONSECUTIVE_FAILURES): $reason")
        
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            Log.e(TAG, "Maximum consecutive failures reached, initiating graceful disconnect")
            // Try one last time to check connection before disconnecting
            handler.postDelayed({
                manager.requestGroupInfo(channel) { group ->
                    if (group == null || !isDeviceInGroup(group)) {
                        handleDisconnection(reason)
                    } else {
                        // Found valid group, reset failures
                        consecutiveFailures = 0
                        Log.d(TAG, "Connection recovered after final check")
                    }
                }
            }, CONNECTION_CHECK_RETRY_DELAY)
        }
    }

    private fun isDeviceInGroup(group: android.net.wifi.p2p.WifiP2pGroup): Boolean {
        return connectedDevice?.let { device ->
            group.clientList.any { it.deviceAddress == device.deviceAddress } ||
                    group.owner?.deviceAddress == device.deviceAddress
        } ?: false
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

        try {
            Log.d(TAG, "Initiating manual-style disconnect")
            cancelConnectionTimeout()
            stopKeepAlive()
            
            // First stop discovery
            manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Successfully stopped peer discovery")
                    removeGroupAndReset()
                }
                override fun onFailure(reason: Int) {
                    Log.d(TAG, "Failed to stop peer discovery, continuing with group removal")
                    removeGroupAndReset()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect: ${e.message}")
            removeGroupAndReset()
        }
    }

    private fun removeGroupAndReset() {
        try {
            // Remove the P2P group first
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Successfully removed P2P group")
                    resetConnectionState()
                    // Restart discovery after a delay
                    handler.postDelayed({
                        restartDiscovery()
                    }, DISCOVERY_RESTART_DELAY)
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Failed to remove P2P group: $reason")
                    resetConnectionState()
                    // Still try to restart discovery
                    handler.postDelayed({
                        restartDiscovery()
                    }, DISCOVERY_RESTART_DELAY)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error removing group: ${e.message}")
            resetConnectionState()
        }
    }

    private fun resetConnectionState() {
        updateConnectionState(
            isConnected = false,
            errorMessage = "Disconnected"
        )
        connectedDevice = null
        _connectionInfo.value = null
        consecutiveFailures = 0
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
        consecutiveFailures = 0  // Reset failure counter
        lastConnectionCheck = System.currentTimeMillis()  // Reset timestamp
        handler.postDelayed(keepAliveRunnable, KEEP_ALIVE_INTERVAL)
        Log.d(TAG, "Started keep-alive monitoring")
    }

    private fun stopKeepAlive() {
        handler.removeCallbacks(keepAliveRunnable)
        Log.d(TAG, "Stopped keep-alive monitoring")
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
        
        // Connection establishment timings
        private const val CONNECTION_TIMEOUT = 15000L // 15 seconds for initial connection
        private const val QUICK_CONNECTION_CHECK_DELAY = 1000L // 1 second for quick verification
        private const val CONNECTION_RETRY_DELAY = 2000L // 2 seconds between connection retries
        private const val MAX_CONNECTION_RETRIES = 3
        
        // Keep-alive timings
        private const val KEEP_ALIVE_INTERVAL = 10000L // Check every 10 seconds
        private const val KEEP_ALIVE_CHECK_INTERVAL = 8000L // Minimum 8 seconds between checks
        private const val MAX_CONSECUTIVE_FAILURES = 3
        
        // Disconnect and discovery timings
        private const val DISCOVERY_RESTART_DELAY = 1000L // 1 second before restarting discovery
        private const val CONNECTION_CHECK_RETRY_DELAY = 2000L // 2 second delay before final check
    }
} 