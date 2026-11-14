package com.munchkin.app.core

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-platform parity suite validating [CombatCalculator.calculateResult] against
 * the shared canonical combat test vectors in `shared/combat_vectors.json`.
 */
class CrossPlatformCombatVectorTest {

    @Serializable
    private data class CombatVectorFileEntry(
        val id: String,
        val description: String,
        val tiesGoToMonsters: Boolean = true,
        val players: List<VectorPlayer>,
        val combat: VectorCombat,
        val expected: VectorExpected
    )

    @Serializable
    private data class VectorPlayer(
        val playerId: String,
        val name: String,
        val level: Int,
        val gear: Int = 0,
        val tempCombatBonus: Int = 0,
        val gender: String = "NA",
        val characterClass: String = "NONE",
        val secondaryClass: String? = null,
        val characterRace: String = "HUMAN",
        val secondaryRace: String? = null,
        val hasHalfBreed: Boolean = false,
        val hasSuperMunchkin: Boolean = false
    )

    @Serializable
    private data class VectorCombat(
        val mainPlayerId: String,
        val helperPlayerId: String? = null,
        val heroModifier: Int = 0,
        val monsterModifier: Int = 0,
        val tempBonuses: List<VectorTempBonus> = emptyList(),
        val monsters: List<VectorMonster> = emptyList()
    )

    @Serializable
    private data class VectorTempBonus(
        val id: String,
        val label: String,
        val amount: Int,
        val appliesTo: String
    )

    @Serializable
    private data class VectorMonster(
        val id: String,
        val name: String,
        val baseLevel: Int,
        val flatModifier: Int = 0,
        val isUndead: Boolean = false,
        val levels: Int = 1,
        val treasures: Int = 1,
        val conditionalModifiers: List<VectorConditionalModifier> = emptyList()
    )

    @Serializable
    private data class VectorConditionalModifier(
        val id: String,
        val conditionType: String,
        val conditionValue: String,
        val side: String,
        val amount: Int,
        val scope: String = "ANY_PARTICIPANT",
        val applyMode: String = "ONCE_IF_MATCH"
    )

    @Serializable
    private data class VectorExpected(
        val heroesPower: Int,
        val monstersPower: Int,
        val outcome: String,
        val totalLevels: Int,
        val totalTreasures: Int,
        val helperLevelsGained: Int,
        val warriorTieBreak: Boolean,
        val isWarriorInvolved: Boolean,
        val marginToWin: Int
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun resolveVectorFile(): File {
        val candidates = listOf(
            File("../shared/combat_vectors.json"),
            File("shared/combat_vectors.json")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error(
                "Shared combat vector file not found in any candidate path: " +
                    candidates.joinToString { it.absolutePath }
            )
    }

    @Test
    fun allSharedCombatVectorsMatchCombatCalculator() {
        val vectorFile = resolveVectorFile()
        assertTrue("Vector file must exist: ${vectorFile.absolutePath}", vectorFile.exists())

        val vectors = json.decodeFromString<List<CombatVectorFileEntry>>(vectorFile.readText())
        assertTrue("Shared combat_vectors.json must not be empty", vectors.isNotEmpty())

        vectors.forEach { vector ->
            val ctx = "Vector [${vector.id}] (${vector.description})"

            val playersMap = vector.players.associate { p ->
                val id = PlayerId(p.playerId)
                id to PlayerState(
                    playerId = id,
                    name = p.name,
                    gender = Gender.valueOf(p.gender),
                    characterClass = CharacterClass.valueOf(p.characterClass),
                    secondaryClass = p.secondaryClass?.let { CharacterClass.valueOf(it) }
                        ?: CharacterClass.NONE,
                    characterRace = CharacterRace.valueOf(p.characterRace),
                    secondaryRace = p.secondaryRace?.let { CharacterRace.valueOf(it) }
                        ?: CharacterRace.HUMAN,
                    level = p.level,
                    gearBonus = p.gear,
                    tempCombatBonus = p.tempCombatBonus,
                    hasHalfBreed = p.hasHalfBreed,
                    hasSuperMunchkin = p.hasSuperMunchkin
                )
            }

            val mainPlayerId = PlayerId(vector.combat.mainPlayerId)
            val helperPlayerId = vector.combat.helperPlayerId?.let { PlayerId(it) }

            val combatState = CombatState(
                mainPlayerId = mainPlayerId,
                helperPlayerId = helperPlayerId,
                monsters = vector.combat.monsters.map { m ->
                    MonsterInstance(
                        id = m.id,
                        name = m.name,
                        baseLevel = m.baseLevel,
                        flatModifier = m.flatModifier,
                        treasures = m.treasures,
                        levels = m.levels,
                        isUndead = m.isUndead,
                        conditionalModifiers = m.conditionalModifiers.map { cm ->
                            ConditionalModifier(
                                id = cm.id,
                                amount = cm.amount,
                                side = ModifierSide.valueOf(cm.side),
                                conditionType = ConditionType.valueOf(cm.conditionType),
                                conditionValue = cm.conditionValue,
                                scope = ModifierScope.valueOf(cm.scope),
                                applyMode = ApplyMode.valueOf(cm.applyMode)
                            )
                        }
                    )
                },
                tempBonuses = vector.combat.tempBonuses.map { tb ->
                    TempBonus(
                        id = tb.id,
                        label = tb.label,
                        amount = tb.amount,
                        appliesTo = BonusTarget.valueOf(tb.appliesTo)
                    )
                },
                heroModifier = vector.combat.heroModifier,
                monsterModifier = vector.combat.monsterModifier
            )

            val gameState = GameState(
                gameId = GameId("vector-${vector.id}"),
                joinCode = "VECTOR",
                hostId = mainPlayerId,
                players = playersMap,
                combat = combatState,
                phase = GamePhase.IN_GAME,
                settings = GameSettings(tiesGoToMonsters = vector.tiesGoToMonsters)
            )

            val result = CombatCalculator.calculateResult(combatState, gameState)
            val expected = vector.expected

            assertEquals("$ctx: heroesPower", expected.heroesPower, result.heroesPower)
            assertEquals("$ctx: monstersPower", expected.monstersPower, result.monstersPower)
            assertEquals(
                "$ctx: outcome",
                CombatOutcome.valueOf(expected.outcome),
                result.outcome
            )
            assertEquals("$ctx: totalLevels", expected.totalLevels, result.totalLevels)
            assertEquals("$ctx: totalTreasures", expected.totalTreasures, result.totalTreasures)
            assertEquals(
                "$ctx: helperLevelsGained",
                expected.helperLevelsGained,
                result.helperLevelsGained
            )
            assertEquals("$ctx: warriorTieBreak", expected.warriorTieBreak, result.warriorTieBreak)
            assertEquals(
                "$ctx: isWarriorInvolved",
                expected.isWarriorInvolved,
                result.isWarriorInvolved
            )
            assertEquals("$ctx: marginToWin", expected.marginToWin, result.marginToWin)
        }
    }
}
