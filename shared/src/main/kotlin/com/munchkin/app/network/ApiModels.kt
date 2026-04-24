package com.munchkin.app.network

import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(
    val id: String,
    val username: String,
    val email: String,
    val avatarId: Int
)

@Serializable
data class RegisterHttpRequest(
    val username: String,
    val email: String,
    val password: String,
    val avatarId: Int = 0
)

@Serializable
data class LoginHttpRequest(
    val email: String,
    val password: String
)

@Serializable
data class UpdateProfileHttpRequest(
    val username: String? = null,
    val password: String? = null
)

@Serializable
data class AuthHttpResponse(
    val user: UserProfile,
    val token: String
)

@Serializable
data class ApiErrorResponse(
    val message: String
)

@Serializable
data class OpenGameSummary(
    val joinCode: String,
    val hostName: String,
    val playerCount: Int,
    val maxPlayers: Int,
    val createdAt: Long
)

@Serializable
data class OpenGamesResponse(
    val games: List<OpenGameSummary>
)

@Serializable
data class DiscoveredGame(
    val hostName: String,
    val joinCode: String,
    val playerCount: Int = 1,
    val maxPlayers: Int = 6,
    val wsUrl: String = "",
    val port: Int = 8765
)

@Serializable
data class HostedGame(
    val gameId: String,
    val joinCode: String,
    val playerCount: Int,
    val phase: String,
    val createdAt: Long
)

@Serializable
data class CatalogMonster(
    val id: String = "",
    val name: String,
    val level: Int,
    val modifier: Int = 0,
    val treasures: Int = 1,
    val levels: Int = 1,
    val isUndead: Boolean = false,
    val badStuff: String = "",
    val expansion: String = "base",
    val createdBy: String? = null
)

@Serializable
data class CatalogAddHttpRequest(
    val monster: CatalogMonster
)

@Serializable
data class GameHistoryItem(
    val id: String,
    val endedAt: Long,
    val winnerId: String?,
    val playerCount: Int
)

@Serializable
data class LeaderboardEntry(
    val id: String,
    val username: String,
    val avatarId: Int,
    val wins: Int
)
