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
        val p1 = createPlayer("p1", 5, 0).copy(
            characterClass = CharacterClass.WARRIOR,
            classIds = listOf(warriorClassId)
        )
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

    @Test
    fun `test elf helper gains one level per monster defeated`() {
        val p1 = createPlayer("p1", 10, 5)
        val p2 = createPlayer("p2", 5, 5).copy(characterRace = CharacterRace.ELF)
        val m1 = createMonster(4, 0)
        val m2 = createMonster(6, 0).copy(id = "m2")

        val gameState = GameState(
            gameId = GameId("g1"),
            joinCode = "1234",
            hostId = PlayerId("p1"),
            players = mapOf(PlayerId("p1") to p1, PlayerId("p2") to p2)
        )

        val combatState = CombatState(
            mainPlayerId = PlayerId("p1"),
            helperPlayerId = PlayerId("p2"),
            monsters = listOf(m1, m2)
        )

        val result = CombatCalculator.calculateResult(combatState, gameState)
        assertEquals(CombatOutcome.WIN, result.outcome)
        assertEquals(2, result.helperLevelsGained)
    }

    @Test
    fun `test game engine secondary class race and death gear reset`() {
        val engine = GameEngine()
        val p1Id = PlayerId("p1")
        val p1 = createPlayer("p1", 5, 8).copy(
            characterClass = CharacterClass.WARRIOR,
            characterRace = CharacterRace.DWARF,
            hasSuperMunchkin = true,
            hasHalfBreed = true
        )
        engine.loadState(
            GameState(
                gameId = GameId("g1"),
                joinCode = "1234",
                hostId = p1Id,
                players = mapOf(PlayerId("p1") to p1)
            )
        )

        engine.applyRemoteEvent(
            SetClass(
                eventId = "e1",
                actorId = p1Id,
                timestamp = 1L,
                targetPlayerId = p1Id,
                newClass = CharacterClass.WIZARD,
                isSecondary = true
            )
        )
        engine.applyRemoteEvent(
            SetRace(
                eventId = "e2",
                actorId = p1Id,
                timestamp = 2L,
                targetPlayerId = p1Id,
                newRace = CharacterRace.ELF,
                isSecondary = true
            )
        )

        val afterSecondary = engine.gameState.value!!.players[p1Id]!!
        assertEquals(CharacterClass.WARRIOR, afterSecondary.characterClass)
        assertEquals(CharacterClass.WIZARD, afterSecondary.secondaryClass)
        assertEquals(CharacterRace.DWARF, afterSecondary.characterRace)
        assertEquals(CharacterRace.ELF, afterSecondary.secondaryRace)

        // Death resets gearBonus to 0 while keeping level, race, and class
        engine.applyRemoteEvent(
            SetGear(
                eventId = "e3",
                actorId = p1Id,
                timestamp = 3L,
                targetPlayerId = p1Id,
                gear = 0
            )
        )
        val afterDeath = engine.gameState.value!!.players[p1Id]!!
        assertEquals(0, afterDeath.gearBonus)
        assertEquals(5, afterDeath.level)
        assertEquals(CharacterClass.WARRIOR, afterDeath.characterClass)
        assertEquals(CharacterRace.DWARF, afterDeath.characterRace)
    }
}
