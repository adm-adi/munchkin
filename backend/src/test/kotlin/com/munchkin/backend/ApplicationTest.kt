package com.munchkin.backend

import com.munchkin.app.core.GamePhase
import com.munchkin.app.core.PlayerId
import com.munchkin.app.core.PlayerMeta
import com.munchkin.app.network.AuthHttpResponse
import com.munchkin.app.network.CatalogMonster
import com.munchkin.app.network.CreateGameRequest
import com.munchkin.app.network.ErrorCode
import com.munchkin.app.network.ErrorMessage
import com.munchkin.app.network.GAME_DELETED_BY_HOST_REASON
import com.munchkin.app.network.GameDeletedMessage
import com.munchkin.app.network.GameHistoryItem
import com.munchkin.app.network.GameOverMessage
import com.munchkin.app.network.GameOverRecordedMessage
import com.munchkin.app.network.HelloMessage
import com.munchkin.app.network.HostedGame
import com.munchkin.app.network.LoginHttpRequest
import com.munchkin.app.network.OpenGamesResponse
import com.munchkin.app.network.PingMessage
import com.munchkin.app.network.PongMessage
import com.munchkin.app.network.RegisterHttpRequest
import com.munchkin.app.network.StateSnapshotMessage
import com.munchkin.app.network.SwapPlayers
import com.munchkin.app.network.UpdateProfileHttpRequest
import com.munchkin.app.network.UserProfile
import com.munchkin.app.network.WelcomeMessage
import com.munchkin.app.network.WsMessage
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    @Test
    fun `health endpoint returns typed json`() = testApplication {
        environment {
            config = MapApplicationConfig()
        }
        application {
            module(testServices(InMemoryPersistence()))
        }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"status\":\"ok\""))
        assertTrue(body.contains("\"openGames\":0"))
    }

    @Test
    fun `register login and profile patch work over http`() = testApplication {
        environment {
            config = MapApplicationConfig()
        }
        val persistence = InMemoryPersistence()
        application {
            module(testServices(persistence))
        }

        val registerResponse = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    RegisterHttpRequest(
                        username = "host",
                        email = "host@example.com",
                        password = "secret123",
                        avatarId = 4
                    )
                )
            )
        }

        assertEquals(HttpStatusCode.OK, registerResponse.status)
        val auth = json.decodeFromString(AuthHttpResponse.serializer(), registerResponse.bodyAsText())
        assertEquals("host", auth.user.username)
        assertTrue(auth.token.isNotBlank())

        val profileResponse = client.get("/api/profile") {
            bearerAuth(auth.token)
        }
        assertEquals(HttpStatusCode.OK, profileResponse.status)
        val profile = json.decodeFromString(UserProfile.serializer(), profileResponse.bodyAsText())
        assertEquals(auth.user.id, profile.id)
        assertEquals("host@example.com", profile.email)

        val patchResponse = client.patch("/api/profile") {
            bearerAuth(auth.token)
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    UpdateProfileHttpRequest(
                        username = "host-renamed",
                        password = "new-secret123"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, patchResponse.status)
        val patched = json.decodeFromString(UserProfile.serializer(), patchResponse.bodyAsText())
        assertEquals("host-renamed", patched.username)

        val loginResponse = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    LoginHttpRequest(
                        email = "host@example.com",
                        password = "new-secret123"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status)
        val login = json.decodeFromString(AuthHttpResponse.serializer(), loginResponse.bodyAsText())
        assertEquals("host-renamed", login.user.username)
    }

    @Test
    fun `history and leaderboard endpoints return persisted data`() = testApplication {
        environment {
            config = MapApplicationConfig()
        }
        val persistence = InMemoryPersistence()
        application {
            module(testServices(persistence))
        }

        val auth = registerUser("alice", "alice@example.com", persistence)
        persistence.addMonster(
            CatalogMonster(name = "Ghoul", level = 8, isUndead = true),
            createdBy = auth.user.id
        )
        persistence.recordGame(
            RecordedGame(
                id = "game-1",
                joinCode = "ABC123",
                winnerUserId = auth.user.id,
                startedAt = 100,
                endedAt = 200,
                participants = listOf(
                    RecordedParticipant(
                        userId = auth.user.id,
                        playerId = "player-1",
                        username = auth.user.username,
                        avatarId = auth.user.avatarId
                    )
                )
            )
        )

        val historyResponse = client.get("/api/history") {
            bearerAuth(auth.token)
        }
        assertEquals(HttpStatusCode.OK, historyResponse.status)
        val history = json.decodeFromString(
            ListSerializer(GameHistoryItem.serializer()),
            historyResponse.bodyAsText()
        )
        assertEquals(1, history.size)
        assertEquals("game-1", history.first().id)
        assertEquals(auth.user.id, history.first().winnerId)

        val leaderboardResponse = client.get("/api/leaderboard")
        assertEquals(HttpStatusCode.OK, leaderboardResponse.status)
        val leaderboard = json.decodeFromString(
            ListSerializer(com.munchkin.app.network.LeaderboardEntry.serializer()),
            leaderboardResponse.bodyAsText()
        )
        assertEquals(1, leaderboard.size)
        assertEquals(auth.user.id, leaderboard.first().id)
        assertEquals(1, leaderboard.first().wins)

        val catalogResponse = client.get("/api/catalog/monsters?q=gh")
        assertEquals(HttpStatusCode.OK, catalogResponse.status)
        val monsters = json.decodeFromString(
            ListSerializer(CatalogMonster.serializer()),
            catalogResponse.bodyAsText()
        )
        assertEquals(1, monsters.size)
        assertEquals("Ghoul", monsters.first().name)
    }

    @Test
    fun `websocket created lobby appears in open and hosted games endpoints`() = testApplication {
        environment {
            config = MapApplicationConfig()
        }
        val persistence = InMemoryPersistence()
        application {
            module(testServices(persistence))
        }

        val auth = registerUser("ws-host", "ws-host@example.com", persistence)
        val wsClient = createClient {
            install(WebSockets)
        }

        var createdGameId: String? = null
        var createdJoinCode: String? = null

        wsClient.webSocket("/ws/game") {
            send(
                Frame.Text(
                    json.encodeToString<WsMessage>(
                        CreateGameRequest(
                            playerMeta = PlayerMeta(
                                playerId = PlayerId("player-host"),
                                name = "ws \"host\"",
                                avatarId = 7,
                                gender = com.munchkin.app.core.Gender.NA,
                                userId = auth.user.id
                            ),
                            superMunchkin = true,
                            turnTimerSeconds = 30
                        )
                    )
                )
            )

            val welcomeFrame = incoming.receive() as Frame.Text
            val welcome = json.decodeFromString<WsMessage>(welcomeFrame.readText()) as WelcomeMessage
            createdGameId = welcome.gameState.gameId.value
            createdJoinCode = welcome.gameState.joinCode
            assertEquals(30, welcome.gameState.settings.turnTimerSeconds)

            val openGamesResponse = client.get("/api/games/open")
            assertEquals(HttpStatusCode.OK, openGamesResponse.status)
            val openGames = json.decodeFromString(
                OpenGamesResponse.serializer(),
                openGamesResponse.bodyAsText()
            )
            assertEquals(1, openGames.games.size)
            assertEquals(createdJoinCode, openGames.games.first().joinCode)
            assertEquals("ws \"host\"", openGames.games.first().hostName)
            assertEquals(1, openGames.games.first().playerCount)

            val hostedGamesResponse = client.get("/api/games/hosted") {
                bearerAuth(auth.token)
            }
            assertEquals(HttpStatusCode.OK, hostedGamesResponse.status)
            val hostedGames = json.decodeFromString(
                ListSerializer(HostedGame.serializer()),
                hostedGamesResponse.bodyAsText()
            )
            assertEquals(1, hostedGames.size)
            assertEquals(createdGameId, hostedGames.first().gameId)

            val deleteResponse = client.delete("/api/games/hosted/$createdGameId") {
                bearerAuth(auth.token)
            }
            assertEquals(HttpStatusCode.OK, deleteResponse.status)

            val deletedFrame = incoming.receive() as Frame.Text
            val deleted = json.decodeFromString<WsMessage>(deletedFrame.readText()) as GameDeletedMessage
            assertEquals(GAME_DELETED_BY_HOST_REASON, deleted.reason)
        }

        assertNotNull(createdGameId)
        assertNotNull(createdJoinCode)

        val hostedAfterDelete = client.get("/api/games/hosted") {
            bearerAuth(auth.token)
        }
        val hostedGames = json.decodeFromString(
            ListSerializer(HostedGame.serializer()),
            hostedAfterDelete.bodyAsText()
        )
        assertTrue(hostedGames.isEmpty())

        val openAfterDelete = client.get("/api/games/open")
        val openGames = json.decodeFromString(
            OpenGamesResponse.serializer(),
            openAfterDelete.bodyAsText()
        )
        assertFalse(openGames.games.any { it.joinCode == createdJoinCode })
    }

    @Test
    fun `non host cannot reorder players over websocket`() = testApplication {
        environment {
            config = MapApplicationConfig()
        }
        val persistence = InMemoryPersistence()
        application {
            module(testServices(persistence))
        }

        val wsClient = createClient {
            install(WebSockets)
        }

        val hostPlayerId = PlayerId("player-host")
        val guestPlayerId = PlayerId("player-guest")

        wsClient.webSocket("/ws/game") {
            send(
                Frame.Text(
                    json.encodeToString<WsMessage>(
                        CreateGameRequest(
                            playerMeta = PlayerMeta(
                                playerId = hostPlayerId,
                                name = "Host",
                                avatarId = 1,
                                gender = com.munchkin.app.core.Gender.NA
                            )
                        )
                    )
                )
            )
            val hostWelcome = json.decodeFromString<WsMessage>((incoming.receive() as Frame.Text).readText()) as WelcomeMessage

            wsClient.webSocket("/ws/game") {
                send(
                    Frame.Text(
                        json.encodeToString<WsMessage>(
                            HelloMessage(
                                gameId = "",
                                joinCode = hostWelcome.gameState.joinCode,
                                playerMeta = PlayerMeta(
                                    playerId = guestPlayerId,
                                    name = "Guest",
                                    avatarId = 2,
                                    gender = com.munchkin.app.core.Gender.NA
                                )
                            )
                        )
                    )
                )

                val joinResponses = withTimeout(2_000) {
                    listOf(
                        json.decodeFromString<WsMessage>((incoming.receive() as Frame.Text).readText()),
                        json.decodeFromString<WsMessage>((incoming.receive() as Frame.Text).readText())
                    )
                }
                assertTrue(joinResponses.any { it is WelcomeMessage })

                send(
                    Frame.Text(
                        json.encodeToString<WsMessage>(
                            SwapPlayers(hostPlayerId, guestPlayerId)
                        )
                    )
                )

                val error = withTimeout(2_000) {
                    json.decodeFromString<WsMessage>((incoming.receive() as Frame.Text).readText()) as ErrorMessage
                }
                assertEquals(ErrorCode.UNAUTHORIZED, error.code)
            }
        }
    }

    @Test
    fun `host game over confirms winner broadcasts final state and persists history`() = testApplication {
        environment {
            config = MapApplicationConfig()
        }
        val persistence = InMemoryPersistence()
        application {
            module(testServices(persistence))
        }

        val auth = registerUser("winner", "winner@example.com", persistence)
        val hostPlayerId = PlayerId("player-host")
        val wsClient = createClient {
            install(WebSockets)
        }

        var gameId: String? = null

        wsClient.webSocket("/ws/game") {
            send(
                Frame.Text(
                    json.encodeToString<WsMessage>(
                        CreateGameRequest(
                            playerMeta = PlayerMeta(
                                playerId = hostPlayerId,
                                name = "Winner",
                                avatarId = 3,
                                gender = com.munchkin.app.core.Gender.NA,
                                userId = auth.user.id
                            )
                        )
                    )
                )
            )

            val welcome = json.decodeFromString<WsMessage>((incoming.receive() as Frame.Text).readText()) as WelcomeMessage
            gameId = welcome.gameState.gameId.value

            send(
                Frame.Text(
                    json.encodeToString<WsMessage>(
                        GameOverMessage(
                            gameId = welcome.gameState.gameId.value,
                            winnerId = hostPlayerId.value
                        )
                    )
                )
            )

            val responses = withTimeout(2_000) {
                listOf(
                    json.decodeFromString<WsMessage>((incoming.receive() as Frame.Text).readText()),
                    json.decodeFromString<WsMessage>((incoming.receive() as Frame.Text).readText())
                )
            }
            val snapshot = responses.filterIsInstance<StateSnapshotMessage>().single()
            val ack = responses.filterIsInstance<GameOverRecordedMessage>().single()

            assertEquals(welcome.gameState.gameId.value, ack.gameId)
            assertEquals(hostPlayerId.value, ack.winnerId)
            assertEquals(GamePhase.FINISHED, snapshot.gameState.phase)
            assertEquals(hostPlayerId, snapshot.gameState.winnerId)
        }

        val historyResponse = client.get("/api/history") {
            bearerAuth(auth.token)
        }
        assertEquals(HttpStatusCode.OK, historyResponse.status)
        val history = json.decodeFromString(
            ListSerializer(GameHistoryItem.serializer()),
            historyResponse.bodyAsText()
        )
        assertEquals(1, history.size)
        assertEquals(gameId, history.first().id)
        assertEquals(auth.user.id, history.first().winnerId)

        val leaderboardResponse = client.get("/api/leaderboard")
        assertEquals(HttpStatusCode.OK, leaderboardResponse.status)
        val leaderboard = json.decodeFromString(
            ListSerializer(com.munchkin.app.network.LeaderboardEntry.serializer()),
            leaderboardResponse.bodyAsText()
        )
        assertEquals(1, leaderboard.first { it.id == auth.user.id }.wins)
    }

    @Test
    fun `websocket invalid payload returns typed error and keeps session open`() = testApplication {
        environment {
            config = MapApplicationConfig()
        }
        val persistence = InMemoryPersistence()
        application {
            module(testServices(persistence))
        }

        val wsClient = createClient {
            install(WebSockets)
        }

        wsClient.webSocket("/ws/game") {
            send(Frame.Text("{not-json"))
            val error = json.decodeFromString<WsMessage>((incoming.receive() as Frame.Text).readText()) as ErrorMessage
            assertEquals(ErrorCode.INVALID_DATA, error.code)

            send(Frame.Text("""{"type":"LIST_GAMES"}"""))
            val unsupported = json.decodeFromString<WsMessage>((incoming.receive() as Frame.Text).readText()) as ErrorMessage
            assertEquals(ErrorCode.INVALID_DATA, unsupported.code)
            assertEquals("Unsupported message", unsupported.message)

            send(Frame.Text(json.encodeToString<WsMessage>(PingMessage())))
            val pong = json.decodeFromString<WsMessage>((incoming.receive() as Frame.Text).readText())
            assertTrue(pong is PongMessage)
        }
    }

    @Test
    fun `auth endpoints rate limit repeated attempts`() = testApplication {
        environment {
            config = MapApplicationConfig()
        }
        val persistence = InMemoryPersistence()
        application {
            module(
                testServices(
                    persistence,
                    config = testConfig(authRateLimitMaxRequests = 1)
                )
            )
        }

        val body = json.encodeToString(
            LoginHttpRequest(
                email = "missing@example.com",
                password = "wrong-password"
            )
        )

        val first = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            header("X-Forwarded-For", "203.0.113.7")
            setBody(body)
        }
        assertEquals(HttpStatusCode.Unauthorized, first.status)

        val second = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            header("X-Forwarded-For", "203.0.113.7")
            setBody(body)
        }
        assertEquals(HttpStatusCode.TooManyRequests, second.status)
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.registerUser(
        username: String,
        email: String,
        persistence: InMemoryPersistence
    ): AuthHttpResponse {
        val response = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    RegisterHttpRequest(
                        username = username,
                        email = email,
                        password = "secret123",
                        avatarId = 2
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val auth = json.decodeFromString(AuthHttpResponse.serializer(), response.bodyAsText())
        assertNotNull(persistence.findUserById(auth.user.id))
        return auth
    }

    private fun testConfig(
        authRateLimitMaxRequests: Int = 20
    ): BackendConfig {
        return BackendConfig(
            port = 8765,
            databaseUrl = "jdbc:postgresql://unused",
            databaseUser = "unused",
            databasePassword = "unused",
            jwtSecret = "test-secret",
            jwtIssuer = "test-issuer",
            jwtAudience = "test-audience",
            authRateLimitMaxRequests = authRateLimitMaxRequests
        )
    }

    private fun testServices(
        persistence: BackendPersistence,
        config: BackendConfig = testConfig()
    ): BackendServices {
        return BackendServices(
            config = config,
            jwtService = JwtService(config),
            persistence = persistence,
            roomManager = RoomManager(persistFinishedGame = persistence::recordGame, clock = { 1_700_000_000_000L })
        )
    }
}
