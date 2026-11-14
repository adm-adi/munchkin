package com.munchkin.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.test.platform.app.InstrumentationRegistry
import com.munchkin.app.R
import com.munchkin.app.core.CombatState
import com.munchkin.app.core.GameId
import com.munchkin.app.core.GamePhase
import com.munchkin.app.core.GameState
import com.munchkin.app.core.MonsterInstance
import com.munchkin.app.core.PlayerId
import com.munchkin.app.core.PlayerState
import com.munchkin.app.network.ConnectionState
import com.munchkin.app.ui.screens.BoardScreen
import com.munchkin.app.ui.screens.CombatScreen
import com.munchkin.app.ui.theme.MunchkinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Jetpack Compose UI instrumentation tests for [CombatScreen] and [BoardScreen].
 */
class GameplayScreensComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun samplePlayers(
        p1Level: Int = 5,
        p2Level: Int = 3,
        p1ReachedViaCombat: Boolean? = false,
        p2ReachedViaCombat: Boolean? = false
    ): Map<PlayerId, PlayerState> {
        val p1 = PlayerState(
            playerId = PlayerId("p1"),
            name = "HeroOne",
            level = p1Level,
            gearBonus = 2,
            reachedMaxLevelViaCombat = p1ReachedViaCombat
        )
        val p2 = PlayerState(
            playerId = PlayerId("p2"),
            name = "HeroTwo",
            level = p2Level,
            gearBonus = 1,
            reachedMaxLevelViaCombat = p2ReachedViaCombat
        )
        return mapOf(p1.playerId to p1, p2.playerId to p2)
    }

    private fun sampleGameState(
        combat: CombatState? = null,
        players: Map<PlayerId, PlayerState> = samplePlayers()
    ) = GameState(
        gameId = GameId("g1"),
        joinCode = "TEST1234",
        hostId = PlayerId("p1"),
        phase = GamePhase.IN_GAME,
        turnPlayerId = PlayerId("p1"),
        players = players,
        combat = combat
    )

    @Test
    fun combatScreen_resolveButtonDisabledWhenMonstersEmpty() {
        val state = sampleGameState(
            combat = CombatState(
                mainPlayerId = PlayerId("p1"),
                monsters = emptyList()
            )
        )
        val victoryText = context.getString(R.string.combat_victory_end)

        composeTestRule.setContent {
            MunchkinTheme {
                CombatScreen(
                    gameState = state,
                    myPlayerId = PlayerId("p1"),
                    monsterSearchResults = emptyList(),
                    onStartCombat = {},
                    onAddMonster = { _, _, _, _, _, _, _ -> },
                    onRemoveMonster = {},
                    onSearchMonsters = {},
                    onRequestCreateGlobalMonster = { _, _, _, _, _, _, _ -> },
                    onAddHelper = {},
                    onRemoveHelper = {},
                    onModifyModifier = { _, _ -> },
                    onRollCombatDice = { _, _, _ -> },
                    onEndCombat = {},
                    onCancelCombat = {},
                    onResolveRunAway = {},
                    onGoToBoard = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("combat_list").performScrollToKey("end_combat_btn")
        composeTestRule.onNodeWithText(victoryText).assertIsNotEnabled()
    }

    @Test
    fun combatScreen_breakdownToggleExpandsBreakdownPanel() {
        val state = sampleGameState(
            combat = CombatState(
                mainPlayerId = PlayerId("p1"),
                monsters = listOf(
                    MonsterInstance(id = "m1", name = "Goblin", baseLevel = 4)
                )
            )
        )
        val breakdownTitle = context.getString(R.string.combat_breakdown_title)

        composeTestRule.setContent {
            MunchkinTheme {
                CombatScreen(
                    gameState = state,
                    myPlayerId = PlayerId("p1"),
                    monsterSearchResults = emptyList(),
                    onStartCombat = {},
                    onAddMonster = { _, _, _, _, _, _, _ -> },
                    onRemoveMonster = {},
                    onSearchMonsters = {},
                    onRequestCreateGlobalMonster = { _, _, _, _, _, _, _ -> },
                    onAddHelper = {},
                    onRemoveHelper = {},
                    onModifyModifier = { _, _ -> },
                    onRollCombatDice = { _, _, _ -> },
                    onEndCombat = {},
                    onCancelCombat = {},
                    onResolveRunAway = {},
                    onGoToBoard = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("combat_list").performScrollToKey("combat_breakdown")
        composeTestRule.onNodeWithText("HeroOne (base)", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithText(breakdownTitle).performClick()
        composeTestRule.onNodeWithText("HeroOne (base)", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("combat_list").performScrollToKey("end_combat_btn")
        composeTestRule.onNodeWithText("Goblin (nivel 4)", substring = true, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun combatScreen_backToBoardIconTriggersCallbackDuringActiveCombat() {
        var goToBoardCalled = false
        var cancelCombatCalled = false
        val state = sampleGameState(
            combat = CombatState(
                mainPlayerId = PlayerId("p1"),
                monsters = listOf(MonsterInstance(id = "m1", name = "Orc", baseLevel = 6))
            )
        )
        val boardNavDesc = context.getString(R.string.board_nav_label)

        composeTestRule.setContent {
            MunchkinTheme {
                CombatScreen(
                    gameState = state,
                    myPlayerId = PlayerId("p1"),
                    monsterSearchResults = emptyList(),
                    onStartCombat = {},
                    onAddMonster = { _, _, _, _, _, _, _ -> },
                    onRemoveMonster = {},
                    onSearchMonsters = {},
                    onRequestCreateGlobalMonster = { _, _, _, _, _, _, _ -> },
                    onAddHelper = {},
                    onRemoveHelper = {},
                    onModifyModifier = { _, _ -> },
                    onRollCombatDice = { _, _, _ -> },
                    onEndCombat = {},
                    onCancelCombat = { cancelCombatCalled = true },
                    onResolveRunAway = {},
                    onGoToBoard = { goToBoardCalled = true }
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(boardNavDesc).performClick()
        assertTrue(goToBoardCalled)
        assertFalse(cancelCombatCalled)
    }

    @Test
    fun boardScreen_showsReturnToCombatBannerWhenCombatActive() {
        var combatClicked = false
        val activeState = sampleGameState(
            combat = CombatState(
                mainPlayerId = PlayerId("p1"),
                monsters = listOf(MonsterInstance(id = "m1", name = "Troll", baseLevel = 10))
            )
        )
        val bannerText = context.getString(R.string.return_to_combat)

        composeTestRule.setContent {
            MunchkinTheme {
                BoardScreen(
                    gameState = activeState,
                    myPlayerId = PlayerId("p1"),
                    isHost = true,
                    connectionState = ConnectionState.CONNECTED,
                    onPlayerClick = {},
                    onCombatClick = { combatClicked = true },
                    onCatalogClick = {},
                    onSettingsClick = {},
                    onLeaveGame = {}
                )
            }
        }

        composeTestRule.onNodeWithText(bannerText).assertIsDisplayed()
        composeTestRule.onNodeWithText(bannerText).performClick()
        assertTrue(combatClicked)
    }

    @Test
    fun boardScreen_multiWinnerDialogDisplaysBothEligibleWinners() {
        val playersAtTen = samplePlayers(
            p1Level = 10,
            p2Level = 10,
            p1ReachedViaCombat = true,
            p2ReachedViaCombat = true
        )
        val winners = listOf(
            playersAtTen.getValue(PlayerId("p1")),
            playersAtTen.getValue(PlayerId("p2"))
        )
        val state = sampleGameState(players = playersAtTen)
        var confirmedWinner: PlayerId? = null
        val dialogTitle = context.getString(R.string.choose_winner_title)

        composeTestRule.setContent {
            MunchkinTheme {
                BoardScreen(
                    gameState = state,
                    myPlayerId = PlayerId("p1"),
                    isHost = true,
                    connectionState = ConnectionState.CONNECTED,
                    onPlayerClick = {},
                    onCombatClick = {},
                    onCatalogClick = {},
                    onSettingsClick = {},
                    onLeaveGame = {},
                    pendingWinners = winners,
                    onConfirmWin = { confirmedWinner = it }
                )
            }
        }

        composeTestRule.onNodeWithText(dialogTitle).assertIsDisplayed()
        composeTestRule.onNode(hasText("HeroOne") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        composeTestRule.onNode(hasText("HeroTwo") and hasAnyAncestor(isDialog())).assertIsDisplayed()

        composeTestRule.onNode(hasText("HeroTwo") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(PlayerId("p2"), confirmedWinner)
    }
}
