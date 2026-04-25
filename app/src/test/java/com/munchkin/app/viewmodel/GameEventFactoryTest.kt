package com.munchkin.app.viewmodel

import com.munchkin.app.core.BonusTarget
import com.munchkin.app.core.CombatAddMonster
import com.munchkin.app.core.CombatSetModifier
import com.munchkin.app.core.DecLevel
import com.munchkin.app.core.DiceRollPurpose
import com.munchkin.app.core.IncLevel
import com.munchkin.app.core.PlayerId
import com.munchkin.app.core.PlayerRoll
import org.junit.Assert.assertEquals
import org.junit.Test

class GameEventFactoryTest {
    private val actorId = PlayerId("actor")

    @Test
    fun buildsEventsWithDeterministicMetadata() {
        val factory = GameEventFactory(
            newId = { "event-1" },
            now = { 123L }
        )

        val event = factory.incrementLevel(actorId) as IncLevel

        assertEquals("event-1", event.eventId)
        assertEquals(actorId, event.actorId)
        assertEquals(123L, event.timestamp)
        assertEquals(actorId, event.targetPlayerId)
    }

    @Test
    fun addMonsterClampsLevelAndPreservesModifier() {
        val ids = ArrayDeque(listOf("event-1", "monster-1"))
        val factory = GameEventFactory(
            newId = { ids.removeFirst() },
            now = { 123L }
        )

        val event = factory.addMonster(
            actorId = actorId,
            name = "Ancient Dragon",
            level = 99,
            modifier = -99,
            isUndead = true,
            treasures = 120,
            levels = 12,
            badStuff = " Lose two levels. "
        ) as CombatAddMonster

        assertEquals("event-1", event.eventId)
        assertEquals("monster-1", event.monster.id)
        assertEquals(20, event.monster.baseLevel)
        assertEquals(-99, event.monster.flatModifier)
        assertEquals(99, event.monster.treasures)
        assertEquals(10, event.monster.levels)
        assertEquals(true, event.monster.isUndead)
        assertEquals("Lose two levels.", event.monster.badStuff)
    }

    @Test
    fun combatModifierPreservesPowerAndRollClampsDie() {
        val factory = GameEventFactory(
            newId = { "event-1" },
            now = { 123L }
        )

        val modifierEvent = factory.setCombatModifier(actorId, BonusTarget.MONSTER, 99) as CombatSetModifier
        val rollEvent = factory.roll(actorId, 99, DiceRollPurpose.RUN_AWAY, success = true) as PlayerRoll

        assertEquals(99, modifierEvent.value)
        assertEquals(6, rollEvent.result)
        assertEquals(DiceRollPurpose.RUN_AWAY, rollEvent.purpose)
        assertEquals(true, rollEvent.success)
    }

    @Test
    fun decrementLevelCanTargetAnotherPlayer() {
        val targetId = PlayerId("target")
        val factory = GameEventFactory(
            newId = { "event-1" },
            now = { 123L }
        )

        val event = factory.decrementLevel(actorId, targetPlayerId = targetId, amount = 2) as DecLevel

        assertEquals(actorId, event.actorId)
        assertEquals(targetId, event.targetPlayerId)
        assertEquals(2, event.amount)
    }
}
