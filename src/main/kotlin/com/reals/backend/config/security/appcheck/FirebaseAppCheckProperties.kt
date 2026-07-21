package com.reals.backend.config.security.appcheck

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

@ConfigurationProperties(prefix = "security.app-check")
data class FirebaseAppCheckProperties(
    val mode: FirebaseAppCheckMode = FirebaseAppCheckMode.DISABLED,
    val projectNumber: String = "",
    val allowedAppIds: List<String> = emptyList(),
    val jwksUri: String = DEFAULT_JWKS_URI
) {
    fun normalizedProjectNumber(): String =
        projectNumber.trim()

    fun normalizedAllowedAppIds(): Set<String> =
        allowedAppIds
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

    fun expectedIssuer(): String =
        "https://firebaseappcheck.googleapis.com/${normalizedProjectNumber()}"

    fun expectedAudience(): String =
        "projects/${normalizedProjectNumber()}"

    fun parsedJwksUri(): URI =
        URI(jwksUri.trim())

    companion object {
        const val DEFAULT_JWKS_URI = "https://firebaseappcheck.googleapis.com/v1/jwks"
    }
}

enum class FirebaseAppCheckMode {
    DISABLED,
    MONITOR,
    ENFORCED
}
