package com.munchkin.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Game Engine - the authoritative processor of game events.
 * Only the host runs this to validate and apply events.
 */
class GameEngine {
    
    private val _gameState = MutableStateFlow<GameState?>(null)
    val gameState: StateFlow<GameState?> = _gameState.asStateFlow()
    
    private val eventLog = mutableListOf<EventEnvelope>()
    
    /**
     * Initialize a new game with the host as first player.
     */
    fun createGame(hostMeta: PlayerMeta): GameState {
        val gameId = GameId(UUID.randomUUID().toString())
        val joinCode = generateJoinCode()
        
        val hostState = PlayerState(
            playerId = hostMeta.playerId,
            name = hostMeta.name,
            avatarId = hostMeta.avatarId,
            gender = hostMeta.gender
        )
        
        val newState = GameState(
            gameId = gameId,
            joinCode = joinCode,
            hostId = hostMeta.playerId,
            players = mapOf(hostMeta.playerId to hostState)
        )
        
        _gameState.value = newState
        return newState
    }
    
    /**
     * Load an existing game state (for recovery/handover).
     */
    fun loadState(state: GameState) {
        _gameState.value = state
    }
    
    /**
     * Process an event and return the result.
     * Returns ValidationResult with success/failure and updated state.
     */
    fun processEvent(event: GameEvent): ValidationResult {
        val currentState = _gameState.value 
            ?: return ValidationResult.Error("No hay partida activa")
        
        // Validate the event
        val validation = validateEvent(event, currentState)
        if (validation is ValidationResult.Error) {
            return validation
        }
        
        // Apply the event
        val newState = applyEvent(event, currentState)
        
        // Create envelope with sequence number
        val envelope = EventEnvelope(
            gameId = currentState.gameId,
            epoch = currentState.epoch,
            seq = currentState.seq + 1,
            event = event
        )
        
        // Update state with new sequence
        val finalState = newState.copy(seq = currentState.seq + 1)
        _gameState.value = finalState
        
        // Log the event
        eventLog.add(envelope)
        
        return ValidationResult.Success(finalState, envelope)
    }
    
    /**
     * Validate an event against current state.
     */
    private fun validateEvent(event: GameEvent, state: GameState): ValidationResult {
        // Check ownership for player modification events
        if (event.targetPlayerId != null) {
            if (event.actorId != event.targetPlayerId) {
                return ValidationResult.Error("No puedes modificar a otro jugador")
            }
            if (!state.players.containsKey(event.targetPlayerId)) {
                return ValidationResult.Error("Jugador no encontrado")
            }
        }
        
        return when (event) {
            is PlayerJoin -> validatePlayerJoin(event, state)
            is GameStart -> validateGameStart(event, state)
            is IncLevel -> validateIncLevel(event, state)
            is AddRace -> validateAddRace(event, state)
            is AddClass -> validateAddClass(event, state)
            is CatalogAddRace -> validateCatalogAddRace(event, state)
            is CatalogAddClass -> validateCatalogAddClass(event, state)
            is SetHalfBreed -> validateSetHalfBreed(event, state)
            is SetSuperMunchkin -> validateSetSuperMunchkin(event, state)
            else -> ValidationResult.Success(state, null)
        }
    }
    
    private fun validatePlayerJoin(event: PlayerJoin, state: GameState): ValidationResult {
        if (state.isFull) {
            return ValidationResult.Error("Partida llena (máximo 6 jugadores)")
        }
        if (state.phase != GamePhase.LOBBY) {
            return ValidationResult.Error("La partida ya ha comenzado")
        }
        if (state.players.containsKey(event.playerMeta.playerId)) {
            return ValidationResult.Error("Ya estás en la partida")
        }
        return ValidationResult.Success(state, null)
    }
    
    private fun validateGameStart(event: GameStart, state: GameState): ValidationResult {
        if (event.actorId != state.hostId) {
            return ValidationResult.Error("Solo el anfitrión puede iniciar la partida")
        }
        if (!state.canStart) {
            return ValidationResult.Error("Se necesitan al menos 2 jugadores")
        }
        return ValidationResult.Success(state, null)
    }
    
    private fun validateIncLevel(event: IncLevel, state: GameState): ValidationResult {
        val player = state.players[event.targetPlayerId] ?: return ValidationResult.Error("Jugador no encontrado")
        val newLevel = player.level + event.amount
        
        // Check level 10 restriction
        if (newLevel >= 10 && state.settings.levelTenOnlyCombat && event.reason == null) {
            if (!state.settings.allowLevelTenOverride) {
                // For now, allow it but in full version could restrict
            }
        }
        
        return ValidationResult.Success(state, null)
    }
    
    private fun validateAddRace(event: AddRace, state: GameState): ValidationResult {
        val player = state.players[event.targetPlayerId] ?: return ValidationResult.Error("Jugador no encontrado")
        
        if (!player.canAddRace) {
            return ValidationResult.Error(
                if (player.hasHalfBreed) "Máximo 2 razas" 
                else "Necesitas Mestizo para tener 2 razas"
            )
        }
        
        if (player.raceIds.contains(event.entryId)) {
            return ValidationResult.Error("Ya tienes esa raza")
        }
        
        if (!state.races.containsKey(event.entryId)) {
            return ValidationResult.Error("Raza no encontrada en el catálogo")
        }
        
        return ValidationResult.Success(state, null)
    }
    
    private fun validateAddClass(event: AddClass, state: GameState): ValidationResult {
        val player = state.players[event.targetPlayerId] ?: return ValidationResult.Error("Jugador no encontrado")
        
        if (!player.canAddClass) {
            return ValidationResult.Error(
                if (player.hasSuperMunchkin) "Máximo 2 clases"
                else "Necesitas Super Munchkin para tener 2 clases"
            )
        }
        
        if (player.classIds.contains(event.entryId)) {
            return ValidationResult.Error("Ya tienes esa clase")
        }
        
        if (!state.classes.containsKey(event.entryId)) {
            return ValidationResult.Error("Clase no encontrada en el catálogo")
        }
        
        return ValidationResult.Success(state, null)
    }
    
    private fun validateSetHalfBreed(event: SetHalfBreed, state: GameState): ValidationResult {
        val player = state.players[event.targetPlayerId] ?: return ValidationResult.Error("Jugador no encontrado")
        
        // If disabling and player has 2 races, need to remove one
        if (!event.enabled && player.raceIds.size > 1) {
            return ValidationResult.Error("Quita una raza antes de desactivar Mestizo")
        }
        
        return ValidationResult.Success(state, null)
    }
    
    private fun validateSetSuperMunchkin(event: SetSuperMunchkin, state: GameState): ValidationResult {
        val player = state.players[event.targetPlayerId] ?: return ValidationResult.Error("Jugador no encontrado")
        
        if (!event.enabled && player.classIds.size > 1) {
            return ValidationResult.Error("Quita una clase antes de desactivar Super Munchkin")
        }
        
        return ValidationResult.Success(state, null)
    }
    
    private fun validateCatalogAddRace(event: CatalogAddRace, state: GameState): ValidationResult {
        if (!CatalogEntry.isValidName(event.displayName)) {
            return ValidationResult.Error("Nombre inválido (2-24 caracteres)")
        }
        
        val normalized = CatalogEntry.normalize(event.displayName)
        val exists = state.races.values.any { it.normalizedName == normalized }
        if (exists) {
            return ValidationResult.Error("Ya existe una raza con ese nombre")
        }
        
        return ValidationResult.Success(state, null)
    }
    
    private fun validateCatalogAddClass(event: CatalogAddClass, state: GameState): ValidationResult {
        if (!CatalogEntry.isValidName(event.displayName)) {
            return ValidationResult.Error("Nombre inválido (2-24 caracteres)")
        }
        
        val normalized = CatalogEntry.normalize(event.displayName)
        val exists = state.classes.values.any { it.normalizedName == normalized }
        if (exists) {
            return ValidationResult.Error("Ya existe una clase con ese nombre")
        }
        
        return ValidationResult.Success(state, null)
    }
    
    /**
     * Apply an event to produce new state.
     */
    private fun applyEvent(event: GameEvent, state: GameState): GameState {
        return when (event) {
            is PlayerJoin -> applyPlayerJoin(event, state)
            is PlayerLeave -> applyPlayerLeave(event, state)
            is GameStart -> state.copy(phase = GamePhase.IN_GAME)
            is GameEnd -> state.copy(phase = GamePhase.FINISHED)
            
            is SetName -> updatePlayer(state, event.targetPlayerId) { it.copy(name = event.name) }
            is SetAvatar -> updatePlayer(state, event.targetPlayerId) { it.copy(avatarId = event.avatarId) }
            is SetGender -> updatePlayer(state, event.targetPlayerId) { it.copy(gender = event.gender) }
            
            is IncLevel -> updatePlayer(state, event.targetPlayerId) { 
                it.copy(level = (it.level + event.amount).coerceIn(state.settings.minLevel, state.settings.maxLevel))
            }
            is DecLevel -> updatePlayer(state, event.targetPlayerId) { 
                it.copy(level = (it.level - event.amount).coerceIn(state.settings.minLevel, state.settings.maxLevel))
            }
            
            is IncGear -> updatePlayer(state, event.targetPlayerId) { it.copy(gearBonus = it.gearBonus + event.amount) }
            is DecGear -> updatePlayer(state, event.targetPlayerId) { it.copy(gearBonus = it.gearBonus - event.amount) }
            
            is SetHalfBreed -> updatePlayer(state, event.targetPlayerId) { it.copy(hasHalfBreed = event.enabled) }
            is SetSuperMunchkin -> updatePlayer(state, event.targetPlayerId) { it.copy(hasSuperMunchkin = event.enabled) }
            
            is AddRace -> updatePlayer(state, event.targetPlayerId) { 
                it.copy(raceIds = it.raceIds + event.entryId) 
            }
            is RemoveRace -> updatePlayer(state, event.targetPlayerId) { 
                it.copy(raceIds = it.raceIds - event.entryId) 
            }
            is ClearRaces -> updatePlayer(state, event.targetPlayerId) { 
                it.copy(raceIds = emptyList()) 
            }
            
            is AddClass -> updatePlayer(state, event.targetPlayerId) { 
                it.copy(classIds = it.classIds + event.entryId) 
            }
            is RemoveClass -> updatePlayer(state, event.targetPlayerId) { 
                it.copy(classIds = it.classIds - event.entryId) 
            }
            is ClearClasses -> updatePlayer(state, event.targetPlayerId) { 
                it.copy(classIds = emptyList()) 
            }
            
            is CatalogAddRace -> applyCatalogAddRace(event, state)
            is CatalogAddClass -> applyCatalogAddClass(event, state)
            is CatalogArchiveRace -> applyCatalogArchive(event.entryId, state, isRace = true)
            is CatalogArchiveClass -> applyCatalogArchive(event.entryId, state, isRace = false)
            
            is CombatStart -> applyCombatStart(event, state)
            is CombatAddHelper -> applyCombatAddHelper(event, state)
            is CombatRemoveHelper -> applyCombatRemoveHelper(state)
            is CombatAddMonster -> applyCombatAddMonster(event, state)
            is CombatRemoveMonster -> applyCombatRemoveMonster(event, state)
            is CombatUpdateMonster -> applyCombatUpdateMonster(event, state)
            is CombatAddBonus -> applyCombatAddBonus(event, state)
            is CombatRemoveBonus -> applyCombatRemoveBonus(event, state)
            is CombatEnd -> applyCombatEnd(event, state)
            
            else -> state
        }
    }
    
    private fun applyPlayerJoin(event: PlayerJoin, state: GameState): GameState {
        val newPlayer = PlayerState(
            playerId = event.playerMeta.playerId,
            name = event.playerMeta.name,
            avatarId = event.playerMeta.avatarId,
            gender = event.playerMeta.gender,
            lastKnownIp = event.lastKnownIp
        )
        return state.copy(
            players = state.players + (event.playerMeta.playerId to newPlayer)
        )
    }
    
    private fun applyPlayerLeave(event: PlayerLeave, state: GameState): GameState {
        return state.copy(
            players = state.players - event.actorId
        )
    }
    
    private fun updatePlayer(
        state: GameState, 
        playerId: PlayerId, 
        update: (PlayerState) -> PlayerState
    ): GameState {
        val player = state.players[playerId] ?: return state
        return state.copy(
            players = state.players + (playerId to update(player))
        )
    }
    
    private fun applyCatalogAddRace(event: CatalogAddRace, state: GameState): GameState {
        val entryId = EntryId(UUID.randomUUID().toString())
        val entry = CatalogEntry(
            entryId = entryId,
            displayName = event.displayName.trim(),
            normalizedName = CatalogEntry.normalize(event.displayName),
            aliases = event.aliases.map { CatalogEntry.normalize(it) },
            createdByPlayerId = event.actorId,
            createdAt = event.timestamp
        )
        return state.copy(races = state.races + (entryId to entry))
    }
    
    private fun applyCatalogAddClass(event: CatalogAddClass, state: GameState): GameState {
        val entryId = EntryId(UUID.randomUUID().toString())
        val entry = CatalogEntry(
            entryId = entryId,
            displayName = event.displayName.trim(),
            normalizedName = CatalogEntry.normalize(event.displayName),
            aliases = event.aliases.map { CatalogEntry.normalize(it) },
            createdByPlayerId = event.actorId,
            createdAt = event.timestamp
        )
        return state.copy(classes = state.classes + (entryId to entry))
    }
    
    private fun applyCatalogArchive(entryId: EntryId, state: GameState, isRace: Boolean): GameState {
        return if (isRace) {
            val entry = state.races[entryId]?.copy(isArchived = true) ?: return state
            state.copy(races = state.races + (entryId to entry))
        } else {
            val entry = state.classes[entryId]?.copy(isArchived = true) ?: return state
            state.copy(classes = state.classes + (entryId to entry))
        }
    }
    
    private fun applyCombatStart(event: CombatStart, state: GameState): GameState {
        val combat = CombatState(mainPlayerId = event.mainPlayerId)
        return state.copy(combat = combat)
    }
    
    private fun applyCombatAddHelper(event: CombatAddHelper, state: GameState): GameState {
        val combat = state.combat?.copy(helperPlayerId = event.helperId) ?: return state
        return state.copy(combat = combat)
    }
    
    private fun applyCombatRemoveHelper(state: GameState): GameState {
        val combat = state.combat?.copy(helperPlayerId = null) ?: return state
        return state.copy(combat = combat)
    }
    
    private fun applyCombatAddMonster(event: CombatAddMonster, state: GameState): GameState {
        val combat = state.combat?.copy(
            monsters = state.combat.monsters + event.monster
        ) ?: return state
        return state.copy(combat = combat)
    }
    
    private fun applyCombatRemoveMonster(event: CombatRemoveMonster, state: GameState): GameState {
        val combat = state.combat?.copy(
            monsters = state.combat.monsters.filter { it.id != event.monsterId }
        ) ?: return state
        return state.copy(combat = combat)
    }
    
    private fun applyCombatUpdateMonster(event: CombatUpdateMonster, state: GameState): GameState {
        val combat = state.combat?.copy(
            monsters = state.combat.monsters.map { 
                if (it.id == event.monster.id) event.monster else it 
            }
        ) ?: return state
        return state.copy(combat = combat)
    }
    
    private fun applyCombatAddBonus(event: CombatAddBonus, state: GameState): GameState {
        val combat = state.combat?.copy(
            tempBonuses = state.combat.tempBonuses + event.bonus
        ) ?: return state
        return state.copy(combat = combat)
    }
    
    private fun applyCombatRemoveBonus(event: CombatRemoveBonus, state: GameState): GameState {
        val combat = state.combat?.copy(
            tempBonuses = state.combat.tempBonuses.filter { it.id != event.bonusId }
        ) ?: return state
        return state.copy(combat = combat)
    }
    
    private fun applyCombatEnd(event: CombatEnd, state: GameState): GameState {
        // Apply level gain if heroes won
        var newState = state.copy(combat = null)
        
        if (event.outcome == CombatOutcome.WIN && event.levelsGained > 0) {
            val combat = state.combat ?: return newState
            newState = updatePlayer(newState, combat.mainPlayerId) { player ->
                player.copy(level = (player.level + event.levelsGained).coerceAtMost(state.settings.maxLevel))
            }
        }
        
        return newState
    }
    
    /**
     * Get events since a specific sequence number.
     */
    fun getEventsSince(seq: Long): List<EventEnvelope> {
        return eventLog.filter { it.seq > seq }
    }
    
    /**
     * Get current event log for persistence.
     */
    fun getEventLog(): List<EventEnvelope> = eventLog.toList()
    
    companion object {
        private fun generateJoinCode(): String {
            val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // No I, O, 0, 1 to avoid confusion
            return (1..6).map { chars.random() }.joinToString("")
        }
    }
}

/**
 * Event envelope with metadata for synchronization.
 */
data class EventEnvelope(
    val gameId: GameId,
    val epoch: Int,
    val seq: Long,
    val event: GameEvent
)

/**
 * Result of event validation.
 */
sealed class ValidationResult {
    data class Success(
        val state: GameState,
        val envelope: EventEnvelope?
    ) : ValidationResult()
    
    data class Error(val message: String) : ValidationResult()
}
