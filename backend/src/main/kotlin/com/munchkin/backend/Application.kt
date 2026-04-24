package com.munchkin.backend

import com.munchkin.app.network.AuthHttpResponse
import com.munchkin.app.network.ApiErrorResponse
import com.munchkin.app.network.CatalogAddHttpRequest
import com.munchkin.app.network.CreateGameRequest
import com.munchkin.app.network.DeleteGameMessage
import com.munchkin.app.network.ErrorCode
import com.munchkin.app.network.ErrorMessage
import com.munchkin.app.network.GameOverMessage
import com.munchkin.app.network.GameOverRecordedMessage
import com.munchkin.app.network.HelloMessage
import com.munchkin.app.network.LoginHttpRequest
import com.munchkin.app.network.OpenGamesResponse
import com.munchkin.app.network.PingMessage
import com.munchkin.app.network.PongMessage
import com.munchkin.app.network.RegisterHttpRequest
import com.munchkin.app.network.UpdateProfileHttpRequest
import com.munchkin.app.network.WsMessage
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class BackendServices(
    val config: BackendConfig,
    val jwtService: JwtService,
    val persistence: BackendPersistence,
    val roomManager: RoomManager
)

@Serializable
private data class HealthResponse(
    val status: String,
    val openGames: Int
)

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

@Suppress("unused")
fun Application.module() {
    val config = BackendConfig.fromEnvironment()
    val persistence = PostgresPersistence.connect(config)
    val services = BackendServices(
        config = config,
        jwtService = JwtService(config),
        persistence = persistence,
        roomManager = RoomManager(persistFinishedGame = persistence::recordGame)
    )
    module(services)
}

fun Application.module(services: BackendServices) {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    install(CallLogging)
    install(ContentNegotiation) {
        json(json)
    }
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(cause.message ?: "Bad request"))
        }
        exception<SerializationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(cause.message ?: "Invalid JSON"))
        }
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, ApiErrorResponse(cause.message ?: "Internal error"))
        }
    }
    install(io.ktor.server.websocket.WebSockets)
    install(io.ktor.server.auth.Authentication) {
        jwt("auth-jwt") {
            verifier(services.jwtService.verifier())
            validate { credential ->
                if (credential.payload.getClaim("userId").asString().isNullOrBlank()) {
                    null
                } else {
                    JWTPrincipal(credential.payload)
                }
            }
        }
    }

    val authRateLimiter = SlidingWindowRateLimiter(
        maxRequests = services.config.authRateLimitMaxRequests,
        windowMillis = services.config.authRateLimitWindowMillis
    )

    routing {
        get("/health") {
            call.respond(HealthResponse(status = "ok", openGames = services.roomManager.openGames().size))
        }

        route("/api") {
            route("/auth") {
                post("/register") {
                    if (!call.enforceAuthRateLimit(authRateLimiter)) return@post
                    val request = call.receive<RegisterHttpRequest>()
                    val user = services.persistence.createUser(
                        username = request.username.trim(),
                        email = request.email.trim(),
                        passwordHash = PasswordService.hash(request.password),
                        avatarId = request.avatarId
                    )
                    call.respond(AuthHttpResponse(user = user, token = services.jwtService.issueToken(user)))
                }

                post("/login") {
                    if (!call.enforceAuthRateLimit(authRateLimiter)) return@post
                    val request = call.receive<LoginHttpRequest>()
                    val user = services.persistence.findUserByIdentifier(request.email.trim())
                    if (user == null || !PasswordService.verify(request.password, user.passwordHash)) {
                        call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse("Invalid credentials"))
                    } else {
                        call.respond(AuthHttpResponse(user = user.profile, token = services.jwtService.issueToken(user.profile)))
                    }
                }
            }

            get("/catalog/monsters") {
                val query = call.request.queryParameters["q"].orEmpty()
                call.respond(services.persistence.searchMonsters(query))
            }

            get("/leaderboard") {
                call.respond(services.persistence.getLeaderboard())
            }

            route("/games") {
                get("/open") {
                    call.respond(OpenGamesResponse(services.roomManager.openGames()))
                }

                authenticate("auth-jwt") {
                    get("/hosted") {
                        val userId = call.principal<JWTPrincipal>()?.userId()
                        if (userId == null) {
                            call.respond(HttpStatusCode.Unauthorized)
                        } else {
                            call.respond(services.roomManager.hostedGamesFor(userId))
                        }
                    }

                    delete("/hosted/{gameId}") {
                        val userId = call.principal<JWTPrincipal>()?.userId()
                        val gameId = call.parameters["gameId"]
                        if (userId == null || gameId == null) {
                            call.respond(HttpStatusCode.BadRequest)
                        } else if (services.roomManager.deleteHostedGame(userId, gameId)) {
                            call.respond(HttpStatusCode.OK)
                        } else {
                            call.respond(HttpStatusCode.NotFound)
                        }
                    }
                }
            }

            authenticate("auth-jwt") {
                get("/profile") {
                    val userId = call.principal<JWTPrincipal>()?.userId()
                    val user = userId?.let { services.persistence.findUserById(it)?.profile }
                    if (user == null) {
                        call.respond(HttpStatusCode.NotFound)
                    } else {
                        call.respond(user)
                    }
                }

                patch("/profile") {
                    val userId = call.principal<JWTPrincipal>()?.userId()
                    val request = call.receive<UpdateProfileHttpRequest>()
                    if (userId == null) {
                        call.respond(HttpStatusCode.Unauthorized)
                    } else {
                        val updated = services.persistence.updateUser(
                            userId = userId,
                            username = request.username?.takeIf { it.isNotBlank() },
                            passwordHash = request.password?.takeIf { it.isNotBlank() }?.let(PasswordService::hash)
                        )
                        if (updated == null) {
                            call.respond(HttpStatusCode.NotFound)
                        } else {
                            call.respond(updated)
                        }
                    }
                }

                post("/catalog/monsters") {
                    val request = call.receive<CatalogAddHttpRequest>()
                    val userId = call.principal<JWTPrincipal>()?.userId()
                    call.respond(services.persistence.addMonster(request.monster, userId))
                }

                get("/history") {
                    val userId = call.principal<JWTPrincipal>()?.userId()
                    if (userId == null) {
                        call.respond(HttpStatusCode.Unauthorized)
                    } else {
                        call.respond(services.persistence.getUserHistory(userId))
                    }
                }
            }
        }

        webSocket("/") {
            handleSocketSession(this, json, services)
        }
        webSocket("/ws/game") {
            handleSocketSession(this, json, services)
        }
    }
}

private suspend fun handleSocketSession(
    session: DefaultWebSocketServerSession,
    json: Json,
    services: BackendServices
) {
    try {
        for (frame in session.incoming) {
            if (frame !is Frame.Text) {
                continue
            }

            val payload = frame.readText()
            if (payload.toByteArray(Charsets.UTF_8).size > services.config.webSocketMaxPayloadBytes) {
                session.sendWs(json, ErrorMessage(ErrorCode.INVALID_DATA, "Payload too large"))
                continue
            }

            try {
                json.parseToJsonElement(payload)
            } catch (_: Exception) {
                session.sendWs(json, ErrorMessage(ErrorCode.INVALID_DATA, "Invalid JSON"))
                continue
            }
            val message = try {
                json.decodeFromString<WsMessage>(payload)
            } catch (_: Exception) {
                null
            }

            when {
                message is CreateGameRequest -> {
                    val welcome = services.roomManager.createRoom(session, message)
                    session.sendWs(json, welcome)
                }

                message is HelloMessage -> {
                    val welcome = services.roomManager.joinRoom(
                        socket = session,
                        joinCode = message.joinCode,
                        playerMeta = message.playerMeta,
                        lastKnownIp = message.lastKnownIp
                    )
                    if (welcome == null) {
                        session.sendWs(json, ErrorMessage(ErrorCode.INVALID_JOIN_CODE, "Invalid join code"))
                    } else {
                        session.sendWs(json, welcome)
                    }
                }

                message is com.munchkin.app.network.EventRequestMessage -> {
                    services.roomManager.handleEvent(session, message.event)?.let { session.sendWs(json, it) }
                }

                message is PingMessage -> session.sendWs(json, PongMessage())

                message is DeleteGameMessage -> {
                    if (!services.roomManager.deleteCurrentRoom(session)) {
                        session.sendWs(json, ErrorMessage(ErrorCode.GAME_NOT_FOUND, "Game not found"))
                    }
                }

                message is GameOverMessage -> {
                    services.roomManager.finishCurrentRoom(session, message)
                        ?.let { session.sendWs(json, it) }
                        ?: session.sendWs(json, GameOverRecordedMessage(message.gameId, message.winnerId))
                }

                message is com.munchkin.app.network.SwapPlayers -> {
                    services.roomManager.handleSwapPlayers(session, message)?.let { session.sendWs(json, it) }
                }

                message is com.munchkin.app.network.KickPlayerMessage -> {
                    services.roomManager.handleKickPlayer(session, message)?.let { session.sendWs(json, it) }
                }

                message == null -> {
                    session.sendWs(json, ErrorMessage(ErrorCode.INVALID_DATA, "Unsupported message"))
                }
            }
        }
    } finally {
        services.roomManager.disconnect(session)
    }
}

private suspend fun DefaultWebSocketServerSession.sendWs(json: Json, message: WsMessage) {
    val payload = json.encodeToString<WsMessage>(message)
    send(Frame.Text(payload))
}

private suspend fun ApplicationCall.enforceAuthRateLimit(limiter: SlidingWindowRateLimiter): Boolean {
    val forwardedFor = request.headers["X-Forwarded-For"]
        ?.substringBefore(',')
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val realIp = request.headers["X-Real-IP"]?.trim()?.takeIf { it.isNotBlank() }
    val key = "auth:${forwardedFor ?: realIp ?: "unknown"}"

    return if (limiter.tryAcquire(key)) {
        true
    } else {
        respond(HttpStatusCode.TooManyRequests, ApiErrorResponse("Too many authentication attempts"))
        false
    }
}
