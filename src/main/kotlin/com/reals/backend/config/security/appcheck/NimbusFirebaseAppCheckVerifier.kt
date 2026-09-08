package com.reals.backend.config.security.appcheck

import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import java.time.Clock

class NimbusFirebaseAppCheckVerifier(
    private val decoderFactory: FirebaseAppCheckJwtDecoderFactory,
    private val properties: FirebaseAppCheckProperties,
    private val clock: Clock = Clock.systemUTC()
) : FirebaseAppCheckVerifier {

    override fun verify(token: String): FirebaseAppCheckVerificationResult {
        if (token.isBlank()) {
            return FirebaseAppCheckVerificationResult.Invalid
        }

        val jwt = try {
            decoderFactory.decoder().decode(token)
        } catch (ex: BadJwtException) {
            return FirebaseAppCheckVerificationResult.Invalid
        } catch (ex: OAuth2AuthenticationException) {
            return FirebaseAppCheckVerificationResult.Invalid
        } catch (ex: JwtException) {
            return if (ex.isInfrastructureFailure()) {
                FirebaseAppCheckVerificationResult.Unavailable(ex.safeExceptionClass())
            } else {
                FirebaseAppCheckVerificationResult.Invalid
            }
        } catch (ex: IllegalArgumentException) {
            return FirebaseAppCheckVerificationResult.Unavailable(ex.safeExceptionClass())
        } catch (ex: IllegalStateException) {
            return FirebaseAppCheckVerificationResult.Unavailable(ex.safeExceptionClass())
        }

        return validateClaims(jwt)
    }

    private fun validateClaims(jwt: Jwt): FirebaseAppCheckVerificationResult {
        if (jwt.headers["alg"]?.toString() != "RS256") {
            return FirebaseAppCheckVerificationResult.Invalid
        }

        if (jwt.headers["typ"]?.toString() != "JWT") {
            return FirebaseAppCheckVerificationResult.Invalid
        }

        if (jwt.issuer?.toString() != properties.expectedIssuer()) {
            return FirebaseAppCheckVerificationResult.Invalid
        }

        val expiresAt = jwt.expiresAt
        if (expiresAt == null || !expiresAt.isAfter(clock.instant())) {
            return FirebaseAppCheckVerificationResult.Invalid
        }

        if (properties.expectedAudience() !in jwt.audience) {
            return FirebaseAppCheckVerificationResult.Invalid
        }

        val appId = jwt.subject?.trim().orEmpty()
        if (appId.isBlank()) {
            return FirebaseAppCheckVerificationResult.Invalid
        }

        if (appId !in properties.normalizedAllowedAppIds()) {
            return FirebaseAppCheckVerificationResult.Invalid
        }

        return FirebaseAppCheckVerificationResult.Valid(appId)
    }

    private fun JwtException.isInfrastructureFailure(): Boolean {
        val message = message.orEmpty().lowercase()
        return message.contains("jwk") ||
            message.contains("remote") ||
            message.contains("retrieve") ||
            message.contains("connect") ||
            message.contains("timed out") ||
            cause != null
    }

    private fun Throwable.safeExceptionClass(): String =
        javaClass.simpleName.ifBlank { "Exception" }
}

fun interface FirebaseAppCheckJwtDecoderFactory {
    fun decoder(): JwtDecoder
}
