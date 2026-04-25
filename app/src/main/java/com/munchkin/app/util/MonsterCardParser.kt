package com.munchkin.app.util

data class ScannedMonsterDraft(
    val name: String = "",
    val level: Int = 1,
    val modifier: Int = 0,
    val treasures: Int = 1,
    val levels: Int = 1,
    val isUndead: Boolean = false,
    val badStuff: String = "",
    val rawText: String = ""
)

object MonsterCardParser {
    fun parse(rawText: String): ScannedMonsterDraft {
        val cleaned = rawText
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        val lines = cleaned.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        return ScannedMonsterDraft(
            name = findName(lines),
            level = findBaseLevel(lines).coerceIn(1, 20),
            modifier = 0,
            treasures = findNumberNear(cleaned, TREASURE_TERMS, defaultValue = 1).coerceIn(0, 99),
            levels = findRewardLevels(lines).coerceIn(1, 10),
            isUndead = UNDEAD_REGEX.containsMatchIn(cleaned),
            badStuff = findBadStuff(cleaned),
            rawText = cleaned
        )
    }

    private fun findName(lines: List<String>): String {
        return lines.firstOrNull { line ->
            line.any(Char::isLetter) &&
                !line.containsDigit() &&
                !NOISE_LINE_REGEX.containsMatchIn(line) &&
                line.length <= 60
        } ?: lines.firstOrNull { line ->
            line.any(Char::isLetter) &&
                !NOISE_LINE_REGEX.containsMatchIn(line) &&
                line.length <= 60
        }.orEmpty()
    }

    private fun findBaseLevel(lines: List<String>): Int {
        val levelLine = lines.firstOrNull { line ->
            LEVEL_TERMS.any { term -> line.contains(term, ignoreCase = true) } &&
                !REWARD_HINT_REGEX.containsMatchIn(line)
        }
        return levelLine?.let { findNumberNear(it, LEVEL_TERMS, defaultValue = 1) } ?: 1
    }

    private fun findRewardLevels(lines: List<String>): Int {
        val rewardLine = lines.firstOrNull { line ->
            REWARD_HINT_REGEX.containsMatchIn(line) &&
                LEVEL_TERMS.any { term -> line.contains(term, ignoreCase = true) }
        }
        return rewardLine?.let { findNumberNear(it, LEVEL_TERMS, defaultValue = 1) } ?: 1
    }

    private fun findBadStuff(text: String): String {
        val match = BAD_STUFF_REGEX.find(text) ?: return ""
        return match.groupValues.getOrNull(1)
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.takeWhile { line ->
                TREASURE_TERMS.none { term -> line.contains(term, ignoreCase = true) } &&
                    !(REWARD_HINT_REGEX.containsMatchIn(line) &&
                        LEVEL_TERMS.any { term -> line.contains(term, ignoreCase = true) })
            }
            ?.joinToString(" ")
            ?.trim()
            .orEmpty()
    }

    private fun findNumberNear(text: String, terms: List<String>, defaultValue: Int): Int {
        for (term in terms) {
            val escapedTerm = Regex.escape(term)
            val after = Regex("""(?i)(\d{1,2})\D{0,12}\b$escapedTerm\b""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            if (after != null) return after

            val before = Regex("""(?i)\b$escapedTerm\b\D{0,12}(\d{1,2})""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            if (before != null) return before
        }
        return defaultValue
    }

    private fun String.containsDigit(): Boolean = any(Char::isDigit)

    private val LEVEL_TERMS = listOf(
        "level",
        "levels",
        "lvl",
        "nivel",
        "niveles",
        "niveau",
        "niveaux",
        "livello",
        "livelli",
        "liv"
    )

    private val TREASURE_TERMS = listOf(
        "treasure",
        "treasures",
        "tesoro",
        "tesoros",
        "tresor",
        "tresors",
        "tresors",
        "tesori",
        "trésor",
        "trésors"
    )

    private val NOISE_LINE_REGEX = Regex(
        pattern = """(?i)\b(level|levels|lvl|nivel|niveles|niveau|niveaux|livello|livelli|treasure|treasures|tesoro|tesoros|tresor|trésor|tesori|bad stuff|mal rollo|sale coup|mauvais truc|brutta roba)\b"""
    )
    private val REWARD_HINT_REGEX = Regex("""(?i)\b(gain|gains|worth|gana|ganas|vale|gagne|gagnez|vaut|valeur|ottieni|guadagni)\b""")
    private val UNDEAD_REGEX = Regex("""(?i)\b(undead|no muerto|no-muerto|mort-vivant|non morto|non-morto)\b""")
    private val BAD_STUFF_REGEX = Regex(
        pattern = """(?is)(?:bad\s*stuff|mal\s*rollo|sale\s*coup|mauvais\s*truc|brutta\s*roba|mala\s*cosa)\s*:?\s*(.+)$"""
    )
}
