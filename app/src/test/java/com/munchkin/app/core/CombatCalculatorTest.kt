package com.munchkin.app.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for combat calculation.
 */
class CombatCalculatorTest {
    
    private val testPlayerId = PlayerId("player1")
    private val helperPlayerId = PlayerId("player2")
    private val warriorRaceId = EntryId("warrior-race")
    
    private fun createTestGameState(
        mainPlayer: PlayerState,
        helper: PlayerState? = null,
        combatState: CombatState
    ): GameState {
        val players = mutableMapOf(mainPlayer.playerId to mainPlayer)
        if (helper != null) {
            players[helper.playerId] = helper
        }
        
        return GameState(
            gameId = GameId("test-game"),
            joinCode = "TEST12",
            hostId = mainPlayer.playerId,
            players = players,
            combat = combatState
        )
    }
    
    @Test
    fun `basic combat power calculation`() {
        val player = PlayerState(
            playerId = testPlayerId,
            name = "Test",
            level = 5,
            gearBonus = 3
        )
        
        val monster = MonsterInstance(
            id = "monster1",
            name = "Goblin",
            baseLevel = 4
        )
        
        val combat = CombatState(
            mainPlayerId = testPlayerId,
            monsters = listOf(monster)
        )
        
        val gameState = createTestGameState(player, combatState = combat)
        val result = CombatCalculator.calculateResult(combat, gameState)
        
        assertEquals(8, result.heroesPower)  // 5 level + 3 gear
        assertEquals(4, result.monstersPower)  // 4 level monster
        assertEquals(CombatOutcome.WIN, result.outcome)
    }
    
    @Test
    fun `tie goes to monsters`() {
        val player = PlayerState(
            playerId = testPlayerId,
            name = "Test",
            level = 5
        )
        
        val monster = MonsterInstance(
            id = "monster1",
            name = "Goblin",
            baseLevel = 5
        )
        
        val combat = CombatState(
            mainPlayerId = testPlayerId,
            monsters = listOf(monster)
        )
        
        val gameState = createTestGameState(player, combatState = combat)
        val result = CombatCalculator.calculateResult(combat, gameState)
        
        assertEquals(5, result.heroesPower)
        assertEquals(5, result.monstersPower)
        assertEquals(CombatOutcome.LOSE, result.outcome)  // Ties go to monsters
        assertEquals(0, result.diff)
    }
    
    @Test
    fun `helper adds combat power`() {
        val mainPlayer = PlayerState(
            playerId = testPlayerId,
            name = "Main",
            level = 3,
            gearBonus = 2
        )
        
        val helper = PlayerState(
            playerId = helperPlayerId,
            name = "Helper",
            level = 2,
            gearBonus = 3
        )
        
        val monster = MonsterInstance(
            id = "monster1",
            name = "Dragon",
            baseLevel = 8
        )
        
        val combat = CombatState(
            mainPlayerId = testPlayerId,
            helperPlayerId = helperPlayerId,
            monsters = listOf(monster)
        )
        
        val gameState = createTestGameState(mainPlayer, helper, combat)
        val result = CombatCalculator.calculateResult(combat, gameState)
        
        // Main: 3+2=5, Helper: 2+3=5, Total: 10
        assertEquals(10, result.heroesPower)
        assertEquals(8, result.monstersPower)
        assertEquals(CombatOutcome.WIN, result.outcome)
    }
    
    @Test
    fun `multiple monsters stack`() {
        val player = PlayerState(
            playerId = testPlayerId,
            name = "Test",
            level = 10,
            gearBonus = 5
        )
        
        val monsters = listOf(
            MonsterInstance(id = "m1", name = "Goblin", baseLevel = 4),
            MonsterInstance(id = "m2", name = "Orc", baseLevel = 6),
            MonsterInstance(id = "m3", name = "Troll", baseLevel = 8)
        )
        
        val combat = CombatState(
            mainPlayerId = testPlayerId,
            monsters = monsters
        )
        
        val gameState = createTestGameState(player, combatState = combat)
        val result = CombatCalculator.calculateResult(combat, gameState)
        
        assertEquals(15, result.heroesPower)  // 10 + 5
        assertEquals(18, result.monstersPower)  // 4 + 6 + 8
        assertEquals(CombatOutcome.LOSE, result.outcome)
    }
    
    @Test
    fun `monster with flat modifier`() {
        val player = PlayerState(
            playerId = testPlayerId,
            name = "Test",
            level = 5
        )
        
        val monster = MonsterInstance(
            id = "monster1",
            name = "Buffed Goblin",
            baseLevel = 4,
            flatModifier = 3  // +3 from card effects
        )
        
        val combat = CombatState(
            mainPlayerId = testPlayerId,
            monsters = listOf(monster)
        )
        
        val gameState = createTestGameState(player, combatState = combat)
        val result = CombatCalculator.calculateResult(combat, gameState)
        
        assertEquals(5, result.heroesPower)
        assertEquals(7, result.monstersPower)  // 4 + 3
        assertEquals(CombatOutcome.LOSE, result.outcome)
    }
    
    @Test
    fun `temp bonus applies to heroes`() {
        val player = PlayerState(
            playerId = testPlayerId,
            name = "Test",
            level = 5
        )
        
        val monster = MonsterInstance(
            id = "monster1",
            name = "Dragon",
            baseLevel = 10
        )
        
        val combat = CombatState(
            mainPlayerId = testPlayerId,
            monsters = listOf(monster),
            tempBonuses = listOf(
                TempBonus(id = "b1", label = "Poción", amount = 3, appliesTo = BonusTarget.HEROES),
                TempBonus(id = "b2", label = "Ayuda", amount = 2, appliesTo = BonusTarget.HEROES)
            )
        )
        
        val gameState = createTestGameState(player, combatState = combat)
        val result = CombatCalculator.calculateResult(combat, gameState)
        
        assertEquals(10, result.heroesPower)  // 5 + 3 + 2
        assertEquals(10, result.monstersPower)
        assertEquals(CombatOutcome.LOSE, result.outcome)  // Tie = lose
    }
    
    @Test
    fun `temp bonus applies to monster`() {
        val player = PlayerState(
            playerId = testPlayerId,
            name = "Test",
            level = 8
        )
        
        val monster = MonsterInstance(
            id = "monster1",
            name = "Weak Goblin",
            baseLevel = 2
        )
        
        val combat = CombatState(
            mainPlayerId = testPlayerId,
            monsters = listOf(monster),
            tempBonuses = listOf(
                TempBonus(id = "b1", label = "Rage", amount = 10, appliesTo = BonusTarget.MONSTER)
            )
        )
        
        val gameState = createTestGameState(player, combatState = combat)
        val result = CombatCalculator.calculateResult(combat, gameState)
        
        assertEquals(8, result.heroesPower)
        assertEquals(12, result.monstersPower)  // 2 + 10
        assertEquals(CombatOutcome.LOSE, result.outcome)
    }
}
