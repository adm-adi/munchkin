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
            players = mapOf(hostMeta.playerId to hostState),
            playerOrder = listOf(hostMeta.playerId)
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
            is PlayerRoll -> ValidationResult.Success(state, null)
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
            is PlayerRoll -> applyPlayerRoll(event, state)
            is GameStart -> {
                // Determine first player: Highest roller, or first in order
                val startingPlayerId = state.players.values
                    .maxByOrNull { it.lastRoll ?: 0 }
                    ?.takeIf { (it.lastRoll ?: 0) > 0 }
                    ?.playerId
                    ?: if (state.playerOrder.isNotEmpty()) {
                        state.playerOrder.first()
                    } else {
                        state.players.keys.sortedBy { it.value }.first()
                    }
                
                state.copy(
                    phase = GamePhase.IN_GAME,
                    turnPlayerId = startingPlayerId,
                    // Optional: Reset combat on start?
                )
            }
            is SwapPlayers -> applySwapPlayers(event, state)
            is EndTurn -> applyEndTurn(event, state)
            is GameEnd -> state.copy(phase = GamePhase.FINISHED)
            
            is SetName -> updatePlayer(state, event.targetPlayerId) { it.copy(name = event.name) }
            is SetAvatar -> updatePlayer(state, event.targetPlayerId) { it.copy(avatarId = event.avatarId) }
            is SetGender -> updatePlayer(state, event.targetPlayerId) { it.copy(gender = event.gender) }
            
            is IncLevel -> updatePlayer(state, event.targetPlayerId) { 
                it.copy(level = (it.level + event.amount).coerceIn(state.settings.minLevel, state.settings.maxLevel))
            }.let { s -> checkWinCondition(s) }
            is DecLevel -> updatePlayer(state, event.targetPlayerId) { 
                it.copy(level = (it.level - event.amount).coerceIn(state.settings.minLevel, state.settings.maxLevel))
            }
            
            is IncGear -> updatePlayer(state, event.targetPlayerId) { it.copy(gearBonus = it.gearBonus + event.amount) }
            is DecGear -> updatePlayer(state, event.targetPlayerId) { it.copy(gearBonus = it.gearBonus - event.amount) }
            
            is SetHalfBreed -> updatePlayer(state, event.targetPlayerId) { it.copy(hasHalfBreed = event.enabled) }
            is SetHalfBreed -> updatePlayer(state, event.targetPlayerId) { it.copy(hasHalfBreed = event.enabled) }
            is SetSuperMunchkin -> updatePlayer(state, event.targetPlayerId) { it.copy(hasSuperMunchkin = event.enabled) }
            
            is SetClass -> applySetClass(event, state)
            is SetRace -> applySetRace(event, state)
            
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
            is CombatEnd -> applyCombatEnd(event, state)
            is CombatModifyModifier -> applyCombatModifyModifier(event, state)
            is CombatSetModifier -> applyCombatSetModifier(event, state)
            
            else -> state
        }
    }
    
    private fun applyCombatModifyModifier(event: CombatModifyModifier, state: GameState): GameState {
        val combat = state.combat ?: return state
        val updatedCombat = when (event.target) {
            BonusTarget.HEROES -> combat.copy(heroModifier = combat.heroModifier + event.delta)
            BonusTarget.MONSTER -> combat.copy(monsterModifier = combat.monsterModifier + event.delta)
        }
        return state.copy(combat = updatedCombat)
    }
    
    private fun applyCombatSetModifier(event: CombatSetModifier, state: GameState): GameState {
        val combat = state.combat ?: return state
        val updatedCombat = when (event.target) {
            BonusTarget.HEROES -> combat.copy(heroModifier = event.value)
            BonusTarget.MONSTER -> combat.copy(monsterModifier = event.value)
        }
        return state.copy(combat = updatedCombat)
    }
    
    private fun applySwapPlayers(event: SwapPlayers, state: GameState): GameState {
        val p1 = event.targetPlayerId ?: return state
        val p2 = event.otherPlayerId
        
        // Use current order or implicit order
        val currentOrder = if (state.playerOrder.isNotEmpty()) {
            state.playerOrder.toMutableList()
        } else {
            state.players.keys.sortedBy { it.value }.toMutableList()
        }
        
        val idx1 = currentOrder.indexOf(p1)
        val idx2 = currentOrder.indexOf(p2)
        
        if (idx1 != -1 && idx2 != -1) {
            val temp = currentOrder[idx1]
            currentOrder[idx1] = currentOrder[idx2]
            currentOrder[idx2] = temp
        }
        
        return state.copy(playerOrder = currentOrder)
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
    


    private fun applySetClass(event: SetClass, state: GameState): GameState {
        return updatePlayer(state, event.targetPlayerId) {
            it.copy(characterClass = event.newClass)
        }
    }

    private fun applySetRace(event: SetRace, state: GameState): GameState {
        return updatePlayer(state, event.targetPlayerId) {
            it.copy(characterRace = event.newRace)
        }
    }
    
    private fun applyCombatAddMonster(event: CombatAddMonster, state: GameState): GameState {
        val combat = state.combat ?: return state
        val updatedCombat = combat.copy(
            monsters = combat.monsters + event.monster
        )
        return state.copy(combat = updatedCombat)
    }

    private fun applyEndTurn(event: EndTurn, state: GameState): GameState {
        // Calculate next player
        val currentPlayerId = state.turnPlayerId ?: return state
        
        // Use implicit or explicit order
        val playerList = if (state.playerOrder.isNotEmpty()) {
            state.playerOrder
        } else {
            state.players.keys.sortedBy { it.value }
        }
        
        val currentIndex = playerList.indexOf(currentPlayerId)
        if (currentIndex == -1) return state // Should not happen
        
        // Find next connected player
        var nextPlayerId = currentPlayerId
        for (i in 1..playerList.size) {
             val nextIndex = (currentIndex + i) % playerList.size
             val candidateId = playerList[nextIndex]
             val candidate = state.players[candidateId]
             
             // Check if connected (default to true if unknown, but should rely on state)
             // GameEngine logic: assumes state.players has up-to-date isConnected
             if (candidate?.isConnected == true) {
                 nextPlayerId = candidateId
                 break
             }
        }
        
        return state.copy(
            turnPlayerId = nextPlayerId,
            combat = null // Clear combat state on turn end
        )
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
        
        if (event.outcome == CombatOutcome.WIN) {
            val combat = state.combat ?: return newState
            
            // Update Main Player
            newState = updatePlayer(newState, combat.mainPlayerId) { player ->
                player.copy(
                    level = (player.level + event.levelsGained).coerceAtMost(state.settings.maxLevel),
                    treasures = player.treasures + event.treasuresGained
                )
            }
            
            // Update Helper Player (if applicable)
            if (combat.helperPlayerId != null && event.helperLevelsGained > 0) {
                 newState = updatePlayer(newState, combat.helperPlayerId) { player ->
                    player.copy(
                        level = (player.level + event.helperLevelsGained).coerceAtMost(state.settings.maxLevel)
                    )
                }
            }
            
            newState = checkWinCondition(newState)
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
    
    private fun checkWinCondition(state: GameState): GameState {
        val winner = state.players.values.find { it.level >= state.settings.maxLevel }
        return state 
        // We do NOT automatically end the game anymore. 
        // The host must confirm the win in UI.
        /* if (winner != null) {
            state.copy(
                phase = GamePhase.FINISHED,
                winnerId = winner.playerId
            )
        } else {
            state
        } */
    }
    private fun applyPlayerRoll(event: PlayerRoll, state: GameState): GameState {
        val player = state.players[event.actorId] ?: return state
        val updatedPlayer = player.copy(lastRoll = event.result)
        val stateWithPlayer = state.copy(players = state.players + (player.playerId to updatedPlayer))
        
        // If in combat or purpose implies combat, update combat state
        return if (stateWithPlayer.combat != null && 
            (event.purpose == DiceRollPurpose.COMBAT || event.purpose == DiceRollPurpose.RUN_AWAY)) {
             
             // Create DiceRollInfo from result
             val rollInfo = DiceRollInfo(
                 playerId = event.actorId,
                 playerName = stateWithPlayer.players[event.actorId]?.name ?: "Unknown",
                 result = event.result,
                 purpose = event.purpose
             )
             
             val updatedCombat = stateWithPlayer.combat.copy(lastDiceRoll = rollInfo)
             stateWithPlayer.copy(combat = updatedCombat)
        } else if (stateWithPlayer.phase == GamePhase.LOBBY && stateWithPlayer.allPlayersRolled) {
            // In lobby: check for ties among highest rollers
            val maxRoll = stateWithPlayer.players.values.maxOfOrNull { it.lastRoll ?: 0 } ?: 0
            val tiedPlayers = stateWithPlayer.players.values.filter { it.lastRoll == maxRoll }
            if (tiedPlayers.size > 1) {
                // Reset tied players' rolls so they must re-roll
                val resetPlayers = stateWithPlayer.players.mapValues { (_, p) ->
                    if (p.lastRoll == maxRoll) p.copy(lastRoll = null) else p
                }
                stateWithPlayer.copy(players = resetPlayers)
            } else {
                stateWithPlayer
            }
        } else {
            stateWithPlayer
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
