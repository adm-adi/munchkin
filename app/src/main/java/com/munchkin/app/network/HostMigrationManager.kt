package com.munchkin.app.network

import android.util.Log
import com.munchkin.app.core.GameState
import com.munchkin.app.core.PlayerId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Manages automatic host migration when the current host disconnects.
 * 
 * Election algorithm:
 * 1. Players sorted by join order (oldest first)
 * 2. First connected player becomes new host
 * 3. New host increments epoch and broadcasts state
 */
class HostMigrationManager {
    
    companion object {
        private const val TAG = "HostMigration"
        private const val HANDOVER_TIMEOUT_MS = 5000L
        private const val HOST_HEARTBEAT_TIMEOUT_MS = 10000L
    }
    
    private val _migrationState = MutableStateFlow<MigrationState>(MigrationState.Stable)
    val migrationState: StateFlow<MigrationState> = _migrationState.asStateFlow()
    
    private var lastHostHeartbeat: Long = System.currentTimeMillis()
    private var currentHostId: PlayerId? = null
    private var myPlayerId: PlayerId? = null
    private var isCurrentlyHost: Boolean = false
    private var gameState: GameState? = null
    
    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    /**
     * Initialize manager with game context.
     */
    fun initialize(
        hostId: PlayerId,
        myId: PlayerId,
        isHost: Boolean,
        state: GameState
    ) {
        currentHostId = hostId
        myPlayerId = myId
        isCurrentlyHost = isHost
        gameState = state
        lastHostHeartbeat = System.currentTimeMillis()
        
        if (!isHost) {
            startHeartbeatMonitor()
        }
        
        Log.d(TAG, "Initialized: hostId=$hostId, myId=$myId, isHost=$isHost")
    }
    
    /**
     * Update game state (called on each state update).
     */
    fun updateState(state: GameState) {
        gameState = state
    }
    
    /**
     * Record heartbeat from host.
     */
    fun recordHostHeartbeat() {
        lastHostHeartbeat = System.currentTimeMillis()
    }
    
    /**
     * Check if I should become the new host.
     * @return true if this client should take over as host
     */
    fun shouldBecomeHost(): Boolean {
        val state = gameState ?: return false
        val myId = myPlayerId ?: return false
        
        // Get connected players (first one in map becomes new host)
        val connectedPlayers = state.players.values
            .filter { it.isConnected && it.playerId != currentHostId }
        
        // First connected player becomes host
        val newHost = connectedPlayers.firstOrNull()
        
        val shouldBeHost = newHost?.playerId == myId
        Log.d(TAG, "shouldBecomeHost: $shouldBeHost (newHost=${newHost?.playerId}, myId=$myId)")
        
        return shouldBeHost
    }
    
    /**
     * Get the candidate for new host (for election verification).
     */
    fun getNewHostCandidate(): PlayerId? {
        val state = gameState ?: return null
        
        val connectedPlayers = state.players.values
            .filter { it.isConnected && it.playerId != currentHostId }
        
        return connectedPlayers.firstOrNull()?.playerId
    }
    
    /**
     * Start migration process.
     */
    fun startMigration() {
        Log.i(TAG, "Starting migration process")
        _migrationState.value = MigrationState.Electing
    }
    
    /**
     * Complete migration to new host.
     */
    fun completeMigration(newHostId: PlayerId) {
        Log.i(TAG, "Migration complete, new host: $newHostId")
        currentHostId = newHostId
        isCurrentlyHost = newHostId == myPlayerId
        _migrationState.value = MigrationState.Stable
        
        if (!isCurrentlyHost) {
            startHeartbeatMonitor()
        } else {
            stopHeartbeatMonitor()
        }
    }
    
    /**
     * Cancel migration (e.g., host reconnected).
     */
    fun cancelMigration() {
        Log.i(TAG, "Migration cancelled")
        _migrationState.value = MigrationState.Stable
    }
    
    /**
     * Start monitoring host heartbeats.
     */
    private fun startHeartbeatMonitor() {
        stopHeartbeatMonitor()
        
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(2000)
                
                val elapsed = System.currentTimeMillis() - lastHostHeartbeat
                if (elapsed > HOST_HEARTBEAT_TIMEOUT_MS) {
                    Log.w(TAG, "Host heartbeat timeout (${elapsed}ms)")
                    _migrationState.value = MigrationState.HostLost
                    break
                }
            }
        }
    }
    
    /**
     * Stop monitoring host heartbeats.
     */
    private fun stopHeartbeatMonitor() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
    
    /**
     * Clean up resources.
     */
    fun cleanup() {
        stopHeartbeatMonitor()
        scope.cancel()
    }
}

/**
 * State of host migration process.
 */
sealed class MigrationState {
    /** Normal operation, host is stable */
    data object Stable : MigrationState()
    
    /** Host connection lost, monitoring for timeout */
    data object HostLost : MigrationState()
    
    /** Election in progress for new host */
    data object Electing : MigrationState()
    
    /** Migrating to new host */
    data class Migrating(val newHostId: PlayerId) : MigrationState()
    
    /** Migration failed */
    data class Failed(val reason: String) : MigrationState()
}
