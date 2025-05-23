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

    fun connectToPeer(device: WifiP2pDevice) {
        if (!hasRequiredPermissions()) {
            updateConnectionState(
                connectionFailed = true,
                errorMessage = "Missing required permissions"
            )
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
        updateConnectionState(
            isConnected = false,
            errorMessage = reason
        )
        connectedDevice = null
        _connectionInfo.value = null
    }

    private fun startKeepAlive() {
        handler.removeCallbacks(keepAliveRunnable)
        handler.postDelayed(keepAliveRunnable, KEEP_ALIVE_INTERVAL)
    }

    private fun stopKeepAlive() {
        handler.removeCallbacks(keepAliveRunnable)
    }

    fun disconnect() {
        if (!hasRequiredPermissions()) return

        try {
            Log.d(TAG, "Initiating disconnect")
            cancelConnectionTimeout()
            stopKeepAlive()
            
            // Only try to remove group if we're connected or connecting
            if (_connectionState.value.isConnected || _connectionState.value.isConnecting) {
                manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "Successfully removed from group")
                        updateConnectionState()
                        connectedDevice = null
                        _connectionInfo.value = null
                    }

                    override fun onFailure(reason: Int) {
                        val message = when (reason) {
                            WifiP2pManager.BUSY -> "System is busy"
                            WifiP2pManager.ERROR -> "Internal error"
                            else -> "Error code: $reason"
                        }
                        Log.e(TAG, "Failed to remove group: $message")
                        // Still reset the state even if removeGroup fails
                        updateConnectionState()
                        connectedDevice = null
                        _connectionInfo.value = null
                    }
                })
            } else {
                // Just reset state if we're not connected
                updateConnectionState()
                connectedDevice = null
                _connectionInfo.value = null
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception during disconnect: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect: ${e.message}")
        }
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