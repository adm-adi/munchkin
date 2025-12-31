package com.munchkin.app.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Helper class for Network Service Discovery (NSD/mDNS).
 * Allows automatic discovery of Munchkin games on the local network.
 */
class NsdHelper(context: Context) {
    companion object {
        private const val TAG = "NsdHelper"
        private const val SERVICE_TYPE = "_munchkin._tcp."
        private const val SERVICE_NAME_PREFIX = "MunchkinGame_"
    }
    
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    
    private val _discoveredGames = MutableStateFlow<List<DiscoveredGame>>(emptyList())
    val discoveredGames: StateFlow<List<DiscoveredGame>> = _discoveredGames.asStateFlow()
    
    private val _isPublishing = MutableStateFlow(false)
    val isPublishing: StateFlow<Boolean> = _isPublishing.asStateFlow()
    
    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()
    
    /**
     * Publish a game service for others to discover.
     */
    fun publishGame(
        hostName: String,
        joinCode: String,
        port: Int,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$SERVICE_NAME_PREFIX$joinCode"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("host", hostName)
            setAttribute("code", joinCode)
        }
        
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Service registered: ${info.serviceName}")
                _isPublishing.value = true
                onSuccess()
            }
            
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed: $errorCode")
                onError("Error al publicar partida: $errorCode")
            }
            
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "Service unregistered")
                _isPublishing.value = false
            }
            
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Unregistration failed: $errorCode")
            }
        }
        
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register service", e)
            onError("Error al publicar: ${e.message}")
        }
    }
    
    /**
     * Stop publishing the game service.
     */
    fun unpublishGame() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering service", e)
            }
            registrationListener = null
        }
        _isPublishing.value = false
    }
    
    /**
     * Start discovering games on the local network.
     */
    fun startDiscovery() {
        if (_isDiscovering.value) return
        
        _discoveredGames.value = emptyList()
        
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "Discovery started")
                _isDiscovering.value = true
            }
            
            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped")
                _isDiscovering.value = false
            }
            
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Service found: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceName.startsWith(SERVICE_NAME_PREFIX)) {
                    resolveService(serviceInfo)
                }
            }
            
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Service lost: ${serviceInfo.serviceName}")
                val code = serviceInfo.serviceName.removePrefix(SERVICE_NAME_PREFIX)
                _discoveredGames.value = _discoveredGames.value.filter { it.joinCode != code }
            }
            
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $errorCode")
                _isDiscovering.value = false
            }
            
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: $errorCode")
            }
        }
        
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
        }
    }
    
    /**
     * Stop discovering games.
     */
    fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping discovery", e)
            }
            discoveryListener = null
        }
        _isDiscovering.value = false
    }
    
    /**
     * Resolve a discovered service to get its IP and port.
     */
    private fun resolveService(serviceInfo: NsdServiceInfo) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed: $errorCode")
            }
            
            override fun onServiceResolved(info: NsdServiceInfo) {
                Log.i(TAG, "Service resolved: ${info.host?.hostAddress}:${info.port}")
                
                val hostName = info.attributes["host"]?.let { String(it) } ?: "Desconocido"
                val joinCode = info.attributes["code"]?.let { String(it) } 
                    ?: info.serviceName.removePrefix(SERVICE_NAME_PREFIX)
                
                val game = DiscoveredGame(
                    hostName = hostName,
                    joinCode = joinCode,
                    port = info.port,
                    wsUrl = "ws://${info.host?.hostAddress}:${info.port}/game"
                )
                
                // Add to list if not already present
                if (_discoveredGames.value.none { it.joinCode == joinCode }) {
                    _discoveredGames.value = _discoveredGames.value + game
                }
            }
        })
    }
    
    /**
     * Clean up resources.
     */
    fun cleanup() {
        unpublishGame()
        stopDiscovery()
    }
}
