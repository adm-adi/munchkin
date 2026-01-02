package com.munchkin.app.core

import kotlinx.serialization.Serializable

/**
 * Combat models for Munchkin combat resolution
 */

// ============== Combat State ==============

@Serializable
data class CombatState(
    val mainPlayerId: PlayerId,
    val helperPlayerId: PlayerId? = null,
    val monsters: List<MonsterInstance> = emptyList(),
    val tempBonuses: List<TempBonus> = emptyList(),
    val heroModifier: Int = 0,      // Quick +/- for heroes
    val monsterModifier: Int = 0,   // Quick +/- for monsters
    val lastDiceRoll: DiceRollInfo? = null, // Last dice roll for all to see
    val isActive: Boolean = true
)

// ============== Monster Instance ==============

@Serializable
data class MonsterInstance(
    val id: String,
    val name: String,
    val baseLevel: Int,
    val flatModifier: Int = 0,
    val treasures: Int = 1,
    val levels: Int = 1,
    val isUndead: Boolean = false,
    val badStuff: String = "",
    val conditionalModifiers: List<ConditionalModifier> = emptyList()
) {
    /**
     * Base power without conditional modifiers
     */
    val basePower: Int get() = baseLevel + flatModifier
}

// ============== Conditional Modifier ==============

@Serializable
data class ConditionalModifier(
    val id: String,
    val amount: Int,
    val side: ModifierSide,
    val conditionType: ConditionType,
    val conditionValue: String,  // EntryId for race/class, "M"/"F"/"NA" for gender
    val scope: ModifierScope = ModifierScope.ANY_PARTICIPANT,
    val applyMode: ApplyMode = ApplyMode.ONCE_IF_MATCH
)

@Serializable
enum class ModifierSide {
    MONSTER,    // Bonus/penalty applies to monster power
    HEROES      // Bonus/penalty applies to heroes power
}

@Serializable
enum class ConditionType {
    RACE_ID,    // Match by race catalog entry ID
    CLASS_ID,   // Match by class catalog entry ID  
    GENDER      // Match by gender (M/F/NA)
}

@Serializable
enum class ModifierScope {
    MAIN_ONLY,      // Only check main player
    HELPER_ONLY,    // Only check helper
    ANY_PARTICIPANT // Check both main and helper
}

@Serializable
enum class ApplyMode {
    ONCE_IF_MATCH,      // Apply once if any participant matches
    PER_MATCHING_PLAYER // Apply once per matching participant
}

// ============== Temporary Bonus ==============

@Serializable
data class TempBonus(
    val id: String,
    val label: String,
    val amount: Int,
    val appliesTo: BonusTarget
)

@Serializable
enum class BonusTarget {
    HEROES,     // Bonus/penalty for heroes
    MONSTER     // Bonus/penalty for monsters
}

// ============== Combat Result ==============

@Serializable
data class CombatResult(
    val heroesPower: Int,
    val monstersPower: Int,
    val outcome: CombatOutcome,
    val totalTreasures: Int = 0,
    val totalLevels: Int = 0,
    val warriorTieBreak: Boolean = false
) {
    val diff: Int get() = kotlin.math.abs(heroesPower - monstersPower)
}

@Serializable
enum class CombatOutcome {
    WIN,    // Heroes win (heroesPower > monstersPower)
    LOSE    // Monsters win (monstersPower >= heroesPower, ties go to monsters)
}
