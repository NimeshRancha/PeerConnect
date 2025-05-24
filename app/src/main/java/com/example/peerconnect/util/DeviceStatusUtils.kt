package com.example.peerconnect.util

import android.net.wifi.p2p.WifiP2pDevice

object DeviceStatusUtils {
    fun getDeviceStatus(deviceStatus: Int?): String {
        return when (deviceStatus) {
            WifiP2pDevice.AVAILABLE -> "Available"
            WifiP2pDevice.INVITED -> "Invited"
            WifiP2pDevice.CONNECTED -> "Connected"
            WifiP2pDevice.FAILED -> "Failed"
            WifiP2pDevice.UNAVAILABLE -> "Unavailable"
            else -> "Unknown"
        }
    }

    fun getDeviceStatusColor(deviceStatus: Int): Int {
        return when (deviceStatus) {
            WifiP2pDevice.CONNECTED -> android.graphics.Color.GREEN
            WifiP2pDevice.INVITED -> android.graphics.Color.BLUE
            WifiP2pDevice.AVAILABLE -> android.graphics.Color.GRAY
            WifiP2pDevice.FAILED -> android.graphics.Color.RED
            else -> android.graphics.Color.DKGRAY
        }
    }
} 