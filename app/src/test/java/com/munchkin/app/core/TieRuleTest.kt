package com.munchkin.app.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Ties in combat.
 *
 * The rule — the monster wins a tie unless a Warrior is in the fight — was
 * hardcoded while `GameSettings.tiesGoToMonsters` declared a switch for it, so
 * the setting was inert. It is now read, and mirrored by combatManager.js.
 */
class TieRuleTest {

    private fun player(
        id: String = "p1",
        level: Int = 5,
        gear: Int = 0,
        klass: CharacterClass = CharacterClass.NONE
    ) = PlayerState(
        playerId = PlayerId(id),
        name = "Player $id",
        level = level,
        gearBonus = gear,
        characterClass = klass
    )

    private fun gameOf(settings: GameSettings, vararg players: PlayerState) = GameState(
        gameId = GameId("g1"),
        joinCode = "TESTCODE",
        hostId = players.first().playerId,
        players = players.associateBy { it.playerId },
        settings = settings
    )

    /** Hero power 5 against monster level 5 — an exact tie. */
    private fun tiedCombat() = CombatState(
        mainPlayerId = PlayerId("p1"),
        monsters = listOf(MonsterInstance(id = "m1", name = "Empate", baseLevel = 5))
    )

    @Test
    fun `by default a tie goes to the monster`() {
        val game = gameOf(GameSettings(), player())
        val result = CombatCalculator.calculateResult(tiedCombat(), game)
        assertEquals(result.heroesPower, result.monstersPower)
        assertEquals(CombatOutcome.LOSE, result.outcome)
    }

    @Test
    fun `a warrior wins the tie`() {
        val game = gameOf(GameSettings(), player(klass = CharacterClass.WARRIOR))
        val result = CombatCalculator.calculateResult(tiedCombat(), game)
        assertEquals(CombatOutcome.WIN, result.outcome)
        assertTrue(result.warriorTieBreak)
    }

    @Test
    fun `a helping warrior also wins the tie`() {
        val main = player(id = "p1", level = 3)
        val helper = player(id = "p2", level = 2, klass = CharacterClass.WARRIOR)
        val game = gameOf(GameSettings(), main, helper)
        val combat = CombatState(
            mainPlayerId = PlayerId("p1"),
            helperPlayerId = PlayerId("p2"),
            monsters = listOf(MonsterInstance(id = "m1", name = "Empate", baseLevel = 5))
        )
        val result = CombatCalculator.calculateResult(combat, game)
        assertEquals(5, result.heroesPower)
        assertEquals(5, result.monstersPower)
        assertEquals(CombatOutcome.WIN, result.outcome)
    }

    @Test
    fun `turning off tiesGoToMonsters hands ties to the heroes`() {
        val game = gameOf(GameSettings(tiesGoToMonsters = false), player())
        val result = CombatCalculator.calculateResult(tiedCombat(), game)
        assertEquals(CombatOutcome.WIN, result.outcome)
    }

    @Test
    fun `the setting never rescues a genuine loss`() {
        val game = gameOf(GameSettings(tiesGoToMonsters = false), player(level = 4))
        val result = CombatCalculator.calculateResult(tiedCombat(), game)
        assertTrue(result.heroesPower < result.monstersPower)
        assertEquals(CombatOutcome.LOSE, result.outcome)
    }

    @Test
    fun `requiresCombatToWin follows both level-ten settings`() {
        assertTrue(GameSettings().requiresCombatToWin)
        assertFalse(GameSettings(levelTenOnlyCombat = false).requiresCombatToWin)
        assertFalse(
            "an explicit override lets the host confirm a win either way",
            GameSettings(allowLevelTenOverride = true).requiresCombatToWin
        )
    }

    @Test
    fun `only a kill qualifies a player as the winner`() {
        val settings = GameSettings()
        val atMax = player(level = settings.maxLevel)

        assertTrue(atMax.copy(reachedMaxLevelViaCombat = true).canBeConfirmedWinner(settings))
        assertFalse(
            "tapping up to the winning level must not qualify",
            atMax.copy(reachedMaxLevelViaCombat = false).canBeConfirmedWinner(settings)
        )
        assertFalse(
            "below the winning level nobody qualifies",
            player(level = settings.maxLevel - 1)
                .copy(reachedMaxLevelViaCombat = true)
                .canBeConfirmedWinner(settings)
        )
    }

    @Test
    fun `a server that does not report the field cannot lock the game`() {
        // An updated app against a not-yet-updated server sees null here. Treating
        // that as "killed nothing" would mean no game could ever be won.
        val settings = GameSettings()
        val legacy = player(level = settings.maxLevel).copy(reachedMaxLevelViaCombat = null)
        assertTrue(legacy.canBeConfirmedWinner(settings))
    }
}
