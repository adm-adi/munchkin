package com.munchkin.app.core

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong

private val nextLogId = AtomicLong(1L)

@Immutable
@Serializable
data class GameLogEntry(
    val id: Long = nextLogId.getAndIncrement(),
    val timestamp: Long = System.currentTimeMillis(),
    val message: String,
    val type: LogType = LogType.INFO
)

@Serializable
enum class LogType {
    INFO,
    @SerialName("combat")
    COMBAT,
    LEVEL_UP,
    GAME_EVENT
}
