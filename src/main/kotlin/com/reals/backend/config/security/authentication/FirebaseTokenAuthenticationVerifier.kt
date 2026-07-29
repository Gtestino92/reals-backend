package com.reals.backend.config.security.authentication

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseToken
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.HexFormat

@Component
@Profile("local-firebase", "dev", "prod")
class FirebaseTokenAuthenticationVerifier(
    private val firebaseAuth: FirebaseAuth,
    properties: FirebaseTokenAuthenticationProperties,
    ticker: Ticker = Ticker.systemTicker()
) {

    private val successfulRevocationChecks: Cache<String, Boolean> =
        Caffeine.newBuilder()
            .maximumSize(MAX_REVOCATION_CACHE_ENTRIES)
            .expireAfterWrite(properties.revocationCacheTtl)
            .ticker(ticker)
            .build()

    @Throws(FirebaseAuthException::class)
    fun verify(token: String): FirebaseToken {
        val decoded = firebaseAuth.verifyIdToken(token, false)
        val cacheKey = sha256Hex(token)

        if (successfulRevocationChecks.getIfPresent(cacheKey) == true) {
            return decoded
        }

        firebaseAuth.verifyIdToken(token, true)
        successfulRevocationChecks.put(cacheKey, true)
        return decoded
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }

    private companion object {
        const val MAX_REVOCATION_CACHE_ENTRIES = 100_000L
    }
}
