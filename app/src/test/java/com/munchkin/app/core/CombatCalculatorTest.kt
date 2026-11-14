package com.munchkin.app.core

import org.junit.Assert.*
import org.junit.Test

class CombatCalculatorTest {

    private fun createPlayer(
        id: String,
        level: Int,
        gear: Int,
        characterClass: CharacterClass = CharacterClass.NONE,
        characterRace: CharacterRace = CharacterRace.HUMAN,
        secondaryClass: CharacterClass = CharacterClass.NONE,
        secondaryRace: CharacterRace = CharacterRace.HUMAN,
        hasSuperMunchkin: Boolean = false,
        hasHalfBreed: Boolean = false,
        gender: Gender = Gender.NA
    ): PlayerState {
        return PlayerState(
            playerId = PlayerId(id),
            name = "Player $id",
            level = level,
            gearBonus = gear,
            gender = gender,
            characterClass = characterClass,
            characterRace = characterRace,
            secondaryClass = secondaryClass,
            secondaryRace = secondaryRace,
            hasSuperMunchkin = hasSuperMunchkin,
            hasHalfBreed = hasHalfBreed
        )
    }

    private fun createMonster(
        level: Int,
        modifier: Int = 0,
        treasures: Int = 1,
        levels: Int = 1,
        isUndead: Boolean = false,
        conditionalModifiers: List<ConditionalModifier> = emptyList()
    ): MonsterInstance {
        return MonsterInstance(
            id = "m1",
            name = "Test Monster",
            baseLevel = level,
            flatModifier = modifier,
            treasures = treasures,
            levels = levels,
            isUndead = isUndead,
            conditionalModifiers = conditionalModifiers
        )
    }

    @Test
    fun `test combat rewards`() {
        val player = createPlayer("p1", 10, 5) // Power 15
        val monster1 = createMonster(10, 0, treasures = 2, levels = 1)
        val monster2 = createMonster(2, 0, treasures = 1, levels = 1).copy(id = "m2")

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
        val p1 = createPlayer("p1", 5, 0, characterClass = CharacterClass.WARRIOR)
        val monster = createMonster(5, 0)

        val gameState = GameState(
            gameId = GameId("g1"),
            joinCode = "1234",
            hostId = PlayerId("p1"),
            players = mapOf(PlayerId("p1") to p1)
        )

        val combatState = CombatState(
            mainPlayerId = PlayerId("p1"),
            monsters = listOf(monster)
        )

        val result = CombatCalculator.calculateResult(combatState, gameState)

        assertEquals(5, result.heroesPower)
        assertEquals(5, result.monstersPower)
        assertEquals(CombatOutcome.WIN, result.outcome)
        assertTrue(result.warriorTieBreak)
        assertEquals(0, result.marginToWin)
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
        assertEquals(CombatOutcome.LOSE, result.outcome)
        assertEquals(1, result.marginToWin)
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
        val p2 = createPlayer("p2", 5, 5, characterRace = CharacterRace.ELF)
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
    fun `test defeat zeroes rewards and elf helper levels`() {
        val p1 = createPlayer("p1", 2, 0)
        val p2 = createPlayer("p2", 1, 0, characterRace = CharacterRace.ELF)
        val monster = createMonster(10, treasures = 4, levels = 2)

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
        assertEquals(CombatOutcome.LOSE, result.outcome)
        assertEquals(0, result.totalLevels)
        assertEquals(0, result.totalTreasures)
        assertEquals(0, result.helperLevelsGained)
    }

    @Test
    fun `test conditional modifiers match enum race class and gender`() {
        val p1 = createPlayer(
            id = "p1",
            level = 4,
            gear = 1,
            characterRace = CharacterRace.ELF,
            gender = Gender.F
        )
        val p2 = createPlayer(
            id = "p2",
            level = 3,
            gear = 0,
            characterClass = CharacterClass.CLERIC,
            characterRace = CharacterRace.ELF
        )
        val monster = createMonster(
            level = 5,
            conditionalModifiers = listOf(
                ConditionalModifier(
                    id = "c1",
                    amount = 3,
                    side = ModifierSide.MONSTER,
                    conditionType = ConditionType.RACE_ID,
                    conditionValue = "ELF",
                    scope = ModifierScope.ANY_PARTICIPANT,
                    applyMode = ApplyMode.PER_MATCHING_PLAYER
                ),
                ConditionalModifier(
                    id = "c2",
                    amount = 2,
                    side = ModifierSide.HEROES,
                    conditionType = ConditionType.GENDER,
                    conditionValue = "F",
                    scope = ModifierScope.MAIN_ONLY,
                    applyMode = ApplyMode.ONCE_IF_MATCH
                )
            )
        )

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
        // Heroes: (4+1) + 3 + 2 (gender F) = 10
        // Monsters: 5 + 3*2 (two Elves) = 11
        assertEquals(10, result.heroesPower)
        assertEquals(11, result.monstersPower)
        assertEquals(CombatOutcome.LOSE, result.outcome)
    }

    @Test
    fun `test inactive secondary slot and duplicate class ignored`() {
        val p1 = createPlayer(
            id = "p1",
            level = 4,
            gear = 0,
            characterClass = CharacterClass.CLERIC,
            secondaryClass = CharacterClass.CLERIC,
            hasSuperMunchkin = false
        )
        val monster = createMonster(level = 6, isUndead = true)
        val gameState = GameState(
            gameId = GameId("g1"),
            joinCode = "1234",
            hostId = p1.playerId,
            players = mapOf(p1.playerId to p1)
        )
        val combatState = CombatState(mainPlayerId = p1.playerId, monsters = listOf(monster))

        val result = CombatCalculator.calculateResult(combatState, gameState)
        assertEquals(7, result.heroesPower) // 4 + 3 (only counted once)
    }

    @Test
    fun `test game engine secondary class race and death gear reset`() {
        val engine = GameEngine()
        val p1Id = PlayerId("p1")
        val p1 = createPlayer(
            id = "p1",
            level = 5,
            gear = 8,
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
