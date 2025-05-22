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
        _connectionState.value = ConnectionState(
            isConnecting = isConnecting,
            isConnected = isConnected,
            connectionFailed = connectionFailed,
            errorMessage = errorMessage
        )

        if (isConnected) {
            startKeepAlive()
        } else {
            stopKeepAlive()
        }
    }

    fun updateConnectionInfo(info: WifiP2pInfo?) {
        _connectionInfo.value = info
        if (info?.groupFormed == true) {
            updateConnectionState(isConnected = true)
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

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }

        updateConnectionState(isConnecting = true)
        connectedDevice = device

        try {
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Connection initiated successfully")
                }

                override fun onFailure(reason: Int) {
                    val message = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this device"
                        WifiP2pManager.ERROR -> "Connection failed due to an error"
                        WifiP2pManager.BUSY -> "System is busy"
                        else -> "Connection failed"
                    }
                    updateConnectionState(
                        isConnecting = false,
                        connectionFailed = true,
                        errorMessage = message
                    )
                }
            })
        } catch (e: SecurityException) {
            updateConnectionState(
                connectionFailed = true,
                errorMessage = "Missing required permissions"
            )
        }
    }

    private fun checkConnection() {
        if (!hasRequiredPermissions()) return

        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) {
                    handleDisconnection()
                    return@requestGroupInfo
                }

                // If we're connected but the connected device is not in the group
                val deviceInGroup = connectedDevice?.let { device ->
                    group.clientList.any { it.deviceAddress == device.deviceAddress } ||
                            group.owner.deviceAddress == device.deviceAddress
                } ?: false

                if (!deviceInGroup) {
                    handleDisconnection()
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception during connection check: ${e.message}")
        }
    }

    private fun handleDisconnection() {
        updateConnectionState(
            isConnected = false,
            errorMessage = "Connection lost"
        )
        
        // Attempt to reconnect if we have a stored device
        connectedDevice?.let { device ->
            Log.d(TAG, "Attempting to reconnect to ${device.deviceAddress}")
            connectToPeer(device)
        }
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
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    updateConnectionState()
                    connectedDevice = null
                    _connectionInfo.value = null
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Failed to remove group: $reason")
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception during disconnect: ${e.message}")
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
        private const val KEEP_ALIVE_INTERVAL = 5000L // 5 seconds
    }
} 