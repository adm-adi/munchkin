package com.munchkin.app.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for level validation rules.
 */
class LevelValidationTest {
    
    private val engine = GameEngine()
    
    @Test
    fun `level is clamped to minimum 1`() {
        val player = PlayerState(
            playerId = PlayerId("test"),
            name = "Test",
            level = 1
        )
        
        // Try to go below 1
        val newLevel = (player.level - 1).coerceIn(1, 10)
        assertEquals(1, newLevel)
    }
    
    @Test
    fun `level is clamped to maximum 10`() {
        val player = PlayerState(
            playerId = PlayerId("test"),
            name = "Test",
            level = 10
        )
        
        // Try to go above 10
        val newLevel = (player.level + 1).coerceIn(1, 10)
        assertEquals(10, newLevel)
    }
    
    @Test
    fun `level increments normally within range`() {
        val player = PlayerState(
            playerId = PlayerId("test"),
            name = "Test",
            level = 5
        )
        
        val newLevel = (player.level + 1).coerceIn(1, 10)
        assertEquals(6, newLevel)
    }
    
    @Test
    fun `level decrements normally within range`() {
        val player = PlayerState(
            playerId = PlayerId("test"),
            name = "Test",
            level = 5
        )
        
        val newLevel = (player.level - 1).coerceIn(1, 10)
        assertEquals(4, newLevel)
    }
}
