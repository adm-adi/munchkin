package com.munchkin.app.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for race/class trait validation.
 */
class TraitValidationTest {
    
    @Test
    fun `player without half-breed can have max 1 race`() {
        val player = PlayerState(
            playerId = PlayerId("test"),
            name = "Test",
            hasHalfBreed = false
        )
        
        assertEquals(1, player.maxRaces)
        assertTrue(player.canAddRace)
        
        val playerWithRace = player.copy(raceIds = listOf(EntryId("race1")))
        assertFalse(playerWithRace.canAddRace)
    }
    
    @Test
    fun `player with half-breed can have max 2 races`() {
        val player = PlayerState(
            playerId = PlayerId("test"),
            name = "Test",
            hasHalfBreed = true
        )
        
        assertEquals(2, player.maxRaces)
        assertTrue(player.canAddRace)
        
        val playerWith1Race = player.copy(raceIds = listOf(EntryId("race1")))
        assertTrue(playerWith1Race.canAddRace)
        
        val playerWith2Races = player.copy(raceIds = listOf(EntryId("race1"), EntryId("race2")))
        assertFalse(playerWith2Races.canAddRace)
    }
    
    @Test
    fun `player without super munchkin can have max 1 class`() {
        val player = PlayerState(
            playerId = PlayerId("test"),
            name = "Test",
            hasSuperMunchkin = false
        )
        
        assertEquals(1, player.maxClasses)
        assertTrue(player.canAddClass)
        
        val playerWithClass = player.copy(classIds = listOf(EntryId("class1")))
        assertFalse(playerWithClass.canAddClass)
    }
    
    @Test
    fun `player with super munchkin can have max 2 classes`() {
        val player = PlayerState(
            playerId = PlayerId("test"),
            name = "Test",
            hasSuperMunchkin = true
        )
        
        assertEquals(2, player.maxClasses)
        assertTrue(player.canAddClass)
        
        val playerWith2Classes = player.copy(classIds = listOf(EntryId("class1"), EntryId("class2")))
        assertFalse(playerWith2Classes.canAddClass)
    }
}
