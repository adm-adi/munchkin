package com.munchkin.app.core

import androidx.compose.runtime.Immutable

/**
 * Combat calculator that evaluates combat outcomes with conditional modifiers.
 */
object CombatCalculator {

    /**
     * Calculate combat result based on current combat state and participating players.
     */
    fun calculateResult(
        combatState: CombatState,
        gameState: GameState
    ): CombatResult {
        val mainPlayer = gameState.players[combatState.mainPlayerId]
            ?: return CombatResult(0, 0, CombatOutcome.LOSE)

        val helperPlayer = combatState.helperPlayerId?.let { gameState.players[it] }

        // Calculate base hero power + active curses
        val cursePenalty = mainPlayer.activeCurses.sumOf { it.combatPenalty } +
            (helperPlayer?.activeCurses?.sumOf { it.combatPenalty } ?: 0)
        val heroBasePower = mainPlayer.combatPower + (helperPlayer?.combatPower ?: 0) + cursePenalty

        // Calculate conditional modifiers for heroes (Data Driven)
        val heroConditionalBonus = calculateConditionalBonus(
            monsters = combatState.monsters,
            mainPlayer = mainPlayer,
            helperPlayer = helperPlayer,
            side = ModifierSide.HEROES
        )

        // Calculate Class/Race intrinsic bonuses (Code Driven)
        val intrinsicHeroBonus = calculateIntrinsicBonuses(
            monsters = combatState.monsters,
            mainPlayer = mainPlayer,
            helperPlayer = helperPlayer
        )

        // Calculate temp bonuses for heroes
        val heroTempBonus = combatState.tempBonuses
            .filter { it.appliesTo == BonusTarget.HEROES }
            .sumOf { it.amount }

        val heroesPower = heroBasePower + heroConditionalBonus + intrinsicHeroBonus + heroTempBonus + combatState.heroModifier

        // Calculate monster power
        val monsterBasePower = combatState.monsters.sumOf { it.basePower }

        // Calculate conditional modifiers for monsters
        val monsterConditionalBonus = calculateConditionalBonus(
            monsters = combatState.monsters,
            mainPlayer = mainPlayer,
            helperPlayer = helperPlayer,
            side = ModifierSide.MONSTER
        )

        // Calculate temp bonuses for monsters
        val monsterTempBonus = combatState.tempBonuses
            .filter { it.appliesTo == BonusTarget.MONSTER }
            .sumOf { it.amount }

        val monstersPower = monsterBasePower + monsterConditionalBonus + monsterTempBonus + combatState.monsterModifier

        // Determine outcome (ties go to monsters unless a hero is a Warrior or tiesGoToMonsters is false)
        val isWarriorInvolved = mainPlayer.hasClass(CharacterClass.WARRIOR) ||
            helperPlayer?.hasClass(CharacterClass.WARRIOR) == true

        val heroesWinTies = isWarriorInvolved || !gameState.settings.tiesGoToMonsters

        val outcome = if (heroesPower > monstersPower || (heroesPower == monstersPower && heroesWinTies)) {
            CombatOutcome.WIN
        } else {
            CombatOutcome.LOSE
        }

        // Zero rewards on defeat
        var levels = 0
        var treasures = 0
        var helperLevels = 0
        var orcOverkill = false

        if (outcome == CombatOutcome.WIN) {
            levels = combatState.monsters.sumOf { it.levels }
            treasures = combatState.monsters.sumOf { it.treasures }

            // Elf Helper Bonus: Helper gains 1 level per monster defeated if they help and win
            if (combatState.monsters.isNotEmpty() && helperPlayer?.hasRace(CharacterRace.ELF) == true) {
                helperLevels = combatState.monsters.size
            }
            // Orc Overkill: fighting alone and winning by > 10 grants +1 extra level
            if (helperPlayer == null &&
                combatState.monsters.isNotEmpty() &&
                mainPlayer.hasRace(CharacterRace.ORC) &&
                (heroesPower - monstersPower) > 10
            ) {
                levels += 1
                orcOverkill = true
            }
        }

        return CombatResult(
            heroesPower = heroesPower,
            monstersPower = monstersPower,
            outcome = outcome,
            totalLevels = levels,
            totalTreasures = treasures,
            warriorTieBreak = heroesPower == monstersPower && isWarriorInvolved,
            helperLevelsGained = helperLevels,
            isWarriorInvolved = isWarriorInvolved,
            heroesWinTies = heroesWinTies,
            orcOverkillBonus = orcOverkill
        )
    }

    /**
     * Calculate intrinsic bonuses from Class/Race traits (e.g. Cleric vs Undead).
     */
    private fun calculateIntrinsicBonuses(
        monsters: List<MonsterInstance>,
        mainPlayer: PlayerState,
        helperPlayer: PlayerState?
    ): Int {
        var bonus = 0
        val isUndeadPresent = monsters.any { it.isUndead }

        // Cleric: +3 vs Undead. Goes through hasClass so Super Munchkin still grants it.
        if (isUndeadPresent) {
            if (mainPlayer.hasClass(CharacterClass.CLERIC)) bonus += 3
            if (helperPlayer?.hasClass(CharacterClass.CLERIC) == true) bonus += 3
        }

        return bonus
    }

    /**
     * Calculate total bonus from conditional modifiers for a given side.
     */
    private fun calculateConditionalBonus(
        monsters: List<MonsterInstance>,
        mainPlayer: PlayerState,
        helperPlayer: PlayerState?,
        side: ModifierSide
    ): Int {
        var totalBonus = 0

        for (monster in monsters) {
            for (modifier in monster.conditionalModifiers) {
                if (modifier.side != side) continue

                val matchCount = countMatches(
                    modifier = modifier,
                    mainPlayer = mainPlayer,
                    helperPlayer = helperPlayer
                )

                if (matchCount > 0) {
                    totalBonus += when (modifier.applyMode) {
                        ApplyMode.ONCE_IF_MATCH -> modifier.amount
                        ApplyMode.PER_MATCHING_PLAYER -> modifier.amount * matchCount
                    }
                }
            }
        }

        return totalBonus
    }

    /**
     * Count how many players match a condition based on scope.
     */
    private fun countMatches(
        modifier: ConditionalModifier,
        mainPlayer: PlayerState,
        helperPlayer: PlayerState?
    ): Int {
        val playersToCheck = when (modifier.scope) {
            ModifierScope.MAIN_ONLY -> listOf(mainPlayer)
            ModifierScope.HELPER_ONLY -> listOfNotNull(helperPlayer)
            ModifierScope.ANY_PARTICIPANT -> listOfNotNull(mainPlayer, helperPlayer)
        }

        return playersToCheck.count { player ->
            matchesCondition(modifier, player)
        }
    }

    /**
     * Check if a player matches a condition using canonical enum names.
     */
    private fun matchesCondition(
        modifier: ConditionalModifier,
        player: PlayerState
    ): Boolean {
        return when (modifier.conditionType) {
            ConditionType.RACE_ID -> {
                val raceName = modifier.conditionValue.uppercase()
                val raceEnum = CharacterRace.entries.find { it.name == raceName }
                raceEnum != null && (player.hasRace(raceEnum) || player.characterRace == raceEnum)
            }
            ConditionType.CLASS_ID -> {
                val className = modifier.conditionValue.uppercase()
                val classEnum = CharacterClass.entries.find { it.name == className }
                classEnum != null && (player.hasClass(classEnum) || player.characterClass == classEnum)
            }
            ConditionType.GENDER -> {
                val targetGender = Gender.entries.find { it.name == modifier.conditionValue }
                targetGender != null && player.gender == targetGender
            }
        }
    }

    /**
     * Get a detailed breakdown of combat power sources.
     */
    fun getBreakdown(
        combatState: CombatState,
        gameState: GameState
    ): CombatBreakdown {
        val mainPlayer = gameState.players[combatState.mainPlayerId]
            ?: return CombatBreakdown.empty()

        val helperPlayer = combatState.helperPlayerId?.let { gameState.players[it] }

        val heroSources = mutableListOf<PowerSource>()
        val monsterSources = mutableListOf<PowerSource>()

        // Main player base
        heroSources.add(
            PowerSource(
                label = "${mainPlayer.name} (base)",
                amount = mainPlayer.combatPower
            )
        )

        // Helper base
        helperPlayer?.let {
            heroSources.add(
                PowerSource(
                    label = "${it.name} (ayudante)",
                    amount = it.combatPower
                )
            )
        }

        // Active curses combat penalties
        for (curse in mainPlayer.activeCurses) {
            if (curse.combatPenalty != 0) {
                heroSources.add(PowerSource("${mainPlayer.name}: ${curse.name}", curse.combatPenalty))
            }
        }
        helperPlayer?.activeCurses?.forEach { curse ->
            if (curse.combatPenalty != 0) {
                heroSources.add(PowerSource("${helperPlayer.name}: ${curse.name}", curse.combatPenalty))
            }
        }

        // Monster bases
        for (monster in combatState.monsters) {
            monsterSources.add(
                PowerSource(
                    label = "${monster.name} (nivel ${monster.baseLevel})",
                    amount = monster.basePower
                )
            )
        }

        // Temp bonuses
        for (bonus in combatState.tempBonuses) {
            val source = PowerSource(label = bonus.label, amount = bonus.amount)
            when (bonus.appliesTo) {
                BonusTarget.HEROES -> heroSources.add(source)
                BonusTarget.MONSTER -> monsterSources.add(source)
            }
        }

        // Manual +/- modifiers
        if (combatState.heroModifier != 0) {
            heroSources.add(
                PowerSource(
                    label = "Modificador manual",
                    amount = combatState.heroModifier
                )
            )
        }
        if (combatState.monsterModifier != 0) {
            monsterSources.add(
                PowerSource(
                    label = "Modificador manual",
                    amount = combatState.monsterModifier
                )
            )
        }

        // Intrinsic Bonuses (e.g. Class Abilities)
        val isUndeadPresent = combatState.monsters.any { it.isUndead }
        if (isUndeadPresent) {
            if (mainPlayer.hasClass(CharacterClass.CLERIC)) {
                heroSources.add(PowerSource("Clérigo vs No-Muerto", 3))
            }
            if (helperPlayer?.hasClass(CharacterClass.CLERIC) == true) {
                heroSources.add(PowerSource("Ayudante Clérigo vs No-Muerto", 3))
            }
        }

        // Conditional modifiers (Data Driven)
        val conditionalHero = calculateConditionalBonus(
            monsters = combatState.monsters,
            mainPlayer = mainPlayer,
            helperPlayer = helperPlayer,
            side = ModifierSide.HEROES
        )
        if (conditionalHero != 0) {
            heroSources.add(
                PowerSource(
                    label = "Modificadores carta",
                    amount = conditionalHero
                )
            )
        }

        val conditionalMonster = calculateConditionalBonus(
            monsters = combatState.monsters,
            mainPlayer = mainPlayer,
            helperPlayer = helperPlayer,
            side = ModifierSide.MONSTER
        )
        if (conditionalMonster != 0) {
            monsterSources.add(
                PowerSource(
                    label = "Modificadores carta",
                    amount = conditionalMonster
                )
            )
        }

        val result = calculateResult(combatState, gameState)

        return CombatBreakdown(
            heroSources = heroSources,
            monsterSources = monsterSources,
            result = result
        )
    }
}

/**
 * Power source for combat breakdown display.
 */
@Immutable
data class PowerSource(
    val label: String,
    val amount: Int
)

/**
 * Full combat breakdown for UI display.
 */
@Immutable
data class CombatBreakdown(
    val heroSources: List<PowerSource>,
    val monsterSources: List<PowerSource>,
    val result: CombatResult
) {
    val totalHeroPower: Int get() = heroSources.sumOf { it.amount }
    val totalMonsterPower: Int get() = monsterSources.sumOf { it.amount }

    companion object {
        fun empty() = CombatBreakdown(
            heroSources = emptyList(),
            monsterSources = emptyList(),
            result = CombatResult(0, 0, CombatOutcome.LOSE)
        )
    }
}
