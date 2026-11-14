package com.munchkin.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Client-side reducer and synchronized state wrapper for authoritative server state.
 *
 * Pure state transitions live in [GameEngine.reduce]; instance methods keep callers
 * thread-safe so concurrent updates (e.g. PLAYER_STATUS vs EVENT_BROADCAST) are never lost.
 */
class GameEngine {

    private val stateLock = Any()
    private val _gameState = MutableStateFlow<GameState?>(null)
    val gameState: StateFlow<GameState?> = _gameState.asStateFlow()

    /**
     * Load an existing game state (from a WELCOME or STATE_SNAPSHOT).
     */
    fun loadState(state: GameState) {
        synchronized(stateLock) {
            _gameState.value = state
        }
    }

    /**
     * Thread-safe snapshot of the current game state.
     */
    fun getCurrentState(): GameState? = synchronized(stateLock) {
        _gameState.value
    }

    /**
     * Applies an event to the current state and returns the updated state.
     */
    fun processEvent(event: GameEvent): Result<GameState> = synchronized(stateLock) {
        val currentState = _gameState.value
            ?: return Result.failure(IllegalStateException("No game state loaded"))
        val newState = reduce(currentState, event).copy(seq = currentState.seq + 1)
        _gameState.value = newState
        Result.success(newState)
    }

    /**
     * Applies an event the server has already validated, accepted, and broadcast.
     */
    fun applyRemoteEvent(event: GameEvent) {
        synchronized(stateLock) {
            val currentState = _gameState.value ?: return
            val newState = reduce(currentState, event)
            _gameState.value = newState.copy(seq = currentState.seq + 1)
        }
    }

    /**
     * Updates a player's connection flag inside the synchronized state so
     * subsequent remote events do not overwrite PLAYER_STATUS changes.
     */
    fun updatePlayerStatus(playerId: PlayerId, isConnected: Boolean) {
        synchronized(stateLock) {
            val currentState = _gameState.value ?: return
            val player = currentState.players[playerId] ?: return
            _gameState.value = currentState.copy(
                players = currentState.players + (playerId to player.copy(isConnected = isConnected))
            )
        }
    }

    companion object {
        const val GEAR_MIN = 0
        const val GEAR_MAX = 999
        const val TEMP_COMBAT_BONUS_MIN = -100
        const val TEMP_COMBAT_BONUS_MAX = 100
        const val STEP_LIMIT = 100
        const val MAX_AVATAR_ID = 100
        const val MAX_NAME_LENGTH = 20
        const val MODIFIER_LIMIT = 9999

        private fun stepAmount(amount: Int): Int =
            if (amount <= 0) 1 else amount.coerceAtMost(STEP_LIMIT)

        /**
         * Pure stateless reducer transforming [prevState] by applying [event].
         * Replicates server validation clamps and edge-case rules for full client/server parity.
         */
        fun reduce(prevState: GameState, event: GameEvent): GameState {
            return when (event) {
                is PlayerRoll -> applyPlayerRoll(event, prevState)
                is GameStart -> applyGameStart(prevState)
                is EndTurn -> applyEndTurn(prevState)
                is GameEnd -> prevState.copy(
                    phase = GamePhase.FINISHED,
                    winnerId = event.winnerId?.takeIf { it.value.isNotEmpty() },
                    combat = null
                )

                is SetName -> {
                    val trimmed = event.name.trim().take(MAX_NAME_LENGTH)
                    if (trimmed.isEmpty()) {
                        prevState
                    } else {
                        updatePlayer(prevState, event.targetPlayerId) { it.copy(name = trimmed) }
                    }
                }
                is SetAvatar -> updatePlayer(prevState, event.targetPlayerId) {
                    it.copy(avatarId = event.avatarId.coerceIn(0, MAX_AVATAR_ID))
                }
                is SetGender -> updatePlayer(prevState, event.targetPlayerId) {
                    it.copy(gender = event.gender)
                }

                is IncLevel -> applyIncLevel(event, prevState)
                is DecLevel -> applyDecLevel(event, prevState)
                is SetLevel -> applySetLevel(event, prevState)

                is IncGear -> updatePlayer(prevState, event.targetPlayerId) {
                    it.copy(gearBonus = (it.gearBonus + stepAmount(event.amount)).coerceIn(GEAR_MIN, GEAR_MAX))
                }
                is DecGear -> updatePlayer(prevState, event.targetPlayerId) {
                    it.copy(gearBonus = (it.gearBonus - stepAmount(event.amount)).coerceIn(GEAR_MIN, GEAR_MAX))
                }
                is SetGear -> updatePlayer(prevState, event.targetPlayerId) {
                    it.copy(gearBonus = event.gear.coerceIn(GEAR_MIN, GEAR_MAX))
                }

                is SetHalfBreed -> updatePlayer(prevState, event.targetPlayerId) {
                    it.copy(
                        hasHalfBreed = event.enabled,
                        secondaryRace = if (!event.enabled || it.secondaryRace == it.characterRace) {
                            CharacterRace.HUMAN
                        } else {
                            it.secondaryRace
                        }
                    )
                }
                is SetSuperMunchkin -> updatePlayer(prevState, event.targetPlayerId) {
                    it.copy(
                        hasSuperMunchkin = event.enabled,
                        secondaryClass = if (!event.enabled || it.secondaryClass == it.characterClass) {
                            CharacterClass.NONE
                        } else {
                            it.secondaryClass
                        }
                    )
                }

                is SetClass -> applySetClass(event, prevState)
                is SetRace -> applySetRace(event, prevState)

                is CombatStart -> prevState.copy(combat = CombatState(mainPlayerId = event.mainPlayerId))
                is CombatAddHelper -> applyCombatAddHelper(event, prevState)
                is CombatRemoveHelper -> {
                    val combat = prevState.combat?.copy(helperPlayerId = null) ?: return prevState
                    prevState.copy(combat = combat)
                }
                is CombatAddMonster -> {
                    val combat = prevState.combat ?: return prevState
                    prevState.copy(combat = combat.copy(monsters = combat.monsters + event.monster))
                }
                is CombatRemoveMonster -> {
                    val combat = prevState.combat?.copy(
                        monsters = prevState.combat.monsters.filter { it.id != event.monsterId }
                    ) ?: return prevState
                    prevState.copy(combat = combat)
                }
                is CombatUpdateMonster -> {
                    val combat = prevState.combat?.copy(
                        monsters = prevState.combat.monsters.map {
                            if (it.id == event.monster.id) event.monster else it
                        }
                    ) ?: return prevState
                    prevState.copy(combat = combat)
                }
                is CombatAddBonus -> {
                    val combat = prevState.combat?.copy(
                        tempBonuses = prevState.combat.tempBonuses + event.bonus
                    ) ?: return prevState
                    prevState.copy(combat = combat)
                }
                is CombatRemoveBonus -> {
                    val combat = prevState.combat?.copy(
                        tempBonuses = prevState.combat.tempBonuses.filter { it.id != event.bonusId }
                    ) ?: return prevState
                    prevState.copy(combat = combat)
                }
                is CombatSetModifier -> applyCombatSetModifier(event, prevState)
                is CombatEnd -> applyCombatEnd(event, prevState)
            }
        }

        private inline fun updatePlayer(
            state: GameState,
            playerId: PlayerId,
            update: (PlayerState) -> PlayerState
        ): GameState {
            val player = state.players[playerId] ?: return state
            return state.copy(players = state.players + (playerId to update(player)))
        }

        private fun orderedPlayerIds(state: GameState): List<PlayerId> {
            return if (state.playerOrder.isNotEmpty()) {
                val validOrder = state.playerOrder.filter { state.players.containsKey(it) }
                val orderedSet = validOrder.toHashSet()
                val extra = state.players.keys.filter { it !in orderedSet }.sortedBy { it.value }
                validOrder + extra
            } else {
                state.players.keys.sortedBy { it.value }
            }
        }

        private fun applyIncLevel(event: IncLevel, state: GameState): GameState {
            return updatePlayer(state, event.targetPlayerId) { player ->
                val minLevel = state.settings.minLevel
                val maxLevel = state.settings.maxLevel
                val manualCap = if (state.settings.requiresCombatToWin) {
                    maxOf(minLevel, maxLevel - 1)
                } else {
                    maxLevel
                }
                val current = player.level.coerceIn(minLevel, maxLevel)
                val newLevel = if (current < manualCap) {
                    minOf(manualCap, current + stepAmount(event.amount))
                } else {
                    current
                }
                player.copy(
                    level = newLevel,
                    reachedMaxLevelViaCombat = if (newLevel < maxLevel) false else player.reachedMaxLevelViaCombat
                )
            }
        }

        private fun applyDecLevel(event: DecLevel, state: GameState): GameState {
            return updatePlayer(state, event.targetPlayerId) { player ->
                val minLevel = state.settings.minLevel
                val maxLevel = state.settings.maxLevel
                val current = player.level.coerceIn(minLevel, maxLevel)
                val newLevel = (current - stepAmount(event.amount)).coerceIn(minLevel, maxLevel)
                player.copy(
                    level = newLevel,
                    reachedMaxLevelViaCombat = if (newLevel < maxLevel) false else player.reachedMaxLevelViaCombat
                )
            }
        }

        private fun applySetLevel(event: SetLevel, state: GameState): GameState {
            return updatePlayer(state, event.targetPlayerId) { player ->
                val minLevel = state.settings.minLevel
                val maxLevel = state.settings.maxLevel
                val manualCap = if (state.settings.requiresCombatToWin) {
                    maxOf(minLevel, maxLevel - 1)
                } else {
                    maxLevel
                }
                val prev = player.level.coerceIn(minLevel, maxLevel)
                val requested = event.level.coerceIn(minLevel, maxLevel)
                val newLevel = if (state.settings.requiresCombatToWin && requested >= maxLevel) {
                    if (prev < maxLevel) manualCap else prev
                } else {
                    requested
                }
                player.copy(
                    level = newLevel,
                    reachedMaxLevelViaCombat = if (newLevel < maxLevel || prev < maxLevel) false else player.reachedMaxLevelViaCombat
                )
            }
        }

        private fun applySetClass(event: SetClass, state: GameState): GameState {
            return updatePlayer(state, event.targetPlayerId) {
                if (event.isSecondary) {
                    if (!it.hasSuperMunchkin) {
                        it
                    } else {
                        it.copy(
                            secondaryClass = if (event.newClass == it.characterClass) {
                                CharacterClass.NONE
                            } else {
                                event.newClass
                            }
                        )
                    }
                } else {
                    val keepSecondary = it.hasSuperMunchkin && it.secondaryClass != event.newClass
                    it.copy(
                        characterClass = event.newClass,
                        secondaryClass = if (keepSecondary) it.secondaryClass else CharacterClass.NONE
                    )
                }
            }
        }

        private fun applySetRace(event: SetRace, state: GameState): GameState {
            return updatePlayer(state, event.targetPlayerId) {
                if (event.isSecondary) {
                    if (!it.hasHalfBreed) {
                        it
                    } else {
                        it.copy(
                            secondaryRace = if (event.newRace == it.characterRace) {
                                CharacterRace.HUMAN
                            } else {
                                event.newRace
                            }
                        )
                    }
                } else {
                    val keepSecondary = it.hasHalfBreed && it.secondaryRace != event.newRace
                    it.copy(
                        characterRace = event.newRace,
                        secondaryRace = if (keepSecondary) it.secondaryRace else CharacterRace.HUMAN
                    )
                }
            }
        }

        private fun applyGameStart(state: GameState): GameState {
            val order = orderedPlayerIds(state)
            var highestRoll = 0
            var startingPlayerId: PlayerId? = null
            for (pid in order) {
                val roll = state.players[pid]?.lastRoll ?: 0
                if (roll > highestRoll) {
                    highestRoll = roll
                    startingPlayerId = pid
                }
            }
            return state.copy(
                phase = GamePhase.IN_GAME,
                turnPlayerId = startingPlayerId ?: order.firstOrNull() ?: state.hostId
            )
        }

        private fun applyEndTurn(state: GameState): GameState {
            val currentPlayerId = state.turnPlayerId ?: return state
            val order = orderedPlayerIds(state)
            if (order.isEmpty()) return state

            val currentIndex = order.indexOf(currentPlayerId)
            val startIndex = if (currentIndex == -1) 0 else currentIndex

            var nextPlayerId = currentPlayerId
            for (i in 1..order.size) {
                val candidateId = order[(startIndex + i) % order.size]
                val candidate = state.players[candidateId]
                if (candidate?.isConnected == true) {
                    nextPlayerId = candidateId
                    break
                }
            }

            return state.copy(
                turnPlayerId = nextPlayerId,
                combat = null
            )
        }

        private fun applyCombatAddHelper(event: CombatAddHelper, state: GameState): GameState {
            val combat = state.combat ?: return state
            if (event.helperId == combat.mainPlayerId) return state
            if (!state.players.containsKey(event.helperId)) return state
            return state.copy(combat = combat.copy(helperPlayerId = event.helperId))
        }

        private fun applyCombatSetModifier(event: CombatSetModifier, state: GameState): GameState {
            val combat = state.combat ?: return state
            val clamped = event.value.coerceIn(-MODIFIER_LIMIT, MODIFIER_LIMIT)
            val updatedCombat = when (event.target) {
                BonusTarget.HEROES -> combat.copy(heroModifier = clamped)
                BonusTarget.MONSTER -> combat.copy(monsterModifier = clamped)
            }
            return state.copy(combat = updatedCombat)
        }

        private fun applyCombatEnd(event: CombatEnd, state: GameState): GameState {
            val combat = state.combat
            var newState = state.copy(combat = null)
            if (combat == null || event.cancelled || event.outcome != CombatOutcome.WIN) {
                return newState
            }

            val maxLevel = state.settings.maxLevel
            val levelsGained = event.levelsGained.coerceAtLeast(0)
            val treasuresGained = event.treasuresGained.coerceAtLeast(0)
            val helperLevelsGained = event.helperLevelsGained.coerceAtLeast(0)

            newState = updatePlayer(newState, combat.mainPlayerId) { player ->
                val prevLevel = player.level
                val newLevel = (prevLevel + levelsGained).coerceAtMost(maxLevel)
                player.copy(
                    level = newLevel,
                    treasures = player.treasures + treasuresGained,
                    reachedMaxLevelViaCombat = if (levelsGained > 0 && prevLevel < maxLevel && newLevel >= maxLevel) {
                        true
                    } else {
                        player.reachedMaxLevelViaCombat
                    }
                )
            }

            if (combat.helperPlayerId != null && helperLevelsGained > 0) {
                newState = updatePlayer(newState, combat.helperPlayerId) { helper ->
                    val prevLevel = helper.level
                    val newLevel = (prevLevel + helperLevelsGained).coerceAtMost(maxLevel)
                    helper.copy(
                        level = newLevel,
                        reachedMaxLevelViaCombat = if (prevLevel < maxLevel && newLevel >= maxLevel) {
                            true
                        } else {
                            helper.reachedMaxLevelViaCombat
                        }
                    )
                }
            }

            return newState
        }

        private fun applyPlayerRoll(event: PlayerRoll, state: GameState): GameState {
            val player = state.players[event.actorId] ?: return state
            val clampedRoll = event.result.coerceIn(1, 6)

            // Evaluate LOBBY ties on the pre-roll state before writing this player's roll.
            val basePlayers = if (state.phase == GamePhase.LOBBY &&
                state.players.size > 1 &&
                state.allPlayersRolled
            ) {
                val maxRoll = state.players.values.maxOfOrNull { it.lastRoll ?: 0 } ?: 0
                val tiedCount = state.players.values.count { it.lastRoll == maxRoll }
                if (tiedCount > 1 && player.lastRoll == maxRoll) {
                    state.players.mapValues { (pid, p) ->
                        when {
                            pid == event.actorId -> p
                            p.lastRoll == maxRoll -> p.copy(lastRoll = null)
                            else -> p.copy(lastRoll = 0)
                        }
                    }
                } else {
                    state.players
                }
            } else {
                state.players
            }

            val updatedPlayer = (basePlayers[event.actorId] ?: player).copy(lastRoll = clampedRoll)
            val stateWithPlayer = state.copy(players = basePlayers + (player.playerId to updatedPlayer))

            return if (stateWithPlayer.combat != null &&
                (event.purpose == DiceRollPurpose.COMBAT || event.purpose == DiceRollPurpose.RUN_AWAY)
            ) {
                val computedSuccess = if (event.purpose == DiceRollPurpose.RUN_AWAY) {
                    (clampedRoll + Abilities.runAwayBonus(updatedPlayer)) >= Abilities.RUN_AWAY_THRESHOLD
                } else {
                    event.success
                }
                val rollInfo = DiceRollInfo(
                    playerId = event.actorId,
                    playerName = updatedPlayer.name.ifEmpty { "Unknown" },
                    result = clampedRoll,
                    success = computedSuccess,
                    timestamp = event.timestamp,
                    purpose = event.purpose
                )
                stateWithPlayer.copy(combat = stateWithPlayer.combat.copy(lastDiceRoll = rollInfo))
            } else {
                stateWithPlayer
            }
        }
    }
}
