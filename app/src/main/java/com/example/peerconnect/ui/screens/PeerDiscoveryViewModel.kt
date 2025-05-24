package com.example.peerconnect.ui.screens

import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.peerconnect.util.ConnectionManager
import com.example.peerconnect.util.ConnectionState
import com.example.peerconnect.util.DeviceStatusUtils
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class PeerDiscoveryViewModel(
    private val context: Context,
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel
) : ViewModel() {
    val connectionManager = ConnectionManager(context, manager, channel)

    var peers by mutableStateOf<List<WifiP2pDevice>>(emptyList())
        private set

    var connectionState by mutableStateOf(ConnectionState())
        private set

    var connectionInfo by mutableStateOf<WifiP2pInfo?>(null)
        private set

    init {
        // Observe connection state changes
        connectionManager.connectionState
            .onEach { state -> connectionState = state }
            .launchIn(viewModelScope)

        // Observe connection info changes
        connectionManager.connectionInfo
            .onEach { info -> connectionInfo = info }
            .launchIn(viewModelScope)
    }

    fun updatePeers(devices: List<WifiP2pDevice>) {
        // Log status changes for each device
        devices.forEach { device ->
            val existingDevice = peers.find { it.deviceAddress == device.deviceAddress }
            if (existingDevice?.status != device.status) {
                Log.d("PeerDiscoveryViewModel", "Device ${device.deviceName} (${device.deviceAddress}) status changed from ${DeviceStatusUtils.getDeviceStatus(existingDevice?.status)} to ${DeviceStatusUtils.getDeviceStatus(device.status)}")
            }
        }
        peers = devices
    }

    fun connectToPeer(device: WifiP2pDevice) {
        connectionManager.connectToPeer(device)
    }

    fun disconnect() {
        connectionManager.disconnect()
    }

    override fun onCleared() {
        super.onCleared()
        connectionManager.disconnect()
    }

    companion object {
        fun provideFactory(
            context: Context,
            manager: WifiP2pManager,
            channel: WifiP2pManager.Channel
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return PeerDiscoveryViewModel(context, manager, channel) as T
            }
        }
    }
} 