package com.munchkin.app.core

import org.junit.Test
import org.junit.Assert.*

class CombatCalculatorTest {

    private fun createPlayer(id: String, level: Int, gear: Int, raceIds: List<EntryId> = emptyList()): PlayerState {
        return PlayerState(
            playerId = PlayerId(id),
            name = "Player $id",
            level = level,
            gearBonus = gear,
            raceIds = raceIds
        )
    }

    private fun createMonster(level: Int, modifier: Int = 0, treasures: Int = 1, levels: Int = 1): MonsterInstance {
        return MonsterInstance(
            id = "m1",
            name = "Test Monster",
            baseLevel = level,
            flatModifier = modifier,
            treasures = treasures,
            levels = levels
        )
    }

    @Test
    fun `test combat rewards`() {
        val player = createPlayer("p1", 10, 5) // Power 15
        val monster1 = createMonster(10, 0, treasures = 2, levels = 1)
        val monster2 = createMonster(2, 0, treasures = 1, levels = 1)
        
        val gameState = GameState(
            gameId = GameId("g1"),
            joinCode = "1234",
            hostId = PlayerId("p1"),
            players = mapOf(PlayerId("p1") to player)
        )
        
        val combatState = CombatState(
            mainPlayerId = PlayerId("p1"),
            monsters = listOf(monster1, monster2)
        )
        
        val result = CombatCalculator.calculateResult(combatState, gameState)
        
        assertEquals(15, result.heroesPower)
        assertEquals(12, result.monstersPower)
        assertEquals(CombatOutcome.WIN, result.outcome)
        assertEquals(3, result.totalTreasures)
        assertEquals(2, result.totalLevels)
    }

    @Test
    fun `test warrior wins ties`() {
        val warriorClassId = EntryId("class_warrior")
        val p1 = createPlayer("p1", 5, 0).copy(classIds = listOf(warriorClassId))
        val monster = createMonster(5, 0)
        
        val gameState = GameState(
            gameId = GameId("g1"),
            joinCode = "1234",
            hostId = PlayerId("p1"),
            players = mapOf(PlayerId("p1") to p1),
            classes = mapOf(warriorClassId to CatalogEntry(
                entryId = warriorClassId,
                displayName = "Guerrero",
                normalizedName = "guerrero",
                createdByPlayerId = PlayerId("admin"),
                createdAt = 0
            ))
        )
        
        val combatState = CombatState(
            mainPlayerId = PlayerId("p1"),
            monsters = listOf(monster)
        )
        
        val result = CombatCalculator.calculateResult(combatState, gameState)
        
        assertEquals(5, result.heroesPower)
        assertEquals(5, result.monstersPower)
        assertEquals(CombatOutcome.WIN, result.outcome) // Warrior wins ties
        assertTrue(result.warriorTieBreak)
    }

    @Test
    fun `test simple combat win`() {
        val player = createPlayer("p1", 5, 2) // Power 7
        val monster = createMonster(5, 0) // Power 5
        
        val gameState = GameState(
            gameId = GameId("g1"),
            joinCode = "1234",
            hostId = PlayerId("p1"),
            players = mapOf(PlayerId("p1") to player)
        )
        
        val combatState = CombatState(
            mainPlayerId = PlayerId("p1"),
            monsters = listOf(monster)
        )
        
        val result = CombatCalculator.calculateResult(combatState, gameState)
        
        assertEquals(7, result.heroesPower)
        assertEquals(5, result.monstersPower)
        assertEquals(CombatOutcome.WIN, result.outcome)
    }

    @Test
    fun `test simple combat lose ties`() {
        val player = createPlayer("p1", 5, 0) // Power 5
        val monster = createMonster(5, 0) // Power 5
        
        val gameState = GameState(
            gameId = GameId("g1"),
            joinCode = "1234",
            hostId = PlayerId("p1"),
            players = mapOf(PlayerId("p1") to player)
        )
        
        val combatState = CombatState(
            mainPlayerId = PlayerId("p1"),
            monsters = listOf(monster)
        )
        
        val result = CombatCalculator.calculateResult(combatState, gameState)
        
        assertEquals(5, result.heroesPower)
        assertEquals(5, result.monstersPower)
        assertEquals(CombatOutcome.LOSE, result.outcome) // Ties go to monsters
    }

    @Test
    fun `test helper combat`() {
        val p1 = createPlayer("p1", 5, 0) // Power 5
        val p2 = createPlayer("p2", 3, 2) // Power 5
        val monster = createMonster(9, 0) // Power 9
        
        val gameState = GameState(
            gameId = GameId("g1"),
            joinCode = "1234",
            hostId = PlayerId("p1"),
            players = mapOf(PlayerId("p1") to p1, PlayerId("p2") to p2)
        )
        
        val combatState = CombatState(
            mainPlayerId = PlayerId("p1"),
            helperPlayerId = PlayerId("p2"),
            monsters = listOf(monster)
        )
        
        val result = CombatCalculator.calculateResult(combatState, gameState)
        
        assertEquals(10, result.heroesPower) // 5 + 5
        assertEquals(9, result.monstersPower)
        assertEquals(CombatOutcome.WIN, result.outcome)
    }

    @Test
    fun `test temp bonuses`() {
        val p1 = createPlayer("p1", 1, 0) // Power 1
        val monster = createMonster(1, 0) // Power 1
        
        val gameState = GameState(
            gameId = GameId("g1"),
            joinCode = "1234",
            hostId = PlayerId("p1"),
            players = mapOf(PlayerId("p1") to p1)
        )
        
        val combatState = CombatState(
            mainPlayerId = PlayerId("p1"),
            monsters = listOf(monster),
            tempBonuses = listOf(
                TempBonus("b1", "Potion", 3, BonusTarget.HEROES),
                TempBonus("b2", "Enhancer", 5, BonusTarget.MONSTER)
            )
        )
        
        val result = CombatCalculator.calculateResult(combatState, gameState)
        
        assertEquals(4, result.heroesPower) // 1 + 3
        assertEquals(6, result.monstersPower) // 1 + 5
        assertEquals(CombatOutcome.LOSE, result.outcome)
    }
}
