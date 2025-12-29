package com.munchkin.app.network

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.net.wifi.p2p.WifiP2pManager.*
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Helper for WiFi Direct (P2P) connections.
 * Allows gameplay without a WiFi router - devices connect directly.
 */
class WifiDirectHelper(private val context: Context) {
    
    companion object {
        private const val TAG = "WifiDirect"
    }
    
    private var manager: WifiP2pManager? = null
    private var channel: Channel? = null
    private var receiver: BroadcastReceiver? = null
    
    private val _state = MutableStateFlow(WifiDirectState.IDLE)
    val state: StateFlow<WifiDirectState> = _state.asStateFlow()
    
    private val _peers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val peers: StateFlow<List<WifiP2pDevice>> = _peers.asStateFlow()
    
    private val _connectionInfo = MutableStateFlow<WifiP2pInfo?>(null)
    val connectionInfo: StateFlow<WifiP2pInfo?> = _connectionInfo.asStateFlow()
    
    private val _groupInfo = MutableStateFlow<WifiP2pGroup?>(null)
    val groupInfo: StateFlow<WifiP2pGroup?> = _groupInfo.asStateFlow()
    
    private var peerListListener: PeerListListener? = null
    private var connectionInfoListener: ConnectionInfoListener? = null
    
    /**
     * Initialize WiFi Direct.
     */
    @SuppressLint("MissingPermission")
    fun initialize(): Boolean {
        manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (manager == null) {
            Log.e(TAG, "WiFi P2P not supported")
            return false
        }
        
        channel = manager?.initialize(context, context.mainLooper) { 
            Log.w(TAG, "Channel disconnected")
            _state.value = WifiDirectState.ERROR
        }
        
        if (channel == null) {
            Log.e(TAG, "Failed to initialize channel")
            return false
        }
        
        // Setup listeners
        peerListListener = PeerListListener { peers ->
            _peers.value = peers.deviceList.toList()
            Log.d(TAG, "Found ${peers.deviceList.size} peers")
        }
        
        connectionInfoListener = ConnectionInfoListener { info ->
            _connectionInfo.value = info
            Log.d(TAG, "Connection info: isGroupOwner=${info.isGroupOwner}, groupFormed=${info.groupFormed}")
            
            if (info.groupFormed) {
                _state.value = if (info.isGroupOwner) {
                    WifiDirectState.HOST
                } else {
                    WifiDirectState.CLIENT
                }
            }
        }
        
        // Register broadcast receiver
        registerReceiver()
        
        _state.value = WifiDirectState.READY
        Log.i(TAG, "WiFi Direct initialized")
        return true
    }
    
    /**
     * Start discovering nearby devices.
     */
    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        _state.value = WifiDirectState.DISCOVERING
        
        manager?.discoverPeers(channel, object : ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Discovery started")
            }
            
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Discovery failed: ${getErrorMessage(reason)}")
                _state.value = WifiDirectState.ERROR
            }
        })
    }
    
    /**
     * Stop discovering peers.
     */
    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        manager?.stopPeerDiscovery(channel, null)
        _state.value = WifiDirectState.READY
    }
    
    /**
     * Create a group (become host).
     */
    @SuppressLint("MissingPermission")
    fun createGroup() {
        _state.value = WifiDirectState.CREATING_GROUP
        
        manager?.createGroup(channel, object : ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Group created successfully")
                _state.value = WifiDirectState.HOST
            }
            
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Create group failed: ${getErrorMessage(reason)}")
                _state.value = WifiDirectState.ERROR
            }
        })
    }
    
    /**
     * Connect to a peer device.
     */
    @SuppressLint("MissingPermission")
    fun connectToPeer(device: WifiP2pDevice) {
        _state.value = WifiDirectState.CONNECTING
        
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }
        
        manager?.connect(channel, config, object : ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Connection initiated to ${device.deviceName}")
            }
            
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Connection failed: ${getErrorMessage(reason)}")
                _state.value = WifiDirectState.ERROR
            }
        })
    }
    
    /**
     * Disconnect from current group.
     */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        manager?.removeGroup(channel, object : ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Disconnected from group")
                _state.value = WifiDirectState.READY
                _connectionInfo.value = null
                _groupInfo.value = null
            }
            
            override fun onFailure(reason: Int) {
                Log.w(TAG, "Disconnect failed: ${getErrorMessage(reason)}")
            }
        })
    }
    
    /**
     * Get the host IP address (group owner).
     */
    fun getHostAddress(): String? {
        return connectionInfo.value?.groupOwnerAddress?.hostAddress
    }
    
    /**
     * Check if this device is the group owner (host).
     */
    fun isGroupOwner(): Boolean {
        return connectionInfo.value?.isGroupOwner == true
    }
    
    /**
     * Request current connection info.
     */
    @SuppressLint("MissingPermission")
    fun requestConnectionInfo() {
        manager?.requestConnectionInfo(channel, connectionInfoListener)
    }
    
    /**
     * Request current group info.
     */
    @SuppressLint("MissingPermission")
    fun requestGroupInfo() {
        manager?.requestGroupInfo(channel) { group ->
            _groupInfo.value = group
            Log.d(TAG, "Group info: ${group?.networkName}, clients=${group?.clientList?.size}")
        }
    }
    
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        
        receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(EXTRA_WIFI_STATE, -1)
                        if (state != WIFI_P2P_STATE_ENABLED) {
                            Log.w(TAG, "WiFi P2P not enabled")
                            _state.value = WifiDirectState.DISABLED
                        }
                    }
                    
                    WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        manager?.requestPeers(channel, peerListListener)
                    }
                    
                    WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        manager?.requestConnectionInfo(channel, connectionInfoListener)
                        manager?.requestGroupInfo(channel) { group ->
                            _groupInfo.value = group
                        }
                    }
                    
                    WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        // Device details changed
                    }
                }
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, intentFilter)
        }
    }
    
    /**
     * Clean up resources.
     */
    fun cleanup() {
        try {
            receiver?.let { context.unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receiver", e)
        }
        receiver = null
        channel = null
        manager = null
        _state.value = WifiDirectState.IDLE
    }
    
    private fun getErrorMessage(reason: Int): String {
        return when (reason) {
            ERROR -> "Internal error"
            P2P_UNSUPPORTED -> "P2P not supported"
            BUSY -> "Framework busy"
            else -> "Unknown error ($reason)"
        }
    }
}

/**
 * WiFi Direct connection state.
 */
enum class WifiDirectState {
    IDLE,
    READY,
    DISABLED,
    DISCOVERING,
    CREATING_GROUP,
    CONNECTING,
    HOST,
    CLIENT,
    ERROR
}
