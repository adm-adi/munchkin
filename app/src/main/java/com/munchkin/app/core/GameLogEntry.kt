package com.munchkin.app.core

import kotlinx.serialization.Serializable

@Serializable
data class GameLogEntry(
    val id: Long = System.currentTimeMillis(),
    val timestamp: Long = System.currentTimeMillis(),
    val message: String,
    val type: LogType = LogType.INFO
)

enum class LogType {
    INFO,
    combat, // Lowercase to match potential messy integration if needed, or just style
    LEVEL_UP,
    GAME_EVENT
}
