package com.munchkin.app.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class GameEngineReducerTest {

    private val p1Id = PlayerId("p1")
    private val p2Id = PlayerId("p2")
    private val p3Id = PlayerId("p3")

    private fun baseState(
        phase: GamePhase = GamePhase.IN_GAME,
        combat: CombatState? = null,
        playerOrder: List<PlayerId> = listOf(p1Id, p2Id, p3Id),
        settings: GameSettings = GameSettings()
    ): GameState {
        return GameState(
            gameId = GameId("g1"),
            joinCode = "ABCD",
            hostId = p1Id,
            phase = phase,
            combat = combat,
            turnPlayerId = p1Id,
            playerOrder = playerOrder,
            settings = settings,
            players = mapOf(
                p1Id to PlayerState(playerId = p1Id, name = "Ana", avatarId = 0, isConnected = true),
                p2Id to PlayerState(
                    playerId = p2Id,
                    name = "Beto",
                    avatarId = 1,
                    characterRace = CharacterRace.ELF,
                    isConnected = true
                ),
                p3Id to PlayerState(
                    playerId = p3Id,
                    name = "Cati",
                    avatarId = 2,
                    characterRace = CharacterRace.HALFLING,
                    isConnected = false
                )
            )
        )
    }

    @Test
    fun `reduce is pure and enforces server clamps for gear avatar name and tempCombatBonus`() {
        val initial = baseState()

        // Official Munchkin rules allow negative gear/combat strength down to GEAR_MIN (-999).
        val afterDecGear = GameEngine.reduce(initial, DecGear("e1", p1Id, 1L, p1Id, amount = 10))
        assertEquals(-10, afterDecGear.players[p1Id]!!.gearBonus)
        assertEquals(0, initial.players[p1Id]!!.gearBonus)

        val afterFloorClamp = GameEngine.reduce(afterDecGear, SetGear("e1b", p1Id, 1L, p1Id, gear = -5000))
        assertEquals(GameEngine.GEAR_MIN, afterFloorClamp.players[p1Id]!!.gearBonus)

        val afterResetZero = GameEngine.reduce(afterFloorClamp, SetGear("e1c", p1Id, 1L, p1Id, gear = 0))
        val afterHugeInc = GameEngine.reduce(afterResetZero, IncGear("e2", p1Id, 2L, p1Id, amount = 500))
        // Step is capped at STEP_LIMIT = 100
        assertEquals(100, afterHugeInc.players[p1Id]!!.gearBonus)

        val afterSetGear = GameEngine.reduce(afterHugeInc, SetGear("e3", p1Id, 3L, p1Id, gear = 5000))
        assertEquals(GameEngine.GEAR_MAX, afterSetGear.players[p1Id]!!.gearBonus)

        val afterAvatar = GameEngine.reduce(afterSetGear, SetAvatar("e4", p1Id, 4L, p1Id, avatarId = 250))
        assertEquals(GameEngine.MAX_AVATAR_ID, afterAvatar.players[p1Id]!!.avatarId)

        val afterLongName = GameEngine.reduce(
            afterAvatar,
            SetName("e5", p1Id, 5L, p1Id, name = "  VeryLongPlayerNameExceedingTwentyChars  ")
        )
        assertEquals(20, afterLongName.players[p1Id]!!.name.length)

        val clampedTempPower = PlayerState(
            playerId = p1Id,
            name = "Ana",
            level = 5,
            gearBonus = 2,
            tempCombatBonus = 500
        ).combatPower
        assertEquals(5 + 2 + 100, clampedTempPower)
    }

    @Test
    fun `PLAYER_ROLL clamps result 1 to 6 and recomputes RUN_AWAY success`() {
        val state = baseState(
            combat = CombatState(mainPlayerId = p2Id, helperPlayerId = p3Id)
        )

        // p2 is ELF (+1): 4 + 1 >= 5 -> true even if client passed success = false
        val elfRoll = GameEngine.reduce(
            state,
            PlayerRoll("e1", p2Id, 10L, null, result = 4, purpose = DiceRollPurpose.RUN_AWAY, success = false)
        )
        val elfLastRoll = elfRoll.combat!!.lastDiceRoll!!
        assertEquals(4, elfLastRoll.result)
        assertTrue(elfLastRoll.success)

        // p3 is HALFLING (-1): 5 - 1 = 4 < 5 -> false even if client passed success = true
        val halflingRoll = GameEngine.reduce(
            state,
            PlayerRoll("e2", p3Id, 11L, null, result = 5, purpose = DiceRollPurpose.RUN_AWAY, success = true)
        )
        assertFalse(halflingRoll.combat!!.lastDiceRoll!!.success)

        // Manual card modifier (+2): HALFLING (-1) rolling 4 - 1 + 2 = 5 -> true
        val halflingBuffed = GameEngine.reduce(
            state,
            PlayerRoll("e2b", p3Id, 11L, null, result = 4, purpose = DiceRollPurpose.RUN_AWAY, success = true, modifier = 2)
        )
        assertEquals(2, halflingBuffed.combat!!.lastDiceRoll!!.modifier)
        assertTrue(halflingBuffed.combat!!.lastDiceRoll!!.success)

        // Manual curse modifier (-2): ELF (+1) rolling 5 + 1 - 2 = 4 -> false
        val elfCursed = GameEngine.reduce(
            state,
            PlayerRoll("e2c", p2Id, 11L, null, result = 5, purpose = DiceRollPurpose.RUN_AWAY, success = false, modifier = -2)
        )
        assertEquals(-2, elfCursed.combat!!.lastDiceRoll!!.modifier)
        assertFalse(elfCursed.combat!!.lastDiceRoll!!.success)

        // Out-of-range roll clamped to 6
        val highRoll = GameEngine.reduce(
            state,
            PlayerRoll("e3", p2Id, 12L, null, result = 99, purpose = DiceRollPurpose.RANDOM, success = false)
        )
        assertEquals(6, highRoll.players[p2Id]!!.lastRoll)
    }

    @Test
    fun `PLAYER_ROLL evaluates LOBBY ties before updating so non-tied get 0 and other tied get null`() {
        var s = baseState(phase = GamePhase.LOBBY)
        s = GameEngine.reduce(s, PlayerRoll("e1", p1Id, 1L, null, result = 5))
        s = GameEngine.reduce(s, PlayerRoll("e2", p2Id, 2L, null, result = 5))
        s = GameEngine.reduce(s, PlayerRoll("e3", p3Id, 3L, null, result = 2))

        assertEquals(setOf(p1Id, p2Id), s.tiedPlayerIds)
        assertFalse(s.canStart)

        val afterP1 = GameEngine.reduce(s, PlayerRoll("e4", p1Id, 4L, null, result = 3))
        // Original state remains untouched
        assertEquals(5, s.players[p2Id]!!.lastRoll)
        assertEquals(2, s.players[p3Id]!!.lastRoll)

        assertEquals(3, afterP1.players[p1Id]!!.lastRoll)
        assertNull(afterP1.players[p2Id]!!.lastRoll)
        assertEquals(0, afterP1.players[p3Id]!!.lastRoll)

        val afterP2 = GameEngine.reduce(afterP1, PlayerRoll("e5", p2Id, 5L, null, result = 6))
        assertEquals(6, afterP2.players[p2Id]!!.lastRoll)
        assertTrue(afterP2.tiedPlayerIds.isEmpty())
        assertTrue(afterP2.canStart)
    }

    @Test
    fun `SET_LEVEL resets reachedMaxLevelViaCombat to false when below maxLevel`() {
        val atMax = baseState(
            settings = GameSettings(levelTenOnlyCombat = false)
        ).let { s ->
            s.copy(
                players = s.players + (p1Id to s.players[p1Id]!!.copy(level = 10, reachedMaxLevelViaCombat = true))
            )
        }

        val dropped = GameEngine.reduce(atMax, SetLevel("e1", p1Id, 1L, p1Id, level = 8))
        assertEquals(8, dropped.players[p1Id]!!.level)
        assertEquals(false, dropped.players[p1Id]!!.reachedMaxLevelViaCombat)

        // Raising via SetLevel from < maxLevel to maxLevel must still record reachedMaxLevelViaCombat = false
        val backToMax = GameEngine.reduce(dropped, SetLevel("e2", p1Id, 2L, p1Id, level = 10))
        assertEquals(10, backToMax.players[p1Id]!!.level)
        assertEquals(false, backToMax.players[p1Id]!!.reachedMaxLevelViaCombat)
    }

    @Test
    fun `GAME_START sets IN_GAME and picks highest roller as starting player`() {
        var s = baseState(phase = GamePhase.LOBBY)
        s = GameEngine.reduce(s, PlayerRoll("e1", p1Id, 1L, null, result = 2))
        s = GameEngine.reduce(s, PlayerRoll("e2", p2Id, 2L, null, result = 6))
        s = GameEngine.reduce(s, PlayerRoll("e3", p3Id, 3L, null, result = 4))

        val started = GameEngine.reduce(s, GameStart("e4", p1Id, 4L))
        assertEquals(GamePhase.IN_GAME, started.phase)
        assertEquals(p2Id, started.turnPlayerId)
    }

    @Test
    fun `END_TURN filters stale playerOrder entries and skips disconnected players`() {
        val state = baseState(
            playerOrder = listOf(p1Id, PlayerId("ghost"), p3Id, p2Id)
        )
        val afterEndTurn = GameEngine.reduce(state, EndTurn("e1", p1Id, 10L))
        assertEquals(p2Id, afterEndTurn.turnPlayerId)
        assertNull(afterEndTurn.combat)
    }

    @Test
    fun `COMBAT_END WIN awards levels treasures and elf helper levels while LOSE ESCAPE and cancelled award 0`() {
        var s = baseState()
        s = GameEngine.reduce(s, IncLevel("e1", p1Id, 1L, p1Id, amount = 8)) // p1 at 9
        s = GameEngine.reduce(s, IncLevel("e2", p2Id, 2L, p2Id, amount = 8)) // p2 (Elf) at 9
        s = GameEngine.reduce(s, CombatStart("e3", p1Id, 3L, mainPlayerId = p1Id))
        s = GameEngine.reduce(s, CombatAddHelper("e4", p1Id, 4L, helperId = p2Id))

        // Cancelled WIN must not apply rewards
        val cancelled = GameEngine.reduce(
            s,
            CombatEnd(
                eventId = "e5",
                actorId = p1Id,
                timestamp = 5L,
                outcome = CombatOutcome.WIN,
                levelsGained = 1,
                treasuresGained = 2,
                helperLevelsGained = 1,
                cancelled = true
            )
        )
        assertNull(cancelled.combat)
        assertEquals(9, cancelled.players[p1Id]!!.level)
        assertEquals(0, cancelled.players[p1Id]!!.treasures)
        assertEquals(9, cancelled.players[p2Id]!!.level)

        // LOSE must not apply rewards even if non-zero rewards were sent
        val lost = GameEngine.reduce(
            s,
            CombatEnd(
                eventId = "e5b",
                actorId = p1Id,
                timestamp = 5L,
                outcome = CombatOutcome.LOSE,
                levelsGained = 2,
                treasuresGained = 3,
                helperLevelsGained = 1
            )
        )
        assertNull(lost.combat)
        assertEquals(9, lost.players[p1Id]!!.level)
        assertEquals(0, lost.players[p1Id]!!.treasures)
        assertEquals(false, lost.players[p1Id]!!.reachedMaxLevelViaCombat)
        assertEquals(9, lost.players[p2Id]!!.level)

        // ESCAPE must not apply rewards
        val escaped = GameEngine.reduce(
            s,
            CombatEnd(
                eventId = "e5c",
                actorId = p1Id,
                timestamp = 5L,
                outcome = CombatOutcome.ESCAPE,
                levelsGained = 1,
                treasuresGained = 2,
                helperLevelsGained = 1
            )
        )
        assertNull(escaped.combat)
        assertEquals(9, escaped.players[p1Id]!!.level)
        assertEquals(0, escaped.players[p1Id]!!.treasures)

        // Authoritative WIN applies rewards and sets reachedMaxLevelViaCombat for both
        val won = GameEngine.reduce(
            s,
            CombatEnd(
                eventId = "e6",
                actorId = p1Id,
                timestamp = 6L,
                outcome = CombatOutcome.WIN,
                levelsGained = 1,
                treasuresGained = 2,
                helperLevelsGained = 1
            )
        )
        assertNull(won.combat)
        assertEquals(10, won.players[p1Id]!!.level)
        assertEquals(2, won.players[p1Id]!!.treasures)
        assertEquals(true, won.players[p1Id]!!.reachedMaxLevelViaCombat)
        assertEquals(10, won.players[p2Id]!!.level)
        assertEquals(true, won.players[p2Id]!!.reachedMaxLevelViaCombat)
    }

    @Test
    fun `combat helper monster and bonus events update combat cleanly`() {
        var s = baseState()
        s = GameEngine.reduce(s, CombatStart("e1", p1Id, 1L, mainPlayerId = p1Id))
        // Cannot add main player or unknown player as helper
        s = GameEngine.reduce(s, CombatAddHelper("e2", p1Id, 2L, helperId = p1Id))
        assertNull(s.combat!!.helperPlayerId)
        s = GameEngine.reduce(s, CombatAddHelper("e3", p1Id, 3L, helperId = PlayerId("unknown")))
        assertNull(s.combat!!.helperPlayerId)
        s = GameEngine.reduce(s, CombatAddHelper("e4", p1Id, 4L, helperId = p2Id))
        assertEquals(p2Id, s.combat!!.helperPlayerId)

        val monster = MonsterInstance(id = "m1", name = "Goblin", baseLevel = 1)
        s = GameEngine.reduce(s, CombatAddMonster("e5", p1Id, 5L, monster = monster))
        assertEquals(1, s.combat!!.monsters.size)
        s = GameEngine.reduce(s, CombatUpdateMonster("e6", p1Id, 6L, monster = monster.copy(flatModifier = 4)))
        assertEquals(4, s.combat!!.monsters.single().flatModifier)

        s = GameEngine.reduce(s, CombatAddBonus("e7", p1Id, 7L, bonus = TempBonus("b1", "Potion", 3, BonusTarget.HEROES)))
        assertEquals(1, s.combat!!.tempBonuses.size)
        s = GameEngine.reduce(s, CombatRemoveBonus("e8", p1Id, 8L, bonusId = "b1"))
        assertTrue(s.combat!!.tempBonuses.isEmpty())

        s = GameEngine.reduce(s, CombatRemoveMonster("e9", p1Id, 9L, monsterId = "m1"))
        assertTrue(s.combat!!.monsters.isEmpty())

        s = GameEngine.reduce(s, CombatRemoveHelper("e10", p1Id, 10L))
        assertNull(s.combat!!.helperPlayerId)
    }

    @Test
    fun `GAME_END sets winnerId clears active combat and treats blank winnerId as null`() {
        val withWinner = GameEngine.reduce(
            baseState(combat = CombatState(mainPlayerId = p1Id)),
            GameEnd("e1", p1Id, 1L, winnerId = p2Id)
        )
        assertEquals(GamePhase.FINISHED, withWinner.phase)
        assertEquals(p2Id, withWinner.winnerId)
        assertNull(withWinner.combat)

        val withoutWinner = GameEngine.reduce(
            baseState(combat = CombatState(mainPlayerId = p1Id)),
            GameEnd("e2", p1Id, 2L, winnerId = PlayerId(""))
        )
        assertEquals(GamePhase.FINISHED, withoutWinner.phase)
        assertNull(withoutWinner.winnerId)
        assertNull(withoutWinner.combat)
    }

    @Test
    fun `GameState playerList preserves order and appends unordered players cleanly`() {
        val state = baseState(
            playerOrder = listOf(p2Id, PlayerId("ghost"), p1Id)
        )
        assertEquals(listOf(p2Id, p1Id, p3Id), state.playerList.map { it.playerId })
    }

    @Test
    fun `GameEngine wrapper preserves updatePlayerStatus across events`() {
        val engine = GameEngine()
        engine.loadState(baseState())
        engine.updatePlayerStatus(p3Id, isConnected = true)
        engine.processEvent(IncLevel("e1", p1Id, 1L, p1Id, amount = 1))

        val current = engine.getCurrentState()!!
        assertTrue(current.players[p3Id]!!.isConnected)
        assertEquals(2, current.players[p1Id]!!.level)
        assertEquals(1L, current.seq)
    }

    @Test
    fun `GameLogEntry generates unique monotonic ids and LogType COMBAT serializes as combat`() {
        val first = GameLogEntry(message = "One", type = LogType.COMBAT)
        val second = GameLogEntry(message = "Two", type = LogType.COMBAT)
        assertTrue(second.id > first.id)

        val encoded = Json.encodeToString(first)
        assertTrue(encoded.contains("\"combat\""))
    }
}
