package com.munchkin.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonsterCardParserTest {
    @Test
    fun parsesSpanishMonsterScan() {
        val draft = MonsterCardParser.parse(
            """
            Bestia de Prueba
            Nivel 14
            No muerto
            Mal Rollo: Pierdes tu casco.
            4 tesoros
            Ganas 2 niveles
            """.trimIndent()
        )

        assertEquals("Bestia de Prueba", draft.name)
        assertEquals(14, draft.level)
        assertEquals(4, draft.treasures)
        assertEquals(2, draft.levels)
        assertTrue(draft.isUndead)
        assertEquals("Pierdes tu casco.", draft.badStuff)
    }

    @Test
    fun parsesEnglishMonsterScan() {
        val draft = MonsterCardParser.parse(
            """
            Test Goblin
            Level 3
            Bad Stuff: Lose one item.
            1 treasure
            Worth 1 level
            """.trimIndent()
        )

        assertEquals("Test Goblin", draft.name)
        assertEquals(3, draft.level)
        assertEquals(1, draft.treasures)
        assertEquals(1, draft.levels)
        assertFalse(draft.isUndead)
        assertEquals("Lose one item.", draft.badStuff)
    }

    @Test
    fun parsesFrenchMonsterScan() {
        val draft = MonsterCardParser.parse(
            """
            Monstre Exemple
            Niveau 8
            Mort-vivant
            Sale coup : Perdez un niveau.
            2 tresors
            Gagnez 1 niveau
            """.trimIndent()
        )

        assertEquals("Monstre Exemple", draft.name)
        assertEquals(8, draft.level)
        assertEquals(2, draft.treasures)
        assertEquals(1, draft.levels)
        assertTrue(draft.isUndead)
    }
}
