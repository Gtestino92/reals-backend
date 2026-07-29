package com.reals.backend.config.security.authentication

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "security.firebase-auth")
data class FirebaseTokenAuthenticationProperties(
    val revocationCacheTtl: Duration = Duration.ofSeconds(60)
) {
    init {
        require(!revocationCacheTtl.isZero && !revocationCacheTtl.isNegative) {
            "security.firebase-auth.revocation-cache-ttl must be positive"
        }
    }
}
