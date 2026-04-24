package com.munchkin.backend

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.munchkin.app.network.UserProfile
import io.ktor.server.auth.jwt.JWTPrincipal
import org.mindrot.jbcrypt.BCrypt
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

class JwtService(
    private val config: BackendConfig
) {
    private val algorithm = Algorithm.HMAC256(config.jwtSecret)

    fun issueToken(user: UserProfile): String {
        return JWT.create()
            .withIssuer(config.jwtIssuer)
            .withAudience(config.jwtAudience)
            .withSubject(user.id)
            .withClaim("userId", user.id)
            .withClaim("username", user.username)
            .withExpiresAt(Date.from(Instant.now().plus(48, ChronoUnit.HOURS)))
            .sign(algorithm)
    }

    fun verifier(): JWTVerifier {
        return JWT.require(algorithm)
            .withIssuer(config.jwtIssuer)
            .withAudience(config.jwtAudience)
            .build()
    }
}

object PasswordService {
    fun hash(password: String): String = BCrypt.hashpw(password, BCrypt.gensalt(12))

    fun verify(password: String, hash: String): Boolean = BCrypt.checkpw(password, hash)
}

fun JWTPrincipal.userId(): String? = payload.getClaim("userId")?.asString()
