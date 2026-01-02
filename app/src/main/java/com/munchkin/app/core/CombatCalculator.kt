package com.munchkin.app.core

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
        
        // Calculate base hero power
        val heroBasePower = mainPlayer.combatPower + (helperPlayer?.combatPower ?: 0)
        
        // Calculate conditional modifiers for heroes (Data Driven)
        val heroConditionalBonus = calculateConditionalBonus(
            monsters = combatState.monsters,
            mainPlayer = mainPlayer,
            helperPlayer = helperPlayer,
            gameState = gameState,
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
            gameState = gameState,
            side = ModifierSide.MONSTER
        )
        
        // Calculate temp bonuses for monsters
        val monsterTempBonus = combatState.tempBonuses
            .filter { it.appliesTo == BonusTarget.MONSTER }
            .sumOf { it.amount }
        
        val monstersPower = monsterBasePower + monsterConditionalBonus + monsterTempBonus + combatState.monsterModifier
        
        // Determine outcome (ties go to monsters unless a hero is a Warrior)
        val isWarriorInvolved = (mainPlayer.characterClass == CharacterClass.WARRIOR) || 
                               (helperPlayer?.characterClass == CharacterClass.WARRIOR)
        
        val outcome = if (heroesPower > monstersPower || (heroesPower == monstersPower && isWarriorInvolved)) {
            CombatOutcome.WIN
        } else {
            CombatOutcome.LOSE
        }
        
        // Sum rewards if won
        var levels = 0
        var treasures = 0
        var helperLevels = 0
        
        if (outcome == CombatOutcome.WIN) {
            levels = combatState.monsters.sumOf { it.levels }
            treasures = combatState.monsters.sumOf { it.treasures }
            
            // Elf Helper Bonus: Helper gains 1 level if they help and win
            if (helperPlayer != null && helperPlayer.characterRace == CharacterRace.ELF) {
                helperLevels = 1
            }
        }
        
        return CombatResult(
            heroesPower = heroesPower,
            monstersPower = monstersPower,
            outcome = outcome,
            totalLevels = levels,
            totalTreasures = treasures,
            warriorTieBreak = heroesPower == monstersPower && isWarriorInvolved,
            helperLevelsGained = helperLevels
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
        
        // Cleric: +3 vs Undead
        if (isUndeadPresent) {
            if (mainPlayer.characterClass == CharacterClass.CLERIC) bonus += 3
            if (helperPlayer?.characterClass == CharacterClass.CLERIC) bonus += 3
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
        gameState: GameState,
        side: ModifierSide
    ): Int {
        var totalBonus = 0
        
        for (monster in monsters) {
            for (modifier in monster.conditionalModifiers) {
                if (modifier.side != side) continue
                
                val matchCount = countMatches(
                    modifier = modifier,
                    mainPlayer = mainPlayer,
                    helperPlayer = helperPlayer,
                    gameState = gameState
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
        helperPlayer: PlayerState?,
        gameState: GameState
    ): Int {
        val playersToCheck = when (modifier.scope) {
            ModifierScope.MAIN_ONLY -> listOf(mainPlayer)
            ModifierScope.HELPER_ONLY -> listOfNotNull(helperPlayer)
            ModifierScope.ANY_PARTICIPANT -> listOfNotNull(mainPlayer, helperPlayer)
        }
        
        return playersToCheck.count { player ->
            matchesCondition(modifier, player, gameState)
        }
    }
    
    /**
     * Check if a player matches a condition.
     */
    private fun matchesCondition(
        modifier: ConditionalModifier,
        player: PlayerState,
        gameState: GameState
    ): Boolean {
        return when (modifier.conditionType) {
            ConditionType.RACE_ID -> {
                // Determine if checking against Enum or ID
                val raceName = modifier.conditionValue.uppercase()
                try {
                    val raceEnum = CharacterRace.valueOf(raceName)
                    player.characterRace == raceEnum
                } catch (e: Exception) {
                    val targetEntryId = EntryId(modifier.conditionValue)
                    player.raceIds.contains(targetEntryId)
                }
            }
            ConditionType.CLASS_ID -> {
                val className = modifier.conditionValue.uppercase()
                try {
                    val classEnum = CharacterClass.valueOf(className)
                    player.characterClass == classEnum
                } catch (e: Exception) {
                    val targetEntryId = EntryId(modifier.conditionValue)
                    player.classIds.contains(targetEntryId)
                }
            }
            ConditionType.GENDER -> {
                val targetGender = try {
                    Gender.valueOf(modifier.conditionValue)
                } catch (e: IllegalArgumentException) {
                    return false
                }
                player.gender == targetGender
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
        heroSources.add(PowerSource(
            label = "${mainPlayer.name} (base)",
            amount = mainPlayer.combatPower
        ))
        
        // Helper base
        helperPlayer?.let {
            heroSources.add(PowerSource(
                label = "${it.name} (ayudante)",
                amount = it.combatPower
            ))
        }
        
        // Monster bases
        for (monster in combatState.monsters) {
            monsterSources.add(PowerSource(
                label = "${monster.name} (nivel ${monster.baseLevel})",
                amount = monster.basePower
            ))
        }
        
        // Temp bonuses
        for (bonus in combatState.tempBonuses) {
            val source = PowerSource(label = bonus.label, amount = bonus.amount)
            when (bonus.appliesTo) {
                BonusTarget.HEROES -> heroSources.add(source)
                BonusTarget.MONSTER -> monsterSources.add(source)
            }
        }
        
        // Intrinsic Bonuses (e.g. Class Abilities)
        val intrinsicWithDesc = mutableListOf<String>()
        // Re-calc to get details (simplified for display)
        val isUndeadPresent = combatState.monsters.any { it.isUndead }
        if (isUndeadPresent) {
            if (mainPlayer.characterClass == CharacterClass.CLERIC) {
                heroSources.add(PowerSource("Clérigo vs No-Muerto", 3))
            }
            if (helperPlayer?.characterClass == CharacterClass.CLERIC) {
                heroSources.add(PowerSource("Ayudante Clérigo vs No-Muerto", 3))
            }
        }
        
        // Conditional modifiers (Data Driven)
        val conditionalHero = calculateConditionalBonus(
            monsters = combatState.monsters,
            mainPlayer = mainPlayer,
            helperPlayer = helperPlayer,
            gameState = gameState,
            side = ModifierSide.HEROES
        )
        if (conditionalHero != 0) {
            heroSources.add(PowerSource(
                label = "Modificadores carta",
                amount = conditionalHero
            ))
        }
        
        val conditionalMonster = calculateConditionalBonus(
            monsters = combatState.monsters,
            mainPlayer = mainPlayer,
            helperPlayer = helperPlayer,
            gameState = gameState,
            side = ModifierSide.MONSTER
        )
        if (conditionalMonster != 0) {
            monsterSources.add(PowerSource(
                label = "Modificadores carta",
                amount = conditionalMonster
            ))
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
data class PowerSource(
    val label: String,
    val amount: Int
)

/**
 * Full combat breakdown for UI display.
 */
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
