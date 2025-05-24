package com.example.peerconnect.ui.screens

import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.peerconnect.util.ConnectionManager
import com.example.peerconnect.util.ConnectionState
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class PeerDiscoveryViewModel(
    private val context: Context,
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel
) : ViewModel() {
    private val connectionManager = ConnectionManager(context, manager, channel)

    var peers by mutableStateOf<List<WifiP2pDevice>>(emptyList())
        private set

    var connectionState by mutableStateOf(ConnectionState())
        private set

    var connectionInfo by mutableStateOf<WifiP2pInfo?>(null)
        private set

    private var navigatingToFolderSync = false

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

    fun setNavigatingToFolderSync(navigating: Boolean) {
        navigatingToFolderSync = navigating
    }

    fun isNavigatingToFolderSync(): Boolean = navigatingToFolderSync

    fun updatePeers(devices: List<WifiP2pDevice>) {
        peers = devices
    }

    fun connectToPeer(device: WifiP2pDevice) {
        // Reset navigation state when starting a new connection
        navigatingToFolderSync = false
        connectionManager.connectToPeer(device)
    }

    fun disconnect() {
        // Reset navigation state when disconnecting
        navigatingToFolderSync = false
        connectionManager.disconnect()
    }

    fun cancelInvitation() {
        // Reset navigation state
        navigatingToFolderSync = false
        connectionManager.cancelInvitation()
    }

    override fun onCleared() {
        super.onCleared()
        // Only disconnect if we're not navigating to FolderSync
        if (!navigatingToFolderSync) {
            connectionManager.disconnect()
        }
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